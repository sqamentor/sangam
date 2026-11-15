// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.rights;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.consent.NoticeTemplate;
import org.sangam.compliance.jurisdiction.JurisdictionPack.Language;
import org.sangam.compliance.ports.CompliancePorts.PrincipalAuthenticator;
import org.sangam.compliance.ports.CompliancePorts.PrincipalMessage;
import org.sangam.compliance.ports.CompliancePorts.PrincipalNotifier;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

/**
 * Owner decision 360: the compliance-owned one-time code that authenticates a rights request made with the identifier
 * the jurisdiction pack accepts (IN: {@code verification.principal} OTP, DPDP Rules r.14(1)), sent through the
 * {@link PrincipalNotifier} (the outbox and the SMS / e-mail connectors). Not the Aadhaar OTP connector and not the IdP.
 *
 * <ul>
 *   <li><b>Issue</b>: the identifier type must be one the tenant accepts ({@code rights.otp.identifier-types}); a new
 *       challenge supersedes the identifier's open one; an identifier locked out is refused ({@code OTP_LOCKED}).
 *       Owner decision 369 (d): at most {@code rights.otp.issue-max-per-identifier} codes per identifier and
 *       {@code rights.otp.issue-max-per-address} per client address are sent within
 *       {@code rights.otp.issue-window-minutes}; one more is refused with {@code OTP_RATE_LIMITED}, naming the limit.
 *       A refused issue rolls the caller back, so its audit event is a {@linkplain ComplianceAudit#denial denial}
 *       that commits on its own (M-009), on the denial chain of the identifier's token.</li>
 *   <li><b>Verify</b>: the code is compared in constant time with its salted hash (the code itself is never stored or
 *       audited); a wrong code counts against the identifier, and {@code rights.otp.max-attempts} wrong codes within
 *       {@code rights.otp.lockout-minutes} lock it out for that period; an expired, used or other tenant's challenge
 *       is refused. Verify never throws for an outcome, so the attempt it records commits with the caller's
 *       transaction; the caller refuses afterwards ({@link Verification#require}).</li>
 *   <li><b>Consume</b> ({@link #authenticate}): a rights submission with that identifier and
 *       {@code proof.challengeId} of a verified, unexpired challenge of the tenant and identifier; single use (a
 *       conditional update), so a second submission with the same challenge is refused.</li>
 * </ul>
 * Lifetime, attempts, lock-out, code length and the issue limits come from tenant configuration over the pack defaults
 * (IN pack {@code defaults}); none is defaulted in code: an unset one refuses the issue with {@code OTP_NOT_CONFIGURED}.
 * Owner decision 369 (e): the stored identifier and client-address hashes and the audited token are keyed with the
 * compliance {@link org.sangam.compliance.PrincipalKey} (HMAC), never a plain SHA-256; without the key every call
 * refuses with {@code PRINCIPAL_KEY_NOT_CONFIGURED}.
 * Every issue, verification and refusal is audited on the tenant's chain ({@code RIGHTS_OTP_ISSUED},
 * {@code RIGHTS_OTP_VERIFIED}, {@code RIGHTS_OTP_FAILED}; SANGAM.md X.4.0.3 Security) with the token ({@link ComplianceAudit#principalToken}) of the
 * identifier, never the identifier or the code.
 */
public final class PrincipalOtpChallenges implements PrincipalAuthenticator {

    public static final String ISSUED = "RIGHTS_OTP_ISSUED";
    public static final String VERIFIED = "RIGHTS_OTP_VERIFIED";
    public static final String FAILED = "RIGHTS_OTP_FAILED";
    /** The proof key of a rights submission that names the verified challenge. */
    public static final String PROOF_CHALLENGE = "challengeId";
    /** The message kind given to the notifier. */
    public static final String MESSAGE_KIND = "RIGHTS_OTP";

    public enum Outcome { VERIFIED, WRONG_CODE, LOCKED, EXPIRED, INVALID }

    public record Challenge(String challengeId, String identifierType, Instant expiresAt) {
    }

    /** The result of a verification; {@link #require} turns a refusal into its named code. */
    public record Verification(String challengeId, Outcome outcome, Integer attemptsLeft) {
        public Verification require() {
            switch (outcome) {
                case VERIFIED -> {
                    return this;
                }
                case WRONG_CODE -> throw new ComplianceException(Code.OTP_INVALID, "the code is not correct"
                        + (attemptsLeft == null ? "" : "; attempts left: " + attemptsLeft));
                case LOCKED -> throw new ComplianceException(Code.OTP_LOCKED, "too many wrong codes; try again later");
                case EXPIRED -> throw new ComplianceException(Code.OTP_EXPIRED, "the code has expired; ask for a new one");
                default -> throw new ComplianceException(Code.OTP_INVALID, "the challenge is not open");
            }
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final PrincipalNotifier notifier;
    private final NoticeTemplate.Catalogues templates;

    public PrincipalOtpChallenges(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config,
                                  PrincipalNotifier notifier, NoticeTemplate.Catalogues templates) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
        this.notifier = notifier;
        this.templates = templates;
    }

    /**
     * Sends a code to the identifier; returns the challenge (never the code).
     *
     * @param clientAddress the address the request came from (the server's resolved client address, behind trusted
     *                      proxies only); required, it is what the per-address limit counts (owner decision 369 (d))
     */
    public Challenge issue(Connection c, String tenantId, String identifierType, String identifierValue, Language language,
                           String clientAddress) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        String type = acceptedType(cfg, identifierType);
        String value = normalise(Sql.requireText(identifierValue, "identifierValue"));
        Settings s = settings(cfg);
        if (notifier == null) {
            throw new ComplianceException(Code.OTP_NOT_CONFIGURED, "no principal notifier is configured to send the code");
        }
        Map<String, String> attributes = attributes(cfg, type, value);
        String client = Sql.requireText(clientAddress, "clientAddress").strip().toLowerCase(Locale.ROOT);
        Instant now = Sql.now(c, dialect);
        String idHash = identifierHash(tenantId, type, value);
        String clientHash = audit.principalKey().clientAddressHash(tenantId, client);
        String who = token(tenantId, value);
        if (lockedUntil(c, tenantId, idHash, now).isPresent()) {
            refusedIssue(c, tenantId, type, who, "LOCKED", null);
            throw new ComplianceException(Code.OTP_LOCKED, "too many wrong codes; try again later");
        }
        Instant window = now.minus(Duration.ofMinutes(s.issueWindowMinutes()));
        if (issuedSince(c, tenantId, "identifier_hash", idHash, window) >= s.issueMaxPerIdentifier()) {
            refusedIssue(c, tenantId, type, who, "RATE_LIMITED", "IDENTIFIER");
            throw new ComplianceException(Code.OTP_RATE_LIMITED, "too many codes were asked for this identifier (limit "
                    + s.issueMaxPerIdentifier() + " in " + s.issueWindowMinutes() + " minutes); try again later");
        }
        if (issuedSince(c, tenantId, "client_hash", clientHash, window) >= s.issueMaxPerAddress()) {
            refusedIssue(c, tenantId, type, who, "RATE_LIMITED", "ADDRESS");
            throw new ComplianceException(Code.OTP_RATE_LIMITED, "too many codes were asked from this address (limit "
                    + s.issueMaxPerAddress() + " in " + s.issueWindowMinutes() + " minutes); try again later");
        }
        Jdbc.update(c, "UPDATE CMP_OTP_CHALLENGE SET status='SUPERSEDED' WHERE tenant_id=? AND identifier_hash=? AND status='ISSUED'",
                tenantId, idHash);
        String id = Sql.newId();
        String code = code(s.codeLength());
        String salt = HexFormat.of().formatHex(bytes(16));
        Instant expires = now.plus(Duration.ofMinutes(s.ttlMinutes()));
        Jdbc.update(c, "INSERT INTO CMP_OTP_CHALLENGE(tenant_id, challenge_id, identifier_type, identifier_hash, code_hash, salt,"
                        + " status, attempts, issued_at, expires_at, locked_until, verified_at, consumed_at, client_hash)"
                        + " VALUES (?,?,?,?,?,?,'ISSUED',0,?,?,NULL,NULL,NULL,?)",
                tenantId, id, type, idHash, codeHash(salt, id, code), salt, now, expires, clientHash);
        Language lang = language == null ? cfg.defaultLanguage : language;
        NoticeTemplate.Catalogue cat = templates.of(cfg.pack);
        // the chosen language's message, else the default language's; never a message without the code
        NoticeTemplate t = cat.get(lang.code()).filter(x -> !x.label("otp.body", "").isBlank())
                .or(() -> cat.get(cfg.defaultLanguage.code()).filter(x -> !x.label("otp.body", "").isBlank()))
                .orElseThrow(() -> new ComplianceException(Code.OTP_NOT_CONFIGURED, "no notice template has the otp.body message"));
        String body = t.label("otp.body", cfg.organisationName).replace("{code}", code)
                .replace("{minutes}", Integer.toString(s.ttlMinutes()));
        notifier.send(c, new PrincipalMessage(tenantId, value, MESSAGE_KIND, t.language(), t.label("otp.subject",
                cfg.organisationName), body, attributes));
        audit.organisation(c, tenantId, ISSUED, Sql.kv("challenge", id, "identifierType", type, "identifier", who,
                "expiresAt", expires.toString()));
        return new Challenge(id, type, expires);
    }

    /** Records one attempt; never throws for an outcome (the caller refuses after its transaction commits). */
    public Verification verify(Connection c, String tenantId, String challengeId, String code) {
        Sql.requireText(challengeId, "challengeId");
        Sql.requireText(code, "code");
        Instant now = Sql.now(c, dialect);
        Optional<Row> found = Jdbc.one(c, "SELECT identifier_type, identifier_hash, code_hash, salt, status, attempts, expires_at,"
                + " locked_until FROM CMP_OTP_CHALLENGE WHERE tenant_id=? AND challenge_id=?", PrincipalOtpChallenges::row, tenantId,
                challengeId);
        if (found.isEmpty()) {
            return failed(c, tenantId, challengeId, null, Outcome.INVALID, null);
        }
        Row r = found.get();
        if ("LOCKED".equals(r.status()) || lockedUntil(c, tenantId, r.identifierHash(), now).isPresent()) {
            return failed(c, tenantId, challengeId, r.identifierType(), Outcome.LOCKED, 0);
        }
        if (!"ISSUED".equals(r.status())) {
            return failed(c, tenantId, challengeId, r.identifierType(), Outcome.INVALID, null);
        }
        if (!now.isBefore(r.expiresAt())) {
            Jdbc.update(c, "UPDATE CMP_OTP_CHALLENGE SET status='EXPIRED' WHERE tenant_id=? AND challenge_id=? AND status='ISSUED'",
                    tenantId, challengeId);
            return failed(c, tenantId, challengeId, r.identifierType(), Outcome.EXPIRED, null);
        }
        boolean match = MessageDigest.isEqual(r.codeHash().getBytes(StandardCharsets.US_ASCII),
                codeHash(r.salt(), challengeId, code.strip()).getBytes(StandardCharsets.US_ASCII));
        if (match) {
            Jdbc.update(c, "UPDATE CMP_OTP_CHALLENGE SET status='VERIFIED', verified_at=? WHERE tenant_id=? AND challenge_id=?"
                    + " AND status='ISSUED'", now, tenantId, challengeId);
            audit.organisation(c, tenantId, VERIFIED, Sql.kv("challenge", challengeId, "identifierType", r.identifierType()));
            return new Verification(challengeId, Outcome.VERIFIED, null);
        }
        Settings s = settings(config.forTenant(tenantId));
        Jdbc.update(c, "UPDATE CMP_OTP_CHALLENGE SET attempts=attempts+1 WHERE tenant_id=? AND challenge_id=?", tenantId, challengeId);
        Instant window = now.minus(Duration.ofMinutes(s.lockoutMinutes()));
        long wrong = Jdbc.one(c, "SELECT COALESCE(SUM(attempts), 0) FROM CMP_OTP_CHALLENGE WHERE tenant_id=? AND identifier_hash=?"
                + " AND issued_at>?", rs -> rs.getLong(1), tenantId, r.identifierHash(), window).orElse(0L);
        if (wrong >= s.maxAttempts()) {
            Instant until = now.plus(Duration.ofMinutes(s.lockoutMinutes()));
            Jdbc.update(c, "UPDATE CMP_OTP_CHALLENGE SET status='LOCKED', locked_until=? WHERE tenant_id=? AND identifier_hash=?"
                    + " AND status IN ('ISSUED','LOCKED')", until, tenantId, r.identifierHash());
            return failed(c, tenantId, challengeId, r.identifierType(), Outcome.LOCKED, 0);
        }
        return failed(c, tenantId, challengeId, r.identifierType(), Outcome.WRONG_CODE, (int) (s.maxAttempts() - wrong));
    }

    /**
     * Consumes a verified challenge for a rights submission: the identifier must be the challenge's, of the tenant, and
     * the challenge unexpired and unused. The requester is the identifier (single use).
     */
    @Override
    public Optional<String> authenticate(Connection c, String tenantId, String identifierType, String identifierValue,
                                         Map<String, String> proof) {
        String challengeId = proof == null ? null : proof.get(PROOF_CHALLENGE);
        if (challengeId == null || challengeId.isBlank() || identifierType == null || identifierValue == null
                || identifierValue.isBlank()) {
            return Optional.empty();
        }
        String type = identifierType.strip().toUpperCase(Locale.ROOT);
        String value = normalise(identifierValue);
        Instant now = Sql.now(c, dialect);
        int n = Jdbc.update(c, "UPDATE CMP_OTP_CHALLENGE SET status='CONSUMED', consumed_at=? WHERE tenant_id=? AND challenge_id=?"
                        + " AND status='VERIFIED' AND identifier_type=? AND identifier_hash=? AND expires_at>?",
                now, tenantId, challengeId.strip(), type, identifierHash(tenantId, type, value), now);
        if (n != 1) {
            audit.organisation(c, tenantId, FAILED, Sql.kv("challenge", challengeId.strip(), "identifierType", type, "outcome", "NOT_VERIFIED"));
            return Optional.empty();
        }
        return Optional.of(value);
    }

    // ---------------------------------------------------------------- internals

    record Settings(int ttlMinutes, int maxAttempts, int lockoutMinutes, int codeLength, int issueWindowMinutes,
                    int issueMaxPerIdentifier, int issueMaxPerAddress) {
    }

    record Row(String identifierType, String identifierHash, String codeHash, String salt, String status, int attempts,
               Instant expiresAt, Instant lockedUntil) {
    }

    static Settings settings(ComplianceConfig cfg) {
        if (cfg.otpTtlMinutes == null || cfg.otpMaxAttempts == null || cfg.otpLockoutMinutes == null || cfg.otpCodeLength == null
                || cfg.otpIssueWindowMinutes == null || cfg.otpIssueMaxPerIdentifier == null || cfg.otpIssueMaxPerAddress == null) {
            throw new ComplianceException(Code.OTP_NOT_CONFIGURED, "rights.otp.ttl-minutes, max-attempts, lockout-minutes,"
                    + " code-length, issue-window-minutes, issue-max-per-identifier and issue-max-per-address must be configured"
                    + " (tenant configuration or the jurisdiction pack defaults)");
        }
        if (cfg.otpTtlMinutes < 1 || cfg.otpMaxAttempts < 1 || cfg.otpLockoutMinutes < 1 || cfg.otpCodeLength < 6
                || cfg.otpIssueWindowMinutes < 1 || cfg.otpIssueMaxPerIdentifier < 1 || cfg.otpIssueMaxPerAddress < 1) {
            throw new ComplianceException(Code.OTP_NOT_CONFIGURED, "rights.otp settings must be positive and the code at least six digits");
        }
        return new Settings(cfg.otpTtlMinutes, cfg.otpMaxAttempts, cfg.otpLockoutMinutes, cfg.otpCodeLength,
                cfg.otpIssueWindowMinutes, cfg.otpIssueMaxPerIdentifier, cfg.otpIssueMaxPerAddress);
    }

    private static String acceptedType(ComplianceConfig cfg, String identifierType) {
        boolean packOtp = cfg.pack.principalVerification().stream().anyMatch(m -> DataSubjectRightsService.OTP.equals(m.code()));
        if (!packOtp) {
            throw new ComplianceException(Code.OTP_NOT_CONFIGURED, "jurisdiction " + cfg.pack.code() + " does not authenticate a"
                    + " rights request by a one-time code");
        }
        String type = Sql.requireText(identifierType, "identifierType").strip().toUpperCase(Locale.ROOT);
        if (!cfg.otpIdentifierTypes.contains(type)) {
            throw ComplianceException.invalid("identifierType must be one of " + cfg.otpIdentifierTypes);
        }
        return type;
    }

    /** The connector attributes the notifier needs: an e-mail address, or a mobile number with its DLT template. */
    private static Map<String, String> attributes(ComplianceConfig cfg, String type, String value) {
        return switch (type) {
            case "EMAIL" -> Map.of("email", value);
            case "MOBILE" -> {
                if (cfg.otpSmsTemplateId.isBlank()) {
                    throw new ComplianceException(Code.OTP_NOT_CONFIGURED, "rights.otp.sms-template-id is not configured");
                }
                yield Map.of("mobile", value, "templateId", cfg.otpSmsTemplateId);
            }
            default -> throw new ComplianceException(Code.OTP_NOT_CONFIGURED, "no delivery is known for identifier type " + type);
        };
    }

    /**
     * A refused issue: the caller rolls back, so the event is a denial committed on its own (M-009) on the denial chain
     * of the identifier's token (never of the identifier itself).
     */
    private void refusedIssue(Connection c, String tenantId, String type, String who, String outcome, String limit) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("identifierType", type);
        p.put("identifier", who);
        p.put("outcome", outcome);
        if (limit != null) {
            p.put("limit", limit);
        }
        audit.denial(c, tenantId, who, FAILED, p);
    }

    /** Challenges issued since {@code from} with {@code column} (identifier_hash or client_hash) equal to {@code hash}. */
    private static long issuedSince(Connection c, String tenantId, String column, String hash, Instant from) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM CMP_OTP_CHALLENGE WHERE tenant_id=? AND " + column + "=? AND issued_at>?",
                rs -> rs.getLong(1), tenantId, hash, from).orElse(0L);
    }

    private Verification failed(Connection c, String tenantId, String challengeId, String type, Outcome outcome, Integer left) {
        audit.organisation(c, tenantId, FAILED, Sql.kv("challenge", challengeId, "identifierType", type == null ? "" : type,
                "outcome", outcome.name()));
        return new Verification(challengeId, outcome, left);
    }

    private static Optional<Instant> lockedUntil(Connection c, String tenantId, String idHash, Instant now) {
        return Jdbc.one(c, "SELECT MAX(locked_until) FROM CMP_OTP_CHALLENGE WHERE tenant_id=? AND identifier_hash=? AND status='LOCKED'",
                rs -> Optional.ofNullable(instant(rs, 1)), tenantId, idHash).flatMap(x -> x).filter(now::isBefore);
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6),
                instant(rs, 7), instant(rs, 8));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static String normalise(String identifierValue) {
        return identifierValue.strip().toLowerCase(Locale.ROOT);
    }

    /** Owner decision 369 (e): keyed with the compliance principal key, never a plain SHA-256. */
    String identifierHash(String tenantId, String type, String value) {
        return audit.principalKey().identifierHash(tenantId, type, value);
    }

    private String token(String tenantId, String value) {
        return audit.principalToken(tenantId, value);
    }

    static String codeHash(String salt, String challengeId, String code) {
        return sha256(salt + "\u0000" + challengeId + "\u0000" + code);
    }

    private static String code(int length) {
        StringBuilder b = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            b.append(RANDOM.nextInt(10));
        }
        return b.toString();
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
