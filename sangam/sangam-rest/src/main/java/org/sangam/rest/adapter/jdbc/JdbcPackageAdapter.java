// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.sangam.rest.adapter.jdbc.JdbcSupport.invalid;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.payload;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.required;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Executable packages (R43 / X.2.4, FC-41 app bundles) persisted in the product table {@code PACKAGE_VERSION}:
 * one row per REST package, addressed by {@code package_id} (changeset 0016-05, indexed). Columns:
 * {@code package_key}; {@code version} = the per-key sequence number assigned at creation (the REST {@code revision}
 * counts the edits of that package); {@code package_hash} = the frozen content hash (32 zero bytes until submit);
 * {@code state}; {@code author}, {@code reviewer}, {@code deployer}; {@code engine_deployment_id};
 * {@code created_at} / {@code state_changed_at}; {@code manifest_json} = name, revision, approver, environment,
 * activateAt, resource hashes, the resources themselves (base64), the transition history and the author's seat.
 * Signatures and environment promotion (X.2.4.5) are not produced by this adapter ({@code signature} stays null).
 *
 * <p>Lifecycle (X.2.4.4): DRAFT → submit → IN_REVIEW → approve → APPROVED → deploy → DEPLOYED → activate → ACTIVE;
 * IN_REVIEW → reject → DRAFT (new revision); DRAFT → withdraw → WITHDRAWN; ACTIVE ⇄ SUSPENDED (suspend/resume);
 * ACTIVE → RETIRED when a newer version of the key is activated or on {@code retire}; RETIRED → archive → ARCHIVED.
 * The content hash is frozen at submit. Reviewer/approver and deployer must differ from the author by person and by
 * seat (422 SEGREGATION_OF_DUTIES); the relaxed {@code sod} mode is not offered here.
 * An {@code activateAt} in the future leaves the package DEPLOYED with {@code activateAt} recorded; the timed
 * activation itself is done by the engine-side scheduler (out of scope for this adapter): call {@code activate}
 * again at or after that time.
 */
public final class JdbcPackageAdapter implements PackagePort {

    private static final class Entry {
        Package pkg;
        Map<String, byte[]> resources;
        String authorSeat;
    }

    private final TxSupport tx;
    private final DeploymentPort deployments;
    private final PermissionPort permissions;
    private final AuditPort audit;
    private final RestProperties props;

    public JdbcPackageAdapter(TxSupport tx, DeploymentPort deployments, PermissionPort permissions, AuditPort audit,
                              RestProperties props) {
        this.tx = tx;
        this.deployments = deployments;
        this.permissions = permissions;
        this.audit = audit;
        this.props = props;
    }

    @Override
    public synchronized Package create(RequestContext ctx, CreatePackageRequest request) {
        return tx.inTx(() -> doCreate(ctx, request));
    }

    private Package doCreate(RequestContext ctx, CreatePackageRequest request) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        required(request.key(), "key");
        if (!request.key().matches("[A-Za-z0-9._-]{1,128}")) {
            throw invalid("key", "package key must be 1..128 of [A-Za-z0-9._-]");
        }
        Map<String, byte[]> res = decode(request.resources());
        Instant now = Instant.now();
        Entry e = new Entry();
        e.resources = res;
        e.authorSeat = ctx.seatId();
        e.pkg = new Package(JdbcSupport.newId(), request.key(), request.name() == null ? request.key() : request.name(),
                1, "DRAFT", null, hashes(res), ctx.principalId(), null, null, null, null, null, null, now, now,
                List.of(event("create", null, "DRAFT", ctx, null, now)), null);
        insert(JdbcSupport.tenant(ctx), e);
        audit.record(ctx, chain(e), "PACKAGE_DRAFT_SAVED",
                payload("packageId", e.pkg.id(), "key", e.pkg.key(), "revision", 1, "resources", e.pkg.resourceHashes(),
                        "actor", ctx.actor()));
        return view(e);
    }

    @Override
    public Page<Package> list(RequestContext ctx, String key, String state, Page.PageRequest page) {
        List<Package> all = new ArrayList<>(load(JdbcSupport.tenant(ctx), key).stream().map(JdbcPackageAdapter::view)
                .filter(p -> key == null || key.equals(p.key()))
                .filter(p -> state == null || state.equals(p.state()))
                .sorted(Comparator.comparing(Package::key).thenComparing(Package::createdAt))
                .toList());
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public Package get(RequestContext ctx, String packageId) {
        return view(entry(ctx, packageId));
    }

    @Override
    public synchronized Package update(RequestContext ctx, String packageId, long expectedRevision,
                                       UpdatePackageRequest request) {
        return tx.inTx(() -> doUpdate(ctx, packageId, expectedRevision, request));
    }

    private Package doUpdate(RequestContext ctx, String packageId, long expectedRevision, UpdatePackageRequest request) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        Entry e = entry(ctx, packageId);
        Package p = e.pkg;
        if (!"DRAFT".equals(p.state())) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "Only a DRAFT package can be edited; it is " + p.state());
        }
        JdbcSupport.checkRevision(expectedRevision, p.revision(), "Package", packageId);
        if (request.resources() != null) {
            e.resources = decode(request.resources());
        }
        Instant now = Instant.now();
        e.pkg = copy(p, p.revision() + 1, "DRAFT", null, hashes(e.resources), request.name() == null ? p.name() : request.name(),
                p.reviewer(), p.approver(), p.deployer(), p.environment(), p.activateAt(), p.deploymentId(), now,
                append(p.history(), event("update", "DRAFT", "DRAFT", ctx, null, now)));
        save(JdbcSupport.tenant(ctx), e);
        audit.record(ctx, chain(e), "PACKAGE_DRAFT_SAVED",
                payload("packageId", packageId, "key", p.key(), "revision", e.pkg.revision(),
                        "resources", e.pkg.resourceHashes(), "actor", ctx.actor()));
        return view(e);
    }

    @Override
    public synchronized Package transition(RequestContext ctx, String packageId, TransitionRequest request) {
        return tx.inTx(() -> {
            Entry e = entry(ctx, packageId);
            Package result = doTransition(ctx, e, request);
            save(JdbcSupport.tenant(ctx), e);
            return result;
        });
    }

    private Package doTransition(RequestContext ctx, Entry e, TransitionRequest request) {
        String packageId = e.pkg.id();
        Package p = e.pkg;
        String action = required(request.action(), "action").toLowerCase(java.util.Locale.ROOT);
        Instant now = Instant.now();
        String from = p.state();
        switch (action) {
            case "submit" -> {
                permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
                expect(p, action, "DRAFT");
                if (e.resources.isEmpty()) {
                    throw ApiProblem.unprocessable("PACKAGE_EMPTY", "A package needs at least one resource");
                }
                String hash = packageHash(e.resources);
                e.pkg = copy(p, p.revision(), "IN_REVIEW", hash, hashes(e.resources), p.name(), null, null, null,
                        p.environment(), p.activateAt(), p.deploymentId(), now, append(p.history(),
                                event(action, from, "IN_REVIEW", ctx, request.reason(), now)));
                audit(ctx, e, "PACKAGE_SUBMITTED", request, from);
            }
            case "approve" -> {
                requireAny(ctx, Permissions.PACKAGE_REVIEW, Permissions.PACKAGE_APPROVE);
                expect(p, action, "IN_REVIEW");
                separate(ctx, e, "approver");
                e.pkg = copy(p, p.revision(), "APPROVED", p.packageHash(), p.resourceHashes(), p.name(),
                        ctx.principalId(), ctx.principalId(), null, p.environment(), p.activateAt(), p.deploymentId(), now,
                        append(p.history(), event(action, from, "APPROVED", ctx, request.reason(), now)));
                audit(ctx, e, "PACKAGE_APPROVED", request, from);
            }
            case "reject" -> {
                requireAny(ctx, Permissions.PACKAGE_REVIEW, Permissions.PACKAGE_APPROVE);
                expect(p, action, "IN_REVIEW");
                separate(ctx, e, "reviewer");
                requireReason(request);
                e.pkg = copy(p, p.revision() + 1, "DRAFT", null, p.resourceHashes(), p.name(), ctx.principalId(), null,
                        null, p.environment(), p.activateAt(), p.deploymentId(), now,
                        append(p.history(), event(action, from, "DRAFT", ctx, request.reason(), now)));
                audit(ctx, e, "PACKAGE_REJECTED", request, from);
            }
            case "deploy" -> {
                permissions.require(ctx, Permissions.PACKAGE_DEPLOY);
                expect(p, action, "APPROVED");
                separate(ctx, e, "deployer");
                if (!p.packageHash().equals(packageHash(e.resources))) {
                    throw ApiProblem.conflict("PACKAGE_HASH_MISMATCH", "The package content no longer matches its approved hash");
                }
                DeploymentPort.Deployment d = deployments.deploy(ctx, new DeploymentPort.DeployRequest(
                        "pkg:" + p.key() + ":" + p.packageHash(), "sangam-package", false, e.resources));
                String env = request.environment() == null || request.environment().isBlank() ? "default" : request.environment();
                e.pkg = copy(p, p.revision(), "DEPLOYED", p.packageHash(), p.resourceHashes(), p.name(), p.reviewer(),
                        p.approver(), ctx.principalId(), env, p.activateAt(), d.id(), now,
                        append(p.history(), event(action, from, "DEPLOYED", ctx, request.reason(), now)));
                audit(ctx, e, "PACKAGE_DEPLOYED", request, from);
            }
            case "activate" -> {
                permissions.require(ctx, Permissions.PACKAGE_DEPLOY);
                expect(p, action, "DEPLOYED");
                separate(ctx, e, "deployer");
                if (request.activateAt() != null && request.activateAt().isAfter(now)) {
                    e.pkg = copy(p, p.revision(), "DEPLOYED", p.packageHash(), p.resourceHashes(), p.name(), p.reviewer(),
                            p.approver(), p.deployer(), p.environment(), request.activateAt(), p.deploymentId(), now,
                            append(p.history(), event("schedule-activation", from, "DEPLOYED", ctx, request.reason(), now)));
                    audit(ctx, e, "PACKAGE_ACTIVATION_SCHEDULED", request, from);
                    return view(e);
                }
                for (Entry other : load(JdbcSupport.tenant(ctx), p.key())) {
                    if (!other.pkg.id().equals(e.pkg.id()) && "ACTIVE".equals(other.pkg.state())) {
                        Package o = other.pkg;
                        other.pkg = copy(o, o.revision(), "RETIRED", o.packageHash(), o.resourceHashes(), o.name(),
                                o.reviewer(), o.approver(), o.deployer(), o.environment(), o.activateAt(), o.deploymentId(),
                                now, append(o.history(), event("retire", "ACTIVE", "RETIRED", ctx,
                                        "superseded by " + p.id(), now)));
                        save(JdbcSupport.tenant(ctx), other);
                        audit(ctx, other, "PACKAGE_RETIRED", request, "ACTIVE");
                    }
                }
                e.pkg = copy(p, p.revision(), "ACTIVE", p.packageHash(), p.resourceHashes(), p.name(), p.reviewer(),
                        p.approver(), p.deployer(), p.environment(), request.activateAt() == null ? now : request.activateAt(),
                        p.deploymentId(), now, append(p.history(), event(action, from, "ACTIVE", ctx, request.reason(), now)));
                audit(ctx, e, "PACKAGE_ACTIVATED", request, from);
            }
            case "suspend" -> {
                permissions.require(ctx, Permissions.PACKAGE_OPERATE);
                expect(p, action, "ACTIVE");
                requireReason(request);
                e.pkg = simple(p, "SUSPENDED", ctx, action, request, now);
                audit(ctx, e, "PACKAGE_SUSPENDED", request, from);
            }
            case "resume" -> {
                permissions.require(ctx, Permissions.PACKAGE_OPERATE);
                expect(p, action, "SUSPENDED");
                requireReason(request);
                e.pkg = simple(p, "ACTIVE", ctx, action, request, now);
                audit(ctx, e, "PACKAGE_RESUMED", request, from);
            }
            case "withdraw" -> {
                permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
                expect(p, action, "DRAFT");
                e.pkg = simple(p, "WITHDRAWN", ctx, action, request, now);
                audit(ctx, e, "PACKAGE_WITHDRAWN", request, from);
            }
            case "retire" -> {
                permissions.require(ctx, Permissions.PACKAGE_OPERATE);
                expect(p, action, "ACTIVE", "SUSPENDED");
                requireReason(request);
                e.pkg = simple(p, "RETIRED", ctx, action, request, now);
                audit(ctx, e, "PACKAGE_RETIRED", request, from);
            }
            case "archive" -> {
                permissions.require(ctx, Permissions.PACKAGE_OPERATE);
                expect(p, action, "RETIRED");
                e.pkg = simple(p, "ARCHIVED", ctx, action, request, now);
                audit(ctx, e, "PACKAGE_ARCHIVED", request, from);
            }
            default -> throw invalid("action", "unknown package transition " + request.action());
        }
        return view(e);
    }

    @Override
    public List<DiffEntry> diff(RequestContext ctx, String packageId) {
        Entry e = entry(ctx, packageId);
        Map<String, String> mine = e.pkg.resourceHashes();
        Map<String, String> active = load(JdbcSupport.tenant(ctx), e.pkg.key()).stream()
                .filter(o -> !o.pkg.id().equals(e.pkg.id()) && "ACTIVE".equals(o.pkg.state()))
                .map(o -> o.pkg.resourceHashes()).findFirst().orElse(Map.of());
        List<DiffEntry> out = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>(mine.keySet());
        names.addAll(active.keySet());
        for (String n : names) {
            String a = active.get(n);
            String b = mine.get(n);
            if (a == null) {
                out.add(new DiffEntry(n, "ADDED", null, b));
            } else if (b == null) {
                out.add(new DiffEntry(n, "REMOVED", a, null));
            } else if (!a.equals(b)) {
                out.add(new DiffEntry(n, "CHANGED", a, b));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Entry entry(RequestContext ctx, String id) {
        Entry e = id == null ? null : find(JdbcSupport.tenant(ctx), id);
        if (e == null) {
            throw ApiProblem.notFound("Package", id);
        }
        return e;
    }

    private static String chain(Entry e) {
        return "pkg:" + e.pkg.key();
    }

    private void audit(RequestContext ctx, Entry e, String type, TransitionRequest r, String from) {
        Package p = e.pkg;
        audit.record(ctx, chain(e), type, payload("packageId", p.id(), "key", p.key(), "revision", p.revision(),
                "package_hash", p.packageHash(), "fromState", from, "toState", p.state(), "environment", p.environment(),
                "deploymentId", p.deploymentId(), "activateAt", p.activateAt() == null ? null : p.activateAt().toString(),
                "reason", r.reason(), "actor", ctx.actor()));
    }

    private void requireAny(RequestContext ctx, String... perms) {
        for (String p : perms) {
            if (permissions.has(ctx, p)) {
                return;
            }
        }
        throw ApiProblem.notPermitted(String.join(" or ", perms));
    }

    private static void expect(Package p, String action, String... states) {
        for (String s : states) {
            if (s.equals(p.state())) {
                return;
            }
        }
        throw ApiProblem.conflict("INVALID_TRANSITION",
                "Transition '" + action + "' is not allowed from state " + p.state()).with("state", p.state());
    }

    /** X.2.4.4: reviewer, approver and deployer differ from the author by person and by seat. */
    private static void separate(RequestContext ctx, Entry e, String role) {
        boolean samePerson = Objects.equals(ctx.principalId(), e.pkg.author());
        boolean sameSeat = ctx.seatId() != null && Objects.equals(ctx.seatId(), e.authorSeat);
        if (samePerson || sameSeat) {
            throw ApiProblem.unprocessable("SEGREGATION_OF_DUTIES",
                    "The " + role + " must differ from the author by person and by seat").with("role", role);
        }
    }

    private static void requireReason(TransitionRequest r) {
        if (r.reason() == null || r.reason().isBlank()) {
            throw invalid("reason", "a reason is required for this transition");
        }
    }

    private static Package simple(Package p, String to, RequestContext ctx, String action, TransitionRequest r, Instant now) {
        return copy(p, p.revision(), to, p.packageHash(), p.resourceHashes(), p.name(), p.reviewer(), p.approver(),
                p.deployer(), p.environment(), p.activateAt(), p.deploymentId(), now,
                append(p.history(), event(action, p.state(), to, ctx, r.reason(), now)));
    }

    private static Package copy(Package p, long revision, String state, String hash, Map<String, String> resourceHashes,
                                String name, String reviewer, String approver, String deployer, String environment,
                                Instant activateAt, String deploymentId, Instant updatedAt, List<PackageEvent> history) {
        return new Package(p.id(), p.key(), name, revision, state, hash, resourceHashes, p.author(), reviewer, approver,
                deployer, environment, activateAt, deploymentId, p.createdAt(), updatedAt, history, null);
    }

    private static PackageEvent event(String transition, String from, String to, RequestContext ctx, String reason, Instant at) {
        return new PackageEvent(transition, from, to, ctx.principalId(), ctx.seatId(), reason, at);
    }

    private static List<PackageEvent> append(List<PackageEvent> history, PackageEvent e) {
        List<PackageEvent> l = new ArrayList<>(history == null ? List.of() : history);
        l.add(e);
        return List.copyOf(l);
    }

    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(".bpmn", ".dmn", ".cmmn", ".form",
            ".json", ".xml", ".yaml", ".yml", ".txt");

    /** Text resources (by extension) travel as UTF-8 text; everything else as base64 (PackagePort contract). */
    static boolean isText(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return TEXT_EXTENSIONS.stream().anyMatch(n::endsWith);
    }

    /** The stored package with its resource content attached ({@link Package#resources()}). */
    private static Package view(Entry e) {
        Package p = e.pkg;
        Map<String, String> content = new TreeMap<>();
        e.resources.forEach((k, v) -> content.put(k, isText(k) ? new String(v, StandardCharsets.UTF_8)
                : Base64.getEncoder().encodeToString(v)));
        return new Package(p.id(), p.key(), p.name(), p.revision(), p.state(), p.packageHash(), p.resourceHashes(),
                p.author(), p.reviewer(), p.approver(), p.deployer(), p.environment(), p.activateAt(), p.deploymentId(),
                p.createdAt(), p.updatedAt(), p.history(), Map.copyOf(content));
    }

    private static Map<String, byte[]> decode(Map<String, String> encoded) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (encoded == null) {
            return out;
        }
        for (Map.Entry<String, String> en : encoded.entrySet()) {
            if (en.getKey() == null || en.getKey().isBlank() || en.getKey().contains("..")) {
                throw invalid("resources", "invalid resource name " + en.getKey());
            }
            String v = en.getValue() == null ? "" : en.getValue();
            String trimmed = v.stripLeading();
            if (isText(en.getKey()) || trimmed.startsWith("<") || trimmed.startsWith("{")) {
                out.put(en.getKey(), v.getBytes(StandardCharsets.UTF_8));
                continue;
            }
            try {
                out.put(en.getKey(), Base64.getDecoder().decode(v));
            } catch (IllegalArgumentException ex) {
                throw invalid("resources", "resource " + en.getKey() + " is neither text nor valid base64");
            }
        }
        return out;
    }

    private static Map<String, String> hashes(Map<String, byte[]> res) {
        Map<String, String> m = new TreeMap<>();
        res.forEach((k, v) -> m.put(k, Hashes.hex(Hashes.sha256(v))));
        return Map.copyOf(m);
    }

    // ------------------------------------------------------------------ PACKAGE_VERSION access

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String COLS = "package_id, package_key, version, package_hash, state, manifest_json, author,"
            + " reviewer, deployer, engine_deployment_id, created_at, state_changed_at";

    /** The part of a package not held in its own columns. */
    record Manifest(String name, long revision, String approver, String environment, Instant activateAt,
                    Map<String, String> resourceHashes, Map<String, String> resources, List<PackageEvent> history,
                    String authorSeat, String packageHash) {
    }

    private Entry find(String tenant, String id) {
        return tx.withConnection(c -> Jdbc.one(c, "SELECT " + COLS + " FROM PACKAGE_VERSION WHERE tenant_id=?"
                + " AND package_id=?", JdbcPackageAdapter::row, tenant, id)).orElse(null);
    }

    /** Every package of the tenant, or of one key. */
    private List<Entry> load(String tenant, String key) {
        return tx.withConnection(c -> Jdbc.query(c, "SELECT " + COLS + " FROM PACKAGE_VERSION WHERE tenant_id=?"
                        + " AND package_id IS NOT NULL" + (key == null ? "" : " AND package_key=?")
                        + " ORDER BY package_key, version", JdbcPackageAdapter::row,
                key == null ? new Object[]{tenant} : new Object[]{tenant, key}));
    }

    private void insert(String tenant, Entry e) {
        Package p = e.pkg;
        tx.withConnection(c -> {
            int version = Jdbc.one(c, "SELECT MAX(version) FROM PACKAGE_VERSION WHERE tenant_id=? AND package_key=?",
                    rs -> rs.getInt(1), tenant, p.key()).orElse(0) + 1;
            Instant now = Jdbc.dbNow(c, tx.dialect());
            Jdbc.update(c, "INSERT INTO PACKAGE_VERSION(tenant_id, package_key, version, package_hash, state, manifest_json,"
                            + " author, reviewer, deployer, engine_deployment_id, created_at, state_changed_at, package_id)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenant, p.key(), version, hashBytes(p.packageHash()), p.state(), manifest(e), p.author(),
                    p.reviewer(), p.deployer(), p.deploymentId(), now, now, p.id());
            return null;
        });
    }

    private void save(String tenant, Entry e) {
        Package p = e.pkg;
        int n = tx.withConnection(c -> Jdbc.update(c, "UPDATE PACKAGE_VERSION SET package_hash=?, state=?, manifest_json=?,"
                        + " reviewer=?, deployer=?, engine_deployment_id=?, state_changed_at=" + tx.dialect().now()
                        + " WHERE tenant_id=? AND package_id=?",
                hashBytes(p.packageHash()), p.state(), manifest(e), p.reviewer(), p.deployer(), p.deploymentId(),
                tenant, p.id()));
        if (n == 0) {
            throw ApiProblem.notFound("Package", p.id());
        }
    }

    private static String manifest(Entry e) {
        Package p = e.pkg;
        Map<String, String> res = new TreeMap<>();
        e.resources.forEach((k, v) -> res.put(k, Base64.getEncoder().encodeToString(v)));
        Manifest m = new Manifest(p.name(), p.revision(), p.approver(), p.environment(), p.activateAt(),
                p.resourceHashes(), res, p.history(), e.authorSeat, p.packageHash());
        try {
            return JSON.writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("package manifest is not serialisable", ex);
        }
    }

    private static Entry row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Manifest m;
        try {
            m = JSON.readValue(rs.getString("manifest_json"), Manifest.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("PACKAGE_VERSION.manifest_json of " + rs.getString("package_id")
                    + " is not valid JSON", ex);
        }
        Entry e = new Entry();
        e.authorSeat = m.authorSeat();
        e.resources = new LinkedHashMap<>();
        if (m.resources() != null) {
            m.resources().forEach((k, v) -> e.resources.put(k, Base64.getDecoder().decode(v)));
        }
        e.pkg = new Package(rs.getString("package_id"), rs.getString("package_key"), m.name(), m.revision(),
                rs.getString("state"), m.packageHash(), m.resourceHashes() == null ? Map.of() : m.resourceHashes(),
                rs.getString("author"), rs.getString("reviewer"), m.approver(), rs.getString("deployer"),
                m.environment(), m.activateAt(), rs.getString("engine_deployment_id"), Jdbc.instant(rs, "created_at"),
                Jdbc.instant(rs, "state_changed_at"), m.history() == null ? List.of() : m.history(), null);
        return e;
    }

    /** 32 zero bytes until the hash is frozen at submit (the column is NOT NULL). */
    private static byte[] hashBytes(String hex) {
        byte[] b = new byte[32];
        if (hex == null) {
            return b;
        }
        for (int i = 0; i < 32 && 2 * i + 1 < hex.length(); i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    /** sha256 over the sorted {@code name:sha256(content)} lines (X.2.4.5 hash addressing). */
    static String packageHash(Map<String, byte[]> res) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(hashes(res)).forEach((k, v) -> sb.append(k).append(':').append(v).append('\n'));
        return Hashes.hex(Hashes.sha256(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
