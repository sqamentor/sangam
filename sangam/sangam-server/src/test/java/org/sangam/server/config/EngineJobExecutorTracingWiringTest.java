// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.job.SangamJobExecutor;
import org.sangam.engine.trace.EngineTracing;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-054 / M-062 server wiring: the Sangam job executor replaces the Operaton starter's only when an executor option is
 * set (the starter's executor stays otherwise), sized from the engine settings; engine tracing is off by default and,
 * when on, writes command spans under the caller's span and job spans as roots (R13).
 */
class EngineJobExecutorTracingWiringTest {

    static MockEnvironment env() {
        MockEnvironment env = new MockEnvironment();
        env.setConversionService(new ApplicationConversionService());
        return env;
    }

    @Test
    void theSangamExecutorOnlyWithAnOption() {
        assertFalse(EngineWiring.JobExecutorOptionsCondition.optionsOn(env(), Map.of()), "default: the starter's executor");
        assertTrue(EngineWiring.JobExecutorOptionsCondition.optionsOn(
                env().withProperty("sangam.engine.job-virtual-threads", "true"), Map.of()));
        assertTrue(EngineWiring.JobExecutorOptionsCondition.optionsOn(
                env().withProperty("sangam.engine.job-bulkheads.timer-transition", "4"), Map.of()));
        assertTrue(EngineWiring.JobExecutorOptionsCondition.optionsOn(env(), Map.of("SANGAM_JOB_VIRTUAL_THREADS", "true")),
                "the engine's own env switch");
        assertFalse(EngineWiring.JobExecutorOptionsCondition.optionsOn(
                env().withProperty("sangam.engine.job-virtual-threads", "true"), Map.of("SANGAM_JOB_VIRTUAL_THREADS", "false")),
                "env wins over the property, as in SangamEngineSettings.applyEnvironment");
        assertFalse(EngineWiring.JobExecutorOptionsCondition.optionsOn(
                env().withProperty("sangam.engine.apply-environment", "false"), Map.of("SANGAM_JOB_VIRTUAL_THREADS", "true")),
                "env ignored when the settings ignore it");
    }

    @Test
    void theExecutorCarriesTheOptionsAndTheSettingsSizing() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setJobVirtualThreads(true);
        s.setJobBulkheads(Map.of("timer-transition", 3));
        s.setJobBulkheadWait(Duration.ofSeconds(2));
        s.setJobCorePoolSize(7);
        s.setJobMaxPoolSize(9);
        s.setJobQueueCapacity(11);
        s.setJobMaxJobsPerAcquisition(13);
        s.setJobLockTimeInMillis(60_000);
        SangamJobExecutor je = EngineWiring.jobExecutor(s);
        assertTrue(je.isVirtualThreads());
        assertTrue(je.bulkheads() != null, "bulkheads installed");
        assertEquals(7, je.getCorePoolSize());
        assertEquals(9, je.getMaxPoolSize());
        assertEquals(11, je.getQueueSize());
        assertEquals(13, je.getMaxJobsPerAcquisition());
        assertEquals(60_000, je.getLockTimeInMillis());
    }

    @Test
    void tracingIsOffByDefaultAndRefusedWithoutOpenTelemetry() {
        assertSame(EngineTracing.NONE, EngineWiring.tracing(env(), null));
        assertThrows(IllegalStateException.class,
                () -> EngineWiring.tracing(env().withProperty("sangam.tracing.engine.enabled", "true"), null));
    }

    @Test
    void commandSpansJoinTheCallerAndJobSpansAreRoots() {
        List<SpanData> ended = new CopyOnWriteArrayList<>();
        SpanProcessor collect = new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
            }

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnd(ReadableSpan span) {
                ended.add(span.toSpanData());
            }

            @Override
            public boolean isEndRequired() {
                return true;
            }
        };
        try (OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(collect).build()).build()) {
            EngineTracing tracing = assertInstanceOf(OpenTelemetryEngineTracing.class,
                    EngineWiring.tracing(env().withProperty("sangam.tracing.engine.enabled", "true"), otel));
            io.opentelemetry.api.trace.Span request = otel.getTracer("test").spanBuilder("request").startSpan();
            try (Scope ignored = request.makeCurrent()) {
                EngineTracing.Span command = tracing.start("StartProcessInstanceCmd", EngineTracing.Kind.COMMAND);
                command.end();
                EngineTracing.Span job = tracing.start("ExecuteJobsCmd", EngineTracing.Kind.JOB);
                job.error(new IllegalStateException("case 4711 failed"));
                job.end();
            } finally {
                request.end();
            }
            SpanData command = ended.stream().filter(d -> d.getName().equals("StartProcessInstanceCmd")).findFirst().orElseThrow();
            SpanData job = ended.stream().filter(d -> d.getName().equals("ExecuteJobsCmd")).findFirst().orElseThrow();
            assertEquals(request.getSpanContext().getSpanId(), command.getParentSpanId(), "a command is the caller's child");
            assertFalse(job.getParentSpanContext().isValid(), "a job span is a root");
            assertEquals(StatusCode.ERROR, job.getStatus().getStatusCode());
            assertFalse(job.getStatus().getDescription().contains("4711"), "no business data from the message");
            assertTrue(job.getEvents().isEmpty(), "no exception event with message or stack trace");
        }
    }
}
