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

    @PutMapping("/service-points/{servicePointId}")
    public ResponseEntity<QueueModel.ServicePoint> defineServicePoint(RequestContext ctx,
            @PathVariable String servicePointId, @RequestBody QueueRestPort.ServicePointRequest body) {
        return Lists.okRef(servicePointId, port.defineServicePoint(admin(ctx), servicePointId, body));
    }

    @PostMapping("/service-points/{servicePointId}/status")
    public ResponseEntity<QueueModel.ServicePoint> setServicePointStatus(RequestContext ctx,
            @PathVariable String servicePointId, @RequestBody StatusRequest body) {
        return Lists.okRef(servicePointId, port.setServicePointStatus(admin(ctx), servicePointId, body.status()));
    }

    @GetMapping("/service-points/{servicePointId}")
    public QueueModel.ServicePoint servicePoint(RequestContext ctx, @PathVariable String servicePointId) {
        return port.servicePoint(read(ctx), servicePointId);
    }

    @GetMapping("/service-points")
    public ResponseEntity<List<QueueModel.ServicePoint>> servicePoints(RequestContext ctx,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page.PageRequest pr = Lists.page(page, size);
        return Lists.ok(port.servicePoints(read(ctx), pr.page(), pr.size()));
    }

    @PutMapping("/service-points/{servicePointId}/counters/{counterId}")
    public ResponseEntity<QueueModel.Counter> defineCounter(RequestContext ctx, @PathVariable String servicePointId,
            @PathVariable String counterId, @RequestBody QueueRestPort.CounterRequest body) {
        return Lists.okRef(counterId, port.defineCounter(admin(ctx), servicePointId, counterId, body));
    }

    @GetMapping("/service-points/{servicePointId}/counters")
    public ResponseEntity<List<QueueModel.Counter>> counters(RequestContext ctx, @PathVariable String servicePointId) {
        return Lists.ok(port.counters(read(ctx), servicePointId));
    }

    @PostMapping("/counters/{counterId}/open")
    public ResponseEntity<QueueModel.Counter> openCounter(RequestContext ctx, @PathVariable String counterId,
                                                          @RequestBody OpenRequest body) {
        return Lists.okRef(counterId, port.openCounter(operate(ctx), counterId, body.operatorId()));
    }

    @PostMapping("/counters/{counterId}/pause")
    public ResponseEntity<QueueModel.Counter> pauseCounter(RequestContext ctx, @PathVariable String counterId,
                                                           @RequestBody ReasonRequest body) {
        return Lists.okRef(counterId, port.pauseCounter(operate(ctx), counterId, body.reason()));
    }

    @PostMapping("/counters/{counterId}/close")
    public ResponseEntity<QueueModel.Counter> closeCounter(RequestContext ctx, @PathVariable String counterId) {
        return Lists.okRef(counterId, port.closeCounter(operate(ctx), counterId));
    }

    @PostMapping("/service-points/{servicePointId}/tokens")
    public ResponseEntity<QueueModel.Token> issueToken(RequestContext ctx, @PathVariable String servicePointId,
                                                       @RequestBody QueueRestPort.IssueTokenRequest body) {
        QueueModel.Token t = port.issueToken(write(ctx), servicePointId, body);
        return Lists.created("/api/v1/queues/tokens/" + t.tokenId(), t.tokenId(), t);
    }

    @GetMapping("/service-points/{servicePointId}/tokens")
    public ResponseEntity<List<QueueModel.Token>> tokens(RequestContext ctx, @PathVariable String servicePointId,
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

    @PostMapping("/counters/{counterId}/call-next")
    public ResponseEntity<QueueModel.Token> callNext(RequestContext ctx, @PathVariable String counterId) {
        return port.callNext(operate(ctx), counterId)
                .map(t -> Lists.okRef(t.tokenId(), t))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/counters/{counterId}/call")
    public ResponseEntity<QueueModel.Token> callToken(RequestContext ctx, @PathVariable String counterId,
                                                      @RequestBody CallRequest body) {
        return Lists.okRef(body.tokenId(), port.callToken(operate(ctx), counterId, body.tokenId(), body.reason()));
    }

    @PostMapping("/counters/{counterId}/recall")
    public ResponseEntity<QueueModel.Token> recall(RequestContext ctx, @PathVariable String counterId) {
        QueueModel.Token t = port.recall(operate(ctx), counterId);
        return Lists.okRef(t.tokenId(), t);
    }

    @PostMapping("/counters/{counterId}/skip")
    public ResponseEntity<QueueModel.Token> skip(RequestContext ctx, @PathVariable String counterId,
                                                 @RequestBody ReasonRequest body) {
        QueueModel.Token t = port.skip(operate(ctx), counterId, body.reason());
        return Lists.okRef(t.tokenId(), t);
    }

    @PostMapping("/counters/{counterId}/start-serving")
    public ResponseEntity<QueueModel.Token> startServing(RequestContext ctx, @PathVariable String counterId) {
        QueueModel.Token t = port.startServing(operate(ctx), counterId);
        return Lists.okRef(t.tokenId(), t);
    }

    @PostMapping("/counters/{counterId}/complete")
    public ResponseEntity<QueueModel.Token> complete(RequestContext ctx, @PathVariable String counterId,
                                                     @RequestBody CompleteRequest body) {
        QueueModel.Token t = port.complete(operate(ctx), counterId, body.outcome());
        return Lists.okRef(t.tokenId(), t);
    }

    @PostMapping("/service-points/{servicePointId}/no-show-sweep")
    public Map<String, Object> noShowSweep(RequestContext ctx, @PathVariable String servicePointId) {
        return Map.of("noShows", port.noShowSweep(admin(ctx), servicePointId));
    }

    @GetMapping("/service-points/{servicePointId}/sla-breaches")
    public ResponseEntity<List<QueueModel.Token>> slaBreaches(RequestContext ctx, @PathVariable String servicePointId) {
        return Lists.ok(port.slaBreaches(read(ctx), servicePointId));
    }

    @GetMapping("/service-points/{servicePointId}/stats")
    public QueueModel.QueueStats stats(RequestContext ctx, @PathVariable String servicePointId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return port.stats(read(ctx), servicePointId, from, to);
    }

    @GetMapping("/service-points/{servicePointId}/display")
    public QueueModel.DisplaySnapshot display(RequestContext ctx, @PathVariable String servicePointId,
            @RequestParam(required = false, defaultValue = "10") int next) {
        return port.displaySnapshot(read(ctx), servicePointId, Math.min(Math.max(next, 1), 100));
    }

    @GetMapping("/service-points/{servicePointId}/call-events")
    public ResponseEntity<List<QueueModel.CallEvent>> callEvents(RequestContext ctx, @PathVariable String servicePointId,
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
