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
 *       whatever span the executor thread may carry; it is a root span (linked to its creator once the creating
 *       trace context is stored with the job, which this release does not do);</li>
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
