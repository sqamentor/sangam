// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.sangam.core.layer.AuthorizationPolicy;
import org.sangam.core.layer.Jdbc;
import org.sangam.ops.outlier.OutlierAnalysis;
import org.sangam.ops.metrics.UsageMetrics;
import org.sangam.ops.retention.HierarchyRetention;
import org.sangam.ops.search.GlobalSearch;
import org.sangam.ops.search.SearchIndexMaintenance;
import org.sangam.ops.sequence.SequenceAdmin;
import org.sangam.ops.vars.ScopedVariables;

import org.sangam.rest.modules.ModulePaging;
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
 * The console's view of tenant operations, over the same services and checks as {@link OpsController}: {@code /search} and
 * {@code /ops/*}. These paths do not overlap the engine operations under {@code /ops}. Every call acts on the caller's tenant.
 */
@RestController
@RequestMapping("/api/v1")
public class OpsUiController {

    private final OpsApi api;
    private final PermissionPort permissions;

    public OpsUiController(OpsApi api, PermissionPort permissions) {
        this.api = api;
        this.permissions = permissions;
    }

    public record SearchRow(String type, String id, String title) {
    }

    public record OutlierRow(String subject, String metric, double value, double baseline, String correlatedWith, double lift, double pValue) {
    }

    public record VariableRow(String id, String scope, String name, Object value, Long version, String updatedBy, Instant updatedAt, String reason) {
    }

    public record VariableRequest(String scope, String name, Object value, String reason) {
    }

    public record SecretRow(String name, String scope, int version, Instant updatedAt, String updatedBy, String reason) {
    }

    public record SecretRequest(String name, String scope, String value, String reason) {
    }

    public record SequenceRow(String name, long current, String table, String series, String period) {
    }

    public record AdvanceRequest(Long to, String reason) {
    }

    public record IndexJobRow(String id, String kind, String status, String progress, Instant startedAt, String message, String reason) {
    }

    public record IndexJobRequest(String alias, String kind, String reason) {
    }

    public record MigrationRow(String checkpoint, String source, long migrated, Long failed, Long mismatched, String status) {
    }

    public record RetentionPlan(String planId, String hash, List<HierarchyRetention.Tree> items, List<String> blocked) {
    }

    public record RetentionExecute(String planId, String presentedHash, String reason) {
    }

    public record PurgeResult(int purged) {
    }

    @GetMapping("/search")
    public ResponseEntity<List<SearchRow>> search(RequestContext ctx, @RequestParam String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        permissions.require(ctx, OpsController.SEARCH);
        int cap = size == null ? 10 : Math.min(Math.max(size, 1), 50);
        Map<String, GlobalSearch.TypeResult> found = api.search().search(org.sangam.rest.modules.cases.CaseIdentity.identity(ctx), q, cap);
        List<SearchRow> rows = new ArrayList<>();
        found.forEach((type, r) -> r.hits().forEach(h -> rows.add(new SearchRow(type, h.id(), h.title()))));
        return ModulePaging.slice(rows, page, size);
    }

    /** Defaults to the last 30 days. Always the caller's tenant; a {@code tenant} parameter is not read. */
    @GetMapping("/ops/usage")
    public ResponseEntity<List<UsageMetrics.Metric>> usage(RequestContext ctx,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        permissions.require(ctx, OpsController.READ);
        LocalDate end = to == null ? LocalDate.now() : to;
        return Lists.ok(api.usage().read(ctx.tenantId(), from == null ? end.minusDays(30) : from, end));
    }

    /** Analyses one activity, or the process's most-run activities. Variables come only from the server's allow-list. */
    @GetMapping("/ops/outliers")
    public ResponseEntity<List<OutlierRow>> outliers(RequestContext ctx, @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String activityId) {
        permissions.require(ctx, OpsController.READ);
        if (processDefinitionKey == null || processDefinitionKey.isBlank()) {
            return Lists.ok(List.of());
        }
        List<String> activities = activityId != null ? List.of(activityId) : api.tx().inTransaction(c -> Jdbc.query(c,
                "SELECT ACT_ID_ FROM ACT_HI_ACTINST WHERE TENANT_ID_=? AND PROC_DEF_KEY_=? AND END_TIME_ IS NOT NULL GROUP BY ACT_ID_ ORDER BY COUNT(*) DESC",
                rs -> rs.getString(1), ctx.tenantId(), processDefinitionKey));
        List<OutlierRow> rows = new ArrayList<>();
        for (String activity : activities.stream().limit(20).toList()) {
            OutlierAnalysis.Result r = OutlierAnalysis.analyse(activity, OutlierAnalysis.readSamples(api.tx(), org.sangam.rest.modules.cases.CaseIdentity.identity(ctx), api.search().visibility("process-instance"), processDefinitionKey, activity,
                    api.outlierVariables(), 10_000), 30, 3.0, 0.01);
            r.variables().forEach(v -> rows.add(new OutlierRow(activity, v.variable(), v.outlierRate(), v.otherRate(), v.variable() + "=" + v.value(), v.lift(), v.pValue())));
        }
        return Lists.ok(rows);
    }

    /** Tenant-scope variables. The tables have no version and package or process scopes, so {@code version} stays null. */
    @GetMapping("/ops/variables")
    public ResponseEntity<List<VariableRow>> variables(RequestContext ctx, @RequestParam(required = false) String scope, @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, OpsController.READ);
        if (scope != null && !scope.isBlank() && !scope.equalsIgnoreCase("TENANT")) {
            return ModulePaging.slice(List.of(), page, size);
        }
        List<VariableRow> rows = api.variables().entries(ScopedVariables.tenantScope(ctx.tenantId())).stream()
                .filter(e -> name == null || name.isBlank() || e.name().contains(name))
                .sorted(Comparator.comparing(ScopedVariables.Entry::name))
                .map(e -> new VariableRow(e.name(), "TENANT", e.name(), e.value(), e.version(), e.updatedBy(), e.updatedAt(), e.reason())).toList();
        return ModulePaging.slice(rows, page, size);
    }

    @PutMapping("/ops/variables")
    public ResponseEntity<Void> setVariable(RequestContext ctx, @RequestBody VariableRequest b) {
        permissions.require(ctx, OpsController.ADMIN);
        requireReason(b.reason());
        if (b.scope() != null && !b.scope().equalsIgnoreCase("TENANT")) {
            throw ApiProblem.badRequest("SCOPE_NOT_SUPPORTED", "only the TENANT scope can be set from this route");
        }
        if (b.name() == null || b.name().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "name is required");
        }
        try {
            api.variables().set(ScopedVariables.tenantScope(ctx.tenantId()), b.name(), b.value(), ctx.principalId(), b.reason());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/ops/variables/{id}")
    public ResponseEntity<Void> deleteVariable(RequestContext ctx, @PathVariable String id, @RequestParam(required = false) String reason) {
        permissions.require(ctx, OpsController.ADMIN);
        requireReason(reason);
        api.variables().delete(ScopedVariables.tenantScope(ctx.tenantId()), id, ctx.principalId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/ops/secrets")
    public ResponseEntity<List<SecretRow>> secrets(RequestContext ctx, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, OpsController.ADMIN);
        return ModulePaging.slice(api.secrets().list(ctx.tenantId()).stream()
                .map(s -> new SecretRow(s.ref(), "TENANT", s.version(), s.updatedAt(), s.updatedBy(), s.reason())).toList(), page, size);
    }

    /** The value is write-only and is never returned. The path names the secret; a differing body name is refused. */
    @PutMapping("/ops/secrets/{name}")
    public ResponseEntity<Void> putSecret(RequestContext ctx, @PathVariable String name, @RequestBody SecretRequest b) {
        permissions.require(ctx, OpsController.ADMIN);
        requireReason(b.reason());
        if (b.name() != null && !b.name().equals(name)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "the body name differs from the path");
        }
        if (b.scope() != null && !b.scope().isBlank() && !b.scope().equalsIgnoreCase("TENANT")) {
            throw ApiProblem.badRequest("SCOPE_NOT_SUPPORTED", "secrets belong to the tenant");
        }
        // importAll is the reason-carrying write path; a one-entry map is a single put.
        api.secrets().importAll(ctx.tenantId(), Map.of(name, b.value() == null ? "" : b.value()), ctx.principalId(), b.reason());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/ops/secrets/{name}")
    public ResponseEntity<Void> deleteSecret(RequestContext ctx, @PathVariable String name, @RequestParam(required = false) String reason) {
        permissions.require(ctx, OpsController.ADMIN);
        requireReason(reason);
        api.secrets().delete(ctx.tenantId(), name, ctx.principalId());
        return ResponseEntity.noContent().build();
    }

    /** One row per table, series and period; {@code name} identifies it for advance. */
    @GetMapping("/ops/sequences")
    public ResponseEntity<List<SequenceRow>> sequences(RequestContext ctx) {
        permissions.require(ctx, OpsController.READ);
        return Lists.ok(sequenceRows(ctx.tenantId()));
    }

    @PostMapping("/ops/sequences/{name}/advance")
    public ResponseEntity<Void> advance(RequestContext ctx, @PathVariable String name, @RequestBody AdvanceRequest b) {
        permissions.require(ctx, OpsController.ADMIN);
        requireReason(b.reason());
        if (b.to() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "to is required");
        }
        SequenceRow row = sequenceRows(ctx.tenantId()).stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> ApiProblem.notFound("sequence", name));
        try {
            api.sequences().advance(ctx.tenantId(), row.table(), row.series(), row.period(), b.to(), ctx.principalId(), b.reason());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.conflict("SEQUENCE_REFUSED", e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/ops/index-jobs")
    public ResponseEntity<List<IndexJobRow>> indexJobs(RequestContext ctx, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, OpsController.READ);
        return ModulePaging.slice(api.index().jobs(ctx.tenantId()).stream().map(j -> row(j.job(), j.updatedAt())).toList(), page, size);
    }

    /** REBUILD builds a new generation of the caller's index. DRIFT_CHECK compares counts and changes nothing. */
    @PostMapping("/ops/index-jobs")
    public Object startIndexJob(RequestContext ctx, @RequestBody IndexJobRequest b) {
        String kind = String.valueOf(b.kind()).toUpperCase(Locale.ROOT);
        if (kind.equals("DRIFT_CHECK")) {
            permissions.require(ctx, OpsController.READ);
            return api.index().drift(ctx.tenantId(), api.readers());
        }
        if (!kind.equals("REBUILD")) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "kind must be REBUILD or DRIFT_CHECK");
        }
        permissions.require(ctx, OpsController.ADMIN);
        requireReason(b.reason());
        return row(api.index().rebuild(ctx.tenantId(), api.readers(), 500, b.reason()), Instant.now());
    }

    /** Checkpoints span tenants: platform operators only. */
    @GetMapping("/ops/migration-status")
    public ResponseEntity<List<MigrationRow>> migration(RequestContext ctx, @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.OPS_READ);
        return ModulePaging.slice(api.migration().get().stream()
                .filter(m -> source == null || source.isBlank() || source.equals(m.sourceTable()))
                .map(m -> new MigrationRow(m.jobId() + "/" + m.sourceTable(), m.sourceTable(), m.copied(), m.failed(), m.mismatched(), m.status())).toList(), page, size);
    }

    /** The plan is not stored: its hash covers the trees to be purged, and execute re-plans and compares. */
    @GetMapping("/ops/retention/plan")
    public RetentionPlan retentionPlan(RequestContext ctx) {
        permissions.require(ctx, OpsController.READ);
        return plan(ctx.tenantId());
    }

    @PostMapping("/ops/retention/execute")
    public PurgeResult retentionExecute(RequestContext ctx, @RequestBody RetentionExecute b) {
        permissions.require(ctx, OpsController.ADMIN);
        requireReason(b.reason());
        if (!plan(ctx.tenantId()).hash().equals(b.presentedHash())) {
            throw ApiProblem.conflict("RETENTION_PLAN_CHANGED", "the plan changed since it was previewed; preview it again");
        }
        return new PurgeResult(api.retention().execute(ctx.tenantId(), 500, ctx.principalId(), b.reason().trim()));
    }

    /** Executed retention purges for the caller's tenant, newest first, with plan hash, reason and actor. */
    @GetMapping("/ops/retention/runs")
    public ResponseEntity<List<HierarchyRetention.Run>> retentionRuns(RequestContext ctx, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        permissions.require(ctx, OpsController.READ);
        return ModulePaging.slice(api.retention().runs(ctx.tenantId()), page, size);
    }

    private RetentionPlan plan(String tenantId) {
        List<HierarchyRetention.Tree> trees = api.retention().plan(tenantId, 500).stream()
                .sorted(Comparator.comparing(HierarchyRetention.Tree::rootId)).toList();
        StringBuilder canon = new StringBuilder();
        trees.forEach(t -> canon.append(t.rootId()).append(':').append(t.instanceIds().stream().sorted().toList()).append('\n'));
        String hash = sha256(canon.toString());
        return new RetentionPlan(hash.substring(0, 16), hash, trees, List.of());
    }

    private List<SequenceRow> sequenceRows(String tenantId) {
        List<SequenceRow> rows = new ArrayList<>();
        for (String table : api.sequences().tableNames()) {
            for (SequenceAdmin.Value v : api.sequences().list(tenantId, table)) {
                rows.add(new SequenceRow(table + ":" + v.series() + ":" + v.period(), v.value(), table, v.series(), v.period()));
            }
        }
        return rows;
    }

    private static IndexJobRow row(SearchIndexMaintenance.Job j, Instant at) {
        return new IndexJobRow(Long.toString(j.generation()), "REBUILD", j.state(), j.indexed() + "/" + j.expected(), at, j.message(), j.reason());
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "This action requires a reason");
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
