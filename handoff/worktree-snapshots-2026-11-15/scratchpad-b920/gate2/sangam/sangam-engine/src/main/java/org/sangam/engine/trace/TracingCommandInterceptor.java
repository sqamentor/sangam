// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import org.operaton.bpm.engine.impl.cmd.AcquireJobsCmd;
import org.operaton.bpm.engine.impl.cmd.ExecuteJobsCmd;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;

/**
 * M-062 (SANGAM.md R13): one span per engine command through {@link EngineTracing}. Registered as a <b>pre</b> command
 * interceptor (REQUIRED and REQUIRES_NEW) only when a tracing implementation is bound, so the span also covers the
 * command's flush and commit; a nested command gets a nested span. {@link ExecuteJobsCmd} (every job, timer and async
 * continuation) is {@link EngineTracing.Kind#JOB}; job acquisition ({@link AcquireJobsCmd}, the executor's idle poll) is
 * not traced, so an idle node emits no spans.
 *
 * <p>Every call into the port is guarded: a tracing failure is logged at debug level and never reaches the command,
 * whose result or exception is returned unchanged (R13, T49).
 */
public class TracingCommandInterceptor extends CommandInterceptor {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TracingCommandInterceptor.class);

    private final EngineTracing tracing;

    public TracingCommandInterceptor(EngineTracing tracing) {
        this.tracing = tracing;
    }

    @Override
    public <T> T execute(Command<T> command) {
        if (command instanceof AcquireJobsCmd) {
            return next.execute(command);
        }
        EngineTracing.Span span = start(command);
        try {
            return next.execute(command);
        } catch (RuntimeException | Error e) {
            if (span != null) {
                try {
                    span.error(e);
                } catch (RuntimeException tracingFailure) {
                    LOG.debug("M-062: tracing error() failed", tracingFailure);
                }
            }
            throw e;
        } finally {
            if (span != null) {
                try {
                    span.end();
                } catch (RuntimeException tracingFailure) {
                    LOG.debug("M-062: tracing end() failed", tracingFailure);
                }
            }
        }
    }

    private EngineTracing.Span start(Command<?> command) {
        try {
            return tracing.start(operation(command.getClass()),
                    command instanceof ExecuteJobsCmd ? EngineTracing.Kind.JOB : EngineTracing.Kind.COMMAND);
        } catch (RuntimeException tracingFailure) {
            LOG.debug("M-062: tracing start() failed", tracingFailure);
            return null;
        }
    }

    /**
     * The bounded operation name of a command class: its simple binary name ({@code ExecuteJobsCmd},
     * {@code OperatonEngineAdapter$3}); a lambda's hidden class is named after its declaring class
     * ({@code OperatonEngineAdapter$Lambda}) instead of the JVM's per-run suffix.
     */
    static String operation(Class<?> type) {
        String name = type.getName();
        int lambda = name.indexOf("$$Lambda");
        if (lambda >= 0) {
            name = name.substring(0, lambda) + "$Lambda";
        }
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
