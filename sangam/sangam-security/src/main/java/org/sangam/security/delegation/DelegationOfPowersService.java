// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.delegation;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
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
 * The version effective on the action date is evaluated for the seat (designation, unit, level, rank) and case type.
 * When the actor acts for the seat (leave/additional charge) a posting power cap, if set, further limits the power.
 * Every check writes {@code AUTHORITY_DECISION} with the decision reference (table key, version, content hash,
 * matched rule, limit, effective date) — X.2.3.2 {@code decision_ref}.
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
    private final DmnPowerEvaluator evaluator;

    public DelegationOfPowersService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit,
                                     SeatService seats, OrgUnitService units, PostingService postings,
                                     DmnPowerEvaluator evaluator) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
        this.seats = seats;
        this.units = units;
        this.postings = postings;
        this.evaluator = evaluator;
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
        evaluator.parse(hash, decisionId, dmnXml);
        runTests(c, t, hash, decisionId, dmnXml, effectiveFrom, tests);
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
            out.put(tc.name(), decide(c, tenant, draft, tc.seatId(), tc.caseType(), tc.amount(), tc.extra(),
                    draft.effectiveFrom(), null).allowed());
        }
        return out;
    }

    // ------------------------------------------------------------------ action-time check (K6)

    @Override
    public SecurityPorts.PowerDecision check(Connection c, String tenant, String tableKey, String seatId, String caseType,
                                             BigDecimal amount, Instant actionTime, Map<String, Object> extraInputs) {
        Optional<PowerTable> pt = versionAt(c, tenant, tableKey, actionTime);
        if (pt.isEmpty()) {
            return new SecurityPorts.PowerDecision(false, tableKey, 0, null, null, null, null,
                    "no delegation-of-powers table in force for " + tableKey);
        }
        return decide(c, tenant, pt.get(), seatId, caseType, amount, extraInputs, actionTime, null);
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
        Map<String, Object> inputs = new LinkedHashMap<>(extraInputs == null ? Map.of() : extraInputs);
        Object caseId = inputs.remove("caseId");
        Optional<PowerTable> pt = versionAt(c, t, tableKey, now);
        SecurityPorts.PowerDecision d = pt.isEmpty()
                ? new SecurityPorts.PowerDecision(false, tableKey, 0, null, null, null, null, "no table in force")
                : decide(c, t, pt.get(), seatId, caseType, amount, inputs, now, holder == null ? null : holder.powerCap());
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
        String root = caseId == null ? null : caseId.toString();
        if (!d.allowed()) {
            audit.denial(c, actor, SecurityEventType.POWER_LIMIT_EXCEEDED, root, p);
            throw new SangamSecurityException.PowerLimitExceeded(d.reason());
        }
        audit.record(c, actor, SecurityEventType.AUTHORITY_DECISION, root, p);
        return d;
    }

    // ------------------------------------------------------------------ internals

    private SecurityPorts.PowerDecision decide(Connection c, String tenant, PowerTable pt, String seatId, String caseType,
                                               BigDecimal amount, Map<String, Object> extra, Instant at, BigDecimal postingCap) {
        OrgModel.Seat seat = seats.requireSeat(c, tenant, seatId, at);
        Map<String, Object> inputs = new LinkedHashMap<>(extra == null ? Map.of() : extra);
        inputs.put("seatId", seatId);
        inputs.put("designation", seat.designation());
        inputs.put("unitId", seat.unitId());
        inputs.put("rankNo", seat.rankNo());
        inputs.put("levelType", seat.unitId() == null ? null
                : units.unitAt(c, tenant, seat.unitId(), at).map(OrgModel.OrgUnit::levelType).orElse(null));
        inputs.put("caseType", caseType);
        inputs.put("amount", amount);
        DmnPowerEvaluator.Evaluation e = evaluator.evaluate(pt.dmnHash(), pt.decisionId(), pt.dmnXml(), inputs);
        BigDecimal limit = e.unlimited() ? null : e.limit();
        if (postingCap != null) {
            limit = limit == null ? postingCap : limit.min(postingCap);
        }
        boolean hasPower = e.allowedFlag() && (e.unlimited() || e.limit() != null);
        boolean within = amount == null || (limit == null ? e.unlimited() : amount.compareTo(limit) <= 0);
        boolean allowed = hasPower && within;
        String reason = allowed ? "within delegated power"
                : !hasPower ? "seat " + seatId + " has no delegated power for " + caseType
                : "amount " + Sql.decimal(amount) + " exceeds the delegated power " + Sql.decimal(limit) + " of seat " + seatId;
        return new SecurityPorts.PowerDecision(allowed, pt.tableKey(), pt.version(), pt.dmnHash(), pt.effectiveFrom(),
                limit, e.ruleRef(), reason);
    }

    private void runTests(Connection c, String tenant, String hash, String decisionId, String xml, Instant from,
                          List<PowerTestCase> tests) {
        if (tests == null) {
            return;
        }
        PowerTable probe = new PowerTable(tenant, "draft", 0, "DRAFT", from, null, xml, hash, decisionId, null, null, null);
        for (PowerTestCase tc : tests) {
            boolean got = decide(c, tenant, probe, tc.seatId(), tc.caseType(), tc.amount(), tc.extra(), from, null).allowed();
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
