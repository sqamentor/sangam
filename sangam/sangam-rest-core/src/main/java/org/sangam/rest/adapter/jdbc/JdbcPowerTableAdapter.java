// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.sangam.rest.adapter.jdbc.JdbcSupport.payload;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.required;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.operaton.bpm.model.dmn.DmnModelInstance;
import org.operaton.bpm.model.dmn.instance.Decision;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.adapter.operaton.DmnTesting;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.PowerTablePort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Delegation-of-powers tables (IX.5 rule 3, K6) over the sangam-security table {@code SEC_POWER_TABLE}
 * (PK {@code tenant_id, table_key, version}; columns added by changeset 0016-04: {@code name}, {@code test_cases},
 * {@code deployment_id}, {@code row_revision}). Module-disabled default: sangam-security owns the table in production.
 *
 * <ul>
 *   <li>REST id = {@code <key>@<version>} (stable, derived from the primary key).</li>
 *   <li>Lifecycle DRAFT → IN_REVIEW → APPROVED → ACTIVE (→ SUPERSEDED by the next ACTIVE version of the key), or
 *       IN_REVIEW → REJECTED. Editing an ACTIVE, SUPERSEDED, REJECTED or APPROVED table creates a new DRAFT version.</li>
 *   <li>Four-eyes: the approver differs from the author by person and by seat (422 SEGREGATION_OF_DUTIES).</li>
 *   <li>Activation deploys the DMN as {@code power-table:<key>:v<version>} (resource {@code <key>.dmn}), so
 *       {@code POST /decisions/{key}/evaluate} uses it; {@code published_at} records the activation.</li>
 *   <li>An approved table with a future {@code effectiveFrom} stays APPROVED; it is activated lazily on the first
 *       {@code list}/{@code get} after that instant (no scheduler in this adapter).</li>
 *   <li>Column mapping: {@code status} = state, {@code dmn_hash} = SHA-256 hex of the DMN, {@code decision_id} = the first
 *       decision table id, {@code row_revision} = the REST revision. {@code effective_from} is NOT NULL, so a table
 *       without an effective date stores its creation time and the flag {@code effectiveFromSet=false};
 *       {@code test_cases} holds {@code {"cases": [...], "authorSeat": ..., "effectiveFromSet": bool}}.</li>
 * </ul>
 */
public final class JdbcPowerTableAdapter implements PowerTablePort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLS = "table_key, version, status, effective_from, dmn_xml, author, approver,"
            + " created_at, name, test_cases, deployment_id, row_revision";

    /** A stored table plus the author's seat (four-eyes by seat). */
    private record Entry(PowerTable table, String authorSeat) {
    }

    private final TxSupport tx;
    private final DeploymentPort deployments;
    private final PermissionPort permissions;
    private final AuditPort audit;
    private final RestProperties props;

    public JdbcPowerTableAdapter(TxSupport tx, DeploymentPort deployments, PermissionPort permissions, AuditPort audit,
                                 RestProperties props) {
        this.tx = tx;
        this.deployments = deployments;
        this.permissions = permissions;
        this.audit = audit;
        this.props = props;
    }

    @Override
    public Page<PowerTable> list(RequestContext ctx, String q, Page.PageRequest page) {
        activateDue(ctx);
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        List<PowerTable> all = load(tenant(ctx), null).stream().map(Entry::table)
                .filter(t -> needle == null || t.key().toLowerCase(Locale.ROOT).contains(needle)
                        || (t.name() != null && t.name().toLowerCase(Locale.ROOT).contains(needle)))
                .sorted(Comparator.comparing(PowerTable::key).thenComparing(PowerTable::version))
                .toList();
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public PowerTable get(RequestContext ctx, String id) {
        activateDue(ctx);
        return entry(ctx, id).table();
    }

    @Override
    public synchronized PowerTable create(RequestContext ctx, PowerTable t) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        validate(t);
        String tenant = tenant(ctx);
        return tx.inTx(() -> {
            int version = tx.withConnection(c -> Jdbc.one(c,
                    "SELECT MAX(version) FROM SEC_POWER_TABLE WHERE tenant_id=? AND table_key=?",
                    rs -> rs.getInt(1), tenant, t.key()).orElse(0)) + 1;
            PowerTable created = new PowerTable(t.key() + "@" + version, t.key(), t.name(), version, "DRAFT",
                    t.effectiveFrom(), ctx.principalId(), null, t.dmnXml(),
                    t.testCases() == null ? List.of() : List.copyOf(t.testCases()), null, 1);
            Entry e = new Entry(created, ctx.seatId());
            insert(tenant, e);
            record(ctx, "POWER_TABLE_DRAFT_SAVED", created, null);
            return created;
        });
    }

    @Override
    public synchronized PowerTable update(RequestContext ctx, String id, PowerTable t) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        return tx.inTx(() -> {
            Entry e = entry(ctx, id);
            PowerTable cur = e.table();
            validate(new PowerTable(id, cur.key(), t.name() == null ? cur.name() : t.name(), cur.version(), cur.state(),
                    t.effectiveFrom(), cur.author(), null, t.dmnXml() == null ? cur.dmnXml() : t.dmnXml(), t.testCases(),
                    null, cur.revision()));
            if (t.key() != null && !t.key().equals(cur.key())) {
                throw JdbcSupport.invalid("key", "the key of a power table cannot change");
            }
            String name = t.name() == null ? cur.name() : t.name();
            String xml = t.dmnXml() == null ? cur.dmnXml() : t.dmnXml();
            List<TestCase> tests = t.testCases() == null ? cur.testCases() : List.copyOf(t.testCases());
            switch (cur.state()) {
                case "DRAFT" -> {
                    JdbcSupport.checkRevision(t.revision(), cur.revision(), "Power table", id);
                    PowerTable saved = new PowerTable(id, cur.key(), name, cur.version(), "DRAFT", t.effectiveFrom(),
                            cur.author(), null, xml, tests, null, cur.revision() + 1);
                    save(tenant(ctx), new Entry(saved, e.authorSeat()));
                    record(ctx, "POWER_TABLE_DRAFT_SAVED", saved, null);
                    return saved;
                }
                case "ACTIVE", "SUPERSEDED", "REJECTED", "APPROVED" -> {
                    // A published or closed version is immutable: the edit becomes the next DRAFT version of the key.
                    return create(ctx, new PowerTable(null, cur.key(), name, 0, null, t.effectiveFrom(), null, null, xml,
                            tests, null, 0));
                }
                default -> throw ApiProblem.conflict("INVALID_TRANSITION",
                        "A power table in state " + cur.state() + " cannot be edited");
            }
        });
    }

    @Override
    public CheckResult check(RequestContext ctx, String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        PowerTable t = entry(ctx, id).table();
        DmnModelInstance model = DmnTesting.parse(t.dmnXml());
        Decision decision = DmnTesting.decisionTables(model).get(0);
        String hitPolicy = DmnTesting.hitPolicy(decision);
        List<String> gaps = new ArrayList<>();
        List<String> overlaps = new ArrayList<>();
        List<TestOutcome> outcomes = new ArrayList<>();
        for (TestCase tc : t.testCases()) {
            DmnTesting.Evaluation ev = DmnTesting.evaluate(t.dmnXml(), decision.getId(), tc.inputs());
            if (ev.matchingRuleIds().isEmpty()) {
                gaps.add("test " + tc.id() + ": no rule matches");
            } else if (ev.matchingRuleIds().size() > 1 && "UNIQUE".equals(hitPolicy)) {
                overlaps.add("test " + tc.id() + ": rules " + String.join(",", ev.matchingRuleIds()) + " overlap");
            }
            boolean passed = ev.error() == null && DmnTesting.matches(tc.expected(), ev.results());
            outcomes.add(new TestOutcome(tc.id(), passed, DmnTesting.actual(ev)));
        }
        return new CheckResult(gaps, overlaps, hitPolicy, outcomes);
    }

    @Override
    public synchronized PowerTable transition(RequestContext ctx, String id, String action, TransitionRequest request) {
        String tenant = tenant(ctx);
        return tx.inTx(() -> {
            Entry e = entry(ctx, id);
            PowerTable t = e.table();
            TransitionRequest r = request == null ? new TransitionRequest(null, null) : request;
            String a = required(action, "action").toLowerCase(Locale.ROOT);
            PowerTable result;
            switch (a) {
                case "submit" -> {
                    permissions.require(ctx, Permissions.ADMIN_WRITE);
                    expect(t, "DRAFT", a);
                    if (!Objects.equals(ctx.principalId(), t.author())) {
                        throw ApiProblem.forbidden("ACTION_NOT_PERMITTED", "Only the author submits a power table");
                    }
                    result = with(t, "IN_REVIEW", t.approver(),
                            r.effectiveFrom() == null ? t.effectiveFrom() : r.effectiveFrom(), t.deploymentId());
                    save(tenant, new Entry(result, e.authorSeat()));
                    record(ctx, "POWER_TABLE_SUBMITTED", result, r.reason());
                }
                case "approve" -> {
                    permissions.require(ctx, Permissions.POWER_TABLE_APPROVE);
                    expect(t, "IN_REVIEW", a);
                    boolean samePerson = Objects.equals(ctx.principalId(), t.author());
                    boolean sameSeat = ctx.seatId() != null && Objects.equals(ctx.seatId(), e.authorSeat());
                    if (samePerson || sameSeat) {
                        throw ApiProblem.unprocessable("SEGREGATION_OF_DUTIES",
                                "The approver of a power table must differ from its author by person and by seat");
                    }
                    Instant effective = r.effectiveFrom() == null ? t.effectiveFrom() : r.effectiveFrom();
                    result = with(t, "APPROVED", ctx.principalId(), effective, t.deploymentId());
                    Entry approved = new Entry(result, e.authorSeat());
                    save(tenant, approved);
                    record(ctx, "POWER_TABLE_APPROVED", result, r.reason());
                    if (effective == null || !effective.isAfter(Instant.now())) {
                        result = activate(ctx, approved);
                    }
                }
                case "reject" -> {
                    permissions.require(ctx, Permissions.POWER_TABLE_APPROVE);
                    expect(t, "IN_REVIEW", a);
                    if (r.reason() == null || r.reason().isBlank()) {
                        throw ApiProblem.badRequest("REASON_REQUIRED", "A rejection needs a reason");
                    }
                    result = with(t, "REJECTED", ctx.principalId(), t.effectiveFrom(), t.deploymentId());
                    save(tenant, new Entry(result, e.authorSeat()));
                    record(ctx, "POWER_TABLE_REJECTED", result, r.reason());
                }
                default -> throw JdbcSupport.invalid("action", "action must be submit, approve or reject");
            }
            return result;
        });
    }

    // ------------------------------------------------------------------ helpers

    private void activateDue(RequestContext ctx) {
        Instant now = Instant.now();
        for (Entry e : load(tenant(ctx), null)) {
            if ("APPROVED".equals(e.table().state()) && e.table().effectiveFrom() != null
                    && !e.table().effectiveFrom().isAfter(now)) {
                synchronized (this) {
                    tx.runInTx(() -> {
                        Entry fresh = find(tenant(ctx), e.table().id());
                        if (fresh != null && "APPROVED".equals(fresh.table().state())) {
                            activate(ctx, fresh);
                        }
                    });
                }
            }
        }
    }

    private PowerTable activate(RequestContext ctx, Entry e) {
        String tenant = tenant(ctx);
        PowerTable t = e.table();
        DeploymentPort.Deployment d = deployments.deploy(ctx, new DeploymentPort.DeployRequest(
                "power-table:" + t.key() + ":v" + t.version(), "sangam-power-table", false,
                Map.of(t.key() + ".dmn", t.dmnXml().getBytes(StandardCharsets.UTF_8))));
        for (Entry other : load(tenant, t.key())) {
            if (other.table().version() != t.version() && "ACTIVE".equals(other.table().state())) {
                save(tenant, new Entry(with(other.table(), "SUPERSEDED", other.table().approver(),
                        other.table().effectiveFrom(), other.table().deploymentId()), other.authorSeat()));
            }
        }
        PowerTable active = with(t, "ACTIVE", t.approver(), t.effectiveFrom() == null ? Instant.now() : t.effectiveFrom(),
                d.id());
        save(tenant, new Entry(active, e.authorSeat()));
        tx.withConnection(c -> Jdbc.update(c, "UPDATE SEC_POWER_TABLE SET published_at=" + tx.dialect().now()
                + " WHERE tenant_id=? AND table_key=? AND version=?", tenant, t.key(), t.version()));
        record(ctx, "POWER_TABLE_ACTIVATED", active, null);
        return active;
    }

    private static void validate(PowerTable t) {
        required(t.key(), "key");
        required(t.name(), "name");
        required(t.dmnXml(), "dmnXml");
        if (!t.key().matches("[A-Za-z0-9._-]{1,64}")) {
            throw JdbcSupport.invalid("key", "key must be 1..64 of [A-Za-z0-9._-]");
        }
        DmnModelInstance model = DmnTesting.parse(t.dmnXml());
        if (DmnTesting.decisionTables(model).isEmpty()) {
            throw ApiProblem.unprocessable("MODEL_INVALID", "A power table must contain a DMN decision table");
        }
        if (t.testCases() != null) {
            for (TestCase tc : t.testCases()) {
                required(tc.id(), "testCases.id");
            }
        }
    }

    private static PowerTable with(PowerTable t, String state, String approver, Instant effectiveFrom,
                                   String deploymentId) {
        return new PowerTable(t.id(), t.key(), t.name(), t.version(), state, effectiveFrom, t.author(), approver,
                t.dmnXml(), t.testCases(), deploymentId, t.revision() + 1);
    }

    private static void expect(PowerTable t, String state, String action) {
        if (!state.equals(t.state())) {
            throw ApiProblem.conflict("INVALID_TRANSITION",
                    "Transition '" + action + "' is not allowed from state " + t.state()).with("state", t.state());
        }
    }

    private void record(RequestContext ctx, String type, PowerTable t, String reason) {
        audit.record(ctx, JdbcSupport.securityChain(ctx), type, payload("powerTableId", t.id(), "key", t.key(),
                "version", t.version(), "state", t.state(), "effectiveFrom",
                t.effectiveFrom() == null ? null : t.effectiveFrom().toString(), "deploymentId", t.deploymentId(),
                "dmn_sha256", Hashes.hex(Hashes.sha256(t.dmnXml())), "reason", reason, "actor", ctx.actor()));
    }

    private static String tenant(RequestContext ctx) {
        return JdbcSupport.tenant(ctx);
    }

    private Entry entry(RequestContext ctx, String id) {
        Entry e = id == null ? null : find(tenant(ctx), id);
        if (e == null) {
            throw ApiProblem.notFound("Power table", id);
        }
        return e;
    }

    // ------------------------------------------------------------------ SEC_POWER_TABLE access

    /** {@code <key>@<version>} → the row, or null. */
    private Entry find(String tenant, String id) {
        int at = id.lastIndexOf('@');
        if (at <= 0 || at == id.length() - 1) {
            return null;
        }
        int version;
        try {
            version = Integer.parseInt(id.substring(at + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        String key = id.substring(0, at);
        return tx.withConnection(c -> Jdbc.one(c, "SELECT " + COLS + " FROM SEC_POWER_TABLE"
                + " WHERE tenant_id=? AND table_key=? AND version=?", JdbcPowerTableAdapter::row, tenant, key, version))
                .orElse(null);
    }

    /** Every version of the tenant, or of one key. */
    private List<Entry> load(String tenant, String key) {
        return tx.withConnection(c -> Jdbc.query(c, "SELECT " + COLS + " FROM SEC_POWER_TABLE WHERE tenant_id=?"
                        + (key == null ? "" : " AND table_key=?") + " ORDER BY table_key, version",
                JdbcPowerTableAdapter::row, key == null ? new Object[]{tenant} : new Object[]{tenant, key}));
    }

    private void insert(String tenant, Entry e) {
        PowerTable t = e.table();
        tx.withConnection(c -> {
            Instant now = Jdbc.dbNow(c, tx.dialect());
            Jdbc.update(c, "INSERT INTO SEC_POWER_TABLE(tenant_id, table_key, version, status, effective_from,"
                            + " dmn_xml, dmn_hash, decision_id, author, approver, created_at, name, test_cases,"
                            + " deployment_id, row_revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenant, t.key(), t.version(), t.state(), t.effectiveFrom() == null ? now : t.effectiveFrom(),
                    t.dmnXml(), Hashes.hex(Hashes.sha256(t.dmnXml())), decisionId(t.dmnXml()), t.author(),
                    t.approver(), now, t.name(), extras(e, t.effectiveFrom() != null), t.deploymentId(), t.revision());
            return null;
        });
    }

    private void save(String tenant, Entry e) {
        PowerTable t = e.table();
        tx.withConnection(c -> {
            Instant effective = t.effectiveFrom() == null
                    ? Jdbc.one(c, "SELECT created_at FROM SEC_POWER_TABLE WHERE tenant_id=? AND table_key=? AND version=?",
                            rs -> Jdbc.instant(rs, "created_at"), tenant, t.key(), t.version()).orElse(Instant.now())
                    : t.effectiveFrom();
            int n = Jdbc.update(c, "UPDATE SEC_POWER_TABLE SET status=?, effective_from=?, dmn_xml=?, dmn_hash=?,"
                            + " decision_id=?, approver=?, name=?, test_cases=?, deployment_id=?, row_revision=?"
                            + " WHERE tenant_id=? AND table_key=? AND version=?",
                    t.state(), effective, t.dmnXml(), Hashes.hex(Hashes.sha256(t.dmnXml())), decisionId(t.dmnXml()),
                    t.approver(), t.name(), extras(e, t.effectiveFrom() != null), t.deploymentId(), t.revision(),
                    tenant, t.key(), t.version());
            if (n == 0) {
                throw ApiProblem.notFound("Power table", t.id());
            }
            return null;
        });
    }

    private static String decisionId(String dmnXml) {
        List<Decision> tables = DmnTesting.decisionTables(DmnTesting.parse(dmnXml));
        return tables.isEmpty() ? "-" : tables.get(0).getId();
    }

    private static String extras(Entry e, boolean effectiveFromSet) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cases", e.table().testCases() == null ? List.of() : e.table().testCases());
        m.put("authorSeat", e.authorSeat());
        m.put("effectiveFromSet", effectiveFromSet);
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException ex) {
            throw JdbcSupport.invalid("testCases", "test cases are not serialisable: " + ex.getOriginalMessage());
        }
    }

    private static Entry row(ResultSet rs) throws SQLException {
        String key = rs.getString("table_key");
        int version = rs.getInt("version");
        List<TestCase> cases = List.of();
        String authorSeat = null;
        boolean effectiveSet = true;
        String extra = rs.getString("test_cases");
        if (extra != null && !extra.isBlank()) {
            try {
                Map<String, Object> m = JSON.readValue(extra, new TypeReference<LinkedHashMap<String, Object>>() { });
                cases = JSON.convertValue(m.getOrDefault("cases", List.of()), new TypeReference<List<TestCase>>() { });
                authorSeat = m.get("authorSeat") == null ? null : String.valueOf(m.get("authorSeat"));
                effectiveSet = !Boolean.FALSE.equals(m.get("effectiveFromSet"));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("SEC_POWER_TABLE.test_cases of " + key + "@" + version + " is not JSON", e);
            }
        }
        long revision = rs.getLong("row_revision");
        if (rs.wasNull()) {
            revision = 1;
        }
        Instant effective = effectiveSet ? Jdbc.instant(rs, "effective_from") : null;
        PowerTable t = new PowerTable(key + "@" + version, key, rs.getString("name"), version, rs.getString("status"),
                effective, rs.getString("author"), rs.getString("approver"), rs.getString("dmn_xml"), cases,
                rs.getString("deployment_id"), revision);
        return new Entry(t, authorSeat);
    }
}
