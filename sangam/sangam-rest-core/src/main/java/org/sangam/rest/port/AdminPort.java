// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Administration (IX.5 organisation/seat/delegation model, VI.4 policies, multi-tenancy EC-17). Implemented by
 * sangam-security; every change is a class-B event on the tenant security chain ({@code sec:<tenant>}) and every
 * grant or membership change increments {@code policy_version} (VI.4 rule 5).
 */
public interface AdminPort {

    /** One filter for all admin lists; unused fields are ignored. {@code activeOn} filters effective-dated rows. */
    record AdminFilter(String q, String parentId, String orgUnitId, String seatId, String personId, String kind,
                       LocalDate activeOn, Boolean includeInactive) {
    }

    /** level: MINISTRY | DEPARTMENT | DIRECTORATE | REGION | DISTRICT | OFFICE | SECTION | OTHER (IX.5 rule 2). */
    record OrgUnit(String id, String tenantId, String parentId, String code, String name, Map<String, String> names,
                   String level, boolean active, Map<String, Object> attributes, long revision) {
    }

    /** Seat (post) ≠ person (IX.5 rule 1). */
    record Seat(String id, String tenantId, String orgUnitId, String code, String designation, String name,
                Integer hierarchyLevel, String reportsToSeatId, List<String> roles, boolean active,
                Map<String, Object> attributes, long revision) {
    }

    /** kind: SUBSTANTIVE | ADDITIONAL_CHARGE | LEAVE_SUBSTITUTE | ACTING (IX.5 rules 1, 4). */
    record Assignment(String id, String tenantId, String seatId, String personId, String kind, LocalDate validFrom,
                      LocalDate validTo, String actedForSeatId, String orderRef, String createdBy, Instant createdAt,
                      boolean ended) {
    }

    /**
     * kind: POWER (delegation of financial/administrative powers, versioned DMN table, IX.5 rule 3) |
     * TASK (person-to-person delegation for leave). amountLimit is a decimal string (R33).
     */
    record Delegation(String id, String tenantId, String kind, String seatId, String toSeatId, String fromPersonId,
                      String toPersonId, String decisionKey, Integer decisionVersion, String caseType,
                      String amountLimit, String currency, LocalDate validFrom, LocalDate validTo, String orderRef,
                      boolean revoked, String createdBy, Instant createdAt) {
    }

    /**
     * effect: GRANT | DENY (deny wins at any scope, A-13). principalType: USER | GROUP | SEAT.
     * resourceId {@code *} = every resource of the type.
     */
    record Policy(String id, String tenantId, String effect, String principalType, String principalId,
                  String resourceType, String resourceId, List<String> actions, String conditionId,
                  long policyVersion, String createdBy, Instant createdAt) {
    }

    record PolicyVersion(String tenantId, long policyVersion) {
    }

    /** status: DRAFT | ACTIVE | SUSPENDED. profile: GOVERNMENT | STANDARD. isolation: SHARED | SCHEMA | DATABASE. */
    record Tenant(String id, String name, String status, String profile, String isolation, String defaultLanguage,
                  List<String> languages, Map<String, Object> attributes, Instant createdAt, long revision) {
    }

    Page<OrgUnit> orgUnits(RequestContext ctx, AdminFilter filter, Page.PageRequest page);

    OrgUnit orgUnit(RequestContext ctx, String id);

    OrgUnit createOrgUnit(RequestContext ctx, OrgUnit orgUnit);

    OrgUnit updateOrgUnit(RequestContext ctx, String id, OrgUnit orgUnit);

    void deleteOrgUnit(RequestContext ctx, String id, String reason);

    Page<Seat> seats(RequestContext ctx, AdminFilter filter, Page.PageRequest page);

    Seat seat(RequestContext ctx, String id);

    Seat createSeat(RequestContext ctx, Seat seat);

    Seat updateSeat(RequestContext ctx, String id, Seat seat);

    void deleteSeat(RequestContext ctx, String id, String reason);

    Page<Assignment> assignments(RequestContext ctx, AdminFilter filter, Page.PageRequest page);

    Assignment assignment(RequestContext ctx, String id);

    Assignment createAssignment(RequestContext ctx, Assignment assignment);

    Assignment endAssignment(RequestContext ctx, String id, LocalDate validTo, String reason);

    Page<Delegation> delegations(RequestContext ctx, AdminFilter filter, Page.PageRequest page);

    Delegation delegation(RequestContext ctx, String id);

    Delegation createDelegation(RequestContext ctx, Delegation delegation);

    Delegation revokeDelegation(RequestContext ctx, String id, String reason);

    Page<Policy> policies(RequestContext ctx, AdminFilter filter, Page.PageRequest page);

    Policy createPolicy(RequestContext ctx, Policy policy);

    void deletePolicy(RequestContext ctx, String id, String reason);

    PolicyVersion policyVersion(RequestContext ctx);

    Page<Tenant> tenants(RequestContext ctx, AdminFilter filter, Page.PageRequest page);

    Tenant tenant(RequestContext ctx, String id);

    Tenant createTenant(RequestContext ctx, Tenant tenant);

    Tenant updateTenant(RequestContext ctx, String id, Tenant tenant);
}
