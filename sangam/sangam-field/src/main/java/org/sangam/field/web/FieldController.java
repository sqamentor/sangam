// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.web;

import jakarta.servlet.http.HttpServletRequest;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.rest.FieldCaller;
import org.sangam.field.rest.FieldRestPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Spring MVC adapter of {@link FieldRestPort} at {@code /api/v1/field} (routes listed on the port). Errors are
 * RFC 9457 {@code application/problem+json} with {@code code}. Registered as a bean by the integrator together with a
 * {@link CallerResolver}; endpoint authorization (VI.4 policy) is applied by sangam-security in front of it.
 */
@RestController
@RequestMapping("/api/v1/field")
public class FieldController {

    private final FieldRestPort port;
    private final CallerResolver callers;

    public FieldController(FieldRestPort port, CallerResolver callers) {
        this.port = port;
        this.callers = callers;
    }

    private FieldCaller caller(HttpServletRequest r) {
        return callers.resolve(r);
    }

    // ------------------------------------------------------------------ UI contract

    @GetMapping("/visits")
    public List<Map<String, Object>> visits(HttpServletRequest r) {
        return port.visits(caller(r));
    }

    @PostMapping("/visits/{id}/record")
    public Map<String, Object> record(HttpServletRequest r, @PathVariable("id") String id, @RequestBody Map<String, Object> body,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return port.recordVisit(caller(r), id, body, key);
    }

    // ------------------------------------------------------------------ templates

    @GetMapping("/templates")
    public List<Map<String, Object>> templates(HttpServletRequest r) {
        return port.listTemplates(caller(r));
    }

    @GetMapping("/templates/{key}")
    public Map<String, Object> template(HttpServletRequest r, @PathVariable("key") String key,
                                        @RequestParam(value = "version", required = false) Integer version) {
        return port.getTemplate(caller(r), key, version);
    }

    @PostMapping("/templates")
    public ResponseEntity<Map<String, Object>> publish(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        Map<String, Object> t = port.publishTemplate(caller(r), body);
        return ResponseEntity.created(URI.create("/api/v1/field/templates/" + t.get("key") + "?version=" + t.get("version"))).body(t);
    }

    @PostMapping("/templates/{key}/{version}/retire")
    public ResponseEntity<Void> retire(HttpServletRequest r, @PathVariable("key") String key, @PathVariable("version") int version) {
        port.retireTemplate(caller(r), key, version);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ assignments

    @GetMapping("/assignments")
    public List<Map<String, Object>> assignments(HttpServletRequest r, @RequestParam(value = "assignee", required = false) String assignee,
                                                 @RequestParam(value = "status", required = false) String status,
                                                 @RequestParam(value = "targetRef", required = false) String targetRef,
                                                 @RequestParam(value = "offset", defaultValue = "0") int offset,
                                                 @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return port.listAssignments(caller(r), assignee, status, targetRef, offset, limit);
    }

    @GetMapping("/assignments/{id}")
    public Map<String, Object> assignment(HttpServletRequest r, @PathVariable("id") String id) {
        return port.getAssignment(caller(r), id);
    }

    @PostMapping("/assignments")
    public Map<String, Object> createAssignment(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return port.createAssignment(caller(r), body);
    }

    @PostMapping("/assignments/{id}/{action}")
    public Object assignmentAction(HttpServletRequest r, @PathVariable("id") String id, @PathVariable("action") String action,
                                   @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        FieldCaller c = caller(r);
        return switch (action) {
            case "reassign" -> port.reassign(c, id, b);
            case "transition" -> port.transitionAssignment(c, id, b);
            case "review" -> port.reviewVisit(c, id, b);
            default -> throw new FieldException("NOT_FOUND", "unknown assignment action: " + action);
        };
    }

    @PostMapping("/assignments/route")
    public List<Map<String, Object>> route(HttpServletRequest r, @RequestBody(required = false) Map<String, Object> body) {
        return port.routeList(caller(r), body == null ? Map.of() : body);
    }

    @PostMapping("/assignments/sweep-overdue")
    public Map<String, Object> sweep(HttpServletRequest r) {
        return port.sweepOverdue(caller(r));
    }

    @GetMapping("/assignments/{id}/evidence")
    public List<Object> evidenceOf(HttpServletRequest r, @PathVariable("id") String id) {
        return port.listEvidence(caller(r), id);
    }

    // ------------------------------------------------------------------ schedules, selection

    @GetMapping("/schedules")
    public List<Object> schedules(HttpServletRequest r) {
        return port.listSchedules(caller(r));
    }

    @PostMapping("/schedules")
    public Object defineSchedule(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return port.defineSchedule(caller(r), body);
    }

    @PostMapping("/schedules/{id}/run")
    public Object runSchedule(HttpServletRequest r, @PathVariable("id") String id) {
        return port.runSchedule(caller(r), id);
    }

    @PostMapping("/schedules/run-due")
    public List<Object> runDue(HttpServletRequest r) {
        return port.runDueSchedules(caller(r));
    }

    @PostMapping("/selections")
    public Object select(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return port.randomSelect(caller(r), body);
    }

    @GetMapping("/selections/{id}/verify")
    public Map<String, Object> verifySelection(HttpServletRequest r, @PathVariable("id") String id) {
        return port.verifySelection(caller(r), id);
    }

    // ------------------------------------------------------------------ devices and sync

    @PostMapping("/devices")
    public Map<String, Object> register(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return port.registerDevice(caller(r), body);
    }

    @GetMapping("/devices")
    public List<Map<String, Object>> devices(HttpServletRequest r) {
        return port.listDevices(caller(r));
    }

    @PostMapping("/devices/{id}/status")
    public Map<String, Object> deviceStatus(HttpServletRequest r, @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return port.setDeviceStatus(caller(r), id, body);
    }

    @PostMapping("/devices/{id}/policy")
    public Map<String, Object> devicePolicy(HttpServletRequest r, @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return port.setDevicePolicy(caller(r), id, body);
    }

    @GetMapping("/devices/{id}/chain")
    public Map<String, Object> chain(HttpServletRequest r, @PathVariable("id") String id) {
        return port.verifyChain(caller(r), id);
    }

    @PostMapping("/sync/{deviceId}/upload")
    public List<Object> upload(HttpServletRequest r, @PathVariable("deviceId") String deviceId, @RequestBody List<Map<String, Object>> packets) {
        return port.upload(caller(r), deviceId, packets);
    }

    @GetMapping("/sync/{deviceId}/pull")
    public Object pull(HttpServletRequest r, @PathVariable("deviceId") String deviceId,
                       @RequestParam(value = "cursor", defaultValue = "0") long cursor) {
        return port.pull(caller(r), deviceId, cursor);
    }

    // ------------------------------------------------------------------ conflicts

    @GetMapping("/conflicts")
    public List<Object> conflicts(HttpServletRequest r, @RequestParam(value = "status", required = false) String status) {
        return port.listConflicts(caller(r), status);
    }

    @GetMapping("/conflicts/{id}")
    public Object conflict(HttpServletRequest r, @PathVariable("id") String id) {
        return port.getConflict(caller(r), id);
    }

    @PostMapping("/conflicts/{id}/resolve")
    public Map<String, Object> resolve(HttpServletRequest r, @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return port.resolveConflict(caller(r), id, body);
    }

    // ------------------------------------------------------------------ evidence

    @PostMapping(value = "/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object uploadEvidence(HttpServletRequest r, @RequestPart("meta") String meta, @RequestPart("file") MultipartFile file)
            throws IOException {
        Map<String, Object> m = new java.util.LinkedHashMap<>(FieldJson.map(meta));
        m.putIfAbsent("mediaType", file.getContentType());
        return port.uploadEvidence(caller(r), m, file.getBytes());
    }

    @GetMapping("/evidence/{id}")
    public Object evidence(HttpServletRequest r, @PathVariable("id") String id) {
        return port.getEvidence(caller(r), id);
    }

    @GetMapping("/evidence/{id}/content")
    public ResponseEntity<byte[]> content(HttpServletRequest r, @PathVariable("id") String id) {
        return port.evidenceContent(caller(r), id).map(b -> ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(b))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/evidence/{id}/reverify")
    public Map<String, Object> reverify(HttpServletRequest r, @PathVariable("id") String id) {
        return port.reverifyEvidence(caller(r), id);
    }

    // ------------------------------------------------------------------ dispatch

    @PostMapping("/units")
    public Object upsertUnit(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return port.upsertUnit(caller(r), body);
    }

    @GetMapping("/units")
    public List<Object> units(HttpServletRequest r, @RequestParam(value = "status", required = false) String status) {
        return port.listUnits(caller(r), status);
    }

    @GetMapping("/units/{id}")
    public Object unit(HttpServletRequest r, @PathVariable("id") String id) {
        return port.getUnit(caller(r), id);
    }

    @PostMapping("/units/{id}/status")
    public Object unitStatus(HttpServletRequest r, @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return port.setUnitStatus(caller(r), id, body);
    }

    @PostMapping("/units/{id}/pings")
    public Object ping(HttpServletRequest r, @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return port.ping(caller(r), id, body);
    }

    @GetMapping("/units/{id}/offers")
    public List<Object> unitOffers(HttpServletRequest r, @PathVariable("id") String id) {
        return port.unitOffers(caller(r), id);
    }

    @GetMapping("/units/{id}/eta")
    public Object eta(HttpServletRequest r, @PathVariable("id") String id, @RequestParam("jobId") String jobId) {
        return port.eta(caller(r), id, jobId);
    }

    @GetMapping("/units/{id}/trips")
    public List<Object> trips(HttpServletRequest r, @PathVariable("id") String id) {
        return port.listTrips(caller(r), id);
    }

    @PostMapping("/jobs")
    public Object createJob(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return port.createJob(caller(r), body);
    }

    @GetMapping("/jobs")
    public List<Object> jobs(HttpServletRequest r, @RequestParam(value = "status", required = false) String status,
                             @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return port.listJobs(caller(r), status, limit);
    }

    @GetMapping("/jobs/{id}")
    public Object job(HttpServletRequest r, @PathVariable("id") String id) {
        return port.getJob(caller(r), id);
    }

    @GetMapping("/jobs/{id}/candidates")
    public List<Object> candidates(HttpServletRequest r, @PathVariable("id") String id) {
        return port.candidates(caller(r), id);
    }

    @PostMapping("/jobs/{id}/{action}")
    public Object jobAction(HttpServletRequest r, @PathVariable("id") String id, @PathVariable("action") String action,
                            @RequestBody(required = false) Map<String, Object> body) {
        return port.jobAction(caller(r), id, action, body);
    }

    @PostMapping("/offers/{id}/respond")
    public Object respond(HttpServletRequest r, @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return port.respondOffer(caller(r), id, body);
    }

    @PostMapping("/offers/expire")
    public Map<String, Object> expire(HttpServletRequest r) {
        return port.expireOffers(caller(r));
    }

    @PostMapping("/trips")
    public Object startTrip(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return port.startTrip(caller(r), body);
    }

    @PostMapping("/trips/{id}/{action}")
    public Object tripAction(HttpServletRequest r, @PathVariable("id") String id, @PathVariable("action") String action,
                             @RequestBody(required = false) Map<String, Object> body) {
        return port.tripAction(caller(r), id, action, body);
    }

    // ------------------------------------------------------------------ errors

    @ExceptionHandler(FieldException.class)
    public ResponseEntity<ProblemDetail> problem(FieldException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        p.setType(URI.create("urn:sangam:problem:field:" + e.code().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        p.setTitle(e.code());
        p.setProperty("code", e.code());
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e) {
        return problem(new FieldException("INVALID_REQUEST", e.getMessage()));
    }
}
