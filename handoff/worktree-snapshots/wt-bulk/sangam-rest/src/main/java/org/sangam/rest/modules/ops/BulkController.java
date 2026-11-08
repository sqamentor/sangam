// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.sangam.ops.bulk.BulkIngest;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
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
 * Bulk data ingest (SL-44). The tenant is always the caller's (VI.4 rule 6): a job of another tenant is 404. Every
 * job operation needs the permission its object type's sink declares; listing shows only the types the caller holds.
 * Limits give 413, a quota refusal 429 with {@code Retry-After}, both as problem+json.
 */
@RestController
@RequestMapping("/api/v1/bulk")
public class BulkController {

    private final BulkIngest ingest;
    private final PermissionPort permissions;

    public BulkController(BulkIngest ingest, PermissionPort permissions) {
        this.ingest = ingest;
        this.permissions = permissions;
    }

    public record CreateJobRequest(String objectType, Integer expectedChunks) {
    }

    public record ChunkRequest(List<BulkIngest.RowInput> rows) {
    }

    @PostMapping("/jobs")
    public ResponseEntity<BulkIngest.Job> create(RequestContext ctx, @RequestBody CreateJobRequest body) {
        if (body == null || body.objectType() == null || body.objectType().isBlank()) {
            throw ApiProblem.badRequest("OBJECT_TYPE_REQUIRED", "objectType is required");
        }
        requireType(ctx, body.objectType());
        BulkIngest.Job job = call(() -> ingest.create(ctx.tenantId(), body.objectType(), body.expectedChunks(), ctx.principalId()));
        return Lists.created("/api/v1/bulk/jobs/" + job.id(), "bulk-job:" + job.id(), job);
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<BulkIngest.Job>> list(RequestContext ctx, @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Set<String> visible = new LinkedHashSet<>();
        for (String type : ingest.objectTypes()) {
            ingest.sink(type).filter(s -> permissions.has(ctx, s.permission())).ifPresent(s -> visible.add(type));
        }
        BulkIngest.JobState st = state == null ? null : parse(BulkIngest.JobState.class, state, "state");
        Page.PageRequest p = Lists.page(page, size);
        BulkIngest.Slice<BulkIngest.Job> s = call(() -> ingest.jobs(ctx.tenantId(), visible, st, p.firstResult(), p.size()));
        return Lists.ok(Page.of(s.items(), s.total(), false));
    }

    @GetMapping("/jobs/{id}")
    public BulkIngest.Job job(RequestContext ctx, @PathVariable String id) {
        return guarded(ctx, id);
    }

    /** Idempotent per sequence: the same content again is a replay; other content for a stored sequence is 409. */
    @PutMapping("/jobs/{id}/chunks/{seq}")
    public BulkIngest.ChunkReceipt upload(RequestContext ctx, @PathVariable String id, @PathVariable int seq, @RequestBody ChunkRequest body) {
        guarded(ctx, id);
        return call(() -> ingest.upload(ctx.tenantId(), id, seq, body == null ? null : body.rows(), ctx.principalId()));
    }

    @PostMapping("/jobs/{id}/close")
    public BulkIngest.Job close(RequestContext ctx, @PathVariable String id) {
        guarded(ctx, id);
        return call(() -> ingest.close(ctx.tenantId(), id, ctx.principalId()));
    }

    @PostMapping("/jobs/{id}/abort")
    public BulkIngest.Job abort(RequestContext ctx, @PathVariable String id) {
        guarded(ctx, id);
        return call(() -> ingest.abort(ctx.tenantId(), id, ctx.principalId()));
    }

    @GetMapping("/jobs/{id}/rows")
    public ResponseEntity<List<BulkIngest.RowStatus>> rows(RequestContext ctx, @PathVariable String id, @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        guarded(ctx, id);
        BulkIngest.RowState st = state == null ? null : parse(BulkIngest.RowState.class, state, "state");
        Page.PageRequest p = Lists.page(page, size);
        BulkIngest.Slice<BulkIngest.RowStatus> s = call(() -> ingest.rows(ctx.tenantId(), id, st, p.firstResult(), p.size()));
        return Lists.ok(Page.of(s.items(), s.total(), false));
    }

    /** The error report: failed rows with their code and text. */
    @GetMapping("/jobs/{id}/errors")
    public ResponseEntity<List<BulkIngest.RowStatus>> errors(RequestContext ctx, @PathVariable String id,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        guarded(ctx, id);
        Page.PageRequest p = Lists.page(page, size);
        BulkIngest.Slice<BulkIngest.RowStatus> s = call(() -> ingest.errors(ctx.tenantId(), id, p.firstResult(), p.size()));
        return Lists.ok(Page.of(s.items(), s.total(), false));
    }

    /** Loads the job in the caller's tenant (404 otherwise) and checks the permission of its object type. */
    private BulkIngest.Job guarded(RequestContext ctx, String id) {
        BulkIngest.Job job = call(() -> ingest.job(ctx.tenantId(), id));
        requireType(ctx, job.objectType());
        return job;
    }

    private void requireType(RequestContext ctx, String objectType) {
        BulkIngest.Sink sink = ingest.sink(objectType)
                .orElseThrow(() -> ApiProblem.badRequest("UNKNOWN_OBJECT_TYPE", "no bulk sink for object type " + objectType));
        permissions.require(ctx, sink.permission());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("INVALID_" + name.toUpperCase(java.util.Locale.ROOT), "unknown " + name + " " + value);
        }
    }

    static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (BulkIngest.BulkException e) {
            throw problem(e);
        }
    }

    static ApiProblem problem(BulkIngest.BulkException e) {
        return switch (e.kind()) {
            case NOT_FOUND -> new ApiProblem(404, e.code(), e.getMessage());
            case CONFLICT -> ApiProblem.conflict(e.code(), e.getMessage());
            case INVALID -> ApiProblem.badRequest(e.code(), e.getMessage());
            case LIMIT -> new ApiProblem(413, e.code(), "Bulk limit exceeded", e.getMessage(), null, null);
            case QUOTA -> new ApiProblem(429, e.code(), "Bulk quota exceeded", e.getMessage(), null, e.retryAfterSeconds());
        };
    }
}
