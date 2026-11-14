// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Identity and authority administration beyond the core admin API (A01–A11, R50, R67, IX.5 rules 4–5, VI.4):
 * assignment impact and approval, policy explanation, SoD exceptions, users and groups, identity providers,
 * service principals (R26) and bulk org-unit import. Implemented by sangam-security.
 */
public interface IdentityAdminPort {

    record SodConflict(String taskId, String reason) {
    }

    record AssignmentImpact(long openTasks, List<SodConflict> sodConflicts, List<String> summary) {
    }

    record DecisionRequest(String reason) {
    }

    record ExplainRequest(String principalId, String action, String resourceType, String resourceId) {
    }

    /** effect: ALLOW | DENY. */
    record PolicyMatch(String policyId, String effect, String scope) {
    }

    record ExplainResult(boolean allowed, List<PolicyMatch> matched, long policyVersion) {
    }

    /** status: PENDING | APPROVED | REJECTED | EXPIRED. Approval by a higher authority with a reason (IX.5 rule 5). */
    record SodException(String id, String rule, String personId, String personName, String reason, Instant expiresAt,
                        String status, String approvedBy, String requestedBy) {
    }

    /** source: LOCAL | OIDC | LDAP | SCIM. */
    record User(String id, String username, String displayName, String email, boolean active, List<String> groups,
                String source, Instant lastLogin) {
    }

    record Group(String id, String name, String type, Integer members, String source) {
    }

    /** protocol: OIDC | SAML2 | LDAP. status: DRAFT | TESTED | ACTIVE. */
    record IdentityProvider(String id, String name, String protocol, String issuer, String metadataUrl,
                            Map<String, String> attributeMapping, boolean mfaRequired, int sessionTimeoutMin,
                            String status, Instant lastTestAt) {
    }

    record TestResult(boolean ok, String message) {
    }

    record SyncResult(long users, long groups) {
    }

    /** status: PENDING | ACTIVE | REVOKED. Bound to tenant and topics (R26, K14). */
    record ServicePrincipal(String id, String name, List<String> scopes, List<String> topics, Instant expiresAt,
                            Instant lastUsedAt, String status) {
    }

    /** {@code secret} is returned once, at creation or rotation; only its hash is stored. */
    record ServicePrincipalSecret(ServicePrincipal principal, String secret) {
    }

    record ImportResult(int created, int updated, List<String> errors) {
    }

    AssignmentImpact assignmentImpact(RequestContext ctx, AdminPort.Assignment assignment);

    AdminPort.Assignment decideAssignment(RequestContext ctx, String assignmentId, boolean approve, String reason);

    ExplainResult explain(RequestContext ctx, ExplainRequest request);

    Page<SodException> sodExceptions(RequestContext ctx, String status, Page.PageRequest page);

    SodException createSodException(RequestContext ctx, SodException request);

    SodException decideSodException(RequestContext ctx, String id, boolean approve, String reason);

    Page<User> users(RequestContext ctx, String q, Page.PageRequest page);

    Page<Group> groups(RequestContext ctx, String q, Page.PageRequest page);

    List<User> groupMembers(RequestContext ctx, String groupId);

    void setMembership(RequestContext ctx, String groupId, String userId, boolean member);

    List<IdentityProvider> identityProviders(RequestContext ctx);

    IdentityProvider createIdentityProvider(RequestContext ctx, IdentityProvider provider);

    IdentityProvider updateIdentityProvider(RequestContext ctx, String id, IdentityProvider provider);

    TestResult testIdentityProvider(RequestContext ctx, String id);

    SyncResult syncIdentityProvider(RequestContext ctx, String id);

    List<ServicePrincipal> servicePrincipals(RequestContext ctx);

    ServicePrincipalSecret createServicePrincipal(RequestContext ctx, ServicePrincipal principal);

    ServicePrincipalSecret rotateServicePrincipal(RequestContext ctx, String id);

    ServicePrincipal revokeServicePrincipal(RequestContext ctx, String id, String reason);

    /** CSV columns: id,parentId,code,name,nameHi,level (header row required). */
    ImportResult importOrgUnits(RequestContext ctx, String fileName, byte[] content);
}
