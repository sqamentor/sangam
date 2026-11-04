// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.rest;

import org.sangam.registry.custody.CustodyEvent;
import org.sangam.registry.custody.CustodyItem;
import org.sangam.registry.custody.CustodyRequests;
import org.sangam.registry.custody.CustodyService;
import org.sangam.registry.custody.CustodyTransfer;
import org.sangam.registry.custody.DisposalOrder;
import org.sangam.registry.support.Maps;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Chain-of-custody REST API (N18) under {@code /api/v1/registry/custody}. */
@RestController
@RequestMapping("/api/v1/registry/custody")
public class CustodyController {

    private static final String IDEMPOTENCY = "Idempotency-Key";

    private final CustodyService custody;
    private final RegistryRestSupport support;

    public CustodyController(CustodyService custody, RegistryRestSupport support) {
        this.custody = custody;
        this.support = support;
    }

    // ------------------------------------------------------------------------------------------- items

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CustodyItem register(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                                @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.register(c, ctx, CustodyRequests.Register.from(body)));
    }

    @GetMapping("/items")
    public List<CustodyItem> list(@RequestParam(required = false) String typeKey,
                                  @RequestParam(required = false) String custodian,
                                  @RequestParam(required = false) String caseRef,
                                  @RequestParam(required = false) String containerId,
                                  @RequestParam(required = false) String state,
                                  @RequestParam(required = false) String label,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return support.tx((c, ctx) -> custody.list(c, ctx,
                new CustodyService.Query(typeKey, custodian, caseRef, containerId, state, label, page, size)));
    }

    @GetMapping("/items/{id}")
    public CustodyItem get(@PathVariable String id) {
        return support.tx((c, ctx) -> custody.get(c, ctx, id));
    }

    @GetMapping("/items/by-label/{label}")
    public CustodyItem byLabel(@PathVariable String label) {
        return support.tx((c, ctx) -> custody.byLabel(c, ctx, label));
    }

    @GetMapping("/items/{id}/events")
    public List<CustodyEvent> events(@PathVariable String id) {
        return support.tx((c, ctx) -> custody.events(c, ctx, id));
    }

    @GetMapping("/items/{id}/verify")
    public CustodyService.ChainVerification verify(@PathVariable String id) {
        return support.tx((c, ctx) -> custody.verifyChain(c, ctx, id));
    }

    // ------------------------------------------------------------------------------------------- transfers

    @GetMapping("/items/{id}/transfers")
    public List<CustodyTransfer> transfers(@PathVariable String id) {
        return support.tx((c, ctx) -> custody.transfers(c, ctx, id));
    }

    @PostMapping("/items/{id}/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public CustodyTransfer initiate(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                                    @PathVariable String id, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.initiateTransfer(c, ctx, id, CustodyRequests.Transfer.from(body)));
    }

    @PostMapping("/items/{id}/handover")
    @ResponseStatus(HttpStatus.CREATED)
    public CustodyTransfer handover(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                                    @PathVariable String id, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.handover(c, ctx, id, CustodyRequests.Handover.from(body)));
    }

    @GetMapping("/transfers/{transferId}")
    public CustodyTransfer transfer(@PathVariable String transferId) {
        return support.tx((c, ctx) -> custody.transfer(c, ctx, transferId));
    }

    @GetMapping("/transfers/pending")
    public List<CustodyTransfer> pending(@RequestParam String toCustodian,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return support.tx((c, ctx) -> custody.pendingFor(c, ctx, toCustodian, page, size));
    }

    @PostMapping("/transfers/{transferId}/accept")
    public CustodyTransfer accept(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                                  @PathVariable String transferId, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.acceptTransfer(c, ctx, transferId, CustodyRequests.Accept.from(body)));
    }

    @PostMapping("/transfers/{transferId}/reject")
    public CustodyTransfer reject(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                                  @PathVariable String transferId, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.rejectTransfer(c, ctx, transferId, Maps.str(body, "reason"),
                Maps.str(body, "signatureRef")));
    }

    @PostMapping("/transfers/{transferId}/cancel")
    public CustodyTransfer cancel(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                                  @PathVariable String transferId, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.cancelTransfer(c, ctx, transferId, Maps.str(body, "reason")));
    }

    // ------------------------------------------------------------------------------------------- seals, state, containers

    @PostMapping("/items/{id}/seal")
    public CustodyItem seal(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                            @PathVariable String id, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.seal(c, ctx, id, CustodyRequests.Seal.from(body)));
    }

    @PostMapping("/items/{id}/break-seal")
    public CustodyItem breakSeal(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                                 @PathVariable String id, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.breakSeal(c, ctx, id, Maps.str(body, "reason"),
                Maps.str(body, "witnessRef")));
    }

    @PostMapping("/items/{id}/inspect")
    public CustodyItem inspect(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                               @PathVariable String id, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.inspect(c, ctx, id, Maps.str(body, "conditionText"),
                Maps.str(body, "witnessRef"), Maps.map(body, "details")));
    }

    @PostMapping("/items/{id}/location")
    public CustodyItem location(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                                @PathVariable String id, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.changeLocation(c, ctx, id, Maps.str(body, "locationRef"),
                Maps.str(body, "reason")));
    }

    @PostMapping("/items/{id}/container")
    public CustodyItem putInto(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                               @PathVariable String id, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.putIntoContainer(c, ctx, id, Maps.required(body, "containerId"),
                Maps.str(body, "reason")));
    }

    @PostMapping("/items/{id}/take-out")
    public CustodyItem takeOut(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                               @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.takeOutOfContainer(c, ctx, id, Maps.str(body, "reason")));
    }

    // ------------------------------------------------------------------------------------------- disposal

    @GetMapping("/items/{id}/disposal-orders")
    public List<DisposalOrder> disposalOrders(@PathVariable String id) {
        return support.tx((c, ctx) -> custody.disposalOrders(c, ctx, id));
    }

    @PostMapping("/items/{id}/disposal-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public DisposalOrder orderDisposal(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                                       @PathVariable String id, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.orderDisposal(c, ctx, id, CustodyRequests.Disposal.from(body)));
    }

    @GetMapping("/disposal-orders/{orderId}")
    public DisposalOrder disposalOrder(@PathVariable String orderId) {
        return support.tx((c, ctx) -> custody.disposalOrder(c, ctx, orderId));
    }

    @PostMapping("/disposal-orders/{orderId}/execute")
    public DisposalOrder execute(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                                 @PathVariable String orderId, @RequestBody(required = false) Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.executeDisposal(c, ctx, orderId, Maps.str(body, "witnessRef"),
                Maps.map(body, "details")));
    }

    @PostMapping("/disposal-orders/{orderId}/cancel")
    public DisposalOrder cancelDisposal(@RequestHeader(value = IDEMPOTENCY, required = false) String key,
                                        @PathVariable String orderId, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> custody.cancelDisposal(c, ctx, orderId, Maps.str(body, "reason")));
    }

    // ------------------------------------------------------------------------------------------- gap check

    /** Custody gaps; {@code inTransitHours} and {@code staleDays} are optional thresholds. */
    @GetMapping("/gap-check")
    public CustodyService.GapReport gapCheck(@RequestParam(required = false) Long inTransitHours,
                                             @RequestParam(required = false) Long staleDays,
                                             @RequestParam(defaultValue = "100") int limit) {
        return support.tx((c, ctx) -> custody.gapCheck(c, ctx,
                inTransitHours == null ? Duration.ofHours(24) : Duration.ofHours(inTransitHours),
                staleDays == null ? null : Duration.ofDays(staleDays), limit));
    }
}
