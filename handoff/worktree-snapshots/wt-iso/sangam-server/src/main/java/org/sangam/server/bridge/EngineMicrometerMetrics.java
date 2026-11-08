// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.sangam.core.engine.EngineMetrics;

/**
 * {@link EngineMetrics} on Micrometer (sangam-engine README notes 4 and 17, R13). Prometheus names:
 * {@code sangam_engine_*}, plus the deploy dashboard's {@code sangam_jobs_acquired_total} and
 * {@code sangam_jobs_failed_total{cause}}.
 * Tags are bounded: process definition keys, command names and topics come from deployed models, not from users.
 */
public final class EngineMicrometerMetrics implements EngineMetrics {

    private final MeterRegistry registry;

    public EngineMicrometerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void rootLockWait(String processDefinitionKey, long nanos) {
        Timer.builder("sangam.engine.root.lock.wait").tag("process", tag(processDefinitionKey))
                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void commandRetry(String command) {
        counter("sangam.engine.command.retry", "command", command);
    }

    @Override
    public void commandRetryExhausted(String command) {
        counter("sangam.engine.command.retry.exhausted", "command", command);
    }

    @Override
    public void fetchRetry(String topic) {
        counter("sangam.engine.fetch.retry", "topic", topic);
    }

    @Override
    public void leaseExpired(String topic, String decision) {
        Counter.builder("sangam.engine.lease.expired").tag("topic", tag(topic)).tag("decision", tag(decision))
                .register(registry).increment();
    }

    @Override
    public void timerSuppressed(String processDefinitionKey, String reason) {
        Counter.builder("sangam.engine.timer.suppressed").tag("process", tag(processDefinitionKey))
                .tag("reason", tag(reason)).register(registry).increment();
    }

    /** Note 17: Prometheus {@code sangam_jobs_acquired_total} (job executor and {@code executeDueJobs}). */
    @Override
    public void jobsAcquired(int count) {
        if (count > 0) {
            Counter.builder("sangam.jobs.acquired").description("jobs acquired for execution")
                    .register(registry).increment(count);
        }
    }

    /** Note 17: Prometheus {@code sangam_jobs_failed_total{cause}}; the cause is the exception class simple name. */
    @Override
    public void jobFailed(String cause) {
        Counter.builder("sangam.jobs.failed").description("job executions that failed")
                .tag("cause", tag(simpleName(cause))).register(registry).increment();
    }

    private static String simpleName(String cause) {
        if (cause == null) {
            return null;
        }
        int dot = cause.lastIndexOf('.');
        return dot < 0 ? cause : cause.substring(dot + 1);
    }

    @Override
    public void jobExecutionBounded(String reason) {
        counter("sangam.engine.job.execution.bounded", "reason", reason);
    }

    private void counter(String name, String tagKey, String tagValue) {
        Counter.builder(name).tag(tagKey, tag(tagValue)).register(registry).increment();
    }

    private static String tag(String v) {
        return v == null || v.isBlank() ? "none" : v;
    }
}
