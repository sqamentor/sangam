// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.Objects;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import org.sangam.engine.trace.EngineTracing;

/**
 * M-062 (R13): the engine tracing port over the OpenTelemetry API the server already ships (spring-boot-starter-
 * opentelemetry). Off by default ({@code sangam.engine.tracing.enabled=false}); spans are exported only where the
 * server's OTLP export is configured (R56, air-gapped by default).
 *
 * <ul>
 *   <li>{@link Kind#COMMAND}: a span parented to the caller's current context (a synchronous continuation), a root
 *       when there is none.</li>
 *   <li>{@link Kind#JOB}: always a root span ({@code setNoParent}), never a child of whatever context the job
 *       executor thread carries.</li>
 *   <li>The span name is the engine's bounded operation name (a command class name), never ids or variables.</li>
 * </ul>
 * The engine catches every exception from this port, so a tracing failure never changes engine behaviour.
 */
public final class OpenTelemetryEngineTracing implements EngineTracing {

    /** Instrumentation scope name of the engine spans. */
    public static final String SCOPE = "org.sangam.engine";

    private final Tracer tracer;

    public OpenTelemetryEngineTracing(OpenTelemetry otel) {
        this.tracer = Objects.requireNonNull(otel, "otel").getTracer(SCOPE);
    }

    @Override
    public Span start(String operation, Kind kind) {
        var builder = tracer.spanBuilder(operation).setSpanKind(SpanKind.INTERNAL)
                .setAttribute("sangam.engine.kind", kind.name());
        if (kind == Kind.JOB) {
            builder.setNoParent();
        }
        io.opentelemetry.api.trace.Span span = builder.startSpan();
        return new Span() {
            @Override
            public void error(Throwable error) {
                // the exception class only: messages and stack traces may carry ids or business data (R13)
                span.setStatus(StatusCode.ERROR, error.getClass().getName());
            }

            @Override
            public void end() {
                span.end();
            }
        };
    }

    @Override
    public String toString() {
        return "OpenTelemetryEngineTracing[" + SCOPE + "]";
    }
}
