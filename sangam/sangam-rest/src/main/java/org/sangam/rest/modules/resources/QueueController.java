// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.resources;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.queue.QueueModel;
import org.sangam.resources.rest.QueueRestPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Queue and token management (sangam-resources N07) at {@code /api/v1/queues}: service points, counters, token
 * issue/transfer/cancel, call-next / recall / skip / serve / complete at a counter, SLA breaches, statistics and the
 * display-board snapshot and call-event stream.
 */
@RestController
@RequestMapping("/api/v1/queues")
public class QueueController {

    private static final int COUNT_CAP = 1000;

    private final QueueRestPort port;
    private final ResourcesIdentity identities;
    private final PermissionPort permissions;

    public QueueController(QueueRestPort port, ResourcesIdentity identities, PermissionPort permissions) {
        this.port = port;
        this.identities = identities;
        this.permissions = permissions;
    }

    public record StatusRequest(String status) {
    }

    public record OpenRequest(String operatorId) {
    }

    public record ReasonRequest(String reason) {
    }

    public record CallRequest(String tokenId, String reason) {
    }

    public record CompleteRequest(String outcome) {
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PutMapping("/service-points/by-service-point-id")
    public ResponseEntity<QueueModel.ServicePoint> defineServicePoint(RequestContext ctx,
            @RequestParam("servicePointId") String servicePointId, @RequestBody QueueRestPort.ServicePointRequest body) {
        return Lists.okRef(servicePointId, port.defineServicePoint(admin(ctx), servicePointId, body));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/service-points/by-service-point-id/status")
    public ResponseEntity<QueueModel.ServicePoint> setServicePointStatus(RequestContext ctx,
            @RequestParam("servicePointId") String servicePointId, @RequestBody StatusRequest body) {
        return Lists.okRef(servicePointId, port.setServicePointStatus(admin(ctx), servicePointId, body.status()));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @GetMapping("/service-points/by-service-point-id")
    public QueueModel.ServicePoint servicePoint(RequestContext ctx, @RequestParam("servicePointId") String servicePointId) {
        return port.servicePoint(read(ctx), servicePointId);
    }

    @GetMapping("/service-points")
    public ResponseEntity<List<QueueModel.ServicePoint>> servicePoints(RequestContext ctx,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page.PageRequest pr = Lists.page(page, size);
        return Lists.ok(port.servicePoints(read(ctx), pr.page(), pr.size()));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PutMapping("/service-points/by-service-point-id/counters/by-counter-id")
    public ResponseEntity<QueueModel.Counter> defineCounter(RequestContext ctx, @RequestParam("servicePointId") String servicePointId,
            @RequestParam("counterId") String counterId, @RequestBody QueueRestPort.CounterRequest body) {
        return Lists.okRef(counterId, port.defineCounter(admin(ctx), servicePointId, counterId, body));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @GetMapping("/service-points/by-service-point-id/counters")
    public ResponseEntity<List<QueueModel.Counter>> counters(RequestContext ctx, @RequestParam("servicePointId") String servicePointId) {
        return Lists.ok(port.counters(read(ctx), servicePointId));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/counters/by-counter-id/open")
    public ResponseEntity<QueueModel.Counter> openCounter(RequestContext ctx, @RequestParam("counterId") String counterId,
                                                          @RequestBody OpenRequest body) {
        return Lists.okRef(counterId, port.openCounter(operate(ctx), counterId, body.operatorId()));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/counters/by-counter-id/pause")
    public ResponseEntity<QueueModel.Counter> pauseCounter(RequestContext ctx, @RequestParam("counterId") String counterId,
                                                           @RequestBody ReasonRequest body) {
        return Lists.okRef(counterId, port.pauseCounter(operate(ctx), counterId, body.reason()));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/counters/by-counter-id/close")
    public ResponseEntity<QueueModel.Counter> closeCounter(RequestContext ctx, @RequestParam("counterId") String counterId) {
        return Lists.okRef(counterId, port.closeCounter(operate(ctx), counterId));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/service-points/by-service-point-id/tokens")
    public ResponseEntity<QueueModel.Token> issueToken(RequestContext ctx, @RequestParam("servicePointId") String servicePointId,
                                                       @RequestBody QueueRestPort.IssueTokenRequest body) {
        QueueModel.Token t = port.issueToken(write(ctx), servicePointId, body);
        return Lists.created("/api/v1/queues/tokens/" + t.tokenId(), t.tokenId(), t);
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @GetMapping("/service-points/by-service-point-id/tokens")
    public ResponseEntity<List<QueueModel.Token>> tokens(RequestContext ctx, @RequestParam("servicePointId") String servicePointId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        SecurityIdentity id = read(ctx);
        Page.PageRequest pr = Lists.page(page, size);
        return ResourcesController.listed(port.tokens(id, servicePointId, state, pr.page(), pr.size()),
                port.countTokens(id, servicePointId, state, COUNT_CAP));
    }

    @GetMapping("/tokens/{tokenId}")
    public QueueModel.Token token(RequestContext ctx, @PathVariable String tokenId) {
        return port.token(read(ctx), tokenId);
    }

    @GetMapping("/tokens/{tokenId}/position")
    public Map<String, Object> position(RequestContext ctx, @PathVariable String tokenId) {
        return port.position(read(ctx), tokenId);
    }

    @PostMapping("/tokens/{tokenId}/transfer")
    public ResponseEntity<QueueModel.Token> transfer(RequestContext ctx, @PathVariable String tokenId,
                                                     @RequestBody QueueRestPort.TransferRequest body) {
        return Lists.okRef(tokenId, port.transfer(operate(ctx), tokenId, body));
    }

    @PostMapping("/tokens/{tokenId}/cancel")
    public ResponseEntity<QueueModel.Token> cancel(RequestContext ctx, @PathVariable String tokenId,
                                                   @RequestBody ReasonRequest body) {
        return Lists.okRef(tokenId, port.cancel(write(ctx), tokenId, body.reason()));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/counters/by-counter-id/call-next")
    public ResponseEntity<QueueModel.Token> callNext(RequestContext ctx, @RequestParam("counterId") String counterId) {
        return port.callNext(operate(ctx), counterId)
                .map(t -> Lists.okRef(t.tokenId(), t))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/counters/by-counter-id/call")
    public ResponseEntity<QueueModel.Token> callToken(RequestContext ctx, @RequestParam("counterId") String counterId,
                                                      @RequestBody CallRequest body) {
        return Lists.okRef(body.tokenId(), port.callToken(operate(ctx), counterId, body.tokenId(), body.reason()));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/counters/by-counter-id/recall")
    public ResponseEntity<QueueModel.Token> recall(RequestContext ctx, @RequestParam("counterId") String counterId) {
        QueueModel.Token t = port.recall(operate(ctx), counterId);
        return Lists.okRef(t.tokenId(), t);
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/counters/by-counter-id/skip")
    public ResponseEntity<QueueModel.Token> skip(RequestContext ctx, @RequestParam("counterId") String counterId,
                                                 @RequestBody ReasonRequest body) {
        QueueModel.Token t = port.skip(operate(ctx), counterId, body.reason());
        return Lists.okRef(t.tokenId(), t);
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/counters/by-counter-id/start-serving")
    public ResponseEntity<QueueModel.Token> startServing(RequestContext ctx, @RequestParam("counterId") String counterId) {
        QueueModel.Token t = port.startServing(operate(ctx), counterId);
        return Lists.okRef(t.tokenId(), t);
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/counters/by-counter-id/complete")
    public ResponseEntity<QueueModel.Token> complete(RequestContext ctx, @RequestParam("counterId") String counterId,
                                                     @RequestBody CompleteRequest body) {
        QueueModel.Token t = port.complete(operate(ctx), counterId, body.outcome());
        return Lists.okRef(t.tokenId(), t);
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/service-points/by-service-point-id/no-show-sweep")
    public Map<String, Object> noShowSweep(RequestContext ctx, @RequestParam("servicePointId") String servicePointId) {
        return Map.of("noShows", port.noShowSweep(admin(ctx), servicePointId));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @GetMapping("/service-points/by-service-point-id/sla-breaches")
    public ResponseEntity<List<QueueModel.Token>> slaBreaches(RequestContext ctx, @RequestParam("servicePointId") String servicePointId) {
        return Lists.ok(port.slaBreaches(read(ctx), servicePointId));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @GetMapping("/service-points/by-service-point-id/stats")
    public QueueModel.QueueStats stats(RequestContext ctx, @RequestParam("servicePointId") String servicePointId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return port.stats(read(ctx), servicePointId, from, to);
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @GetMapping("/service-points/by-service-point-id/display")
    public QueueModel.DisplaySnapshot display(RequestContext ctx, @RequestParam("servicePointId") String servicePointId,
            @RequestParam(required = false, defaultValue = "10") int next) {
        return port.displaySnapshot(read(ctx), servicePointId, Math.min(Math.max(next, 1), 100));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @GetMapping("/service-points/by-service-point-id/call-events")
    public ResponseEntity<List<QueueModel.CallEvent>> callEvents(RequestContext ctx, @RequestParam("servicePointId") String servicePointId,
            @RequestParam(required = false, defaultValue = "0") long after,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return Lists.ok(port.callEvents(read(ctx), servicePointId, after, Math.min(Math.max(limit, 1), 1000)));
    }

    private SecurityIdentity read(RequestContext ctx) {
        permissions.require(ctx, ResourcesPermissions.READ);
        return identities.of(ctx);
    }

    private SecurityIdentity write(RequestContext ctx) {
        permissions.require(ctx, ResourcesPermissions.WRITE);
        return identities.of(ctx);
    }

    /** Counter operation (call, serve, transfer): the module's object guard (RES_TOKEN WORK / ASSIGN) decides who. */
    private SecurityIdentity operate(RequestContext ctx) {
        permissions.require(ctx, ResourcesPermissions.WRITE);
        return identities.of(ctx);
    }

    private SecurityIdentity admin(RequestContext ctx) {
        permissions.require(ctx, ResourcesPermissions.ADMIN);
        return identities.of(ctx);
    }
}
