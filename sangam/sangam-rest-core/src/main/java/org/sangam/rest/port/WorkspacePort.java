// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The staff workspace beyond the core task API (S02, S03, S07–S09, S15, R76): inbox counts, bulk actions, candidate
 * seats, field-level diff, task lifecycle (FLOWABLE-INTO-OPERATON FC-52), the caller's profile, seats, notifications, delegations and leave,
 * the start catalogue, case comments and participants, and the field-inspection app (EC-16).
 */
public interface WorkspacePort {

    /** One seat held by the caller (IX.5 rules 1, 4). kind: SUBSTANTIVE | ADDITIONAL_CHARGE | LEAVE_SUBSTITUTE. */
    record SeatHolding(String seatId, String seatName, String designation, String orgUnitName, String kind,
                       String actingForPersonName, LocalDate validFrom, LocalDate validTo, boolean pendingApproval) {
    }

    /** {@code GET /me}. roles are UI personas (TASK_USER, OPERATOR, ADMIN, MODELLER, REVIEWER, AUDITOR, ...). */
    record Me(String tenantId, String tenantName, String principalId, String userId, String displayName,
              List<String> groups, List<String> roles, List<SeatHolding> seats, String seatId, String actingForSeatId,
              String authMethod, List<String> permissions, String preferredLanguage,
              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
              MeLocale locale) {

        /** Without a locale (M-045: the field is then absent from the response). */
        public Me(String tenantId, String tenantName, String principalId, String userId, String displayName,
                  List<String> groups, List<String> roles, List<SeatHolding> seats, String seatId, String actingForSeatId,
                  String authMethod, List<String> permissions, String preferredLanguage) {
            this(tenantId, tenantName, principalId, userId, displayName, groups, roles, seats, seatId, actingForSeatId,
                    authMethod, permissions, preferredLanguage, null);
        }
    }

    /**
     * M-045: money and number formatting of the tenant's resolved profile ({@code locale} section). Each part is absent
     * when the profile does not declare it.
     *
     * @param currency ISO 4217 code of the tenant's money
     * @param zone     IANA time zone of the tenant
     * @param grouping digit grouping of the integer part
     */
    record MeLocale(@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String currency,
                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String zone,
                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    NumberGrouping grouping) {
    }

    /** Digit grouping: the first group (from the right) and every further group, e.g. {@code {3, 2}} gives 12,34,567. */
    record NumberGrouping(int first, int rest) {
    }

    /** category: GOVERNMENT_MESSAGE | TASK | SYSTEM. */
    record Notification(String id, Instant at, String title, String body, boolean read, String category, String link) {
    }

    record LeaveRequest(String seatId, String substituteSeatId, String substitutePersonId, LocalDate from,
                        LocalDate to, String reason) {
    }

    record BulkFailure(String id, String reason) {
    }

    record BulkResult(List<String> succeeded, List<BulkFailure> failed) {
    }

    record BulkClaimRequest(List<String> ids) {
    }

    /** FLOWABLE-INTO-OPERATON D-34: bulk completion runs as a batch, one task per transaction; each task is still hash-bound. */
    record BulkCompleteRequest(List<String> ids, String action, String remarks) {
    }

    record CandidateSeat(String seatId, String seatName, String holderName, Integer workload, boolean onLeave,
                         boolean outOfHierarchy, boolean proposed) {
    }

    record FieldChange(String field, String label, Object from, Object to, String changedBy, Instant changedAt) {
    }

    record LifecycleEvent(Instant at, String event, String by, String seatName, String actedFor) {
    }

    /** kind: PROCESS | CASE. */
    record CatalogueEntry(String key, String name, String kind, String formKey, String description) {
    }

    record CaseComment(String id, String author, String seatName, String actedFor, Instant createdAt, String text,
                       Integer version) {
    }

    record AddCaseCommentRequest(String text) {
    }

    record Participant(String id, String name, String role, boolean external) {
    }

    record ChecklistItem(String id, String label) {
    }

    record FieldVisit(String id, String taskId, String businessKey, String address, Instant scheduledAt,
                      List<ChecklistItem> checklist) {
    }

    /** answers values are boolean or string. Recorded offline-first: {@code recordedAt} is the device time. */
    record VisitRecord(Map<String, Object> answers, String notes, Instant recordedAt) {
    }

    Me me(RequestContext ctx);

    /** Section → count; a capped count is the string "N+" (A-16). Sections: MY_SEAT, ACTING_FOR, POOL, ESCALATED, RETURNED, ALL. */
    Map<String, Object> taskCounts(RequestContext ctx);

    BulkResult bulkClaim(RequestContext ctx, BulkClaimRequest request);

    OpsPort.Batch bulkComplete(RequestContext ctx, BulkCompleteRequest request);

    List<CandidateSeat> candidates(RequestContext ctx, String taskId, String action);

    /** Field-level changes of the material payload between two case versions (CX-2, S09). */
    List<FieldChange> diff(RequestContext ctx, String taskId, long fromVersion, long toVersion);

    List<LifecycleEvent> lifecycle(RequestContext ctx, String taskId);

    Page<Notification> notifications(RequestContext ctx, Page.PageRequest page);

    void markNotificationRead(RequestContext ctx, String notificationId);

    List<AdminPort.Assignment> myDelegations(RequestContext ctx);

    AdminPort.Assignment requestLeave(RequestContext ctx, LeaveRequest request);

    List<CatalogueEntry> startCatalogue(RequestContext ctx);

    List<CaseComment> caseComments(RequestContext ctx, String caseInstanceId);

    CaseComment addCaseComment(RequestContext ctx, String caseInstanceId, AddCaseCommentRequest request);

    List<Participant> participants(RequestContext ctx, String caseInstanceId);

    List<FieldVisit> fieldVisits(RequestContext ctx);

    void recordVisit(RequestContext ctx, String visitId, VisitRecord record);
}
