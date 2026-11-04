// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.rest;

import org.sangam.registry.asset.AssetClassDefinition;
import org.sangam.registry.asset.AssetService;
import org.sangam.registry.asset.DepreciationCalculator;
import org.sangam.registry.asset.MaintenanceService;
import org.sangam.registry.asset.WorkOrder;
import org.sangam.registry.support.Maps;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Asset registry and maintenance REST API (N03) under {@code /api/v1/registry/assets}. */
@RestController
@RequestMapping("/api/v1/registry/assets")
public class AssetController {

    private static final String IDEM = "Idempotency-Key";

    private final AssetService assets;
    private final MaintenanceService maintenance;
    private final RegistryRestSupport support;

    public AssetController(AssetService assets, MaintenanceService maintenance, RegistryRestSupport support) {
        this.assets = assets;
        this.maintenance = maintenance;
        this.support = support;
    }

    // ------------------------------------------------------------------------------------------------ classes

    @PostMapping("/classes")
    public Map<String, Object> defineClass(@RequestHeader(value = IDEM, required = false) String key,
                                           @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> assets.defineClass(c, ctx, body).toMap());
    }

    @GetMapping("/classes")
    public List<Map<String, Object>> classes() {
        return support.tx((c, ctx) -> assets.listClasses(c, ctx));
    }

    @GetMapping("/classes/{classKey}")
    public Map<String, Object> getClass(@PathVariable String classKey,
                                        @RequestParam(defaultValue = "true") boolean resolved) {
        return support.tx((c, ctx) -> {
            AssetClassDefinition d = resolved ? assets.resolveClass(c, ctx.tenant(), classKey) : assets.rawClass(c, ctx.tenant(), classKey);
            return d.toMap();
        });
    }

    // ------------------------------------------------------------------------------------------------- assets

    @PostMapping
    public Map<String, Object> register(@RequestHeader(value = IDEM, required = false) String key,
                                        @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> assets.register(c, ctx, body).toMap());
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String classKey,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String parentAssetId,
                                          @RequestParam(required = false) String locationRef,
                                          @RequestParam(required = false) String custodianRef,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return support.tx((c, ctx) -> assets.list(c, ctx, new AssetService.Query(classKey, status, parentAssetId,
                locationRef, custodianRef, page, size)).stream().map(a -> a.toMap()).toList());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return support.tx((c, ctx) -> assets.view(c, ctx, id).toMap());
    }

    @GetMapping("/by-tag/{tag}")
    public Map<String, Object> byTag(@PathVariable String tag) {
        return support.tx((c, ctx) -> assets.byTag(c, ctx, tag).toMap());
    }

    @PatchMapping("/{id}")
    public Map<String, Object> updateDetails(@RequestHeader(value = IDEM, required = false) String key,
                                             @PathVariable String id, @RequestParam(required = false) Long expectedVersion,
                                             @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> assets.updateDetails(c, ctx, id, expectedVersion, body,
                Maps.str(body, "reason")).toMap());
    }

    @PostMapping("/{id}/move")
    public Map<String, Object> move(@RequestHeader(value = IDEM, required = false) String key, @PathVariable String id,
                                    @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> assets.move(c, ctx, id, Maps.str(body, "toLocation"),
                Maps.str(body, "toCustodian"), Maps.str(body, "geometryRef"), Maps.str(body, "reason")).toMap());
    }

    @GetMapping("/{id}/movements")
    public List<Map<String, Object>> movements(@PathVariable String id) {
        return support.tx((c, ctx) -> assets.movements(c, ctx, id));
    }

    @PostMapping("/{id}/reparent")
    public Map<String, Object> reparent(@RequestHeader(value = IDEM, required = false) String key, @PathVariable String id,
                                        @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> assets.reparent(c, ctx, id, Maps.str(body, "parentAssetId"),
                Maps.str(body, "reason")).toMap());
    }

    @GetMapping("/{id}/children")
    public List<Map<String, Object>> children(@PathVariable String id) {
        return support.tx((c, ctx) -> assets.children(c, ctx, id).stream().map(a -> a.toMap()).toList());
    }

    @GetMapping("/{id}/ancestors")
    public List<Map<String, Object>> ancestors(@PathVariable String id) {
        return support.tx((c, ctx) -> assets.ancestors(c, ctx, id).stream().map(a -> a.toMap()).toList());
    }

    @GetMapping("/{id}/subtree")
    public List<Map<String, Object>> subtree(@PathVariable String id) {
        return support.tx((c, ctx) -> assets.subtree(c, ctx, id).stream().map(a -> a.toMap()).toList());
    }

    @PostMapping("/{id}/dispose")
    public Map<String, Object> dispose(@RequestHeader(value = IDEM, required = false) String key, @PathVariable String id,
                                       @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> assets.dispose(c, ctx, id, Maps.date(body, "date"), Maps.str(body, "method"),
                Maps.str(body, "reason"), Maps.str(body, "transition")).toMap());
    }

    @GetMapping("/{id}/depreciation")
    public List<Map<String, Object>> depreciation(@PathVariable String id) {
        return support.tx((c, ctx) -> assets.depreciationSchedule(c, ctx, id).stream()
                .map(DepreciationCalculator.Line::toMap).toList());
    }

    @GetMapping("/{id}/book-value")
    public Map<String, Object> bookValue(@PathVariable String id, @RequestParam(required = false) String asOf) {
        return support.tx((c, ctx) -> {
            LocalDate d = asOf == null ? LocalDate.now(ctx.zone()) : LocalDate.parse(asOf);
            return Maps.of("assetId", id, "asOf", d, "bookValue", assets.bookValue(c, ctx, id, d));
        });
    }

    // ------------------------------------------------------------------------------------------------- meters

    @GetMapping("/{id}/meters")
    public List<Map<String, Object>> meters(@PathVariable String id) {
        return support.tx((c, ctx) -> maintenance.meters(c, ctx, id).stream().map(MaintenanceService.Meter::toMap).toList());
    }

    @PostMapping("/{id}/meters")
    public Map<String, Object> addMeter(@RequestHeader(value = IDEM, required = false) String key, @PathVariable String id,
                                        @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> maintenance.addMeter(c, ctx, id, body).toMap());
    }

    @PostMapping("/{id}/meters/{meterType}/readings")
    public Map<String, Object> recordReading(@RequestHeader(value = IDEM, required = false) String key,
                                             @PathVariable String id, @PathVariable String meterType,
                                             @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> maintenance.recordReading(c, ctx, id, meterType,
                Maps.decimal(body, "reading"), Maps.instant(body, "readAt"), Maps.str(body, "source", "MANUAL"),
                Maps.str(body, "note")).toMap());
    }

    @GetMapping("/{id}/meters/{meterType}/readings")
    public List<Map<String, Object>> readings(@PathVariable String id, @PathVariable String meterType,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return support.tx((c, ctx) -> maintenance.readings(c, ctx, id, meterType, page, size).stream()
                .map(MaintenanceService.Reading::toMap).toList());
    }

    @PostMapping("/readings/{readingId}/correct")
    public Map<String, Object> correctReading(@RequestHeader(value = IDEM, required = false) String key,
                                              @PathVariable String readingId, @RequestBody Map<String, Object> body) {
        BigDecimal v = Maps.decimal(body, "reading");
        return support.txOp(key, (c, ctx) -> maintenance.correctReading(c, ctx, readingId, v, Maps.str(body, "note")).toMap());
    }

    @PostMapping("/readings/{readingId}/reject")
    public Map<String, Object> rejectReading(@RequestHeader(value = IDEM, required = false) String key,
                                             @PathVariable String readingId, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> maintenance.rejectReading(c, ctx, readingId, Maps.str(body, "note")).toMap());
    }

    // -------------------------------------------------------------------------------------------------- plans

    @GetMapping("/{id}/plans")
    public List<Map<String, Object>> plans(@PathVariable String id) {
        return support.tx((c, ctx) -> maintenance.plans(c, ctx, id).stream().map(MaintenanceService.Plan::toMap).toList());
    }

    @PostMapping("/{id}/plans")
    public Map<String, Object> addPlan(@RequestHeader(value = IDEM, required = false) String key, @PathVariable String id,
                                       @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> maintenance.addPlan(c, ctx, id, body).toMap());
    }

    @PatchMapping("/plans/{planId}")
    public Map<String, Object> updatePlan(@RequestHeader(value = IDEM, required = false) String key,
                                          @PathVariable String planId, @RequestParam(required = false) Long expectedVersion,
                                          @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> maintenance.updatePlan(c, ctx, planId, expectedVersion, body).toMap());
    }

    @PostMapping("/plans/{planId}/status")
    public Map<String, Object> planStatus(@RequestHeader(value = IDEM, required = false) String key,
                                          @PathVariable String planId, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> maintenance.setPlanStatus(c, ctx, planId, Maps.required(body, "status"),
                Maps.str(body, "reason")).toMap());
    }

    @PostMapping("/plans/{planId}/trigger")
    public Map<String, Object> triggerCondition(@RequestHeader(value = IDEM, required = false) String key,
                                                @PathVariable String planId, @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> maintenance.conditionTriggered(c, ctx, planId, Maps.str(body, "condition"),
                Maps.str(body, "priority")).toMap());
    }

    // -------------------------------------------------------------------------------------------- work orders

    @PostMapping("/work-orders")
    public Map<String, Object> createWorkOrder(@RequestHeader(value = IDEM, required = false) String key,
                                               @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> maintenance.createWorkOrder(c, ctx, body).toMap());
    }

    @GetMapping("/work-orders")
    public List<Map<String, Object>> workOrders(@RequestParam(required = false) String assetId,
                                                @RequestParam(required = false) String state,
                                                @RequestParam(required = false) String workType,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return support.tx((c, ctx) -> maintenance.workOrders(c, ctx, assetId, state, workType, page, size).stream()
                .map(WorkOrder::toMap).toList());
    }

    @GetMapping("/work-orders/{woId}")
    public Map<String, Object> workOrder(@PathVariable String woId) {
        return support.tx((c, ctx) -> maintenance.workOrder(c, ctx, woId).toMap());
    }

    @PostMapping("/work-orders/generate-due")
    public List<Map<String, Object>> generateDue(@RequestHeader(value = IDEM, required = false) String key,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        Instant asOf = Maps.instant(body, "asOf");
        int limit = Maps.integer(body, "limit", 100);
        return support.txOp(key, (c, ctx) -> maintenance.generateDueWorkOrders(c, ctx, asOf, limit).stream()
                .map(WorkOrder::toMap).toList());
    }

    @PostMapping("/work-orders/{woId}/{step}")
    public Map<String, Object> step(@RequestHeader(value = IDEM, required = false) String key, @PathVariable String woId,
                                    @PathVariable String step, @RequestParam(required = false) Long expectedVersion,
                                    @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return support.txOp(key, (c, ctx) -> (switch (step) {
            case "assign" -> maintenance.assign(c, ctx, woId, expectedVersion, Maps.required(b, "assignee"));
            case "start" -> maintenance.start(c, ctx, woId, expectedVersion);
            case "hold" -> maintenance.hold(c, ctx, woId, expectedVersion, Maps.str(b, "reason"));
            case "resume" -> maintenance.resume(c, ctx, woId, expectedVersion);
            case "complete" -> maintenance.complete(c, ctx, woId, expectedVersion, b);
            case "close" -> maintenance.close(c, ctx, woId, expectedVersion, Maps.str(b, "remarks"));
            case "cancel" -> maintenance.cancel(c, ctx, woId, expectedVersion, Maps.str(b, "reason"));
            default -> throw org.sangam.registry.RegistryException.invalid("unknown work-order step " + step);
        }).toMap());
    }

    // ------------------------------------------------------------------------------------------------- stats

    @GetMapping("/{id}/reliability")
    public Map<String, Object> reliability(@PathVariable String id, @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        return support.tx((c, ctx) -> maintenance.reliability(c, ctx, id, from == null ? null : Instant.parse(from),
                to == null ? null : Instant.parse(to)));
    }
}
