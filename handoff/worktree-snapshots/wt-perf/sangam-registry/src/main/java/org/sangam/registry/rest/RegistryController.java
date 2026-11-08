// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.rest;

import org.sangam.registry.kernel.ObjectTypeDefinition;
import org.sangam.registry.kernel.ObjectTypeService;
import org.sangam.registry.kernel.RegisteredObject;
import org.sangam.registry.kernel.RegisteredObjectService;
import org.sangam.registry.numbering.NumberSeriesDefinition;
import org.sangam.registry.numbering.NumberingService;
import org.sangam.registry.support.Maps;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Kernel REST API: configured types, registered objects (any kind), and number series.
 * Base path {@code /api/v1/registry}.
 */
@RestController
@RequestMapping("/api/v1/registry")
public class RegistryController {

    private static final String IDEM = "Idempotency-Key";

    private final ObjectTypeService types;
    private final RegisteredObjectService objects;
    private final NumberingService numbering;
    private final RegistryRestSupport support;

    public RegistryController(ObjectTypeService types, RegisteredObjectService objects, NumberingService numbering,
                              RegistryRestSupport support) {
        this.types = types;
        this.objects = objects;
        this.numbering = numbering;
        this.support = support;
    }

    // ------------------------------------------------------------------------------------------------ types

    @PostMapping("/types")
    public ResponseEntity<Map<String, Object>> publishType(@RequestHeader(value = IDEM, required = false) String idem,
                                                           @RequestBody Map<String, Object> body) {
        Instant eff = Maps.instant(body, "effectiveFrom");
        Map<String, Object> def = body.containsKey("definition") ? Maps.map(body, "definition") : body;
        ObjectTypeDefinition d = support.txOp(idem, (c, ctx) -> types.publish(c, ctx, def, eff));
        return ResponseEntity.status(201).body(d.toMap());
    }

    @GetMapping("/types")
    public List<ObjectTypeService.TypeVersion> listTypes(@RequestParam(required = false) String kind) {
        return support.tx((c, ctx) -> types.list(c, ctx.tenant(), kind));
    }

    @GetMapping("/types/{typeKey}")
    public Map<String, Object> currentType(@PathVariable String typeKey) {
        return support.tx((c, ctx) -> types.current(c, ctx.tenant(), typeKey).toMap());
    }

    @GetMapping("/types/{typeKey}/versions/{version}")
    public Map<String, Object> typeVersion(@PathVariable String typeKey, @PathVariable int version) {
        return support.tx((c, ctx) -> types.version(c, ctx.tenant(), typeKey, version).toMap());
    }

    @PostMapping("/types/{typeKey}/versions/{version}/retire")
    public ResponseEntity<Void> retireType(@PathVariable String typeKey, @PathVariable int version) {
        support.tx((c, ctx) -> {
            types.retire(c, ctx, typeKey, version);
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------------------------------- objects

    @PostMapping("/objects")
    public ResponseEntity<Map<String, Object>> create(@RequestHeader(value = IDEM, required = false) String idem,
                                                      @RequestBody Map<String, Object> body) {
        RegisteredObject o = support.txOp(idem, (c, ctx) -> objects.create(c, ctx, RegisteredObjectService.CreateRequest.from(body)));
        return ResponseEntity.status(201).header("ETag", "\"" + o.version() + "\"").body(o.toMap());
    }

    @GetMapping("/objects")
    public List<Map<String, Object>> list(@RequestParam(required = false) String typeKey,
                                          @RequestParam(required = false) String kind,
                                          @RequestParam(required = false) String state,
                                          @RequestParam(required = false) String holderPartyId,
                                          @RequestParam(required = false) String parentId,
                                          @RequestParam(required = false) String number,
                                          @RequestParam(required = false) String subjectRef,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return support.tx((c, ctx) -> objects.list(c, ctx, new RegisteredObjectService.Query(typeKey, kind, state,
                holderPartyId, parentId, number, subjectRef, page, size)).stream().map(RegisteredObject::toMap).toList());
    }

    @GetMapping("/objects/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        RegisteredObject o = support.tx((c, ctx) -> objects.get(c, ctx, id));
        return ResponseEntity.ok().header("ETag", "\"" + o.version() + "\"").body(o.toMap());
    }

    @GetMapping("/objects/by-number/{number}")
    public Map<String, Object> byNumber(@PathVariable String number, @RequestParam(required = false) String typeKey) {
        return support.tx((c, ctx) -> objects.byNumber(c, ctx, typeKey, number).toMap());
    }

    @PatchMapping("/objects/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String idem,
                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                      @RequestBody Map<String, Object> body) {
        return support.txOp(idem, (c, ctx) -> objects.update(c, ctx, id, version(ifMatch, body), Maps.map(body, "attributes"),
                Maps.str(body, "displayName"), Maps.str(body, "reason")).toMap());
    }

    @PostMapping("/objects/{id}/transitions/{name}")
    public Map<String, Object> transition(@PathVariable String id, @PathVariable String name,
                                          @RequestHeader(value = IDEM, required = false) String idem,
                                          @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                          @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return support.txOp(idem, (c, ctx) -> objects.transition(c, ctx, id, version(ifMatch, b), name, Maps.str(b, "reason"),
                Maps.instant(b, "until"), Maps.str(b, "countersignedBy")).toMap());
    }

    @GetMapping("/objects/{id}/transitions")
    public List<String> availableTransitions(@PathVariable String id) {
        return support.tx((c, ctx) -> {
            RegisteredObject o = objects.get(c, ctx, id);
            return objects.typeOf(c, o).lifecycle().available(o.state());
        });
    }

    @GetMapping("/objects/{id}/versions")
    public List<RegisteredObjectService.Version> history(@PathVariable String id) {
        return support.tx((c, ctx) -> objects.history(c, ctx, id));
    }

    @GetMapping("/objects/{id}/as-of")
    public RegisteredObjectService.Version asOf(@PathVariable String id, @RequestParam String at) {
        return support.tx((c, ctx) -> objects.asOf(c, ctx, id, Instant.parse(at)));
    }

    @GetMapping("/objects/{id}/audit/verify")
    public Object verifyAudit(@PathVariable String id) {
        return support.tx((c, ctx) -> objects.verifyAudit(c, ctx, id));
    }

    @PostMapping("/objects/mirror")
    public Map<String, Object> mirror(@RequestBody Map<String, Object> body) {
        return support.tx((c, ctx) -> objects.mirror(c, ctx, Maps.required(body, "typeKey"), Maps.required(body, "externalRef")).toMap());
    }

    // ---------------------------------------------------------------------------------------------- numbers

    @PutMapping("/number-series/{seriesKey}")
    public Map<String, Object> defineSeries(@PathVariable String seriesKey, @RequestBody Map<String, Object> body) {
        Map<String, Object> def = new java.util.LinkedHashMap<>(body);
        def.put("seriesKey", seriesKey);
        return support.tx((c, ctx) -> numbering.define(c, ctx, def).toMap());
    }

    @GetMapping("/number-series")
    public List<Map<String, Object>> listSeries() {
        return support.tx((c, ctx) -> numbering.listSeries(c, ctx.tenant()).stream().map(NumberSeriesDefinition::toMap).toList());
    }

    @GetMapping("/number-series/{seriesKey}")
    public Map<String, Object> series(@PathVariable String seriesKey) {
        return support.tx((c, ctx) -> numbering.series(c, ctx.tenant(), seriesKey).toMap());
    }

    @PostMapping("/number-series/{seriesKey}/allocate")
    public ResponseEntity<Map<String, Object>> allocate(@PathVariable String seriesKey,
                                                        @RequestHeader(value = IDEM, required = false) String idem,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        LocalDate date = Maps.date(b, "date");
        NumberingService.Allocation a = support.txOp(idem, (c, ctx) -> numbering.allocate(c, ctx, seriesKey,
                Maps.str(b, "scope"), date, Maps.map(b, "attributes"), Maps.str(b, "refType"), Maps.str(b, "refId")));
        return ResponseEntity.status(201).body(a.toMap());
    }

    @PostMapping("/number-series/{seriesKey}/void")
    public ResponseEntity<Void> voidNumber(@PathVariable String seriesKey, @RequestBody Map<String, Object> body) {
        support.tx((c, ctx) -> {
            numbering.voidNumber(c, ctx, seriesKey, Maps.required(body, "number"), Maps.required(body, "reason"));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/number-series/{seriesKey}/mark-used")
    public ResponseEntity<Void> markUsed(@PathVariable String seriesKey, @RequestBody Map<String, Object> body) {
        support.tx((c, ctx) -> {
            numbering.markUsed(c, ctx, seriesKey, Maps.required(body, "number"), Maps.str(body, "refType"), Maps.str(body, "refId"));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/number-series/{seriesKey}/close-period")
    public ResponseEntity<Void> closePeriod(@PathVariable String seriesKey, @RequestBody Map<String, Object> body) {
        support.tx((c, ctx) -> {
            numbering.closePeriod(c, ctx, seriesKey, Maps.str(body, "scope"), Maps.required(body, "period"));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/number-series/{seriesKey}/gaps")
    public NumberingService.GapReport gaps(@PathVariable String seriesKey, @RequestParam(required = false) String scope,
                                           @RequestParam String period) {
        return support.tx((c, ctx) -> numbering.gapReport(c, ctx, seriesKey, scope, period));
    }

    @GetMapping("/number-series/{seriesKey}/allocations")
    public List<NumberingService.AllocationRow> allocations(@PathVariable String seriesKey,
                                                            @RequestParam(required = false) String scope,
                                                            @RequestParam String period,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "100") int size) {
        return support.tx((c, ctx) -> numbering.allocations(c, ctx, seriesKey, scope, period, page, size));
    }

    static Long version(String ifMatch, Map<String, Object> body) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            return Long.valueOf(ifMatch.replace("\"", "").replace("W/", "").trim());
        }
        return Maps.lng(body, "expectedVersion");
    }
}
