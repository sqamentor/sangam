// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    /**
     * level: MINISTRY | DEPARTMENT | DIRECTORATE | REGION | DISTRICT | OFFICE | SECTION | OTHER (IX.5 rule 2).
     * Optional on a request (SL-81): {@code active} is server-assigned on create (true), and an update without it
     * keeps the current value; {@code revision} defaults to 0 (none read: server-assigned on create; an update that
     * checks revisions refuses 0 with 409 REVISION_CONFLICT).
     */
    record OrgUnit(String id, String tenantId, String parentId, String code, String name, Map<String, String> names,
                   String level, Boolean active, Map<String, Object> attributes, long revision) {

        /** Request binding: an omitted optional value takes the documented default above, never a guess. */
        @JsonCreator
        public static OrgUnit fromRequest(@JsonProperty("id") String id, @JsonProperty("tenantId") String tenantId,
                                          @JsonProperty("parentId") String parentId, @JsonProperty("code") String code,
                                          @JsonProperty("name") String name,
                                          @JsonProperty("names") Map<String, String> names,
                                          @JsonProperty("level") String level, @JsonProperty("active") Boolean active,
                                          @JsonProperty("attributes") Map<String, Object> attributes,
                                          @JsonProperty("revision") Long revision) {
            return new OrgUnit(id, tenantId, parentId, code, name, names, level, active, attributes,
                    revision == null ? 0L : revision);
        }
    }

    /**
     * Seat (post) ≠ person (IX.5 rule 1). Optional on a request (SL-81): {@code active} is server-assigned on
     * create (true), and an update without it keeps the current value; {@code revision} defaults to 0 (none read:
     * server-assigned on create; an update that checks revisions refuses 0 with 409 REVISION_CONFLICT).
     */
    record Seat(String id, String tenantId, String orgUnitId, String code, String designation, String name,
                Integer hierarchyLevel, String reportsToSeatId, List<String> roles, Boolean active,
                Map<String, Object> attributes, long revision) {

        /** Request binding: an omitted optional value takes the documented default above, never a guess. */
        @JsonCreator
        public static Seat fromRequest(@JsonProperty("id") String id, @JsonProperty("tenantId") String tenantId,
                                       @JsonProperty("orgUnitId") String orgUnitId, @JsonProperty("code") String code,
                                       @JsonProperty("designation") String designation, @JsonProperty("name") String name,
                                       @JsonProperty("hierarchyLevel") Integer hierarchyLevel,
                                       @JsonProperty("reportsToSeatId") String reportsToSeatId,
                                       @JsonProperty("roles") List<String> roles, @JsonProperty("active") Boolean active,
                                       @JsonProperty("attributes") Map<String, Object> attributes,
                                       @JsonProperty("revision") Long revision) {
            return new Seat(id, tenantId, orgUnitId, code, designation, name, hierarchyLevel, reportsToSeatId, roles,
                    active, attributes, revision == null ? 0L : revision);
        }
    }

    /**
     * kind: SUBSTANTIVE | ADDITIONAL_CHARGE | LEAVE_SUBSTITUTE | ACTING (IX.5 rules 1, 4). Optional on a request
     * (SL-81): {@code ended} is server-assigned (false on create; set by POST /admin/assignments/{id}/end) and an
     * omitted value is false.
     */
    record Assignment(String id, String tenantId, String seatId, String personId, String kind, LocalDate validFrom,
                      LocalDate validTo, String actedForSeatId, String orderRef, String createdBy, Instant createdAt,
                      boolean ended) {

        /** Request binding: an omitted optional value takes the documented default above, never a guess. */
        @JsonCreator
        public static Assignment fromRequest(@JsonProperty("id") String id, @JsonProperty("tenantId") String tenantId,
                                             @JsonProperty("seatId") String seatId,
                                             @JsonProperty("personId") String personId,
                                             @JsonProperty("kind") String kind,
                                             @JsonProperty("validFrom") LocalDate validFrom,
                                             @JsonProperty("validTo") LocalDate validTo,
                                             @JsonProperty("actedForSeatId") String actedForSeatId,
                                             @JsonProperty("orderRef") String orderRef,
                                             @JsonProperty("createdBy") String createdBy,
                                             @JsonProperty("createdAt") Instant createdAt,
                                             @JsonProperty("ended") Boolean ended) {
            return new Assignment(id, tenantId, seatId, personId, kind, validFrom, validTo, actedForSeatId, orderRef,
                    createdBy, createdAt, ended != null && ended);
        }
    }

    /**
     * kind: POWER (delegation of financial/administrative powers, versioned DMN table, IX.5 rule 3) |
     * TASK (person-to-person delegation for leave). amountLimit is a decimal string (R33).
     */
    record Delegation(String id, String tenantId, String kind, String seatId, String toSeatId, String fromPersonId,
                      String toPersonId, String decisionKey, Integer decisionVersion, String caseType,
                      String amountLimit, String currency, LocalDate validFrom, LocalDate validTo, String orderRef,
                      boolean revoked, String createdBy, Instant createdAt) {

        /**
         * Request binding (SL-81): {@code revoked} is server-assigned (false on create; set by
         * POST /admin/delegations/{id}/revoke) and an omitted value is false, never a guess.
         */
        @JsonCreator
        public static Delegation fromRequest(@JsonProperty("id") String id, @JsonProperty("tenantId") String tenantId,
                                             @JsonProperty("kind") String kind, @JsonProperty("seatId") String seatId,
                                             @JsonProperty("toSeatId") String toSeatId,
                                             @JsonProperty("fromPersonId") String fromPersonId,
                                             @JsonProperty("toPersonId") String toPersonId,
                                             @JsonProperty("decisionKey") String decisionKey,
                                             @JsonProperty("decisionVersion") Integer decisionVersion,
                                             @JsonProperty("caseType") String caseType,
                                             @JsonProperty("amountLimit") String amountLimit,
                                             @JsonProperty("currency") String currency,
                                             @JsonProperty("validFrom") LocalDate validFrom,
                                             @JsonProperty("validTo") LocalDate validTo,
                                             @JsonProperty("orderRef") String orderRef,
                                             @JsonProperty("revoked") Boolean revoked,
                                             @JsonProperty("createdBy") String createdBy,
                                             @JsonProperty("createdAt") Instant createdAt) {
            return new Delegation(id, tenantId, kind, seatId, toSeatId, fromPersonId, toPersonId, decisionKey,
                    decisionVersion, caseType, amountLimit, currency, validFrom, validTo, orderRef,
                    revoked != null && revoked, createdBy, createdAt);
        }
    }

    /**
     * effect: GRANT | DENY (deny wins at any scope, A-13). principalType: USER | GROUP | SEAT.
     * resourceId {@code *} = every resource of the type.
     */
    record Policy(String id, String tenantId, String effect, String principalType, String principalId,
                  String resourceType, String resourceId, List<String> actions, String conditionId,
                  long policyVersion, String createdBy, Instant createdAt) {

        /**
         * Request binding (SL-81): {@code policyVersion} is server-assigned (the tenant policy version after the
         * change, VI.4 rule 5) and an omitted value is 0, never a guess.
         */
        @JsonCreator
        public static Policy fromRequest(@JsonProperty("id") String id, @JsonProperty("tenantId") String tenantId,
                                         @JsonProperty("effect") String effect,
                                         @JsonProperty("principalType") String principalType,
                                         @JsonProperty("principalId") String principalId,
                                         @JsonProperty("resourceType") String resourceType,
                                         @JsonProperty("resourceId") String resourceId,
                                         @JsonProperty("actions") List<String> actions,
                                         @JsonProperty("conditionId") String conditionId,
                                         @JsonProperty("policyVersion") Long policyVersion,
                                         @JsonProperty("createdBy") String createdBy,
                                         @JsonProperty("createdAt") Instant createdAt) {
            return new Policy(id, tenantId, effect, principalType, principalId, resourceType, resourceId, actions,
                    conditionId, policyVersion == null ? 0L : policyVersion, createdBy, createdAt);
        }
    }

    record PolicyVersion(String tenantId, long policyVersion) {
    }

    /**
     * status: DRAFT | ACTIVE | SUSPENDED. profile: GOVERNMENT | STANDARD. isolation: SHARED | SCHEMA | DATABASE.
     * Optional on a request (SL-81): {@code revision} defaults to 0 (none read: server-assigned on create; an update
     * that checks revisions refuses 0 with 409 REVISION_CONFLICT).
     */
    record Tenant(String id, String name, String status, String profile, String isolation, String defaultLanguage,
                  List<String> languages, Map<String, Object> attributes, Instant createdAt, long revision) {

        /** Request binding: an omitted optional value takes the documented default above, never a guess. */
        @JsonCreator
        public static Tenant fromRequest(@JsonProperty("id") String id, @JsonProperty("name") String name,
                                         @JsonProperty("status") String status, @JsonProperty("profile") String profile,
                                         @JsonProperty("isolation") String isolation,
                                         @JsonProperty("defaultLanguage") String defaultLanguage,
                                         @JsonProperty("languages") List<String> languages,
                                         @JsonProperty("attributes") Map<String, Object> attributes,
                                         @JsonProperty("createdAt") Instant createdAt,
                                         @JsonProperty("revision") Long revision) {
            return new Tenant(id, name, status, profile, isolation, defaultLanguage, languages, attributes, createdAt,
                    revision == null ? 0L : revision);
        }
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
