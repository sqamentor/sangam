// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.consent;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionPack.Language;
import org.sangam.compliance.lawful.LegitimateUseRegistry;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.ports.CompliancePorts.PrincipalCategory;
import org.sangam.compliance.ports.CompliancePorts.PrincipalDirectory;
import org.sangam.compliance.ports.CompliancePorts.ProcessorNotifier;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Consent records and withdrawal (IN: DPDP s.6; CMP-005, CMP-008 … CMP-012, CMP-029, CMP-068 … CMP-070).
 * Tables {@code CMP_CONSENT} (one artefact per grant) and {@code CMP_CONSENT_PURPOSE} (one row per purpose, so
 * withdrawal can be partial). Every grant and withdrawal is appended to the principal's audit chain with an evidence
 * hash binding the exact notice text shown (s.6(10)).
 *
 * <p>Grant rules: a clear affirmative action (no pre-ticked boxes, s.6(1)); only purposes listed on the notice
 * version shown, in a language that version was published in; only purposes whose basis is consent; a child or a
 * person with disability with a lawful guardian needs a valid guardian verification (s.9(1), Rules 10-11) unless
 * every purpose carries a Fourth Schedule exemption; a Consent Manager must be on the Board-registered allow-list.
 *
 * <p>Withdrawal is one call per artefact or purpose set, from the same channels as the grant (s.6(4)). It stops the
 * purpose at once for {@link PurposeBindingGuard}, causes Data Processors to cease ({@link ProcessorNotifier},
 * s.6(6)), schedules erasure ({@link WithdrawalListener}: the retention scheduler, s.8(7)(a)) and informs the
 * Consent Manager when the artefact came from one.
 */
public final class ConsentService {

    public enum Status { ACTIVE, PARTIALLY_WITHDRAWN, WITHDRAWN }

    /** A grant request. {@code givenBy} is the principal, or the parent / lawful guardian. */
    public record GiveConsent(String principalRef, String noticeKey, Integer noticeVersion, Language language,
                              Set<String> purposeCodes, boolean affirmativeAction, String channel, String givenBy,
                              String guardianVerificationId, String consentManagerId, String cmArtefactId) {
    }

    public record Consent(String consentId, String principalRef, String noticeKey, int noticeVersion, String language,
                          Status status, Instant givenAt, Instant withdrawnAt, String consentManagerId, String cmArtefactId,
                          boolean legacy, Set<String> activePurposes, String evidenceHash) {
    }

    /** Reaction to a withdrawal inside the same transaction. */
    @FunctionalInterface
    public interface WithdrawalListener {
        void onWithdrawn(Connection c, String tenantId, String principalRef, String consentId, Set<String> purposeCodes,
                         Consent consent, Instant at);
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final ConsentNoticeService notices;
    private final LegitimateUseRegistry purposes;
    private final VerifiableParentalConsentService guardians;
    private final PrincipalDirectory principals;
    private final ProcessorNotifier processors;
    private final List<WithdrawalListener> listeners = new CopyOnWriteArrayList<>();

    public ConsentService(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config, ConsentNoticeService notices,
                          LegitimateUseRegistry purposes, VerifiableParentalConsentService guardians,
                          PrincipalDirectory principals, ProcessorNotifier processors) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
        this.notices = notices;
        this.purposes = purposes;
        this.guardians = guardians;
        this.principals = principals == null ? PrincipalDirectory.NONE : principals;
        this.processors = processors == null ? ProcessorNotifier.NONE : processors;
    }

    public void addWithdrawalListener(WithdrawalListener l) {
        listeners.add(l);
    }

    public Consent give(Connection c, String tenantId, GiveConsent g) {
        Sql.requireText(g.principalRef(), "principalRef");
        if (!g.affirmativeAction()) {
            throw new ComplianceException(Code.CONSENT_REQUIRED, "consent needs a clear affirmative action");
        }
        if (g.purposeCodes() == null || g.purposeCodes().isEmpty()) {
            throw new ComplianceException(Code.CONSENT_REQUIRED, "select at least one purpose ");
        }
        ComplianceConfig cfg = config.forTenant(tenantId);
        if (g.consentManagerId() != null && !cfg.registeredConsentManagers.contains(g.consentManagerId())) {
            throw new ComplianceException(Code.CONSENT_MANAGER_NOT_REGISTERED, "consent manager " + g.consentManagerId()
                    + " is not on the registered allow-list");
        }
        Language lang = g.language() == null ? cfg.defaultLanguage : g.language();
        ConsentNoticeService.RenderedNotice notice = notices.get(c, tenantId, g.noticeKey(), g.noticeVersion(), lang);
        if (notice.fallback()) {
            throw new ComplianceException(Code.LANGUAGE_NOT_CONFIGURED, "consent must be taken on the notice in the chosen language");
        }
        if (!notice.purposeCodes().containsAll(g.purposeCodes())) {
            throw new ComplianceException(Code.NOTICE_INVALID, "a selected purpose is not on notice " + g.noticeKey()
                    + " v" + notice.version() + "");
        }
        List<PurposeDefinition> defs = new ArrayList<>();
        for (String code : new TreeSet<>(g.purposeCodes())) {
            PurposeDefinition d = purposes.require(c, tenantId, code);
            if (!d.active() || !d.consentBased(cfg.pack)) {
                throw new ComplianceException(Code.PURPOSE_NOT_PERMITTED, "purpose " + code + " is not an active consent purpose");
            }
            defs.add(d);
        }
        PrincipalCategory category = principals.category(c, tenantId, g.principalRef());
        String verificationId = null;
        boolean guardianNeeded = (category == PrincipalCategory.CHILD || category == PrincipalCategory.PERSON_WITH_DISABILITY_WITH_GUARDIAN)
                && !defs.stream().allMatch(d -> ChildDataGuard.guardianConsentExempt(cfg.pack, d));
        if (guardianNeeded) {
            if (g.guardianVerificationId() == null) {
                throw new ComplianceException(Code.GUARDIAN_CONSENT_REQUIRED, category == PrincipalCategory.CHILD
                        ? "verifiable consent of the parent is required"
                        : "consent of the lawful guardian is required");
            }
            VerifiableParentalConsentService.Verification v = guardians.requireValid(c, tenantId, g.principalRef(), g.guardianVerificationId());
            VerifiableParentalConsentService.Kind expected = category == PrincipalCategory.CHILD
                    ? VerifiableParentalConsentService.Kind.CHILD : VerifiableParentalConsentService.Kind.PERSON_WITH_DISABILITY;
            if (v.kind() != expected || g.givenBy() == null || !g.givenBy().equals(v.guardianRef())) {
                throw new ComplianceException(Code.GUARDIAN_CONSENT_REQUIRED, "the consent must be given by the verified parent or guardian");
            }
            verificationId = v.verificationId();
        }
        Instant now = Sql.now(c, dialect);
        String id = Sql.newId();
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("notice", g.noticeKey());
        proof.put("version", notice.version());
        proof.put("language", lang.code());
        proof.put("noticeHash", notice.contentHash());
        proof.put("purposes", new ArrayList<>(new TreeSet<>(g.purposeCodes())));
        proof.put("principal", ComplianceAudit.principalToken(tenantId, g.principalRef()));
        proof.put("givenBy", g.givenBy() == null ? null : ComplianceAudit.principalToken(tenantId, g.givenBy()));
        proof.put("channel", g.channel());
        proof.put("givenAt", now.toString());
        proof.put("category", category.name());
        proof.put("guardianVerification", verificationId);
        proof.put("consentManager", g.consentManagerId());
        proof.put("artefact", g.cmArtefactId());
        String evidence = Sql.evidenceHash(proof);
        Jdbc.update(c, "INSERT INTO CMP_CONSENT(tenant_id, consent_id, principal_ref, notice_key, notice_version, language, status,"
                        + " channel, given_at, withdrawn_at, consent_manager_id, cm_artefact_id, guardian_verification_id, given_by,"
                        + " legacy, evidence_hash) VALUES (?,?,?,?,?,?,?,?,?,NULL,?,?,?,?,0,?)",
                tenantId, id, g.principalRef(), g.noticeKey(), notice.version(), lang.code(), Status.ACTIVE, g.channel(), now,
                g.consentManagerId(), g.cmArtefactId(), verificationId, g.givenBy(), evidence);
        for (PurposeDefinition d : defs) {
            Jdbc.update(c, "INSERT INTO CMP_CONSENT_PURPOSE(tenant_id, consent_id, purpose_code, principal_ref, status, given_at,"
                    + " withdrawn_at) VALUES (?,?,?,?,'ACTIVE',?,NULL)", tenantId, id, d.code(), g.principalRef(), now);
        }
        proof.put("consent", id);
        proof.put("evidenceHash", evidence);
        audit.principal(c, tenantId, g.principalRef(), ComplianceAudit.CONSENT_GIVEN, proof);
        notices.recordDelivery(c, tenantId, g.principalRef(), g.noticeKey(), notice.version(), lang,
                ConsentNoticeService.DeliveryKind.CONSENT_REQUEST, g.channel(), id);
        return get(c, tenantId, id);
    }

    /**
     * s.5(2): records a consent given before commencement (imported from a legacy system) so that the legacy notice
     * job can send the notice; processing may continue until withdrawal.
     */
    public Consent importLegacy(Connection c, String tenantId, String principalRef, Set<String> purposeCodes, Instant givenAt,
                                String sourceRef, String actor) {
        Sql.requireText(sourceRef, "sourceRef");
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_CONSENT(tenant_id, consent_id, principal_ref, notice_key, notice_version, language, status,"
                        + " channel, given_at, withdrawn_at, consent_manager_id, cm_artefact_id, guardian_verification_id, given_by,"
                        + " legacy, evidence_hash) VALUES (?,?,?,NULL,0,NULL,?,?,?,NULL,NULL,NULL,NULL,?,1,?)",
                tenantId, id, principalRef, Status.ACTIVE, "legacy:" + sourceRef, givenAt, principalRef,
                Sql.evidenceHash(Map.of("source", sourceRef, "purposes", new ArrayList<>(new TreeSet<>(purposeCodes)))));
        for (String p : new TreeSet<>(purposeCodes)) {
            PurposeDefinition d = purposes.require(c, tenantId, p);
            if (!d.consentBased(config.forTenant(tenantId).pack)) {
                throw new ComplianceException(Code.PURPOSE_NOT_PERMITTED, "purpose " + p + " is not a consent purpose");
            }
            Jdbc.update(c, "INSERT INTO CMP_CONSENT_PURPOSE(tenant_id, consent_id, purpose_code, principal_ref, status, given_at,"
                    + " withdrawn_at) VALUES (?,?,?,?,'ACTIVE',?,NULL)", tenantId, id, p, principalRef, givenAt);
        }
        audit.principal(c, tenantId, principalRef, ComplianceAudit.CONSENT_GIVEN, org.sangam.compliance.support.Sql.kv("consent", id, "legacy", true, "source", sourceRef, "actor", actor));
        return get(c, tenantId, id);
    }

    /**
     * Withdraws a consent artefact, fully ({@code purposeCodes == null}) or for some purposes.
     *
     * @return the consent after withdrawal
     */
    public Consent withdraw(Connection c, String tenantId, String principalRef, String consentId, Set<String> purposeCodes,
                            String channel, String actor) {
        Consent k = get(c, tenantId, consentId);
        if (!k.principalRef().equals(principalRef)) {
            throw ComplianceException.notFound("consent", consentId);
        }
        Set<String> target = purposeCodes == null || purposeCodes.isEmpty() ? k.activePurposes()
                : new LinkedHashSet<>(purposeCodes);
        target.retainAll(k.activePurposes());
        if (target.isEmpty()) {
            return k;
        }
        Instant now = Sql.now(c, dialect);
        for (String p : target) {
            Jdbc.update(c, "UPDATE CMP_CONSENT_PURPOSE SET status='WITHDRAWN', withdrawn_at=? WHERE tenant_id=? AND consent_id=?"
                    + " AND purpose_code=? AND status='ACTIVE'", now, tenantId, consentId, p);
        }
        boolean all = target.containsAll(k.activePurposes());
        Jdbc.update(c, "UPDATE CMP_CONSENT SET status=?, withdrawn_at=? WHERE tenant_id=? AND consent_id=?",
                all ? Status.WITHDRAWN : Status.PARTIALLY_WITHDRAWN, all ? now : null, tenantId, consentId);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("consent", consentId);
        ev.put("purposes", new ArrayList<>(new TreeSet<>(target)));
        ev.put("full", all);
        ev.put("channel", channel);
        ev.put("actor", actor == null ? null : ComplianceAudit.principalToken(tenantId, actor));
        ev.put("withdrawnAt", now.toString());
        audit.principal(c, tenantId, principalRef, ComplianceAudit.CONSENT_WITHDRAWN, ev);
        processors.cease(c, tenantId, principalRef, target, "CONSENT_WITHDRAWN");
        for (WithdrawalListener l : listeners) {
            l.onWithdrawn(c, tenantId, principalRef, consentId, target, k, now);
        }
        return get(c, tenantId, consentId);
    }

    /** Withdraws every active consent of the principal for a purpose (e.g. "stop using my data for X"). */
    public int withdrawPurpose(Connection c, String tenantId, String principalRef, String purposeCode, String channel, String actor) {
        List<String> ids = Jdbc.query(c, "SELECT consent_id FROM CMP_CONSENT_PURPOSE WHERE tenant_id=? AND principal_ref=?"
                + " AND purpose_code=? AND status='ACTIVE'", rs -> rs.getString(1), tenantId, principalRef, purposeCode);
        for (String id : ids) {
            withdraw(c, tenantId, principalRef, id, Set.of(purposeCode), channel, actor);
        }
        return ids.size();
    }

    public boolean isConsented(Connection c, String tenantId, String principalRef, String purposeCode) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM CMP_CONSENT_PURPOSE WHERE tenant_id=? AND principal_ref=? AND purpose_code=?"
                + " AND status='ACTIVE'", rs -> rs.getLong(1), tenantId, principalRef, purposeCode).orElse(0L) > 0;
    }

    public Set<String> activePurposes(Connection c, String tenantId, String principalRef) {
        return new LinkedHashSet<>(Jdbc.query(c, "SELECT DISTINCT purpose_code FROM CMP_CONSENT_PURPOSE WHERE tenant_id=?"
                + " AND principal_ref=? AND status='ACTIVE' ORDER BY purpose_code", rs -> rs.getString(1), tenantId, principalRef));
    }

    public List<Consent> history(Connection c, String tenantId, String principalRef) {
        List<String> ids = Jdbc.query(c, "SELECT consent_id FROM CMP_CONSENT WHERE tenant_id=? AND principal_ref=? ORDER BY given_at",
                rs -> rs.getString(1), tenantId, principalRef);
        List<Consent> out = new ArrayList<>();
        for (String id : ids) {
            out.add(get(c, tenantId, id));
        }
        return out;
    }

    public Optional<Consent> byArtefact(Connection c, String tenantId, String consentManagerId, String artefactId) {
        return Jdbc.one(c, "SELECT consent_id FROM CMP_CONSENT WHERE tenant_id=? AND consent_manager_id=? AND cm_artefact_id=?",
                rs -> rs.getString(1), tenantId, consentManagerId, artefactId).map(id -> get(c, tenantId, id));
    }

    public Consent get(Connection c, String tenantId, String consentId) {
        Set<String> active = new LinkedHashSet<>(Jdbc.query(c, "SELECT purpose_code FROM CMP_CONSENT_PURPOSE WHERE tenant_id=?"
                + " AND consent_id=? AND status='ACTIVE' ORDER BY purpose_code", rs -> rs.getString(1), tenantId, consentId));
        return Jdbc.one(c, "SELECT consent_id, principal_ref, notice_key, notice_version, language, status, given_at, withdrawn_at,"
                        + " consent_manager_id, cm_artefact_id, legacy, evidence_hash FROM CMP_CONSENT WHERE tenant_id=? AND consent_id=?",
                rs -> new Consent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                        Status.valueOf(rs.getString(6)), Jdbc.instant(rs, "given_at"), Jdbc.instant(rs, "withdrawn_at"),
                        rs.getString(9), rs.getString(10), Sql.bool(rs.getInt(11)), active, rs.getString(12)),
                tenantId, consentId).orElseThrow(() -> ComplianceException.notFound("consent", consentId));
    }

    /** Counts for the evidence report. */
    public Map<String, Long> counts(Connection c, String tenantId, Instant from, Instant to) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("given", Jdbc.one(c, "SELECT COUNT(*) FROM CMP_CONSENT WHERE tenant_id=? AND given_at>=? AND given_at<?",
                rs -> rs.getLong(1), tenantId, from, to).orElse(0L));
        m.put("withdrawn", Jdbc.one(c, "SELECT COUNT(*) FROM CMP_CONSENT_PURPOSE WHERE tenant_id=? AND withdrawn_at>=? AND withdrawn_at<?",
                rs -> rs.getLong(1), tenantId, from, to).orElse(0L));
        m.put("active", Jdbc.one(c, "SELECT COUNT(*) FROM CMP_CONSENT WHERE tenant_id=? AND status<>'WITHDRAWN'",
                rs -> rs.getLong(1), tenantId).orElse(0L));
        m.put("legacyAwaitingNotice", (long) notices.pendingLegacyNotices(c, tenantId, 100_000).size());
        m.put("viaConsentManager", Jdbc.one(c, "SELECT COUNT(*) FROM CMP_CONSENT WHERE tenant_id=? AND consent_manager_id IS NOT NULL",
                rs -> rs.getLong(1), tenantId).orElse(0L));
        return m;
    }
}
