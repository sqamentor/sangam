// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.core.layer.AuthorizationPolicy;
import org.sangam.ops.metrics.UsageMetrics;
import org.sangam.ops.outlier.OutlierAnalysis;
import org.sangam.ops.retention.HierarchyRetention;
import org.sangam.ops.search.GlobalSearch;
import org.sangam.ops.search.SearchIndexMaintenance;
import org.sangam.ops.secrets.SecretAdmin;
import org.sangam.ops.sequence.SequenceAdmin;
import org.sangam.ops.vars.ScopedVariables;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.web.Lists;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant operations (R19): every call acts on the caller's tenant from the request context, never one from the request.
 * Secret values are write-only. Search results are filtered by the policy compiler's visibility per resource type.
 */
@RestController
@RequestMapping("/api/v1/ops-admin")
public class OpsController {

    public static final String SEARCH = "search.use";
    public static final String READ = "ops.tenant.read";
    public static final String ADMIN = "ops.tenant.admin";

    private final OpsApi api;
    private final PermissionPort permissions;

    public OpsController(OpsApi api, PermissionPort permissions) {
        this.api = api;
        this.permissions = permissions;
    }

    public record ValueRequest(Object value, String reason) {
    }

    public record SecretRequest(String value) {
    }

    public record AdvanceRequest(String series, String period, long value, String reason) {
    }

    public record ReasonRequest(String reason) {
    }

    public record PurgeResult(int purged) {
    }

    @GetMapping("/search")
    public Map<String, GlobalSearch.TypeResult> search(RequestContext ctx, @RequestParam String q, @RequestParam(required = false) Integer cap) {
        permissions.require(ctx, SEARCH);
        int n = cap == null ? 10 : Math.min(Math.max(cap, 1), 50);
        return api.search().search(org.sangam.rest.modules.cases.CaseIdentity.identity(ctx), q, n);
    }

    @GetMapping("/usage")
    public ResponseEntity<List<UsageMetrics.Metric>> usage(RequestContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        permissions.require(ctx, READ);
        return Lists.ok(api.usage().read(ctx.tenantId(), from, to));
    }

    /** Only variables on the server's allow-list are analysed; a caller cannot widen it. */
    @GetMapping("/outliers")
    public OutlierAnalysis.Result outliers(RequestContext ctx, @RequestParam String processKey, @RequestParam String activityId,
            @RequestParam(required = false) Integer minSamples) {
        permissions.require(ctx, READ);
        Set<String> allowed = api.outlierVariables();
        return OutlierAnalysis.analyse(activityId, OutlierAnalysis.readSamples(api.tx(), org.sangam.rest.modules.cases.CaseIdentity.identity(ctx), api.search().visibility("process-instance"), processKey, activityId, allowed, 10_000),
                minSamples == null ? 30 : minSamples, 3.0, 0.01);
    }

    @GetMapping("/variables")
    public Map<String, Object> variables(RequestContext ctx) {
        permissions.require(ctx, READ);
        return api.variables().merged(ctx.tenantId());
    }

    @PutMapping("/variables/{name}")
    public ResponseEntity<Void> setVariable(RequestContext ctx, @PathVariable String name, @RequestBody ValueRequest b) {
        permissions.require(ctx, ADMIN);
        api.variables().set(ScopedVariables.tenantScope(ctx.tenantId()), name, b.value(), ctx.principalId(), b.reason());
        return ResponseEntity.noContent().build();
    }

    /** The tenant scope's variables with version, last writer and reason (the plain GET is the merged value map). */
    @GetMapping("/variables/entries")
    public ResponseEntity<List<ScopedVariables.Entry>> variableEntries(RequestContext ctx) {
        permissions.require(ctx, READ);
        return Lists.ok(api.variables().entries(ScopedVariables.tenantScope(ctx.tenantId())));
    }

    @DeleteMapping("/variables/{name}")
    public ResponseEntity<Void> deleteVariable(RequestContext ctx, @PathVariable String name) {
        permissions.require(ctx, ADMIN);
        api.variables().delete(ScopedVariables.tenantScope(ctx.tenantId()), name, ctx.principalId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/secrets")
    public ResponseEntity<List<SecretAdmin.SecretInfo>> secrets(RequestContext ctx) {
        permissions.require(ctx, ADMIN);
        return Lists.ok(api.secrets().list(ctx.tenantId()));
    }

    @PutMapping("/secrets/{ref}")
    public ResponseEntity<Void> putSecret(RequestContext ctx, @PathVariable String ref, @RequestBody SecretRequest b) {
        permissions.require(ctx, ADMIN);
        api.secrets().put(ctx.tenantId(), ref, b.value(), ctx.principalId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/secrets/{ref}")
    public ResponseEntity<Void> deleteSecret(RequestContext ctx, @PathVariable String ref) {
        permissions.require(ctx, ADMIN);
        api.secrets().delete(ctx.tenantId(), ref, ctx.principalId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sequences/{table}")
    public ResponseEntity<List<SequenceAdmin.Value>> sequences(RequestContext ctx, @PathVariable String table) {
        permissions.require(ctx, READ);
        return Lists.ok(api.sequences().list(ctx.tenantId(), table));
    }

    @PostMapping("/sequences/{table}/advance")
    public ResponseEntity<Void> advance(RequestContext ctx, @PathVariable String table, @RequestBody AdvanceRequest b) {
        permissions.require(ctx, ADMIN);
        try {
            api.sequences().advance(ctx.tenantId(), table, b.series(), b.period(), b.value(), ctx.principalId(), b.reason());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.conflict("SEQUENCE_REFUSED", e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/index-jobs")
    public SearchIndexMaintenance.Job rebuild(RequestContext ctx, @RequestBody(required = false) ReasonRequest b) {
        permissions.require(ctx, ADMIN);
        return api.index().rebuild(ctx.tenantId(), api.readers(), 500, b == null ? null : b.reason());
    }

    @GetMapping("/index-drift")
    public ResponseEntity<List<SearchIndexMaintenance.Drift>> drift(RequestContext ctx) {
        permissions.require(ctx, READ);
        return Lists.ok(api.index().drift(ctx.tenantId(), api.readers()));
    }

    /** Generations are not tenant-scoped in the module, so looking one up is a platform operator's call. */
    @GetMapping("/index-jobs/{generation}")
    public SearchIndexMaintenance.Job job(RequestContext ctx, @PathVariable long generation) {
        permissions.require(ctx, Permissions.OPS_READ);
        return api.index().job(generation);
    }

    @GetMapping("/retention/plan")
    public ResponseEntity<List<HierarchyRetention.Tree>> retentionPlan(RequestContext ctx, @RequestParam(required = false) Integer limit) {
        permissions.require(ctx, READ);
        return Lists.ok(api.retention().plan(ctx.tenantId(), bound(limit)));
    }

    @PostMapping("/retention/execute")
    public PurgeResult retentionExecute(RequestContext ctx, @RequestBody ReasonRequest b, @RequestParam(required = false) Integer limit) {
        permissions.require(ctx, ADMIN);
        if (b.reason() == null || b.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "This action requires a reason");
        }
        return new PurgeResult(api.retention().execute(ctx.tenantId(), bound(limit), ctx.principalId(), b.reason().trim()));
    }

    @GetMapping("/retention/runs")
    public ResponseEntity<List<HierarchyRetention.Run>> retentionRuns(RequestContext ctx) {
        permissions.require(ctx, READ);
        return Lists.ok(api.retention().runs(ctx.tenantId()));
    }

    private static int bound(Integer limit) {
        return limit == null ? 500 : Math.min(Math.max(limit, 1), 5000);
    }

    /** Migration checkpoints span tenants: platform operators only. */
    @GetMapping("/migration")
    public ResponseEntity<List<OpsApi.MigrationStatus>> migration(RequestContext ctx) {
        permissions.require(ctx, Permissions.OPS_READ);
        return Lists.ok(api.migration().get());
    }
}
