// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.IdentityAdminPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.admin.ApiTokenService;
import org.sangam.security.admin.SecurityDirectoryService;
import org.sangam.security.org.OrgModel;
import org.sangam.security.policy.AuthorizedQueryBuilder;

/**
 * sangam-rest {@link IdentityAdminPort} on sangam-security where the security module has the service:
 * SoD exceptions ({@code SegregationOfDutiesService}, IX.5 rule 5), users ({@code PersonService}), groups and
 * memberships ({@code GroupAdminService}), service principals ({@code ApiTokenService} + {@code WorkerBindingService},
 * R26/K14). Assignment impact and approval, policy explanation, identity providers and org-unit import have no
 * security-service counterpart and use the default adapter ({@code fallback}), which itself works on the
 * security-backed {@link AdminPort}.
 *
 * <p>Lists come from {@code SecurityDirectoryService} (SoD exceptions, users, groups) and
 * {@code ApiTokenService.listTenant} (service principals): paged and tenant-bound in SQL with a capped total; this
 * bridge runs no SQL of its own.
 *
 * <p>SoD exception {@code rule} format: {@code <caseId>/<duty>} (the exception is bound to one case and one duty).
 * Service principals are persons {@code svc-<name>}; their topics are worker bindings, their scopes token scopes.
 */
public final class SecurityIdentityAdminBridge implements IdentityAdminPort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DEFAULT_TOKEN_LIFETIME = Duration.ofDays(365);
    private static final Duration DEFAULT_SOD_EXCEPTION_LIFETIME = Duration.ofDays(7);

    private final SangamSecurity security;
    private final TxSupport tx;
    private final IdentityAdminPort fallback;
    private final RestProperties props;

    public SecurityIdentityAdminBridge(SangamSecurity security, TxSupport tx, IdentityAdminPort fallback,
                                       RestProperties props) {
        this.security = security;
        this.tx = tx;
        this.fallback = fallback;
        this.props = props;
    }

    // ------------------------------------------------------------------ default adapter

    @Override
    public AssignmentImpact assignmentImpact(RequestContext ctx, AdminPort.Assignment assignment) {
        return fallback.assignmentImpact(ctx, assignment);
    }

    @Override
    public AdminPort.Assignment decideAssignment(RequestContext ctx, String assignmentId, boolean approve, String reason) {
        return fallback.decideAssignment(ctx, assignmentId, approve, reason);
    }

    @Override
    public ExplainResult explain(RequestContext ctx, ExplainRequest request) {
        return fallback.explain(ctx, request);
    }

    @Override
    public List<IdentityProvider> identityProviders(RequestContext ctx) {
        return fallback.identityProviders(ctx);
    }

    @Override
    public IdentityProvider createIdentityProvider(RequestContext ctx, IdentityProvider provider) {
        return fallback.createIdentityProvider(ctx, provider);
    }

    @Override
    public IdentityProvider updateIdentityProvider(RequestContext ctx, String id, IdentityProvider provider) {
        return fallback.updateIdentityProvider(ctx, id, provider);
    }

    @Override
    public TestResult testIdentityProvider(RequestContext ctx, String id) {
        return fallback.testIdentityProvider(ctx, id);
    }

    @Override
    public SyncResult syncIdentityProvider(RequestContext ctx, String id) {
        return fallback.syncIdentityProvider(ctx, id);
    }

    @Override
    public ImportResult importOrgUnits(RequestContext ctx, String fileName, byte[] content) {
        return fallback.importOrgUnits(ctx, fileName, content);
    }

    // ------------------------------------------------------------------ SoD exceptions

    @Override
    public Page<SodException> sodExceptions(RequestContext ctx, String status, Page.PageRequest page) {
        var filter = new SecurityDirectoryService.SodExceptionFilter(
                status == null ? null : status.toUpperCase(Locale.ROOT), null, null);
        var p = run(c -> security.directory().sodExceptions(c, actor(ctx), filter, page.firstResult(), page.size(),
                cap()));
        return SecurityAdminBridge.rest(p, SecurityIdentityAdminBridge::sod);
    }

    private static SodException sod(SecurityDirectoryService.SodExceptionRow r) {
        return new SodException(r.exceptionId(), r.caseId() + "/" + r.duty(), r.personId(), null, r.reason(),
                r.expiresAt(), r.status(), r.approvedBy(), r.requestedBy());
    }

    @Override
    public SodException createSodException(RequestContext ctx, SodException r) {
        if (r.rule() == null || r.rule().indexOf('/') <= 0) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "rule must be <caseId>/<duty>").with("field", "rule");
        }
        if (r.reason() == null || r.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "An SoD exception needs a reason (IX.5 rule 5)");
        }
        if (r.personId() != null && !r.personId().equals(ctx.principalId())) {
            throw ApiProblem.unprocessable("VALIDATION_FAILED",
                    "An SoD exception is requested by the person who needs it");
        }
        String caseId = r.rule().substring(0, r.rule().lastIndexOf('/'));
        String duty = r.rule().substring(r.rule().lastIndexOf('/') + 1);
        String id = run(c -> security.sod().requestException(c, actor(ctx), caseId, duty, ctx.seatId(), r.reason()));
        return find(ctx, id);
    }

    @Override
    public SodException decideSodException(RequestContext ctx, String id, boolean approve, String reason) {
        SodException current = find(ctx, id);
        run(c -> {
            if (approve) {
                Instant expires = current.expiresAt() != null ? current.expiresAt()
                        : Instant.now().plus(DEFAULT_SOD_EXCEPTION_LIFETIME);
                security.sod().grantException(c, actor(ctx), id, ctx.seatId(), expires, reason);
            } else {
                security.sod().rejectException(c, actor(ctx), id, reason);
            }
            return null;
        });
        return find(ctx, id);
    }

    private SodException find(RequestContext ctx, String id) {
        // The directory filters by status/case/person, not id: page through the tenant's exceptions (bounded by the cap).
        int size = Page.PageRequest.MAX_SIZE;
        for (int page = 0; (long) page * size < props.getCountCap(); page++) {
            Page<SodException> p = sodExceptions(ctx, null, Page.PageRequest.of(page, size));
            for (SodException e : p.items()) {
                if (e.id().equals(id)) {
                    return e;
                }
            }
            if (p.items().size() < size) {
                break;
            }
        }
        throw ApiProblem.notFound("SoD exception", id);
    }

    // ------------------------------------------------------------------ users and groups

    @Override
    public Page<User> users(RequestContext ctx, String q, Page.PageRequest page) {
        var filter = new SecurityDirectoryService.UserFilter(q, null, null);
        return run(c -> SecurityAdminBridge.rest(security.directory().users(c, actor(ctx), filter, page.firstResult(),
                page.size(), cap()), p -> user(c, p)));
    }

    @Override
    public Page<Group> groups(RequestContext ctx, String q, Page.PageRequest page) {
        var p = run(c -> security.directory().groups(c, actor(ctx), q, null, page.firstResult(), page.size(), cap()));
        return SecurityAdminBridge.rest(p, g -> new Group(g.groupId(), g.name(),
                g.externalRef() == null ? "LOCAL" : "EXTERNAL", (int) Math.min(Integer.MAX_VALUE, g.members()),
                g.source()));
    }

    @Override
    public List<User> groupMembers(RequestContext ctx, String groupId) {
        return run(c -> {
            List<User> out = new ArrayList<>();
            for (String personId : security.groups().members(c, ctx.tenantId(), groupId)) {
                security.persons().find(c, ctx.tenantId(), personId).ifPresent(p -> out.add(user(c, p)));
            }
            return out;
        });
    }

    @Override
    public void setMembership(RequestContext ctx, String groupId, String userId, boolean member) {
        run(c -> {
            if (member) {
                security.groups().addMember(c, actor(ctx), groupId, userId, "LOCAL");
            } else {
                security.groups().removeMember(c, actor(ctx), groupId, userId);
            }
            return null;
        });
    }

    private User user(Connection c, OrgModel.Person p) {
        return new User(p.personId(), p.externalSubject() != null ? p.externalSubject() : p.personId(),
                p.displayName() != null ? p.displayName() : p.personId(), p.email(),
                p.status() == null || "ACTIVE".equalsIgnoreCase(p.status()),
                security.groups().groupsOf(c, p.tenantId(), p.personId()), null, null);
    }

    // ------------------------------------------------------------------ service principals (R26, K14)

    @Override
    /** The tenant's API tokens (newest first), bounded by the count cap and the security page limit. */
    public List<ServicePrincipal> servicePrincipals(RequestContext ctx) {
        int limit = (int) Math.min(props.getCountCap(), AuthorizedQueryBuilder.MAX_PAGE_SIZE);
        return run(c -> security.tokens().listTenant(c, actor(ctx), 0, limit, cap()).items().stream()
                .map(t -> principal(c, ctx, t)).toList());
    }

    private ServicePrincipal principal(Connection c, RequestContext ctx, ApiTokenService.TokenInfo t) {
        return new ServicePrincipal(t.tokenId(), t.name(), t.scopes(),
                security.workers().topicsOf(c, ctx.tenantId(), t.ownerPersonId()), t.expiresAt(), t.lastUsedAt(),
                t.revoked() ? "REVOKED" : "ACTIVE");
    }

    @Override
    public ServicePrincipalSecret createServicePrincipal(RequestContext ctx, ServicePrincipal p) {
        if (p.name() == null || p.name().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "name is required").with("field", "name");
        }
        if (p.scopes() == null || p.scopes().isEmpty() || p.topics() == null || p.topics().isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED",
                    "a service principal needs scopes and topics (bound to tenant and topics, R26, K14)");
        }
        String owner = ownerOf(p.name());
        Instant expires = p.expiresAt() != null ? p.expiresAt() : Instant.now().plus(DEFAULT_TOKEN_LIFETIME);
        ApiTokenService.IssuedToken issued = run(c -> {
            SecurityIdentity actor = actor(ctx);
            if (security.persons().find(c, ctx.tenantId(), owner).isEmpty()) {
                security.persons().createUser(c, actor, owner, p.name(), null, null);
            }
            for (String topic : p.topics()) {
                security.workers().bind(c, actor, owner, topic);
            }
            return security.tokens().issue(c, actor, owner, p.name(), new LinkedHashSet<>(p.scopes()), expires);
        });
        return new ServicePrincipalSecret(new ServicePrincipal(issued.tokenId(), p.name(), List.copyOf(p.scopes()),
                List.copyOf(p.topics()), issued.expiresAt(), null, "ACTIVE"), issued.plaintext());
    }

    @Override
    public ServicePrincipalSecret rotateServicePrincipal(RequestContext ctx, String id) {
        ServicePrincipal current = servicePrincipal(ctx, id);
        if ("REVOKED".equals(current.status())) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "A revoked service principal cannot be rotated");
        }
        String owner = ownerOf(current.name());
        ApiTokenService.IssuedToken issued = run(c -> {
            ApiTokenService.IssuedToken next = security.tokens().issue(c, actor(ctx), owner, current.name(),
                    new LinkedHashSet<>(current.scopes()), Instant.now().plus(DEFAULT_TOKEN_LIFETIME));
            security.tokens().revoke(c, actor(ctx), id);
            return next;
        });
        return new ServicePrincipalSecret(new ServicePrincipal(issued.tokenId(), current.name(), current.scopes(),
                current.topics(), issued.expiresAt(), null, "ACTIVE"), issued.plaintext());
    }

    @Override
    public ServicePrincipal revokeServicePrincipal(RequestContext ctx, String id, String reason) {
        servicePrincipal(ctx, id);
        run(c -> {
            security.tokens().revoke(c, actor(ctx), id);
            return null;
        });
        return servicePrincipal(ctx, id);
    }

    private ServicePrincipal servicePrincipal(RequestContext ctx, String id) {
        return run(c -> security.tokens().find(c, actor(ctx), id).map(t -> principal(c, ctx, t)))
                .orElseThrow(() -> ApiProblem.notFound("service principal", id));
    }

    private int cap() {
        return (int) Math.min(props.getCountCap(), Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------ helpers

    private static String ownerOf(String name) {
        String id = "svc-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return id.length() > 64 ? id.substring(0, 64) : id;
    }

    private static List<String> scopes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of(json);
        }
    }

    private <T> T run(Function<Connection, T> work) {
        try {
            return tx.withConnection(work);
        } catch (SangamSecurityException e) {
            throw new ApiProblem(e.httpStatus(), e.code(), e.getMessage());
        }
    }

    private static SecurityIdentity actor(RequestContext ctx) {
        return SecurityIdentities.of(ctx);
    }

    private static boolean matches(String q, String... values) {
        String needle = q.toLowerCase(Locale.ROOT);
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unused")
    private static Set<String> none() {
        return Set.of();
    }
}
