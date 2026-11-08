// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.cases;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.sangam.casefile.reference.ReferenceService;
import org.sangam.casefile.rest.ReferenceRestPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** References with a deadline (N16): legislative questions, assurances, dignitary references, grievances. */
@RestController
@RequestMapping("/api/v1/references")
public class ReferenceController {

    private final ReferenceRestPort port;
    private final PermissionPort permissions;

    public ReferenceController(ReferenceRestPort port, PermissionPort permissions) {
        this.port = port;
        this.permissions = permissions;
    }

    public record DraftBody(String text) {
    }

    public record ApproveBody(String draftHash) {
    }

    public record ReasonBody(String reason) {
    }

    /** Register a reference (the deadline follows the reference kind). */
    @PostMapping
    public ResponseEntity<ReferenceService.Reference> register(RequestContext ctx,
                                                               @RequestBody ReferenceService.RegisterRequest body) {
        ReferenceService.Reference r = port.register(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), body);
        return Lists.created("/api/v1/references/" + r.referenceId(), r.referenceId(), r);
    }

    /** References visible to the caller (VI.4 predicate). */
    @GetMapping
    public ResponseEntity<List<ReferenceService.Reference>> references(RequestContext ctx,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String status,
            @RequestParam(required = false) String seat, @RequestParam(required = false) String orgUnit,
            @RequestParam(required = false) String sourceCategory, @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        var filter = new ReferenceService.Filter(kind, CaseIdentity.enumOf(ReferenceService.Status.class, status, "status"),
                seat, orgUnit, sourceCategory, overdue, q);
        return Lists.ok(CaseIdentity.page(port.references(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), filter,
                CaseIdentity.page(page, size))));
    }

    /** Pendency of references by kind, with the "due soon" window (ISO-8601 duration, default P7D). */
    @GetMapping("/pendency")
    public ResponseEntity<List<ReferenceService.Pendency>> pendency(RequestContext ctx,
            @RequestParam(required = false) String kind, @RequestParam(required = false, defaultValue = "P7D") String dueSoon) {
        Duration window;
        try {
            window = Duration.parse(dueSoon);
        } catch (DateTimeParseException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "dueSoon must be an ISO-8601 duration such as P7D");
        }
        return Lists.ok(port.pendency(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), kind, window));
    }

    /** A reference with its movements, follow-ups, deadlines and history. */
    @GetMapping("/{id}")
    public ReferenceRestPort.ReferenceView reference(RequestContext ctx, @PathVariable String id) {
        return port.reference(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id);
    }

    /** Assign to a seat or person. */
    @PostMapping("/{id}/assign")
    public ReferenceService.Reference assign(RequestContext ctx, @PathVariable String id,
                                             @RequestBody ReferenceRestPort.AssignRequest body) {
        return port.assign(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Transfer to another org unit or seat. */
    @PostMapping("/{id}/transfer")
    public ReferenceService.Reference transfer(RequestContext ctx, @PathVariable String id,
                                               @RequestBody ReferenceRestPort.TransferRequest body) {
        return port.transfer(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Start work. */
    @PostMapping("/{id}/start")
    public ReferenceService.Reference start(RequestContext ctx, @PathVariable String id) {
        return port.startWork(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id);
    }

    /** Send an interim reply. */
    @PostMapping("/{id}/interim-reply")
    public ReferenceService.Reference interimReply(RequestContext ctx, @PathVariable String id,
                                                   @RequestBody ReferenceRestPort.ReplyRequest body) {
        return port.interimReply(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Save the draft reply. */
    @PostMapping("/{id}/draft-reply")
    public ReferenceService.Reference draftReply(RequestContext ctx, @PathVariable String id, @RequestBody DraftBody body) {
        return port.draftReply(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body.text());
    }

    /** Approve the draft reply (bound to its hash). */
    @PostMapping("/{id}/approve-reply")
    public ReferenceService.Reference approveReply(RequestContext ctx, @PathVariable String id, @RequestBody ApproveBody body) {
        return port.approveReply(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body.draftHash());
    }

    /** Send the final reply. */
    @PostMapping("/{id}/reply")
    public ReferenceService.Reference reply(RequestContext ctx, @PathVariable String id,
                                            @RequestBody ReferenceRestPort.ReplyRequest body) {
        return port.reply(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Extend the deadline with a reason. */
    @PostMapping("/{id}/extend")
    public ReferenceService.Reference extend(RequestContext ctx, @PathVariable String id,
                                             @RequestBody ReferenceRestPort.ExtendRequest body) {
        CaseIdentity.requireReason(body.reason());
        return port.extend(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Register a follow-up reference. */
    @PostMapping("/{id}/follow-ups")
    public ReferenceService.Reference followUp(RequestContext ctx, @PathVariable String id,
                                               @RequestBody ReferenceRestPort.FollowUpRequest body) {
        return port.followUp(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Close the reference with a reason. */
    @PostMapping("/{id}/close")
    public ReferenceService.Reference close(RequestContext ctx, @PathVariable String id, @RequestBody ReasonBody body) {
        return port.close(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id,
                CaseIdentity.requireReason(body.reason()));
    }
}
