// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.rest.port.LowCodePort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Low-code pages (R77, EC-03), named queries (R25) and data objects (R78, EC-06); authorized on the server. */
@RestController
@RequestMapping("/api/v1")
public class LowCodeController {

    private static final Set<String> PAGING = Set.of("page", "size");

    private final LowCodePort lowCode;
    private final PermissionPort permissions;

    public LowCodeController(LowCodePort lowCode, PermissionPort permissions) {
        this.lowCode = lowCode;
        this.permissions = permissions;
    }

    @GetMapping("/pages")
    public ResponseEntity<List<LowCodePort.PageSummary>> pages(RequestContext ctx) {
        permissions.require(ctx, Permissions.LOWCODE_READ);
        return Lists.ok(lowCode.pages(ctx));
    }

    @PostMapping("/pages")
    public ResponseEntity<LowCodePort.PageModel> createPage(RequestContext ctx, @RequestBody LowCodePort.PageModel body) {
        permissions.require(ctx, Permissions.LOWCODE_ADMIN);
        LowCodePort.PageModel p = lowCode.createPage(ctx, body);
        return Lists.created("/api/v1/pages/" + p.key(), p.key(), p);
    }

    @GetMapping("/pages/{key}")
    public LowCodePort.PageModel page(RequestContext ctx, @PathVariable String key) {
        permissions.require(ctx, Permissions.LOWCODE_READ);
        return lowCode.page(ctx, key);
    }

    @PutMapping("/pages/{key}")
    public ResponseEntity<LowCodePort.PageModel> updatePage(RequestContext ctx, @PathVariable String key,
                                                            @RequestBody LowCodePort.PageModel body) {
        permissions.require(ctx, Permissions.LOWCODE_ADMIN);
        return Lists.okRef(key, lowCode.updatePage(ctx, key, body));
    }

    @GetMapping("/queries/{name}")
    public ResponseEntity<List<Map<String, Object>>> query(RequestContext ctx, @PathVariable String name,
            @RequestParam Map<String, String> all,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.LOWCODE_READ);
        Map<String, String> params = new LinkedHashMap<>(all);
        params.keySet().removeAll(PAGING);
        return Lists.ok(lowCode.query(ctx, name, params, Lists.page(page, size)));
    }

    @GetMapping("/data-objects")
    public ResponseEntity<List<LowCodePort.DataObjectDef>> dataObjects(RequestContext ctx) {
        permissions.require(ctx, Permissions.LOWCODE_READ);
        return Lists.ok(lowCode.dataObjects(ctx));
    }

    @PostMapping("/data-objects")
    public ResponseEntity<LowCodePort.DataObjectDef> createDataObject(RequestContext ctx,
                                                                      @RequestBody LowCodePort.DataObjectDef body) {
        permissions.require(ctx, Permissions.LOWCODE_ADMIN);
        LowCodePort.DataObjectDef d = lowCode.createDataObject(ctx, body);
        return Lists.created("/api/v1/data-objects/" + d.id(), d.id(), d);
    }

    @PutMapping("/data-objects/{id}")
    public ResponseEntity<LowCodePort.DataObjectDef> updateDataObject(RequestContext ctx, @PathVariable String id,
                                                                      @RequestBody LowCodePort.DataObjectDef body) {
        permissions.require(ctx, Permissions.LOWCODE_ADMIN);
        return Lists.okRef(id, lowCode.updateDataObject(ctx, id, body));
    }

    @GetMapping("/data-objects/{key}/records")
    public ResponseEntity<List<Map<String, Object>>> records(RequestContext ctx, @PathVariable String key,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.LOWCODE_READ);
        return Lists.ok(lowCode.records(ctx, key, q, Lists.page(page, size)));
    }

    /**
     * DB backing: 201 with the created record. REST backing (EC-06, owner decision 265): 202 Accepted with the
     * {@code DataObjectRecordOperation} (operationId, status PENDING), {@code Location} the operation route below,
     * resource reference the operationId.
     */
    @PostMapping("/data-objects/{key}/records")
    public ResponseEntity<Object> createRecord(RequestContext ctx, @PathVariable String key,
                                               @RequestBody Map<String, Object> body) {
        // decision 284: creating a record is a write; lowcode.read alone is refused 403
        permissions.require(ctx, Permissions.LOWCODE_RECORD_WRITE);
        LowCodePort.RecordCreation c = lowCode.createRecord(ctx, key, body);
        if (c.accepted() != null) {
            LowCodePort.RecordOperation a = c.accepted();
            return ResponseEntity.accepted()
                    .location(URI.create("/api/v1/data-objects/" + key + "/records/operations/" + a.operationId()))
                    .header(Lists.RESOURCE_REF, a.operationId()).body(a);
        }
        Map<String, Object> r = c.created();
        String id = String.valueOf(r.get("id"));
        return Lists.created("/api/v1/data-objects/" + key + "/records/" + id, id, r);
    }

    /** EC-06, owner decision 265 (getDataObjectRecordOperation): the outcome of a REST-backed create. */
    @GetMapping("/data-objects/{key}/records/operations/{operationId}")
    public LowCodePort.RecordOperation recordOperation(RequestContext ctx, @PathVariable String key,
                                                       @PathVariable String operationId) {
        permissions.require(ctx, Permissions.LOWCODE_READ);
        return lowCode.recordOperation(ctx, key, operationId);
    }
}
