// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.impl.ProcessEngineImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutorContext;
import org.operaton.bpm.engine.impl.jobexecutor.JobFailureCollector;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.trace.EngineTracing;
import org.sangam.engine.trace.JobTraceContexts;

/**
 * M-062 links (R13) against contract {@code W2-JOB-SIDE-TABLES-CONTRACT.md} §2, on H2 with the contract DDL: with trace
 * links on, the creating trace context is stored with each new job and external task in the creating transaction;
 * a job then runs in a JOB root span linked to it, with its {@code ExecuteJobsCmd} as a child command span; the rows go
 * with the job / external task. Off by default, and nothing is stored without a bound tracer.
 */
class TraceLinksH2Test {

    static final EngineTracing.TraceContext CREATOR =
            new EngineTracing.TraceContext("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=x");

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="m062l" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <serviceTask id="work" operaton:asyncBefore="true" operaton:expression="${true}"/>
                <sequenceFlow id="f2" sourceRef="work" targetRef="worker"/>
                <serviceTask id="worker" operaton:type="external" operaton:topic="m062-topic"/>
                <sequenceFlow id="f3" sourceRef="worker" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    /** Records spans; its current context is {@link #CREATOR}. */
    static final class Recorder implements EngineTracing {
        final List<String> events = new ArrayList<>();
        EngineTracing.TraceContext lastLink;

        @Override
        public Span start(String operation, Kind kind) {
            events.add(kind + " " + operation);
            return new Span() {
                @Override
                public void error(Throwable error) {
                }

                @Override
                public void end() {
                }
            };
        }

        @Override
        public Span start(String operation, Kind kind, TraceContext link) {
            lastLink = link;
            return start(operation, kind);
        }

        @Override
        public TraceContext current() {
            return CREATOR;
        }
    }

    private static Optional<EngineTracing.TraceContext> stored(JdbcDataSource ds, String id) throws Exception {
        try (Connection c = ds.getConnection()) {
            return JobTraceContexts.read(c, id);
        }
    }

    private static String kind(JdbcDataSource ds, String id) throws Exception {
        try (Connection c = ds.getConnection()) {
            return Jdbc.one(c, "SELECT kind FROM JOB_TRACE_CONTEXT WHERE job_id = ?", rs -> rs.getString(1), id).orElse(null);
        }
    }

    @Test
    void offByDefault() {
        assertFalse(new SangamEngineSettings().isTraceLinks());
    }

    @Test
    void creatingContextIsStoredLinkedAndRemoved() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m062l" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            JobSideTablesDdl.create(c);
        }
        Recorder r = new Recorder();
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setTracing(r);
        s.setTraceLinks(true);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine().getProcessEngineConfiguration();
            SangamJobExecutor je = (SangamJobExecutor) cfg.getJobExecutor();
            assertSame(r, je.linkedTracing(), "trace links need the Sangam executor to open linked job spans");
            a.deploy("t1", "m062l", Map.of("m062l.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "m062l", "M062L-1", Map.of());
            Job job = a.processEngine().getManagementService().createJobQuery().processInstanceId(pid).singleResult();
            assertEquals(Optional.of(CREATOR), stored(ds, job.getId()), "stored in the creating transaction");
            assertEquals("JOB", kind(ds, job.getId()));

            r.events.clear();
            SangamExecuteJobsRunnable runnable = new SangamExecuteJobsRunnable(List.of(job.getId()),
                    (ProcessEngineImpl) a.processEngine(), null, je.linkedTracing());
            Context.setJobExecutorContext(new JobExecutorContext());
            try {
                runnable.executeJob(job.getId(), cfg.getCommandExecutorTxRequired(), new JobFailureCollector(job.getId()));
            } finally {
                Context.removeJobExecutorContext();
            }
            assertEquals(CREATOR, r.lastLink, "the job span links to the creating context");
            assertEquals("JOB async-continuation", r.events.stream().filter(e -> e.startsWith("JOB")).findFirst().orElseThrow());
            assertEquals(1, r.events.stream().filter(e -> e.startsWith("JOB")).count(), "one job root span per job");
            assertTrue(r.events.contains("COMMAND ExecuteJobsCmd"), "the job's command is a child span: " + r.events);
            assertTrue(stored(ds, job.getId()).isEmpty(), "removed with the executed job");

            LockedExternalTask task = a.processEngine().getExternalTaskService().fetchAndLock(1, "w1")
                    .topic("m062-topic", 60_000).execute().get(0);
            assertEquals(Optional.of(CREATOR), stored(ds, task.getId()), "worker tasks carry the creating context too");
            assertEquals("EXTERNAL_TASK", kind(ds, task.getId()));
            a.processEngine().getExternalTaskService().complete(task.getId(), "w1");
            assertTrue(stored(ds, task.getId()).isEmpty(), "removed with the completed external task");
        }
    }

    @Test
    void traceContextValidation() {
        assertThrows(IllegalArgumentException.class, () -> new EngineTracing.TraceContext("garbage", null));
        assertThrows(IllegalArgumentException.class, () -> new EngineTracing.TraceContext(
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01", null), "all-zero trace id");
        assertThrows(IllegalArgumentException.class, () -> new EngineTracing.TraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01", null), "all-zero span id");
        assertNull(new EngineTracing.TraceContext(CREATOR.traceparent(), " ").tracestate(), "blank tracestate dropped");
        assertNull(new EngineTracing.TraceContext(CREATOR.traceparent(), "x".repeat(513)).tracestate(), "over 512 dropped");
        assertEquals("vendor=x", CREATOR.tracestate());
    }
}
