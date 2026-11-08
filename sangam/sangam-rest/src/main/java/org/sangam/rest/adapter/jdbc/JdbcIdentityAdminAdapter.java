// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.sangam.rest.adapter.jdbc.JdbcSupport.payload;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.required;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.IdentityAdminPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Identity and authority administration (A01–A11, R50, R67, IX.5 rules 4–5, VI.4). Module-disabled default: in
 * production sangam-security is bound (its bridge serves SoD exceptions, users, groups and service principals). State is
 * persisted, nothing is held in memory:
 * <ul>
 *   <li>SoD exceptions in the security table {@code SEC_SOD_EXCEPTION}: the rule {@code <caseId>/<duty>} maps to
 *       {@code case_id} / {@code duty}, a rule without {@code /} to {@code case_id} with duty {@code *}; the person's
 *       display name is kept in the document kind {@code identity:sod-extra};</li>
 *   <li>{@code REST_DOCUMENT} kinds {@code identity:user}, {@code identity:group-member} (one document per
 *       group/user; doc_key = group, owner_id = user), {@code identity:idp}, {@code identity:service-principal} (only
 *       the secret's SHA-256 is stored) and {@code identity:assignment-decision}.</li>
 * </ul>
 *
 * <ul>
 *   <li>Outbound calls (identity-provider test) are off by default: Sangam is air-gapped unless configured
 *       (ARCHITECTURE §1.9).</li>
 *   <li>Directory sync (LDAP/SCIM, R67) is provided by sangam-security: {@code 501 CAPABILITY_NOT_INSTALLED} here.</li>
 *   <li>A membership change is audited ({@code MEMBERSHIP_CHANGED}); this default cannot bump the VI.4
 *       {@code policy_version}, because the {@link AdminPort} offers no such operation. sangam-security does it in the
 *       same transaction as the membership change.</li>
 *   <li>Service-principal secrets are returned once and stored only as SHA-256.</li>
 * </ul>
 */
public final class JdbcIdentityAdminAdapter implements IdentityAdminPort {

    private static final Set<String> SOD_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "EXPIRED");
    private static final Set<String> PROTOCOLS = Set.of("OIDC", "SAML2", "LDAP");
    private static final int MAX_IMPORT_LINES = 10_000;
    private static final int MAX_TASK_SCAN = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    record AssignmentDecision(boolean approved, String by, String reason, Instant at) {
    }

    record PrincipalSecret(ServicePrincipal principal, String secretSha256) {
    }

    /** One group membership (document kind {@code identity:group-member}). */
    record Membership(String groupId, String userId) {
    }

    private final TxSupport tx;
    private final AdminPort admin;
    private final TaskPort tasks;
    private final PermissionPort permissions;
    private final AuditPort audit;
    private final RestProperties props;
    private final boolean allowOutbound;
    private final org.sangam.rest.support.BusinessDates dates;

    private final DocMap<User> users;
    private final DocMap<Membership> members;
    private final DocMap<IdentityProvider> idps;
    private final DocMap<PrincipalSecret> sps;
    private final DocMap<AssignmentDecision> decisions;
    private final DocMap<Map<String, Object>> sodExtra;

    public JdbcIdentityAdminAdapter(DocumentStore store, AdminPort admin, TaskPort tasks, PermissionPort permissions,
                                    AuditPort audit, RestProperties props) {
        this(store, admin, tasks, permissions, audit, props, false);
    }

    public JdbcIdentityAdminAdapter(DocumentStore store, AdminPort admin, TaskPort tasks, PermissionPort permissions,
                                    AuditPort audit, RestProperties props, boolean allowOutbound) {
        this(store, admin, tasks, permissions, audit, props, allowOutbound, org.sangam.rest.support.BusinessDates.utc());
    }

    /** @param dates the rejection end date (\"today\") in the tenant's business zone */
    public JdbcIdentityAdminAdapter(DocumentStore store, AdminPort admin, TaskPort tasks, PermissionPort permissions,
                                    AuditPort audit, RestProperties props, boolean allowOutbound,
                                    org.sangam.rest.support.BusinessDates dates) {
        this.dates = java.util.Objects.requireNonNull(dates, "dates");
        this.tx = store.tx();
        this.admin = admin;
        this.tasks = tasks;
        this.permissions = permissions;
        this.audit = audit;
        this.props = props;
        this.allowOutbound = allowOutbound;
        this.users = new DocMap<>(store, "identity:user", User.class,
                u -> DocMap.ix(u.username(), null, null, u.active() ? "ACTIVE" : "INACTIVE"));
        this.members = new DocMap<>(store, "identity:group-member", Membership.class,
                m -> DocMap.ix(m.groupId(), m.userId(), null, null));
        this.idps = new DocMap<>(store, "identity:idp", IdentityProvider.class,
                p -> DocMap.ix(p.name(), null, null, p.status()));
        this.sps = new DocMap<>(store, "identity:service-principal", PrincipalSecret.class,
                p -> DocMap.ix(p.principal().name(), null, null, p.principal().status()));
        this.decisions = new DocMap<>(store, "identity:assignment-decision", AssignmentDecision.class,
                d -> DocMap.ix(null, d.by(), null, d.approved() ? "APPROVED" : "REJECTED"));
        this.sodExtra = new DocMap<>(store, "identity:sod-extra", body -> body, m -> DocMap.ix(null, null, null, null));
    }

    /** Records a principal seen by the authentication layer, so the user and group lists are populated. */
    public void noteLogin(String tenant, String principalId, Collection<String> groupIds) {
        if (tenant == null || principalId == null) {
            return;
        }
        List<String> g = groupIds == null ? List.of() : List.copyOf(groupIds);
        tx.runInTx(() -> {
            DocMap<User>.View dir = users.in(tenant);
            User u = dir.get(principalId);
            dir.put(principalId, u == null
                    ? new User(principalId, principalId, principalId, null, true, g, "OIDC", Instant.now())
                    : new User(u.id(), u.username(), u.displayName(), u.email(), u.active(), g, u.source(), Instant.now()));
            for (String gid : g) {
                addMember(tenant, gid, principalId);
            }
        });
    }

    private boolean addMember(String tenant, String groupId, String userId) {
        return members.in(tenant).putIfAbsent(memberId(groupId, userId), new Membership(groupId, userId)) == null;
    }

    private boolean removeMember(String tenant, String groupId, String userId) {
        DocMap<Membership>.View v = members.in(tenant);
        String id = memberId(groupId, userId);
        if (!v.containsKey(id)) {
            return false;
        }
        v.remove(id);
        return true;
    }

    /** Document id of a membership: SHA-256 of {@code group\0user}, so ids stay within 64 characters. */
    private static String memberId(String groupId, String userId) {
        return Hashes.hex(Hashes.sha256(groupId + "\u0000" + userId));
    }

    /** group id → member user ids. */
    private Map<String, Set<String>> groupMap(String tenant) {
        Map<String, Set<String>> gm = new HashMap<>();
        for (Membership m : members.in(tenant).values()) {
            gm.computeIfAbsent(m.groupId(), k -> new LinkedHashSet<>()).add(m.userId());
        }
        return gm;
    }

    // ------------------------------------------------------------------ assignments

    @Override
    public AssignmentImpact assignmentImpact(RequestContext ctx, AdminPort.Assignment a) {
        required(a == null ? null : a.seatId(), "seatId");
        RequestContext sys = system(ctx);
        TaskPort.TaskFilter filter = new TaskPort.TaskFilter(null, null, null, a.seatId(), null, null, null, null, null,
                null, null, null, null);
        Page<TaskPort.Task> open = tasks.list(sys, filter, new Page.PageRequest(0, MAX_TASK_SCAN));
        List<SodConflict> conflicts = new ArrayList<>();
        if (a.personId() != null) {
            for (TaskPort.Task t : open.items()) {
                Map<String, Object> vars = tasks.get(sys, t.id()).variables();
                if (a.personId().equals(String.valueOf(vars.get("sangamInitiator")))) {
                    conflicts.add(new SodConflict(t.id(), "SAME_PERSON_AS_INITIATOR"));
                } else if (vars.get("sangamActors") instanceof Collection<?> actors
                        && actors.stream().map(String::valueOf).anyMatch(a.personId()::equals)) {
                    conflicts.add(new SodConflict(t.id(), "ALREADY_ACTED_IN_CHAIN"));
                }
            }
        }
        List<String> summary = new ArrayList<>();
        summary.add((open.capped() ? "More than " : "") + open.total() + " open task(s) are queued for seat " + a.seatId());
        if (!conflicts.isEmpty()) {
            summary.add(conflicts.size() + " open task(s) would breach segregation of duties for " + a.personId());
        }
        if (open.total() > MAX_TASK_SCAN) {
            summary.add("SoD conflicts were checked on the first " + MAX_TASK_SCAN + " tasks only");
        }
        if (a.kind() != null && !"SUBSTANTIVE".equals(a.kind())) {
            summary.add("Actions will be recorded as \"acted for\" seat " + a.actedForSeatId());
        }
        return new AssignmentImpact(open.total(), conflicts, summary);
    }

    @Override
    public synchronized AdminPort.Assignment decideAssignment(RequestContext ctx, String assignmentId, boolean approve,
                                                              String reason) {
        return tx.inTx(() -> {
            requireReason(reason);
            AdminPort.Assignment a = admin.assignment(ctx, assignmentId);
            if (a.ended()) {
                throw ApiProblem.conflict("INVALID_TRANSITION", "The assignment has already ended");
            }
            DocMap<AssignmentDecision>.View d = decisions.in(JdbcSupport.tenant(ctx));
            if (d.containsKey(assignmentId)) {
                throw ApiProblem.conflict("INVALID_TRANSITION", "The assignment was already "
                        + (d.get(assignmentId).approved() ? "approved" : "rejected"));
            }
            if (Objects.equals(ctx.principalId(), a.createdBy())) {
                throw ApiProblem.unprocessable("SEGREGATION_OF_DUTIES",
                        "The approver of an assignment must differ from the person who recorded it");
            }
            d.put(assignmentId, new AssignmentDecision(approve, ctx.principalId(), reason, Instant.now()));
            AdminPort.Assignment result = a;
            if (!approve) {
                result = admin.endAssignment(ctx, assignmentId, dates.today(ctx.tenantId()), reason);
            }
            audit.record(ctx, JdbcSupport.securityChain(ctx), approve ? "ASSIGNMENT_APPROVED" : "ASSIGNMENT_REJECTED",
                    payload("assignmentId", assignmentId, "seatId", a.seatId(), "personId", a.personId(), "kind", a.kind(),
                            "reason", reason, "actor", ctx.actor()));
            return result;
        });
    }

    // ------------------------------------------------------------------ policies

    @Override
    public ExplainResult explain(RequestContext ctx, ExplainRequest r) {
        required(r == null ? null : r.principalId(), "principalId");
        required(r.action(), "action");
        required(r.resourceType(), "resourceType");
        String resourceId = r.resourceId() == null ? "*" : r.resourceId();
        Set<String> principalIds = new LinkedHashSet<>();
        principalIds.add(r.principalId());
        User u = users.in(JdbcSupport.tenant(ctx)).get(r.principalId());
        if (u != null && u.groups() != null) {
            principalIds.addAll(u.groups());
        }
        List<PolicyMatch> matched = new ArrayList<>();
        boolean grant = false;
        boolean deny = false;
        for (AdminPort.Policy p : allPolicies(ctx)) {
            if (!principalIds.contains(p.principalId()) || !r.resourceType().equals(p.resourceType())) {
                continue;
            }
            if (!("*".equals(p.resourceId()) || resourceId.equals(p.resourceId()))) {
                continue;
            }
            if (p.actions() == null || !(p.actions().contains(r.action()) || p.actions().contains("*"))) {
                continue;
            }
            boolean isDeny = "DENY".equals(p.effect());
            deny |= isDeny;
            grant |= !isDeny && p.conditionId() == null;
            matched.add(new PolicyMatch(p.id(), isDeny ? "DENY" : "ALLOW",
                    p.principalType() + ":" + p.principalId() + " on " + p.resourceType() + ":" + p.resourceId()
                            + (p.conditionId() == null ? "" : " if " + p.conditionId())));
        }
        long version = admin.policyVersion(ctx).policyVersion();
        // A-13: a matching deny wins at any scope; conditional grants fail closed here.
        return new ExplainResult(grant && !deny, matched, version);
    }

    private List<AdminPort.Policy> allPolicies(RequestContext ctx) {
        List<AdminPort.Policy> all = new ArrayList<>();
        AdminPort.AdminFilter f = new AdminPort.AdminFilter(null, null, null, null, null, null, null, true);
        for (int page = 0; page < 1000; page++) {
            Page<AdminPort.Policy> p = admin.policies(ctx, f, new Page.PageRequest(page, Page.PageRequest.MAX_SIZE));
            all.addAll(p.items());
            if (p.items().size() < Page.PageRequest.MAX_SIZE) {
                break;
            }
        }
        return all;
    }

    // ------------------------------------------------------------------ SoD exceptions (IX.5 rule 5)

    @Override
    public Page<SodException> sodExceptions(RequestContext ctx, String status, Page.PageRequest page) {
        if (status != null) {
            JdbcSupport.oneOf(status, "status", SOD_STATUSES);
        }
        String tenant = JdbcSupport.tenant(ctx);
        List<SodException> all = tx.inTx(() -> {
            // Expiry is applied on read and persisted.
            tx.withConnection(c -> Jdbc.update(c, "UPDATE SEC_SOD_EXCEPTION SET status='EXPIRED' WHERE tenant_id=?"
                    + " AND status IN ('PENDING','APPROVED') AND expires_at IS NOT NULL AND expires_at < "
                    + tx.dialect().now(), tenant));
            return loadSod(tenant, null).stream().filter(e -> status == null || status.equals(e.status()))
                    .sorted(Comparator.comparing(SodException::expiresAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        });
        return Page.slice(all, page, props.getCountCap());
    }

    private static final String SOD_COLS = "exception_id, case_id, person_id, duty, reason, requested_by, status,"
            + " approved_by, expires_at";

    /** The SoD exceptions of a tenant ({@code id} null) or one of them, mapped back to the REST record. */
    private List<SodException> loadSod(String tenant, String id) {
        DocMap<Map<String, Object>>.View extra = sodExtra.in(tenant);
        return tx.withConnection(c -> Jdbc.query(c, "SELECT " + SOD_COLS + " FROM SEC_SOD_EXCEPTION WHERE tenant_id=?"
                        + (id == null ? "" : " AND exception_id=?") + " ORDER BY created_at",
                rs -> {
                    String exId = rs.getString("exception_id");
                    String duty = rs.getString("duty");
                    String caseId = rs.getString("case_id");
                    String rule = "*".equals(duty) ? caseId : caseId + "/" + duty;
                    Map<String, Object> x = extra.get(exId);
                    String personName = x == null || x.get("personName") == null ? null : String.valueOf(x.get("personName"));
                    return new SodException(exId, rule, rs.getString("person_id"), personName, rs.getString("reason"),
                            Jdbc.instant(rs, "expires_at"), rs.getString("status"), rs.getString("approved_by"),
                            rs.getString("requested_by"));
                }, id == null ? new Object[]{tenant} : new Object[]{tenant, id}));
    }

    /** rule {@code <caseId>/<duty>} → (case_id, duty); a rule without '/' or with a duty above 32 chars → (rule, *). */
    private static String[] splitRule(String rule) {
        int slash = rule.lastIndexOf('/');
        if (slash > 0 && slash < rule.length() - 1 && rule.length() - slash - 1 <= 32) {
            return new String[]{rule.substring(0, slash), rule.substring(slash + 1)};
        }
        return new String[]{rule, "*"};
    }

    @Override
    public SodException createSodException(RequestContext ctx, SodException in) {
        required(in == null ? null : in.rule(), "rule");
        required(in.personId(), "personId");
        requireReason(in.reason());
        JdbcSupport.requiredObj(in.expiresAt(), "expiresAt");
        if (!in.expiresAt().isAfter(Instant.now())) {
            throw JdbcSupport.invalid("expiresAt", "expiresAt must be in the future");
        }
        if (in.reason().length() > 1000) {
            throw JdbcSupport.invalid("reason", "reason must be at most 1000 characters");
        }
        SodException e = new SodException(JdbcSupport.newId(), in.rule(), in.personId(), in.personName(), in.reason(),
                in.expiresAt(), "PENDING", null, ctx.principalId());
        String tenant = JdbcSupport.tenant(ctx);
        String[] rule = splitRule(e.rule());
        return tx.inTx(() -> {
            tx.withConnection(c -> {
                Instant now = Jdbc.dbNow(c, tx.dialect());
                return Jdbc.update(c, "INSERT INTO SEC_SOD_EXCEPTION(tenant_id, exception_id, case_id, person_id, seat_id,"
                                + " duty, reason, requested_by, status, expires_at, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        tenant, e.id(), rule[0], e.personId(), ctx.seatId(), rule[1], e.reason(), e.requestedBy(),
                        e.status(), e.expiresAt(), now);
            });
            if (e.personName() != null) {
                sodExtra.in(tenant).put(e.id(), Map.of("personName", e.personName()));
            }
            audit.record(ctx, JdbcSupport.securityChain(ctx), "SOD_EXCEPTION_REQUESTED",
                    payload("exceptionId", e.id(), "rule", e.rule(), "personId", e.personId(), "reason", e.reason(),
                            "expiresAt", e.expiresAt().toString(), "actor", ctx.actor()));
            return e;
        });
    }

    @Override
    public synchronized SodException decideSodException(RequestContext ctx, String id, boolean approve, String reason) {
        return tx.inTx(() -> {
            permissions.require(ctx, Permissions.POLICY_ADMIN);
            requireReason(reason);
            String tenant = JdbcSupport.tenant(ctx);
            SodException e = id == null ? null : loadSod(tenant, id).stream().findFirst().orElse(null);
            if (e == null) {
                throw ApiProblem.notFound("SoD exception", id);
            }
            if (!"PENDING".equals(e.status())) {
                throw ApiProblem.conflict("INVALID_TRANSITION", "The SoD exception is " + e.status());
            }
            if (Objects.equals(ctx.principalId(), e.requestedBy()) || Objects.equals(ctx.principalId(), e.personId())) {
                throw ApiProblem.unprocessable("SEGREGATION_OF_DUTIES",
                        "A SoD exception is decided by a higher authority, not by its requester or beneficiary");
            }
            SodException d = withStatus(e, approve ? "APPROVED" : "REJECTED", ctx.principalId());
            int n = tx.withConnection(c -> Jdbc.update(c, "UPDATE SEC_SOD_EXCEPTION SET status=?, approved_by=?,"
                            + " approver_seat_id=?, approval_reason=?, decided_at=" + tx.dialect().now()
                            + " WHERE tenant_id=? AND exception_id=? AND status='PENDING'",
                    d.status(), ctx.principalId(), ctx.seatId(), reason.length() > 1000 ? reason.substring(0, 1000) : reason,
                    tenant, id));
            if (n == 0) {
                throw ApiProblem.conflict("INVALID_TRANSITION", "The SoD exception was decided concurrently");
            }
            audit.record(ctx, JdbcSupport.securityChain(ctx), approve ? "SOD_EXCEPTION_GRANTED" : "SOD_EXCEPTION_REJECTED",
                    payload("exceptionId", id, "rule", e.rule(), "personId", e.personId(), "reason", reason,
                            "expiresAt", e.expiresAt() == null ? null : e.expiresAt().toString(), "actor", ctx.actor()));
            return d;
        });
    }

    private static SodException withStatus(SodException e, String status, String approvedBy) {
        return new SodException(e.id(), e.rule(), e.personId(), e.personName(), e.reason(), e.expiresAt(), status,
                approvedBy, e.requestedBy());
    }

    // ------------------------------------------------------------------ users and groups

    @Override
    public Page<User> users(RequestContext ctx, String q, Page.PageRequest page) {
        refreshFromAssignments(ctx);
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        String tenant = JdbcSupport.tenant(ctx);
        Map<String, Set<String>> gm = groupMap(tenant);
        List<User> all = users.in(tenant).values().stream()
                .map(u -> withGroups(gm, u))
                .filter(u -> needle == null || u.username().toLowerCase(Locale.ROOT).contains(needle)
                        || (u.displayName() != null && u.displayName().toLowerCase(Locale.ROOT).contains(needle)))
                .sorted(Comparator.comparing(User::username)).toList();
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public Page<Group> groups(RequestContext ctx, String q, Page.PageRequest page) {
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        Map<String, Set<String>> gm = groupMap(JdbcSupport.tenant(ctx));
        for (String known : Permissions.DEFAULT_GROUP_PERMISSIONS.keySet()) {
            gm.putIfAbsent(known, Set.of());
        }
        List<Group> all = gm.entrySet().stream()
                .filter(en -> needle == null || en.getKey().toLowerCase(Locale.ROOT).contains(needle))
                .map(en -> new Group(en.getKey(), en.getKey(),
                        en.getKey().startsWith("sangam-") ? "ROLE" : "GROUP", en.getValue().size(), "LOCAL"))
                .sorted(Comparator.comparing(Group::id)).toList();
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public List<User> groupMembers(RequestContext ctx, String groupId) {
        String tenant = JdbcSupport.tenant(ctx);
        Map<String, Set<String>> gm = groupMap(tenant);
        Set<String> groupMembers = gm.getOrDefault(groupId, Set.of());
        DocMap<User>.View dir = users.in(tenant);
        return groupMembers.stream().sorted()
                .map(id -> withGroups(gm, dir.getOrDefault(id, new User(id, id, id, null, true, List.of(), "LOCAL", null))))
                .toList();
    }

    @Override
    public void setMembership(RequestContext ctx, String groupId, String userId, boolean member) {
        required(groupId, "groupId");
        required(userId, "userId");
        String tenant = JdbcSupport.tenant(ctx);
        tx.runInTx(() -> {
            boolean changed = member ? addMember(tenant, groupId, userId) : removeMember(tenant, groupId, userId);
            users.in(tenant).putIfAbsent(userId,
                    new User(userId, userId, userId, null, true, List.of(), "LOCAL", null));
            if (changed) {
                audit.record(ctx, JdbcSupport.securityChain(ctx), "MEMBERSHIP_CHANGED",
                        payload("groupId", groupId, "userId", userId, "member", member, "actor", ctx.actor()));
            }
        });
    }

    private static User withGroups(Map<String, Set<String>> gm, User u) {
        Set<String> g = new LinkedHashSet<>(u.groups() == null ? List.of() : u.groups());
        gm.forEach((gid, groupMembers) -> {
            if (groupMembers.contains(u.id())) {
                g.add(gid);
            }
        });
        return new User(u.id(), u.username(), u.displayName(), u.email(), u.active(), List.copyOf(g), u.source(),
                u.lastLogin());
    }

    private void refreshFromAssignments(RequestContext ctx) {
        DocMap<User>.View dir = users.in(JdbcSupport.tenant(ctx));
        AdminPort.AdminFilter f = new AdminPort.AdminFilter(null, null, null, null, null, null, null, true);
        for (int page = 0; page < 100; page++) {
            Page<AdminPort.Assignment> p = admin.assignments(ctx, f, new Page.PageRequest(page, Page.PageRequest.MAX_SIZE));
            for (AdminPort.Assignment a : p.items()) {
                if (a.personId() != null) {
                    dir.putIfAbsent(a.personId(), new User(a.personId(), a.personId(), a.personId(), null, true,
                            List.of(), "LOCAL", null));
                }
            }
            if (p.items().size() < Page.PageRequest.MAX_SIZE) {
                break;
            }
        }
    }

    // ------------------------------------------------------------------ identity providers (R50, R67)

    @Override
    public List<IdentityProvider> identityProviders(RequestContext ctx) {
        return idps.in(JdbcSupport.tenant(ctx)).values().stream()
                .sorted(Comparator.comparing(IdentityProvider::name)).toList();
    }

    @Override
    public IdentityProvider createIdentityProvider(RequestContext ctx, IdentityProvider p) {
        return tx.inTx(() -> {
            validateIdp(p);
            IdentityProvider created = new IdentityProvider(JdbcSupport.newId(), p.name(), p.protocol(), p.issuer(),
                    p.metadataUrl(), p.attributeMapping() == null ? Map.of() : Map.copyOf(p.attributeMapping()),
                    p.mfaRequired(), p.sessionTimeoutMin() <= 0 ? 30 : p.sessionTimeoutMin(), "DRAFT", null);
            idps.in(JdbcSupport.tenant(ctx)).put(created.id(), created);
            auditIdp(ctx, "IDP_CONFIGURED", created);
            return created;
        });
    }

    @Override
    public IdentityProvider updateIdentityProvider(RequestContext ctx, String id, IdentityProvider p) {
        return tx.inTx(() -> {
            IdentityProvider cur = idp(ctx, id);
            validateIdp(p);
            IdentityProvider updated = new IdentityProvider(id, p.name(), p.protocol(), p.issuer(), p.metadataUrl(),
                    p.attributeMapping() == null ? Map.of() : Map.copyOf(p.attributeMapping()), p.mfaRequired(),
                    p.sessionTimeoutMin() <= 0 ? cur.sessionTimeoutMin() : p.sessionTimeoutMin(),
                    // Any configuration change needs a new test before activation.
                    "DRAFT", cur.lastTestAt());
            idps.in(JdbcSupport.tenant(ctx)).put(id, updated);
            auditIdp(ctx, "IDP_CONFIGURED", updated);
            return updated;
        });
    }

    @Override
    public TestResult testIdentityProvider(RequestContext ctx, String id) {
        IdentityProvider p = idp(ctx, id);
        if (!allowOutbound) {
            return new TestResult(false, "outbound calls disabled (air-gapped default)");
        }
        if (!"OIDC".equals(p.protocol())) {
            return new TestResult(false, p.protocol() + " test is provided by sangam-security");
        }
        String url = p.issuer().replaceAll("/+$", "") + "/.well-known/openid-configuration";
        TestResult result;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean ok = res.statusCode() == 200 && res.body().contains("\"issuer\"");
            result = new TestResult(ok, ok ? "discovery document found" : "HTTP " + res.statusCode() + " from " + url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = new TestResult(false, "interrupted");
        } catch (Exception e) {
            result = new TestResult(false, "cannot reach " + url + ": " + e.getMessage());
        }
        if (result.ok()) {
            IdentityProvider tested = new IdentityProvider(p.id(), p.name(), p.protocol(), p.issuer(), p.metadataUrl(),
                    p.attributeMapping(), p.mfaRequired(), p.sessionTimeoutMin(), "TESTED", Instant.now());
            tx.runInTx(() -> idps.in(JdbcSupport.tenant(ctx)).put(id, tested));
        }
        return result;
    }

    @Override
    public SyncResult syncIdentityProvider(RequestContext ctx, String id) {
        idp(ctx, id);
        throw ApiProblem.notInstalled("directory sync (R67)", "sangam-security");
    }

    private static void validateIdp(IdentityProvider p) {
        required(p == null ? null : p.name(), "name");
        JdbcSupport.oneOf(p.protocol(), "protocol", PROTOCOLS);
        switch (p.protocol()) {
            case "OIDC" -> required(p.issuer(), "issuer");
            case "SAML2" -> required(p.metadataUrl(), "metadataUrl");
            default -> required(p.attributeMapping() == null ? null : p.attributeMapping().get("url"), "attributeMapping.url");
        }
        if (p.issuer() != null && !p.issuer().startsWith("https://") && !p.issuer().startsWith("http://localhost")) {
            throw JdbcSupport.invalid("issuer", "issuer must be an https URL");
        }
    }

    private IdentityProvider idp(RequestContext ctx, String id) {
        IdentityProvider p = id == null ? null : idps.in(JdbcSupport.tenant(ctx)).get(id);
        if (p == null) {
            throw ApiProblem.notFound("Identity provider", id);
        }
        return p;
    }

    private void auditIdp(RequestContext ctx, String type, IdentityProvider p) {
        audit.record(ctx, JdbcSupport.securityChain(ctx), type, payload("idpId", p.id(), "name", p.name(),
                "protocol", p.protocol(), "issuer", p.issuer(), "mfaRequired", p.mfaRequired(), "actor", ctx.actor()));
    }

    // ------------------------------------------------------------------ service principals (R26, K14)

    @Override
    public List<ServicePrincipal> servicePrincipals(RequestContext ctx) {
        return sps.in(JdbcSupport.tenant(ctx)).values().stream().map(PrincipalSecret::principal)
                .sorted(Comparator.comparing(ServicePrincipal::name)).toList();
    }

    @Override
    public ServicePrincipalSecret createServicePrincipal(RequestContext ctx, ServicePrincipal in) {
        return tx.inTx(() -> {
            required(in == null ? null : in.name(), "name");
            if (in.scopes() == null || in.scopes().isEmpty()) {
                throw JdbcSupport.invalid("scopes", "a service principal needs at least one scope");
            }
            if (in.topics() == null || in.topics().isEmpty()) {
                throw JdbcSupport.invalid("topics", "a service principal is bound to its topics (K14)");
            }
            ServicePrincipal sp = new ServicePrincipal(JdbcSupport.newId(), in.name(), List.copyOf(in.scopes()),
                    List.copyOf(in.topics()), in.expiresAt(), null, "ACTIVE");
            String secret = newSecret();
            sps.in(JdbcSupport.tenant(ctx)).put(sp.id(), new PrincipalSecret(sp, sha(secret)));
            auditSp(ctx, "SERVICE_PRINCIPAL_CREATED", sp, null);
            return new ServicePrincipalSecret(sp, secret);
        });
    }

    @Override
    public synchronized ServicePrincipalSecret rotateServicePrincipal(RequestContext ctx, String id) {
        return tx.inTx(() -> {
            PrincipalSecret cur = sp(ctx, id);
            if ("REVOKED".equals(cur.principal().status())) {
                throw ApiProblem.conflict("INVALID_TRANSITION", "A revoked service principal cannot be rotated");
            }
            String secret = newSecret();
            sps.in(JdbcSupport.tenant(ctx)).put(id, new PrincipalSecret(cur.principal(), sha(secret)));
            auditSp(ctx, "SERVICE_PRINCIPAL_ROTATED", cur.principal(), null);
            return new ServicePrincipalSecret(cur.principal(), secret);
        });
    }

    @Override
    public synchronized ServicePrincipal revokeServicePrincipal(RequestContext ctx, String id, String reason) {
        return tx.inTx(() -> {
            requireReason(reason);
            PrincipalSecret cur = sp(ctx, id);
            ServicePrincipal p = cur.principal();
            ServicePrincipal revoked = new ServicePrincipal(p.id(), p.name(), p.scopes(), p.topics(), p.expiresAt(),
                    p.lastUsedAt(), "REVOKED");
            sps.in(JdbcSupport.tenant(ctx)).put(id, new PrincipalSecret(revoked, cur.secretSha256()));
            auditSp(ctx, "SERVICE_PRINCIPAL_REVOKED", revoked, reason);
            return revoked;
        });
    }

    private PrincipalSecret sp(RequestContext ctx, String id) {
        PrincipalSecret p = id == null ? null : sps.in(JdbcSupport.tenant(ctx)).get(id);
        if (p == null) {
            throw ApiProblem.notFound("Service principal", id);
        }
        return p;
    }

    private void auditSp(RequestContext ctx, String type, ServicePrincipal sp, String reason) {
        // The secret itself is never logged or audited.
        audit.record(ctx, JdbcSupport.securityChain(ctx), type, payload("servicePrincipalId", sp.id(), "name",
                sp.name(), "scopes", sp.scopes(), "topics", sp.topics(), "status", sp.status(), "reason", reason,
                "actor", ctx.actor()));
    }

    private static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha(String s) {
        return Hashes.hex(Hashes.sha256(s));
    }

    // ------------------------------------------------------------------ org-unit import

    @Override
    public ImportResult importOrgUnits(RequestContext ctx, String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw JdbcSupport.invalid("file", "the CSV file is empty");
        }
        List<List<String>> rows = parseCsv(new String(content, StandardCharsets.UTF_8));
        if (rows.isEmpty()) {
            throw JdbcSupport.invalid("file", "the CSV file is empty");
        }
        if (rows.size() - 1 > MAX_IMPORT_LINES) {
            throw JdbcSupport.invalid("file", "at most " + MAX_IMPORT_LINES + " lines per import");
        }
        List<String> header = rows.get(0).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i), i);
        }
        for (String needed : List.of("id", "parentid", "code", "name", "level")) {
            if (!col.containsKey(needed)) {
                throw JdbcSupport.invalid("file", "CSV header must contain id,parentId,code,name,nameHi,level");
            }
        }
        Map<String, String> idMap = new HashMap<>();
        int created = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        for (int line = 1; line < rows.size(); line++) {
            List<String> r = rows.get(line);
            if (r.size() == 1 && r.get(0).isBlank()) {
                continue;
            }
            try {
                String csvId = cell(r, col, "id");
                String parent = cell(r, col, "parentid");
                String resolvedParent = parent == null ? null : idMap.getOrDefault(parent, parent);
                String nameHi = cell(r, col, "namehi");
                Map<String, String> names = nameHi == null ? Map.of() : Map.of("hi", nameHi);
                AdminPort.OrgUnit existing = null;
                if (csvId != null) {
                    try {
                        existing = admin.orgUnit(ctx, idMap.getOrDefault(csvId, csvId));
                    } catch (ApiProblem p) {
                        if (p.status() != 404) {
                            throw p;
                        }
                    }
                }
                AdminPort.OrgUnit in = new AdminPort.OrgUnit(null, ctx.tenantId(), resolvedParent, cell(r, col, "code"),
                        cell(r, col, "name"), names, cell(r, col, "level"), true, Map.of(),
                        existing == null ? 0 : existing.revision());
                if (existing != null) {
                    admin.updateOrgUnit(ctx, existing.id(), in);
                    updated++;
                } else {
                    AdminPort.OrgUnit o = admin.createOrgUnit(ctx, in);
                    if (csvId != null) {
                        idMap.put(csvId, o.id());
                    }
                    created++;
                }
            } catch (ApiProblem p) {
                errors.add("line " + (line + 1) + ": " + p.detail());
            }
        }
        audit.record(ctx, JdbcSupport.securityChain(ctx), "ORG_UNITS_IMPORTED", payload("file", fileName,
                "created", created, "updated", updated, "errors", errors.size(),
                "sha256", Hashes.hex(Hashes.sha256(content)), "actor", ctx.actor()));
        return new ImportResult(created, updated, errors);
    }

    private static String cell(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= row.size()) {
            return null;
        }
        String v = row.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    /** RFC 4180 parser: quoted fields, doubled quotes, CR/LF line ends. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------ helpers

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "This decision requires a reason");
        }
    }

    private static RequestContext system(RequestContext ctx) {
        return new RequestContext(ctx.tenantId(), "system", Set.of(Permissions.GROUP_SYSTEM, Permissions.GROUP_ADMIN),
                null, null, null, ctx.traceId(), null, "SYSTEM:onBehalfOf=" + ctx.principalId());
    }
}
