// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cmd.AcquireJobsCmd;
import org.operaton.bpm.engine.impl.cmd.ExecuteJobsCmd;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandExecutor;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.jobexecutor.DefaultJobExecutor;
import org.operaton.bpm.engine.impl.jobexecutor.JobFailureCollector;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * M-062 (SANGAM.md R13): engine spans through the {@link EngineTracing} port. One span per command with a bounded
 * name; job execution is {@link EngineTracing.Kind#JOB}; the acquisition poll is not traced; a failing tracer never
 * changes the command's result or exception; with {@link EngineTracing#NONE} (default) no interceptor is installed.
 */
class TracingCommandInterceptorTest {

    /** Records start/error/end; can be told to throw from any call. */
    static final class Recorder implements EngineTracing {
        final List<String> events = new ArrayList<>();
        boolean failStart;
        boolean failEnd;

        @Override
        public Span start(String operation, Kind kind) {
            events.add("start " + kind + " " + operation);
            if (failStart) {
                throw new IllegalStateException("exporter down");
            }
            return new Span() {
                @Override
                public void error(Throwable error) {
                    events.add("error " + error.getMessage());
                    if (failEnd) {
                        throw new IllegalStateException("exporter down");
                    }
                }

                @Override
                public void end() {
                    events.add("end " + operation);
                    if (failEnd) {
                        throw new IllegalStateException("exporter down");
                    }
                }
            };
        }
    }

    /** A plain command class with a stable name. */
    static final class PingCmd implements Command<String> {
        @Override
        public String execute(org.operaton.bpm.engine.impl.interceptor.CommandContext commandContext) {
            return "pong";
        }
    }

    private static TracingCommandInterceptor interceptor(EngineTracing tracing, CommandExecutor next) {
        TracingCommandInterceptor i = new TracingCommandInterceptor(tracing);
        i.setNext(next);
        return i;
    }

    /** The end of the chain: runs the command without a context (the commands used here do not need one). */
    private static CommandExecutor runner(RuntimeException failure) {
        return new CommandInterceptor() {
            @Override
            public <T> T execute(Command<T> command) {
                if (failure != null) {
                    throw failure;
                }
                return command instanceof PingCmd p ? (T) p.execute(null) : null;
            }
        };
    }

    @Test
    void aCommandGetsOneSpanWithItsClassName() {
        Recorder r = new Recorder();
        assertEquals("pong", interceptor(r, runner(null)).execute(new PingCmd()));
        assertEquals(List.of("start COMMAND TracingCommandInterceptorTest$PingCmd", "end TracingCommandInterceptorTest$PingCmd"),
                r.events);
    }

    @Test
    void jobExecutionIsAJobSpanAndAcquisitionIsNotTraced() {
        Recorder r = new Recorder();
        interceptor(r, runner(null)).execute(new ExecuteJobsCmd("job-1", new JobFailureCollector("job-1")));
        assertEquals("start JOB ExecuteJobsCmd", r.events.get(0));
        r.events.clear();
        interceptor(r, runner(null)).execute(new AcquireJobsCmd(new DefaultJobExecutor()));
        assertTrue(r.events.isEmpty(), "the idle acquisition poll emits no span: " + r.events);
    }

    @Test
    void aFailingCommandIsRecordedAndItsExceptionIsUnchanged() {
        Recorder r = new Recorder();
        IllegalArgumentException boom = new IllegalArgumentException("boom");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> interceptor(r, runner(boom)).execute(new PingCmd()));
        assertSame(boom, thrown);
        assertEquals(List.of("start COMMAND TracingCommandInterceptorTest$PingCmd", "error boom",
                "end TracingCommandInterceptorTest$PingCmd"), r.events);
    }

    @Test
    void aFailingTracerNeverChangesTheCommand() {
        Recorder startFails = new Recorder();
        startFails.failStart = true;
        assertEquals("pong", interceptor(startFails, runner(null)).execute(new PingCmd()), "start() throws: command runs");

        Recorder endFails = new Recorder();
        endFails.failEnd = true;
        assertEquals("pong", interceptor(endFails, runner(null)).execute(new PingCmd()), "end() throws: result kept");
        IllegalArgumentException boom = new IllegalArgumentException("boom");
        assertSame(boom, assertThrows(IllegalArgumentException.class,
                () -> interceptor(endFails, runner(boom)).execute(new PingCmd())), "error()/end() throw: exception kept");

        EngineTracing nullSpans = (operation, kind) -> null;
        assertEquals("pong", interceptor(nullSpans, runner(null)).execute(new PingCmd()), "a null span is tolerated");
    }

    @Test
    void operationNamesAreBounded() {
        Command<String> lambda = c -> "x";
        String name = TracingCommandInterceptor.operation(lambda.getClass());
        assertEquals("TracingCommandInterceptorTest$Lambda", name, "no per-run hidden-class suffix");
        assertEquals("ExecuteJobsCmd", TracingCommandInterceptor.operation(ExecuteJobsCmd.class));
        Command<String> anonymous = new Command<>() {
            @Override
            public String execute(org.operaton.bpm.engine.impl.interceptor.CommandContext commandContext) {
                return null;
            }
        };
        assertTrue(TracingCommandInterceptor.operation(anonymous.getClass()).matches("TracingCommandInterceptorTest\\$\\d+"));
    }

    // ------------------------------------------------------------------ engine wiring (H2)

    private static void withEngine(Consumer<SangamEngineSettings> config, Consumer<OperatonEngineAdapter> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m062" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        config.accept(s);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            test.accept(a);
        }
    }

    private static boolean installed(List<CommandInterceptor> list) {
        return list != null && list.stream().anyMatch(i -> i instanceof TracingCommandInterceptor);
    }

    @Test
    void byDefaultNoTracingInterceptorIsInstalled() {
        assertSame(EngineTracing.NONE, new SangamEngineSettings().getTracing());
        withEngine(s -> { }, a -> {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine().getProcessEngineConfiguration();
            assertFalse(installed(cfg.getCustomPreCommandInterceptorsTxRequired()));
            assertFalse(installed(cfg.getCustomPreCommandInterceptorsTxRequiresNew()));
        });
    }

    @Test
    void aBoundTracerSeesTheEngineCommandsAndAFailingOneDoesNotStopTheEngine() {
        Recorder r = new Recorder();
        r.failEnd = true; // an exporter failing on every span
        withEngine(s -> s.setTracing(r), a -> {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine().getProcessEngineConfiguration();
            assertTrue(installed(cfg.getCustomPreCommandInterceptorsTxRequired()));
            assertTrue(installed(cfg.getCustomPreCommandInterceptorsTxRequiresNew()));
            r.events.clear();
            a.deploy("t1", "m062", Map.of("m062.bpmn", ("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                      <process id="m062" isExecutable="true">
                        <startEvent id="s"/><sequenceFlow id="f" sourceRef="s" targetRef="e"/><endEvent id="e"/>
                      </process>
                    </definitions>
                    """).getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "m062", "M062-1", Map.of());
            assertTrue(pid != null && !pid.isBlank(), "the engine works although every span end fails");
            assertTrue(r.events.stream().anyMatch(e -> e.startsWith("start COMMAND ")), "commands traced: " + r.events);
            assertTrue(r.events.stream().noneMatch(e -> e.contains(pid)), "no ids in span names");
        });
    }
}
