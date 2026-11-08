// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.admin;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.identity.PrincipalResolver;
import org.sangam.security.policy.Condition;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.sangam.security.policy.ModuleResourceTypes.*;

/**
 * Admin defaults for the generic modules: role templates per module and the person-conditioned self-service grants
 * ("a person can read their own bookings, allotments and duties", sangam-resources README). Installing them is an
 * explicit, idempotent admin action ({@link #install}); a tenant may change or delete any of them afterwards.
 *
 * <p>Self-service policies are conditional grants on {@code '*'} for the implicit role
 * {@value PrincipalResolver#AUTHENTICATED_ROLE} that every resolved person carries. The conditions compile to SQL
 * (VI.4 rule 1), so lists show exactly the caller's own rows; explicit denies still win (A-3).
 */
public final class ModuleDefaults {

    private static final Set<Permission> READ_ONLY = EnumSet.of(Permission.READ, Permission.READ_HISTORY);

    private final RoleAdminService roles;
    private final PolicyAdminService policies;
    private final ResourceRegistry registry;

    public ModuleDefaults(RoleAdminService roles, PolicyAdminService policies, ResourceRegistry registry) {
        this.roles = roles;
        this.policies = policies;
        this.registry = registry;
    }

    /** Role templates: role id → (type → permissions on {@code '*'}). */
    public Map<String, Map<String, Set<Permission>>> roleTemplates() {
        Map<String, Map<String, Set<Permission>>> t = new LinkedHashMap<>();
        List<String> resTypes = List.of(RES_RESOURCE, RES_BOOKING, RES_ALLOTMENT, RES_WAITLIST, RES_DRAW, RES_EVENT,
                RES_SERVICE_POINT, RES_COUNTER, RES_TOKEN, RES_CALL_EVENT, RES_ROSTER, RES_SHIFT_TEMPLATE, RES_ROTATION,
                RES_ROSTER_MEMBER, RES_COVERAGE_DEMAND, RES_ROSTER_ASSIGNMENT, RES_SWAP_REQUEST);
        t.put("resources-admin", full(resTypes));
        t.put("resources-reader", readOnly(resTypes));
        t.put("booking-officer", Map.of(
                RES_RESOURCE, READ_ONLY,
                RES_BOOKING, EnumSet.of(Permission.READ, Permission.READ_HISTORY, Permission.START, Permission.MODIFY,
                        Permission.APPROVE, Permission.CANCEL),
                RES_ALLOTMENT, EnumSet.of(Permission.READ, Permission.READ_HISTORY, Permission.START, Permission.MODIFY,
                        Permission.APPROVE, Permission.CANCEL),
                RES_WAITLIST, EnumSet.of(Permission.READ, Permission.READ_HISTORY, Permission.START, Permission.MODIFY),
                RES_EVENT, READ_ONLY));
        t.put("queue-operator", Map.of(
                RES_SERVICE_POINT, EnumSet.of(Permission.READ),
                RES_COUNTER, EnumSet.of(Permission.READ, Permission.MODIFY),
                RES_TOKEN, EnumSet.of(Permission.READ, Permission.START, Permission.MODIFY, Permission.ASSIGN, Permission.CANCEL),
                RES_CALL_EVENT, EnumSet.of(Permission.READ)));
        t.put("roster-manager", Map.of(
                RES_ROSTER, EnumSet.of(Permission.READ, Permission.READ_HISTORY, Permission.START, Permission.MODIFY,
                        Permission.APPROVE),
                RES_SHIFT_TEMPLATE, EnumSet.of(Permission.READ, Permission.MODIFY, Permission.START),
                RES_ROTATION, EnumSet.of(Permission.READ, Permission.MODIFY, Permission.START),
                RES_ROSTER_MEMBER, EnumSet.of(Permission.READ, Permission.MODIFY, Permission.START),
                RES_COVERAGE_DEMAND, EnumSet.of(Permission.READ, Permission.MODIFY, Permission.START),
                RES_ROSTER_ASSIGNMENT, EnumSet.of(Permission.READ, Permission.MODIFY, Permission.ASSIGN, Permission.START),
                RES_SWAP_REQUEST, EnumSet.of(Permission.READ, Permission.APPROVE, Permission.CANCEL)));
        t.put("case-admin", full(List.of(CASE_FILE, CASE_REFERENCE, COMMITTEE)));
        t.put("case-reader", readOnly(List.of(CASE_FILE, CASE_REFERENCE, COMMITTEE)));
        t.put("programme-admin", full(List.of(PROGRAMME, PROGRAMME_RETURN)));
        t.put("programme-reader", readOnly(List.of(PROGRAMME, PROGRAMME_RETURN)));
        t.put("registry-admin", full(List.of(REGISTRY_OBJECT, REGISTRY_PARTY, REGISTRY_ASSET, REGISTRY_WORK_ORDER,
                REGISTRY_INSTRUMENT_PUBLIC)));
        t.put("registry-reader", readOnly(List.of(REGISTRY_OBJECT, REGISTRY_PARTY, REGISTRY_ASSET, REGISTRY_WORK_ORDER)));
        t.put("compliance-officer", full(List.of(COMPLIANCE)));
        t.put("config-admin", full(List.of(CONFIG)));
        t.put("ledger-admin", full(List.of(LEDGER)));
        t.put("ledger-reader", Map.of(LEDGER, EnumSet.of(Permission.READ, Permission.READ_HISTORY, Permission.EXPORT)));
        t.put("billing-admin", full(List.of(BILLING)));
        t.put("field-agent", Map.of(FIELD, EnumSet.of(Permission.READ, Permission.START, Permission.MODIFY)));
        t.put("field-reviewer", Map.of(FIELD, EnumSet.of(Permission.READ, Permission.READ_SENSITIVE, Permission.APPROVE)));
        t.put("field-admin", full(List.of(FIELD)));
        t.put("dispatch-operator", Map.of(DISPATCH, EnumSet.of(Permission.READ, Permission.START, Permission.MODIFY,
                Permission.ASSIGN, Permission.CANCEL)));
        t.put("geo-admin", full(List.of(GEO)));
        t.put("geo-reader", Map.of(GEO, EnumSet.of(Permission.READ, Permission.EXPORT)));
        return t;
    }

    /** Self-service grants for {@code role:authenticated}: own bookings, allotments, waitlist entries and duties. */
    public List<PolicyAdminService.PolicyDefinition> selfServicePolicies() {
        String subject = SecurityIdentity.ROLE + PrincipalResolver.AUTHENTICATED_ROLE;
        List<PolicyAdminService.PolicyDefinition> out = new ArrayList<>();
        out.add(self("self-bookings", "Own bookings", subject, RES_BOOKING, either("requesterId", "beneficiaryId")));
        out.add(self("self-allotments", "Own allotments", subject, RES_ALLOTMENT, either("holderId", "beneficiaryId")));
        out.add(self("self-waitlist", "Own waitlist entries", subject, RES_WAITLIST, either("requesterId", "beneficiaryId")));
        out.add(self("self-duties", "Own duties", subject, RES_ROSTER_ASSIGNMENT, mine("personId")));
        out.add(self("self-roster-membership", "Own roster membership", subject, RES_ROSTER_MEMBER, mine("personId")));
        out.add(self("self-swaps", "Own swap requests", subject, RES_SWAP_REQUEST, either("requesterId", "counterpartId")));
        return out;
    }

    /** Installs the role templates and the self-service policies (existing ones are left unchanged). */
    public void install(Connection c, SecurityIdentity admin, boolean selfService) {
        for (Map.Entry<String, Map<String, Set<Permission>>> e : roleTemplates().entrySet()) {
            List<RoleAdminService.RoleRule> rules = new ArrayList<>();
            e.getValue().forEach((type, perms) ->
                    rules.add(new RoleAdminService.RoleRule(type, ResourceRegistry.ANY_ID, perms, false)));
            roles.defineRole(c, admin, e.getKey(), e.getKey(), rules);
        }
        if (selfService) {
            for (PolicyAdminService.PolicyDefinition p : selfServicePolicies()) {
                if (policies.find(c, admin.tenantId(), p.policyId()).isEmpty()) {
                    policies.create(c, admin, p, "module default (self-service)");
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Set<Permission>> full(List<String> types) {
        Map<String, Set<Permission>> m = new LinkedHashMap<>();
        types.forEach(t -> m.put(t, registry.permissionsOf(t)));
        return m;
    }

    private Map<String, Set<Permission>> readOnly(List<String> types) {
        Map<String, Set<Permission>> m = new LinkedHashMap<>();
        for (String t : types) {
            Set<Permission> s = EnumSet.noneOf(Permission.class);
            for (Permission p : List.of(Permission.READ, Permission.READ_HISTORY, Permission.EXPORT)) {
                if (registry.permissionsOf(t).isEmpty() || registry.permissionsOf(t).contains(p)) {
                    s.add(p);
                }
            }
            m.put(t, s);
        }
        return m;
    }

    private static PolicyAdminService.PolicyDefinition self(String id, String name, String subject, String type, Condition c) {
        return new PolicyAdminService.PolicyDefinition(id, name, PolicyAdminService.Effect.ALLOW, List.of(subject), type,
                ResourceRegistry.ANY_ID, READ_ONLY, c);
    }

    private static Condition mine(String attr) {
        return new Condition.Compare(attr, Condition.Op.EQ, Condition.Operand.principal("personId"));
    }

    private static Condition either(String a, String b) {
        return new Condition.Or(List.of(mine(a), mine(b)));
    }
}
