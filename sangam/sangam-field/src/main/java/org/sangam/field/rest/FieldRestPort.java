// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.rest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Framework-neutral REST port of the field module, base path {@code /api/v1/field}. Bodies are JSON objects; results
 * are JSON-serialisable records / maps. Errors are {@code FieldException} (code + HTTP status → RFC 9457).
 * Mutations accept an {@code Idempotency-Key} (R41); the sync upload is idempotent by each packet's operationId.
 *
 * <pre>
 * UI contract (sangam-ui src/api/endpoints.ts):
 *   GET  /visits                                → [{id, taskId, businessKey, address, scheduledAt, checklist:[{id,label}]}]
 *   POST /visits/{id}/record                    ← {answers, notes, recordedAt, lat?, lon?, accuracyM?}  (Idempotency-Key)
 * Templates:     GET /templates, GET /templates/{key}[?version], POST /templates, POST /templates/{key}/{version}/retire
 * Assignments:   GET /assignments?assignee&amp;status&amp;targetRef&amp;offset&amp;limit, GET /assignments/{id}, POST /assignments,
 *                POST /assignments/{id}/reassign|transition|review, POST /assignments/route, POST /assignments/sweep-overdue
 * Schedules:     GET /schedules, POST /schedules, POST /schedules/{id}/run, POST /schedules/run-due
 * Selection:     POST /selections, GET /selections/{id}/verify
 * Devices/sync:  POST /devices, GET /devices, POST /devices/{id}/status, POST /devices/{id}/policy,
 *                POST /sync/{deviceId}/upload, GET /sync/{deviceId}/pull?cursor
 * Conflicts:     GET /conflicts?status, GET /conflicts/{id}, POST /conflicts/{id}/resolve
 * Evidence:      POST /evidence (multipart: meta + file), GET /evidence/{id}, GET /evidence/{id}/content,
 *                GET /assignments/{id}/evidence, POST /evidence/{id}/reverify, GET /devices/{id}/chain
 * Dispatch:      POST /units, GET /units?status, GET /units/{id}, POST /units/{id}/status, POST /units/{id}/pings,
 *                GET /units/{id}/offers, GET /units/{id}/eta?jobId, GET /units/{id}/trips,
 *                POST /jobs, GET /jobs?status, GET /jobs/{id}, GET /jobs/{id}/candidates, POST /jobs/{id}/dispatch|assign|
 *                progress|close|cancel|redispatch, POST /offers/{id}/respond, POST /offers/expire,
 *                POST /trips, POST /trips/{id}/legs|end|decision
 * </pre>
 */
public interface FieldRestPort {

    // UI contract
    List<Map<String, Object>> visits(FieldCaller caller);

    Map<String, Object> recordVisit(FieldCaller caller, String assignmentId, Map<String, Object> body, String idempotencyKey);

    // templates
    List<Map<String, Object>> listTemplates(FieldCaller caller);

    Map<String, Object> getTemplate(FieldCaller caller, String key, Integer version);

    Map<String, Object> publishTemplate(FieldCaller caller, Map<String, Object> template);

    void retireTemplate(FieldCaller caller, String key, int version);

    // assignments
    List<Map<String, Object>> listAssignments(FieldCaller caller, String assignee, String status, String targetRef, int offset, int limit);

    Map<String, Object> getAssignment(FieldCaller caller, String id);

    Map<String, Object> createAssignment(FieldCaller caller, Map<String, Object> body);

    Map<String, Object> reassign(FieldCaller caller, String id, Map<String, Object> body);

    Map<String, Object> transitionAssignment(FieldCaller caller, String id, Map<String, Object> body);

    Map<String, Object> reviewVisit(FieldCaller caller, String id, Map<String, Object> body);

    List<Map<String, Object>> routeList(FieldCaller caller, Map<String, Object> body);

    Map<String, Object> sweepOverdue(FieldCaller caller);

    // schedules and selection
    List<Object> listSchedules(FieldCaller caller);

    Object defineSchedule(FieldCaller caller, Map<String, Object> body);

    Object runSchedule(FieldCaller caller, String id);

    List<Object> runDueSchedules(FieldCaller caller);

    Object randomSelect(FieldCaller caller, Map<String, Object> body);

    Map<String, Object> verifySelection(FieldCaller caller, String id);

    // devices and sync
    Map<String, Object> registerDevice(FieldCaller caller, Map<String, Object> body);

    List<Map<String, Object>> listDevices(FieldCaller caller);

    Map<String, Object> setDeviceStatus(FieldCaller caller, String deviceId, Map<String, Object> body);

    Map<String, Object> setDevicePolicy(FieldCaller caller, String deviceId, Map<String, Object> body);

    List<Object> upload(FieldCaller caller, String deviceId, List<Map<String, Object>> packets);

    Object pull(FieldCaller caller, String deviceId, long cursor);

    // conflicts
    List<Object> listConflicts(FieldCaller caller, String status);

    Object getConflict(FieldCaller caller, String id);

    Map<String, Object> resolveConflict(FieldCaller caller, String id, Map<String, Object> body);

    // evidence
    Object uploadEvidence(FieldCaller caller, Map<String, Object> meta, byte[] bytes);

    Object getEvidence(FieldCaller caller, String id);

    Optional<byte[]> evidenceContent(FieldCaller caller, String id);

    List<Object> listEvidence(FieldCaller caller, String assignmentId);

    Map<String, Object> reverifyEvidence(FieldCaller caller, String id);

    Map<String, Object> verifyChain(FieldCaller caller, String deviceId);

    // dispatch
    Object upsertUnit(FieldCaller caller, Map<String, Object> body);

    List<Object> listUnits(FieldCaller caller, String status);

    Object getUnit(FieldCaller caller, String id);

    Object setUnitStatus(FieldCaller caller, String id, Map<String, Object> body);

    Object ping(FieldCaller caller, String unitId, Map<String, Object> body);

    List<Object> unitOffers(FieldCaller caller, String unitId);

    Object eta(FieldCaller caller, String unitId, String jobId);

    List<Object> listTrips(FieldCaller caller, String unitId);

    Object createJob(FieldCaller caller, Map<String, Object> body);

    List<Object> listJobs(FieldCaller caller, String status, int limit);

    Object getJob(FieldCaller caller, String id);

    List<Object> candidates(FieldCaller caller, String jobId);

    Object jobAction(FieldCaller caller, String jobId, String action, Map<String, Object> body);

    Object respondOffer(FieldCaller caller, String offerId, Map<String, Object> body);

    Map<String, Object> expireOffers(FieldCaller caller);

    Object startTrip(FieldCaller caller, Map<String, Object> body);

    Object tripAction(FieldCaller caller, String tripId, String action, Map<String, Object> body);
}
