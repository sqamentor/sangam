// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import java.sql.Connection;
import java.util.Optional;

import org.sangam.core.layer.Jdbc;

/**
 * M-062 (R13): the {@code JOB_TRACE_CONTEXT} side table (contract {@code W2-JOB-SIDE-TABLES-CONTRACT.md} §2). Plain
 * SQL on the caller's connection, in the caller's transaction; nothing here commits.
 */
public final class JobTraceContexts {

    /** {@code JOB_TRACE_CONTEXT.kind}. */
    public enum Kind { JOB, EXTERNAL_TASK }

    private JobTraceContexts() {
    }

    public static void insert(Connection c, String id, Kind kind, EngineTracing.TraceContext ctx, long createdAtMs) {
        Jdbc.update(c, "INSERT INTO JOB_TRACE_CONTEXT (job_id, kind, traceparent, tracestate, created_at_ms) "
                + "VALUES (?,?,?,?,?)", id, kind.name(), ctx.traceparent(), ctx.tracestate(), createdAtMs);
    }

    public static void delete(Connection c, String id) {
        Jdbc.update(c, "DELETE FROM JOB_TRACE_CONTEXT WHERE job_id = ?", id);
    }

    /**
     * The stored creating context of a job or external task ({@code id}); empty when none was stored. W2-L reads it
     * for the {@code traceparent} of a fetched worker task, W2-D for the link of a job span. A stored value that is no
     * longer valid is treated as absent.
     */
    public static Optional<EngineTracing.TraceContext> read(Connection c, String id) {
        return Jdbc.one(c, "SELECT traceparent, tracestate FROM JOB_TRACE_CONTEXT WHERE job_id = ?",
                        rs -> Optional.ofNullable(parse(rs.getString(1), rs.getString(2))), id)
                .flatMap(o -> o);
    }

    private static EngineTracing.TraceContext parse(String traceparent, String tracestate) {
        try {
            return new EngineTracing.TraceContext(traceparent, tracestate);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
