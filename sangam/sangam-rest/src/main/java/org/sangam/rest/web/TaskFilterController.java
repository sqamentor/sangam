// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner decision 404: the shared saved task lists of the caller's tenant. List and read need {@code team.read}, create
 * needs {@code team.write}; an identity without a tenant is refused (403 TENANT_REQUIRED) before anything is read.
 * Creation is audited ({@code TASK_FILTER_CREATED} on the tenant's security chain) in the insert's transaction.
 */
@RestController
@RequestMapping(TaskFilterController.BASE)
public class TaskFilterController {

    static final String BASE = "/api/v1/team/task-filters";
    static final int MAX_NAME = 255;
    static final int MAX_JSON = 16_384;

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
    public TaskFilterPort.TaskFilter get(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TEAM_READ);
        String tenant = TenantCustomFieldController.tenant(ctx);
        return filters.get(tenant, id).orElseThrow(() -> ApiProblem.notFound("Task list", id));
    }

    @PostMapping
    public ResponseEntity<TaskFilterPort.TaskFilter> create(RequestContext ctx,
                                                            @RequestBody(required = false) CreateRequest body) {
        permissions.require(ctx, Permissions.TEAM_WRITE);
        String tenant = TenantCustomFieldController.tenant(ctx);
        if (body == null) {
            throw TenantCustomFieldController.invalid("name", "name is required");
        }
        String name = bounded(TenantCustomFieldController.required(body.name(), "name").trim(), "name");
        String owner = body.owner() == null || body.owner().isBlank() ? null : bounded(body.owner().trim(), "owner");
        if (body.query() == null) {
            throw TenantCustomFieldController.invalid("query", "query is required (a JSON object)");
        }
        checkQuery(body.query());
        size(body.query(), "query");
        if (body.properties() != null) {
            size(body.properties(), "properties");
        }
        TaskFilterPort.TaskFilter f = tx.inTx(() -> {
            TaskFilterPort.TaskFilter created = filters.create(tenant, ctx.principalId(), name, owner, body.query(),
                    body.properties());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("filterId", created.id());
            p.put("name", created.name());
            p.put("actor", ctx.actor());
            audit.record(ctx, "sec:" + tenant, "TASK_FILTER_CREATED", p);
            return created;
        });
        return Lists.created(BASE + "/" + f.id(), f.id(), f);
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

    /** A list is always of the caller's tenant: a criterion naming tenants would be ignored or misleading, so refused. */
    static void checkQuery(Map<String, Object> query) {
        for (String key : query.keySet()) {
            String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (k.isBlank() || k.startsWith("tenant") || k.startsWith("withouttenant")) {
                throw ApiProblem.unprocessable("TASK_FILTER_INVALID", "query criterion '" + key
                        + "' is not allowed: a task list is always limited to the caller's organisation");
            }
        }
    }
}
