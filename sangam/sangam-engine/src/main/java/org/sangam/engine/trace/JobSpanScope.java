// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

/**
 * M-062: marks that the job executor thread has opened the {@link EngineTracing.Kind#JOB} root span of the job it is
 * running (with its link), so {@link TracingCommandInterceptor} makes the job's {@code ExecuteJobsCmd} a child command
 * span instead of a second job root. Thread-confined; always cleared in a {@code finally}.
 */
public final class JobSpanScope {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private JobSpanScope() {
    }

    public static void enter() {
        ACTIVE.set(Boolean.TRUE);
    }

    public static void exit() {
        ACTIVE.remove();
    }

    public static boolean active() {
        return ACTIVE.get() != null;
    }
}
