// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.field;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.rest.FieldCaller;
import org.sangam.field.rest.FieldRestPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Field work (N13 inspections and surveys) and dispatch (N17) under {@code /api/v1/field}, mirroring the module's own
 * {@code FieldController} with the sangam-rest conventions (RequestContext, endpoint permissions, problem+json).
 * {@code GET /field/visits} and {@code POST /field/visits/{id}/record} stay on sangam-rest's WorkspaceController (the
 * existing UI contract); this controller does not map them.
 */
@RestController
@RequestMapping("/api/v1/field")
public class FieldWorkController {

    public static final String CAPTURE = "field.capture";
    public static final String REVIEW = "field.review";
    public static final String ADMIN = "field.admin";
    public static final String DISPATCH = "dispatch.operate";

    private final FieldRestPort port;
    private final PermissionPort permissions;

    public FieldWorkController(FieldRestPort port, PermissionPort permissions) {
        this.port = port;
        this.permissions = permissions;
    }

    // ---------------------------------------------------------------- templates

    /** Published checklist templates. */
    @GetMapping("/templates")
    public ResponseEntity<List<Map<String, Object>>> templates(RequestContext ctx) {
        return Lists.ok(port.listTemplates(caller(ctx, CAPTURE)));
    }

    /** A template (latest version, or a given version). */
    @GetMapping("/templates/{key}")
    public Map<String, Object> template(RequestContext ctx, @PathVariable String key,
                                        @RequestParam(required = false) Integer version) {
        return port.getTemplate(caller(ctx, CAPTURE), key, version);
    }

    /** Publish a template version. */
    @PostMapping("/templates")
    public ResponseEntity<Map<String, Object>> publishTemplate(RequestContext ctx, @RequestBody Map<String, Object> body) {
        Map<String, Object> t = port.publishTemplate(caller(ctx, ADMIN), body);
        String ref = t.get("key") + "@" + t.get("version");
        return Lists.created("/api/v1/field/templates/" + t.get("key") + "?version=" + t.get("version"), ref, t);
    }

    /** Retire a template version. */
    @PostMapping("/templates/{key}/{version}/retire")
    public ResponseEntity<Void> retireTemplate(RequestContext ctx, @PathVariable String key, @PathVariable int version) {
        port.retireTemplate(caller(ctx, ADMIN), key, version);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- assignments

    /** Assignments by assignee, status or target. */
    @GetMapping("/assignments")
    public ResponseEntity<List<Map<String, Object>>> assignments(RequestContext ctx,
            @RequestParam(required = false) String assignee, @RequestParam(required = false) String status,
            @RequestParam(required = false) String targetRef,
            @RequestParam(required = false, defaultValue = "0") int offset,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return Lists.ok(port.listAssignments(caller(ctx, CAPTURE), assignee, status, targetRef, Math.max(0, offset),
                Math.max(1, Math.min(limit, 500))));
    }

    /** An assignment. */
    @GetMapping("/assignments/{id}")
    public Map<String, Object> assignment(RequestContext ctx, @PathVariable String id) {
        return port.getAssignment(caller(ctx, CAPTURE), id);
    }

    /** Create an assignment (optionally linked to a BPMN task and business key). */
    @PostMapping("/assignments")
    public ResponseEntity<Map<String, Object>> createAssignment(RequestContext ctx, @RequestBody Map<String, Object> body) {
        Map<String, Object> a = port.createAssignment(caller(ctx, ADMIN), body);
        String id = String.valueOf(a.get("id"));
        return Lists.created("/api/v1/field/assignments/" + id, id, a);
    }

    /** Reassign an assignment. */
    @PostMapping("/assignments/{id}/reassign")
    public Map<String, Object> reassign(RequestContext ctx, @PathVariable String id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        return port.reassign(caller(ctx, ADMIN), id, body == null ? Map.of() : body);
    }

    /** Transition an assignment (accept, start, cancel, ...). */
    @PostMapping("/assignments/{id}/transition")
    public Map<String, Object> transition(RequestContext ctx, @PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        return port.transitionAssignment(caller(ctx, CAPTURE), id, body == null ? Map.of() : body);
    }

    /** Review a submitted visit (approve or return). */
    @PostMapping("/assignments/{id}/review")
    public Map<String, Object> review(RequestContext ctx, @PathVariable String id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        return port.reviewVisit(caller(ctx, REVIEW), id, body == null ? Map.of() : body);
    }

    /** Route-ordered list of the caller's assignments. */
    @PostMapping("/assignments/route")
    public ResponseEntity<List<Map<String, Object>>> route(RequestContext ctx,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        return Lists.ok(port.routeList(caller(ctx, CAPTURE), body == null ? Map.of() : body));
    }

    /** Mark overdue assignments (also run by the leader-elected field scheduler). */
    @PostMapping("/assignments/sweep-overdue")
    public Map<String, Object> sweepOverdue(RequestContext ctx) {
        return port.sweepOverdue(caller(ctx, ADMIN));
    }

    /** Evidence of an assignment. */
    @GetMapping("/assignments/{id}/evidence")
    public ResponseEntity<List<Object>> evidenceOf(RequestContext ctx, @PathVariable String id) {
        return Lists.ok(port.listEvidence(caller(ctx, CAPTURE), id));
    }

    // ---------------------------------------------------------------- schedules and selection

    /** Inspection schedules. */
    @GetMapping("/schedules")
    public ResponseEntity<List<Object>> schedules(RequestContext ctx) {
        return Lists.ok(port.listSchedules(caller(ctx, ADMIN)));
    }

    /** Define an inspection schedule (frequency, selection rule, rotation). */
    @PostMapping("/schedules")
    public Object defineSchedule(RequestContext ctx, @RequestBody Map<String, Object> body) {
        return port.defineSchedule(caller(ctx, ADMIN), body);
    }

    /** Run one schedule now. */
    @PostMapping("/schedules/{id}/run")
    public Object runSchedule(RequestContext ctx, @PathVariable String id) {
        return port.runSchedule(caller(ctx, ADMIN), id);
    }

    /** Run every due schedule (also run by the leader-elected field scheduler). */
    @PostMapping("/schedules/run-due")
    public ResponseEntity<List<Object>> runDue(RequestContext ctx) {
        return Lists.ok(port.runDueSchedules(caller(ctx, ADMIN)));
    }

    /** Verifiable random selection of inspection targets. */
    @PostMapping("/selections")
    public Object select(RequestContext ctx, @RequestBody Map<String, Object> body) {
        return port.randomSelect(caller(ctx, ADMIN), body);
    }

    /** Re-verify a random selection from its seed. */
    @GetMapping("/selections/{id}/verify")
    public Map<String, Object> verifySelection(RequestContext ctx, @PathVariable String id) {
        return port.verifySelection(caller(ctx, REVIEW), id);
    }

    // ---------------------------------------------------------------- devices and offline sync

    /** Register a field device. */
    @PostMapping("/devices")
    public Map<String, Object> registerDevice(RequestContext ctx, @RequestBody Map<String, Object> body) {
        return port.registerDevice(caller(ctx, CAPTURE), body);
    }

    /** The caller's (or, for admins, the tenant's) devices. */
    @GetMapping("/devices")
    public ResponseEntity<List<Map<String, Object>>> devices(RequestContext ctx) {
        return Lists.ok(port.listDevices(caller(ctx, CAPTURE)));
    }

    /** Set a device status (active, lost, revoked: revoke wipes the local queue). */
    @PostMapping("/devices/{id}/status")
    public Map<String, Object> deviceStatus(RequestContext ctx, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return port.setDeviceStatus(caller(ctx, ADMIN), id, body);
    }

    /** Set a device's offline queue policy. */
    @PostMapping("/devices/{id}/policy")
    public Map<String, Object> devicePolicy(RequestContext ctx, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return port.setDevicePolicy(caller(ctx, ADMIN), id, body);
    }

    /** Verify a device's packet hash chain. */
    @GetMapping("/devices/{id}/chain")
    public Map<String, Object> chain(RequestContext ctx, @PathVariable String id) {
        return port.verifyChain(caller(ctx, REVIEW), id);
    }

    /** Upload offline packets from a device (idempotent per packet). */
    @PostMapping("/sync/{deviceId}/upload")
    public ResponseEntity<List<Object>> upload(RequestContext ctx, @PathVariable String deviceId,
                                               @RequestBody List<Map<String, Object>> packets) {
        return Lists.ok(port.upload(caller(ctx, CAPTURE), deviceId, packets));
    }

    /** Pull server changes for a device since a cursor. */
    @GetMapping("/sync/{deviceId}/pull")
    public Object pull(RequestContext ctx, @PathVariable String deviceId,
                       @RequestParam(required = false, defaultValue = "0") long cursor) {
        return port.pull(caller(ctx, CAPTURE), deviceId, cursor);
    }

    // ---------------------------------------------------------------- conflicts

    /** Sync conflicts. */
    @GetMapping("/conflicts")
    public ResponseEntity<List<Object>> conflicts(RequestContext ctx, @RequestParam(required = false) String status) {
        return Lists.ok(port.listConflicts(caller(ctx, REVIEW), status));
    }

    /** A sync conflict. */
    @GetMapping("/conflicts/{id}")
    public Object conflict(RequestContext ctx, @PathVariable String id) {
        return port.getConflict(caller(ctx, REVIEW), id);
    }

    /** Resolve a sync conflict. */
    @PostMapping("/conflicts/{id}/resolve")
    public Map<String, Object> resolveConflict(RequestContext ctx, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return port.resolveConflict(caller(ctx, REVIEW), id, body);
    }

    // ---------------------------------------------------------------- evidence

    /** Upload evidence (multipart: meta JSON + file); integrity checks (EXIF, location, time) run on upload. */
    @PostMapping(value = "/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object uploadEvidence(RequestContext ctx, @RequestPart("meta") String meta, @RequestPart("file") MultipartFile file)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>(FieldJson.map(meta));
        m.putIfAbsent("mediaType", file.getContentType());
        return port.uploadEvidence(caller(ctx, CAPTURE), m, file.getBytes());
    }

    /** Evidence metadata and integrity report. */
    @GetMapping("/evidence/{id}")
    public Object evidence(RequestContext ctx, @PathVariable String id) {
        return port.getEvidence(caller(ctx, CAPTURE), id);
    }

    /** Evidence content (streamed). */
    @GetMapping("/evidence/{id}/content")
    public ResponseEntity<StreamingResponseBody> evidenceContent(RequestContext ctx, @PathVariable String id) {
        byte[] bytes = port.evidenceContent(caller(ctx, CAPTURE), id)
                .orElseThrow(() -> ApiProblem.notFound("evidence content", id));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(bytes.length)
                .body(out -> out.write(bytes));
    }

    /** Re-run the integrity checks of an evidence item. */
    @PostMapping("/evidence/{id}/reverify")
    public Map<String, Object> reverifyEvidence(RequestContext ctx, @PathVariable String id) {
        return port.reverifyEvidence(caller(ctx, REVIEW), id);
    }

    // ---------------------------------------------------------------- dispatch (N17)

    /** Create or update a field unit. */
    @PostMapping("/units")
    public Object upsertUnit(RequestContext ctx, @RequestBody Map<String, Object> body) {
        return port.upsertUnit(caller(ctx, DISPATCH), body);
    }

    /** Field units, optionally by status. */
    @GetMapping("/units")
    public ResponseEntity<List<Object>> units(RequestContext ctx, @RequestParam(required = false) String status) {
        return Lists.ok(port.listUnits(caller(ctx, DISPATCH), status));
    }

    /** A field unit. */
    @GetMapping("/units/{id}")
    public Object unit(RequestContext ctx, @PathVariable String id) {
        return port.getUnit(caller(ctx, DISPATCH), id);
    }

    /** Set a unit status. */
    @PostMapping("/units/{id}/status")
    public Object unitStatus(RequestContext ctx, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return port.setUnitStatus(caller(ctx, DISPATCH), id, body);
    }

    /** Position ping from a unit (AVL feed, N12). */
    @PostMapping("/units/{id}/pings")
    public Object ping(RequestContext ctx, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return port.ping(caller(ctx, DISPATCH), id, body);
    }

    /** Open offers for a unit. */
    @GetMapping("/units/{id}/offers")
    public ResponseEntity<List<Object>> unitOffers(RequestContext ctx, @PathVariable String id) {
        return Lists.ok(port.unitOffers(caller(ctx, DISPATCH), id));
    }

    /** ETA of a unit to a job. */
    @GetMapping("/units/{id}/eta")
    public Object eta(RequestContext ctx, @PathVariable String id, @RequestParam String jobId) {
        return port.eta(caller(ctx, DISPATCH), id, jobId);
    }

    /** Trips of a unit. */
    @GetMapping("/units/{id}/trips")
    public ResponseEntity<List<Object>> trips(RequestContext ctx, @PathVariable String id) {
        return Lists.ok(port.listTrips(caller(ctx, DISPATCH), id));
    }

    /** Create a dispatch job. */
    @PostMapping("/jobs")
    public Object createJob(RequestContext ctx, @RequestBody Map<String, Object> body) {
        return port.createJob(caller(ctx, DISPATCH), body);
    }

    /** Dispatch jobs, optionally by status. */
    @GetMapping("/jobs")
    public ResponseEntity<List<Object>> jobs(RequestContext ctx, @RequestParam(required = false) String status,
                                             @RequestParam(required = false, defaultValue = "100") int limit) {
        return Lists.ok(port.listJobs(caller(ctx, DISPATCH), status, Math.max(1, Math.min(limit, 1000))));
    }

    /** A dispatch job. */
    @GetMapping("/jobs/{id}")
    public Object job(RequestContext ctx, @PathVariable String id) {
        return port.getJob(caller(ctx, DISPATCH), id);
    }

    /** Ranked candidate units for a job. */
    @GetMapping("/jobs/{id}/candidates")
    public ResponseEntity<List<Object>> candidates(RequestContext ctx, @PathVariable String id) {
        return Lists.ok(port.candidates(caller(ctx, DISPATCH), id));
    }

    /** Job action: offer, assign, arrive, close, cancel, ... */
    @PostMapping("/jobs/{id}/{action}")
    public Object jobAction(RequestContext ctx, @PathVariable String id, @PathVariable String action,
                            @RequestBody(required = false) Map<String, Object> body) {
        return port.jobAction(caller(ctx, DISPATCH), id, action, body == null ? Map.of() : body);
    }

    /** Accept or decline an offer. */
    @PostMapping("/offers/{id}/respond")
    public Object respondOffer(RequestContext ctx, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return port.respondOffer(caller(ctx, DISPATCH), id, body);
    }

    /** Expire stale offers (also run by the leader-elected field scheduler). */
    @PostMapping("/offers/expire")
    public Map<String, Object> expireOffers(RequestContext ctx) {
        return port.expireOffers(caller(ctx, DISPATCH));
    }

    /** Start a trip. */
    @PostMapping("/trips")
    public Object startTrip(RequestContext ctx, @RequestBody Map<String, Object> body) {
        return port.startTrip(caller(ctx, DISPATCH), body);
    }

    /** Trip action: waypoint, pause, end, ... */
    @PostMapping("/trips/{id}/{action}")
    public Object tripAction(RequestContext ctx, @PathVariable String id, @PathVariable String action,
                             @RequestBody(required = false) Map<String, Object> body) {
        return port.tripAction(caller(ctx, DISPATCH), id, action, body == null ? Map.of() : body);
    }

    // ---------------------------------------------------------------- support

    private FieldCaller caller(RequestContext ctx, String permission) {
        permissions.require(ctx, permission);
        try {
            return new FieldCaller(ctx.tenantId(), ctx.principalId(), ctx.seatId(), ctx.groups(), null);
        } catch (IllegalArgumentException e) {
            throw new FieldException("INVALID_REQUEST", e.getMessage());
        }
    }
}
