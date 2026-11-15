// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.delegation;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.power.LegacyPowerContracts;
import org.sangam.core.power.OperationMetadata;
import org.sangam.core.power.PowerDecision;
import org.sangam.core.power.PowerLimitEvaluator;
import org.sangam.core.power.PowerOutcome;
import org.sangam.core.power.PowerRequest;
import org.sangam.core.power.PowerRuleSource;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.org.OrgModel;
import org.sangam.security.org.OrgUnitService;
import org.sangam.security.org.PostingService;
import org.sangam.security.org.SeatService;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Delegation of powers as <b>versioned DMN tables</b> (IX.5 rule 3, K6, AJ-03), checked at the time of action.
 *
 * <h2>Lifecycle</h2>
 * {@code DRAFT → IN_REVIEW → APPROVED → PUBLISHED → RETIRED}. A draft is parsed and its test cases are run (decimal
 * boundaries, overlaps, gaps); the reviewer must differ from the author (K5); publishing sets the effective date and
 * closes the previous version at that date. Earlier decisions keep the version they recorded (AJ-03 step 5).
 *
 * <h2>Check</h2>
 * Decided by the one power-limit evaluator of sangam-core ({@link PowerLimitEvaluator}, contract
 * {@value PowerDecision#CONTRACT_VERSION}; owner decision 142, RM-08). This service only supplies its inputs:
 * <ul>
 *   <li><b>The request.</b> Actor (the authenticated identity of {@link #require}; the seat itself, written
 *       {@code seat:<id>}, where no person is part of the call: {@link #check}, {@link #impact} and the draft test
 *       cases), tenant, the seat's unit as the office, the stored case type and the exact amount. The port carries no
 *       action, case-type version or currency, so the caller states them in {@code extraInputs} under
 *       {@link #IN_ACTION}, {@link #IN_CASE_TYPE_VERSION} and {@link #IN_CURRENCY} ({@link #IN_STATE_VERSION} is
 *       optional); they are read from there and never reach the table. A missing one denies.</li>
 *   <li><b>Trusted metadata.</b> Whether the action is monetary, its currency and its limit table come from the
 *       {@link OperationMetadata} the host binds ({@link #useOperationMetadata}: the deployed pack manifest, owner
 *       decision 193). None bound, or no declaration for the case type, version and action: denied. A
 *       {@code tableKey} other than the one the manifest declares is denied.</li>
 *   <li><b>The rules.</b> Every row the version in force on the action date matches for the seat (designation, unit,
 *       level, rank), the amount and the manifest's rule case type (owner decision 222), each one a delegated limit
 *       ({@link LegacyPowerContracts#serviceRows}); all must pass, so the lowest ceiling binds and an
 *       {@code UNLIMITED} row never lifts another row's ceiling. The posting power cap of the person acting for the
 *       seat is a mandatory cap that {@code UNLIMITED} does not remove.</li>
 * </ul>
 * No table in force, an inactive seat, no matched row, a row without a limit, a malformed value and a failed
 * evaluation are all denials with a reason, never an exception and never a permission. {@link #require} writes
 * {@code AUTHORITY_DECISION} (or, refused, {@code POWER_LIMIT_EXCEEDED} on the denial chain) with the decision
 * reference (table key, version, content hash, matched rule, limit, effective date; X.2.3.2 {@code decision_ref}) and
 * the evaluator's own payload under {@code power_decision} (contract version, actor, action, scope, amount, currency,
 * metadata and rule versions, applicable limits and caps, decision, reason).
 */
public final class DelegationOfPowersService implements SecurityPorts.DelegationOfPowers {

    /** A draft test case: inputs plus the expected outcome. */
    public record PowerTestCase(String name, String seatId, String caseType, BigDecimal amount, Map<String, Object> extra,
                                boolean expectAllowed) {
    }

    public record PowerTable(String tenantId, String tableKey, int version, String status, Instant effectiveFrom,
                             Instant effectiveTo, String dmnXml, String dmnHash, String decisionId, String authorityRef,
                             String author, String approver) {
    }

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final SecurityAuditService audit;
    private final SeatService seats;
    private final OrgUnitService units;
    private final PostingService postings;
    private final DmnPowerRows tables;

    /** No trusted metadata until the host binds it: every check is denied (owner decisions 142 and 193). */
    private volatile OperationMetadata metadata = (tenant, caseType, caseTypeVersion, action) -> Optional.empty();

    /** {@code extraInputs} key: routes the audit event of {@link #require} to that case's chain. */
    public static final String IN_CASE_ID = "caseId";
    /** {@code extraInputs} key: the action being authorised, as the deployed manifest names it. */
    public static final String IN_ACTION = "action";
    /** {@code extraInputs} key: the version of the stored case type (an integer). */
    public static final String IN_CASE_TYPE_VERSION = "caseTypeVersion";
    /** {@code extraInputs} key: the ISO 4217 currency of the amount, read from the stored case or transaction. */
    public static final String IN_CURRENCY = "currency";
    /** {@code extraInputs} key (optional): the version of the state the amount was read from. */
    public static final String IN_STATE_VERSION = "stateVersion";

    public DelegationOfPowersService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit,
                                     SeatService seats, OrgUnitService units, PostingService postings,
                                     DmnPowerRows tables) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
        this.seats = seats;
        this.units = units;
        this.postings = postings;
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    /**
     * Binds the trusted case-type metadata (the deployed pack manifests, owner decision 193). Until a host binds it,
     * no operation is classified and every check is denied.
     */
    public void useOperationMetadata(OperationMetadata trusted) {
        this.metadata = Objects.requireNonNull(trusted, "metadata");
    }

    // ------------------------------------------------------------------ lifecycle

    public PowerTable draft(Connection c, SecurityIdentity author, String tableKey, String decisionId, String dmnXml,
                            Instant effectiveFrom, String authorityRef, String changeNote, List<PowerTestCase> tests) {
        authz.require(c, author, ResourceRegistry.POWER_TABLE, Permission.ADMIN, tableKey);
        String t = author.tenantId();
        if (effectiveFrom == null) {
            throw new SangamSecurityException.AdminConflict("EFFECTIVE_DATE_REQUIRED", "a power table needs an effective date");
        }
        String hash = Hashes.hex(Hashes.sha256(dmnXml));
        tables.parse(hash, decisionId, dmnXml);
        runTests(c, t, tableKey, hash, decisionId, dmnXml, effectiveFrom, tests);
        int version = Jdbc.one(c, "SELECT COALESCE(MAX(version), 0) FROM SEC_POWER_TABLE WHERE tenant_id=? AND table_key=?",
                rs -> rs.getInt(1), t, tableKey).orElse(0) + 1;
        Jdbc.update(c, "INSERT INTO SEC_POWER_TABLE(tenant_id, table_key, version, status, effective_from, effective_to, dmn_xml,"
                        + " dmn_hash, decision_id, authority_ref, change_note, author, approver, created_at, published_at)"
                        + " VALUES (?,?,?,'DRAFT',?,NULL,?,?,?,?,?,?,NULL,?,NULL)",
                t, tableKey, version, effectiveFrom, dmnXml, hash, decisionId, authorityRef, changeNote, author.personId(),
                Sql.dbNow(c, dialect));
        return table(c, t, tableKey, version).orElseThrow();
    }

    public void submitForReview(Connection c, SecurityIdentity author, String tableKey, int version) {
        transition(c, author, tableKey, version, "DRAFT", "IN_REVIEW", Permission.ADMIN, SecurityEventType.PACKAGE_SUBMITTED_FOR_REVIEW);
    }

    /** Approval by a reviewer who is not the author (AJ-03 step 4, K5). */
    public void approve(Connection c, SecurityIdentity reviewer, String tableKey, int version) {
        PowerTable pt = table(c, reviewer.tenantId(), tableKey, version).orElseThrow(() -> unknown(tableKey, version));
        if (reviewer.personId().equals(pt.author())) {
            throw new SangamSecurityException.SodViolation("the author of a power table cannot approve it");
        }
        transition(c, reviewer, tableKey, version, "IN_REVIEW", "APPROVED", Permission.REVIEW_PACKAGE, SecurityEventType.PACKAGE_APPROVED);
        Jdbc.update(c, "UPDATE SEC_POWER_TABLE SET approver=? WHERE tenant_id=? AND table_key=? AND version=?",
                reviewer.personId(), reviewer.tenantId(), tableKey, version);
    }

    /** Publishes an approved version; it becomes effective at its effective date (AJ-03 step 5). */
    public void publish(Connection c, SecurityIdentity actor, String tableKey, int version) {
        PowerTable pt = table(c, actor.tenantId(), tableKey, version).orElseThrow(() -> unknown(tableKey, version));
        transition(c, actor, tableKey, version, "APPROVED", "PUBLISHED", Permission.ADMIN, SecurityEventType.PACKAGE_PUBLISHED);
        Instant from = pt.effectiveFrom();
        Jdbc.update(c, "UPDATE SEC_POWER_TABLE SET effective_to=? WHERE tenant_id=? AND table_key=? AND status='PUBLISHED'"
                        + " AND version <> ? AND effective_from < ? AND (effective_to IS NULL OR effective_to > ?)",
                from, actor.tenantId(), tableKey, version, from, from);
        Jdbc.update(c, "UPDATE SEC_POWER_TABLE SET published_at=? WHERE tenant_id=? AND table_key=? AND version=?",
                Sql.dbNow(c, dialect), actor.tenantId(), tableKey, version);
    }

    public void retire(Connection c, SecurityIdentity actor, String tableKey, int version) {
        transition(c, actor, tableKey, version, "PUBLISHED", "RETIRED", Permission.ADMIN, SecurityEventType.PACKAGE_RETIRED);
    }

    /** The published version effective at {@code at}. */
    public Optional<PowerTable> versionAt(Connection c, String tenant, String tableKey, Instant at) {
        List<PowerTable> rows = Jdbc.query(c, "SELECT version FROM SEC_POWER_TABLE WHERE tenant_id=? AND table_key=?"
                        + " AND status='PUBLISHED' AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)"
                        + " ORDER BY effective_from DESC, version DESC",
                rs -> table(c, tenant, tableKey, rs.getInt(1)).orElseThrow(), tenant, tableKey, at, at);
        return rows.stream().findFirst();
    }

    /** AJ-03 step 3: what a draft would decide for given cases, compared with the version in force. */
    public Map<String, Boolean> impact(Connection c, String tenant, String tableKey, int draftVersion, List<PowerTestCase> cases) {
        PowerTable draft = table(c, tenant, tableKey, draftVersion).orElseThrow(() -> unknown(tableKey, draftVersion));
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (PowerTestCase tc : cases) {
            out.put(tc.name(), decide(c, tenant, tableKey, draft, SecurityIdentity.SEAT + tc.seatId(), tc.seatId(),
                    tc.caseType(), tc.amount(), tc.extra(), draft.effectiveFrom(), null).decision().allowed());
        }
        return out;
    }

    // ------------------------------------------------------------------ action-time check (K6)

    @Override
    public SecurityPorts.PowerDecision check(Connection c, String tenant, String tableKey, String seatId, String caseType,
                                             BigDecimal amount, Instant actionTime, Map<String, Object> extraInputs) {
        return decide(c, tenant, tableKey, null, SecurityIdentity.SEAT + seatId, seatId, caseType, amount, extraInputs,
                actionTime, null).decision();
    }

    /**
     * Checks the actor's power for the seat at the database time, records {@code AUTHORITY_DECISION}, and throws
     * {@code POWER_LIMIT_EXCEEDED} when the power is insufficient. {@code extraInputs.caseId}, when present, routes
     * the audit event to that case's chain (it is not passed to the table).
     */
    @Override
    public SecurityPorts.PowerDecision require(Connection c, SecurityIdentity actor, String tableKey, String seatId,
                                               String caseType, BigDecimal amount, Map<String, Object> extraInputs) {
        String t = actor.tenantId();
        Instant now = Sql.dbNow(c, dialect);
        SecurityPorts.SeatHolder holder = actor.system() ? null : postings.requireHolder(c, t, actor.personId(), seatId, now);
        Object caseId = extraInputs == null ? null : extraInputs.get(IN_CASE_ID);
        Decided decided = decide(c, t, tableKey, null, actor.personId(), seatId, caseType, amount, extraInputs, now, holder);
        SecurityPorts.PowerDecision d = decided.decision();
        Map<String, Object> p = new LinkedHashMap<>();
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("table_key", tableKey);
        ref.put("version", d.tableVersion());
        ref.put("artifact_hash", d.dmnHash());
        ref.put("matched_rule", d.matchedRule());
        ref.put("effective_from", d.effectiveFrom());
        ref.put("limit", d.limit());
        p.put("decision_ref", ref);
        p.put("seat_id", seatId);
        if (holder != null && holder.actingFor()) {
            p.put("acted_for_seat_id", seatId);
        }
        p.put("case_type", caseType);
        p.put("amount", amount);
        p.put("outcome", d.allowed() ? "SUCCESS" : "DENIED");
        p.put("reason", d.reason());
        p.put("power_decision", decided.outcome().auditPayload());
        String root = caseId == null ? null : caseId.toString();
        if (!d.allowed()) {
            audit.denial(c, actor, SecurityEventType.POWER_LIMIT_EXCEEDED, root, p);
            throw new SangamSecurityException.PowerLimitExceeded(d.reason());
        }
        audit.record(c, actor, SecurityEventType.AUTHORITY_DECISION, root, p);
        return d;
    }

    // ------------------------------------------------------------------ internals

    /** The port's answer together with the evaluator's outcome (its audit payload). */
    private record Decided(SecurityPorts.PowerDecision decision, PowerOutcome outcome) {
    }

    /** What the rule source read, for the port's answer: the table version, the first rule reference, the rules. */
    private static final class Seen {
        private PowerTable table;
        private String ruleRef;
        private PowerRuleSource.ApplicableRules rules;
    }

    /**
     * One evaluation through the core evaluator. {@code fixed} is the table version to evaluate (a draft under test or
     * an impact preview); null evaluates the version in force at {@code at}.
     */
    private Decided decide(Connection c, String tenant, String tableKey, PowerTable fixed, String actorId, String seatId,
                           String caseType, BigDecimal amount, Map<String, Object> extra, Instant at,
                           SecurityPorts.SeatHolder holder) {
        Map<String, Object> in = new LinkedHashMap<>(extra == null ? Map.of() : extra);
        in.remove(IN_CASE_ID);
        String action = text(in.remove(IN_ACTION));
        int caseTypeVersion = caseTypeVersion(in.remove(IN_CASE_TYPE_VERSION));
        String currency = text(in.remove(IN_CURRENCY));
        long stateVersion = stateVersion(in.remove(IN_STATE_VERSION));
        Optional<OrgModel.Seat> seat = seats.seatAt(c, tenant, seatId, at).filter(s -> "ACTIVE".equals(s.status()));
        Seen seen = new Seen();
        PowerRuleSource source = (request, cls) -> {
            seen.rules = rules(c, tenant, tableKey, fixed, seatId, seat, in, at, holder, request, cls, seen);
            return seen.rules;
        };
        PowerOutcome outcome = new PowerLimitEvaluator(metadata, source).evaluate(new PowerRequest(actorId, action,
                tenant, seat.map(OrgModel.Seat::unitId).orElse(null), caseType, caseTypeVersion, amount, currency,
                stateVersion));
        // the binding ceiling: the lowest delegated limit and mandatory cap that applied (null: none, or unlimited)
        BigDecimal limit = null;
        if (seen.rules != null) {
            for (PowerRuleSource.DelegatedLimit l : seen.rules.limits()) {
                if (l.decision() instanceof PowerDecision.Limited lim) {
                    limit = limit == null ? lim.maxAmount() : limit.min(lim.maxAmount());
                }
            }
            for (PowerRuleSource.MandatoryCap cap : seen.rules.caps()) {
                limit = limit == null ? cap.maxAmount() : limit.min(cap.maxAmount());
            }
        }
        PowerTable pt = seen.table;
        return new Decided(new SecurityPorts.PowerDecision(outcome.permitted(), tableKey, pt == null ? 0 : pt.version(),
                pt == null ? null : pt.dmnHash(), pt == null ? null : pt.effectiveFrom(), limit, seen.ruleRef,
                outcome.reason()), outcome);
    }

    /**
     * The rules of one request (the evaluator's {@link PowerRuleSource}): read only from the limit table the trusted
     * metadata names. Anything that keeps the table from answering is a single denying limit with its reason.
     */
    private PowerRuleSource.ApplicableRules rules(Connection c, String tenant, String tableKey, PowerTable fixed,
                                                  String seatId, Optional<OrgModel.Seat> seat,
                                                  Map<String, Object> extras, Instant at,
                                                  SecurityPorts.SeatHolder holder, PowerRequest request,
                                                  OperationMetadata.Classification cls, Seen seen) {
        if (!cls.limitTableKey().equals(tableKey)) {
            return none(tableKey, "the case type declares limit table " + cls.limitTableKey() + " ("
                    + cls.metadataVersion() + "), not " + tableKey);
        }
        Optional<PowerTable> inForce = fixed != null ? Optional.of(fixed) : versionAt(c, tenant, tableKey, at);
        if (inForce.isEmpty()) {
            return none(tableKey, "no delegation-of-powers table in force for " + tableKey);
        }
        PowerTable pt = inForce.get();
        seen.table = pt;
        if (seat.isEmpty()) {
            return none(tableKey, "seat " + seatId + " is not active");
        }
        OrgModel.Seat s = seat.get();
        Map<String, Object> inputs = new LinkedHashMap<>(extras);
        inputs.put("seatId", seatId);
        inputs.put("designation", s.designation());
        inputs.put("unitId", s.unitId());
        inputs.put("rankNo", s.rankNo());
        inputs.put("levelType", s.unitId() == null ? null
                : units.unitAt(c, tenant, s.unitId(), at).map(OrgModel.OrgUnit::levelType).orElse(null));
        inputs.put("amount", request.amount());
        // decision 222: the table's caseType is the manifest's rule case type or absent, never a caller value
        List<Map<String, Object>> rows = tables.rows(pt.dmnHash(), pt.decisionId(), pt.dmnXml(),
                new LinkedHashMap<>(cls.ruleInputs(inputs)));
        for (Map<String, Object> row : rows) {
            if (seen.ruleRef == null && row.get("ruleRef") != null) {
                seen.ruleRef = row.get("ruleRef").toString();
            }
        }
        return applicable(pt.tableKey(), pt.version(), rows, cls, request.currency(), seatId, holder);
    }

    /**
     * Contract S of {@link LegacyPowerContracts} (the mapping decision 142 fixed for this service): every matched row is
     * a delegated limit in the table's currency (the manifest's for a monetary action, else the request's), and the
     * posting power cap of the person acting for the seat is a mandatory cap. No cap is invented for a system actor.
     */
    static PowerRuleSource.ApplicableRules applicable(String tableKey, int version, List<Map<String, Object>> rows,
                                                      OperationMetadata.Classification cls, String requestCurrency,
                                                      String seatId, SecurityPorts.SeatHolder holder) {
        String tableCurrency = cls.monetary() ? cls.currency() : requestCurrency;
        List<PowerRuleSource.MandatoryCap> caps = !cls.monetary() || holder == null || holder.powerCap() == null
                ? List.of()
                : LegacyPowerContracts.postingCap(seatId, holder.postingId() == null ? "posting" : holder.postingId(),
                        holder.powerCap(), cls.currency()).stream().toList();
        return new PowerRuleSource.ApplicableRules(
                LegacyPowerContracts.serviceRows(tableKey, version, rows, tableCurrency), caps);
    }

    /** No rule could be read: one denying limit that says why. */
    private static PowerRuleSource.ApplicableRules none(String tableKey, String reason) {
        return new PowerRuleSource.ApplicableRules(List.of(new PowerRuleSource.DelegatedLimit(tableKey, "0",
                new PowerDecision.Deny(reason))), List.of());
    }

    private static String text(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    /** The stated case-type version; absent or not an integer: -1, which no deployment has (the evaluator denies). */
    private static int caseTypeVersion(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            long n = ((Number) v).longValue();
            return n < 0 || n > Integer.MAX_VALUE ? -1 : (int) n;
        }
        try {
            return v == null ? -1 : Math.max(-1, Integer.parseInt(v.toString().trim()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The stated state version (0 when absent). A value that is not an integer is a caller error, never a default. */
    private static long stateVersion(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(IN_STATE_VERSION + " must be an integer");
        }
    }

    private void runTests(Connection c, String tenant, String tableKey, String hash, String decisionId, String xml,
                          Instant from, List<PowerTestCase> tests) {
        if (tests == null) {
            return;
        }
        PowerTable probe = new PowerTable(tenant, tableKey, 0, "DRAFT", from, null, xml, hash, decisionId, null, null, null);
        for (PowerTestCase tc : tests) {
            boolean got = decide(c, tenant, tableKey, probe, SecurityIdentity.SEAT + tc.seatId(), tc.seatId(),
                    tc.caseType(), tc.amount(), tc.extra(), from, null).decision().allowed();
            if (got != tc.expectAllowed()) {
                throw new SangamSecurityException.PolicyRejected("test case '" + tc.name() + "' expected "
                        + (tc.expectAllowed() ? "allowed" : "denied") + " but was " + (got ? "allowed" : "denied"));
            }
        }
    }

    private void transition(Connection c, SecurityIdentity actor, String tableKey, int version, String from, String to,
                            Permission perm, SecurityEventType event) {
        authz.require(c, actor, ResourceRegistry.POWER_TABLE, perm, tableKey);
        int n = Jdbc.update(c, "UPDATE SEC_POWER_TABLE SET status=? WHERE tenant_id=? AND table_key=? AND version=? AND status=?",
                to, actor.tenantId(), tableKey, version, from);
        if (n != 1) {
            throw new SangamSecurityException.AdminConflict("INVALID_STATE", "power table " + tableKey + " v" + version
                    + " is not " + from);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("artifact", "POWER_TABLE");
        p.put("table_key", tableKey);
        p.put("version", version);
        p.put("status", to);
        audit.security(c, actor, event, p);
    }

    public Optional<PowerTable> table(Connection c, String tenant, String tableKey, int version) {
        return Jdbc.one(c, "SELECT table_key, version, status, effective_from, effective_to, dmn_xml, dmn_hash, decision_id,"
                        + " authority_ref, author, approver FROM SEC_POWER_TABLE WHERE tenant_id=? AND table_key=? AND version=?",
                rs -> new PowerTable(tenant, rs.getString(1), rs.getInt(2), rs.getString(3), Jdbc.instant(rs, "effective_from"),
                        Jdbc.instant(rs, "effective_to"), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11)),
                tenant, tableKey, version);
    }

    private static SangamSecurityException unknown(String key, int version) {
        return new SangamSecurityException.AdminConflict("UNKNOWN_POWER_TABLE", "power table " + key + " v" + version + " not found");
    }
}
