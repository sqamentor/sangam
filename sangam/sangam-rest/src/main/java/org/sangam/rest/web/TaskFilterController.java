// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.rest.adapter.jdbc.JdbcTaskFilterAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskFilterPort;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner decisions 404 and 406: the shared saved task lists of the caller's tenant. List and read need
 * {@code team.read}; create, update and delete need {@code team.write}; an identity without a tenant is refused (403
 * TENANT_REQUIRED) before anything is read. Read, create and update answer {@code ETag: "<version>"}; update and
 * delete need {@code If-Match} with that version (428 without it, 409 REVISION_CONFLICT when stale) and delete needs a
 * reason. Every change is audited (TASK_FILTER_CREATED, _UPDATED, _DELETED on the tenant's security chain) in the
 * statement's transaction. A list of another tenant is 404 and never touched. A list is run by
 * {@code GET /api/v1/tasks?filterId=} ({@link SavedTaskQuery}).
 */
@RestController
@RequestMapping(TaskFilterController.BASE)
public class TaskFilterController {

    static final String BASE = "/api/v1/team/task-filters";
    static final int MAX_NAME = 255;
    static final int MAX_JSON = 16_384;
    static final int MAX_REASON = 1000;

    /** Create body: {@code name} and {@code query} are required; {@code owner} and {@code properties} optional. */
    public record CreateRequest(String name, String owner, Map<String, Object> query, Map<String, Object> properties) {
    }

    private final TaskFilterPort filters;
    private final PermissionPort permissions;
    private final AuditPort audit;
    private final TxSupport tx;

    public TaskFilterController(TaskFilterPort filters, PermissionPort permissions, AuditPort audit, TxSupport tx) {
        this.filters = filters;
        this.permissions = permissions;
        this.audit = audit;
        this.tx = tx;
    }

    @GetMapping
    public ResponseEntity<List<TaskFilterPort.TaskFilter>> list(RequestContext ctx) {
        permissions.require(ctx, Permissions.TEAM_READ);
        return Lists.ok(filters.list(TenantCustomFieldController.tenant(ctx)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskFilterPort.TaskFilter> get(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TEAM_READ);
        String tenant = TenantCustomFieldController.tenant(ctx);
        TaskFilterPort.TaskFilter f = filters.get(tenant, id).orElseThrow(() -> ApiProblem.notFound("Task list", id));
        return Lists.withRevision(f.version(), f);
    }

    @PostMapping
    public ResponseEntity<TaskFilterPort.TaskFilter> create(RequestContext ctx,
                                                            @RequestBody(required = false) CreateRequest body) {
        permissions.require(ctx, Permissions.TEAM_WRITE);
        String tenant = TenantCustomFieldController.tenant(ctx);
        Valid v = valid(body);
        TaskFilterPort.TaskFilter f = tx.inTx(() -> {
            TaskFilterPort.TaskFilter created = filters.create(tenant, ctx.principalId(), v.name(), v.owner(),
                    v.query(), v.properties());
            audit(ctx, tenant, "TASK_FILTER_CREATED", created, null);
            return created;
        });
        return ResponseEntity.created(URI.create(BASE + "/" + f.id())).header(Lists.RESOURCE_REF, f.id())
                .eTag("\"" + f.version() + "\"").body(f);
    }

    /** Replaces the list (same body and validation as create) at the If-Match version. */
    @PutMapping("/{id}")
    public ResponseEntity<TaskFilterPort.TaskFilter> update(RequestContext ctx, @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) CreateRequest body) {
        permissions.require(ctx, Permissions.TEAM_WRITE);
        String tenant = TenantCustomFieldController.tenant(ctx);
        int expected = version(ifMatch);
        Valid v = valid(body);
        TaskFilterPort.TaskFilter f = tx.inTx(() -> {
            TaskFilterPort.TaskFilter updated = filters.update(tenant, id, expected, v.name(), v.owner(), v.query(),
                    v.properties()).orElseThrow(() -> ApiProblem.notFound("Task list", id));
            audit(ctx, tenant, "TASK_FILTER_UPDATED", updated, null);
            return updated;
        });
        return ResponseEntity.ok().header(Lists.RESOURCE_REF, id).eTag("\"" + f.version() + "\"").body(f);
    }

    /** Deletes the list at the If-Match version; the reason is audited. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(RequestContext ctx, @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.TEAM_WRITE);
        String tenant = TenantCustomFieldController.tenant(ctx);
        ProcessController.requireReason(reason);
        if (reason.trim().length() > MAX_REASON) {
            throw TenantCustomFieldController.invalid("reason", "reason must be at most " + MAX_REASON
                    + " characters");
        }
        int expected = version(ifMatch);
        tx.inTx(() -> {
            TaskFilterPort.TaskFilter gone = filters.delete(tenant, id, expected)
                    .orElseThrow(() -> ApiProblem.notFound("Task list", id));
            audit(ctx, tenant, "TASK_FILTER_DELETED", gone, reason.trim());
            return gone;
        });
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id).build();
    }

    private record Valid(String name, String owner, Map<String, Object> query, Map<String, Object> properties) {
    }

    private static Valid valid(CreateRequest body) {
        if (body == null) {
            throw TenantCustomFieldController.invalid("name", "name is required");
        }
        String name = bounded(TenantCustomFieldController.required(body.name(), "name").trim(), "name");
        String owner = body.owner() == null || body.owner().isBlank() ? null : bounded(body.owner().trim(), "owner");
        if (body.query() == null) {
            throw TenantCustomFieldController.invalid("query", "query is required (a JSON object)");
        }
        size(body.query(), "query");
        if (body.properties() != null) {
            size(body.properties(), "properties");
        }
        SavedTaskQuery.check(body.query());
        return new Valid(name, owner, body.query(), body.properties());
    }

    private static int version(String ifMatch) {
        long v = Lists.ifMatchRevision(ifMatch);
        if (v < 1 || v > Integer.MAX_VALUE) {
            throw ApiProblem.badRequest("INVALID_IF_MATCH", "If-Match must carry the list's version");
        }
        return (int) v;
    }

    private void audit(RequestContext ctx, String tenant, String type, TaskFilterPort.TaskFilter f, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("filterId", f.id());
        p.put("name", f.name());
        p.put("version", f.version());
        p.put("actor", ctx.actor());
        if (reason != null) {
            p.put("reason", reason);
        }
        audit.record(ctx, "sec:" + tenant, type, p);
    }

    private static String bounded(String value, String field) {
        if (value.length() > MAX_NAME) {
            throw TenantCustomFieldController.invalid(field, field + " must be at most " + MAX_NAME + " characters");
        }
        return value;
    }

    private static void size(Map<String, Object> value, String field) {
        if (JdbcTaskFilterAdapter.json(value).length() > MAX_JSON) {
            throw TenantCustomFieldController.invalid(field, field + " must be at most " + MAX_JSON
                    + " characters as JSON");
        }
    }
}
