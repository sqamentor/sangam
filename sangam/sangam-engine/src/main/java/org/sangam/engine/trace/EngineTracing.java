// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

/**
 * M-062 (SANGAM.md R13): the engine's tracing port. sangam-engine has no tracing library; a host binds an
 * implementation (for example over OpenTelemetry) through {@code SangamEngineSettings.tracing}. {@link #NONE} (the
 * default) means no interceptor is installed at all, and outbound telemetry stays off by default (R56).
 *
 * <p>Contract for implementations (R13):
 * <ul>
 *   <li>{@link Kind#COMMAND}: a child of the caller's current span (a synchronous continuation), or a root when there
 *       is none;</li>
 *   <li>{@link Kind#JOB}: a job, timer or async continuation run by the job executor. It must <b>not</b> be parented to
 *       whatever span the executor thread may carry; it is a root span, linked to the context that created the
 *       job when {@code traceLinks} stores it ({@link #start(String, Kind, TraceContext)});</li>
 *   <li>{@code operation} is a bounded name (a command class name, never ids, variables or business keys), so span
 *       names stay low-cardinality and carry no sensitive data;</li>
 *   <li>an implementation may throw: the engine catches every exception from this port, so an exporter or tracing
 *       failure never changes engine behaviour (R13).</li>
 * </ul>
 */
public interface EngineTracing {

    /** What the span covers. */
    enum Kind { COMMAND, JOB }

    /** An open span; ended exactly once by the engine. */
    interface Span {
        /** The command failed with {@code error} (called before {@link #end()}). */
        void error(Throwable error);

        void end();
    }

    /** Starts a span for one engine command. */
    Span start(String operation, Kind kind);

    /**
     * Starts a {@link Kind#JOB} root span linked to {@code link}, the context that created the job (M-062, stored in
     * {@code JOB_TRACE_CONTEXT}); {@code link} may be {@code null}. The default ignores the link.
     */
    default Span start(String operation, Kind kind, TraceContext link) {
        return start(operation, kind);
    }

    /**
     * The caller's current trace context, stored with jobs and external tasks created now ({@code traceLinks}), or
     * {@code null} when there is none. The default has none.
     */
    default TraceContext current() {
        return null;
    }

    /**
     * A W3C trace context (https://www.w3.org/TR/trace-context/): {@code traceparent} version {@code 00}
     * ({@code 00-<32 hex>-<16 hex>-<2 hex>}, 55 characters) and an optional {@code tracestate} of at most 512
     * characters. Only these header values are stored; never ids, variables or business keys.
     */
    record TraceContext(String traceparent, String tracestate) {
        private static final java.util.regex.Pattern TRACEPARENT =
                java.util.regex.Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

        public TraceContext {
            if (traceparent == null || !TRACEPARENT.matcher(traceparent).matches()
                    || traceparent.startsWith("00-00000000000000000000000000000000")
                    || traceparent.substring(36, 52).equals("0000000000000000")) {
                throw new IllegalArgumentException("not a valid W3C traceparent (version 00): " + traceparent);
            }
            // an empty or over-long tracestate is dropped (W3C: a vendor may discard tracestate it cannot keep)
            if (tracestate != null && (tracestate.isBlank() || tracestate.length() > 512)) {
                tracestate = null;
            }
        }
    }

    /** No tracing (default): the engine installs no tracing interceptor. */
    EngineTracing NONE = new EngineTracing() {
        @Override
        public Span start(String operation, Kind kind) {
            return null;
        }

        @Override
        public String toString() {
            return "EngineTracing.NONE";
        }
    };
}
