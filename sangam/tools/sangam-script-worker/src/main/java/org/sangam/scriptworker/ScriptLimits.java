// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * Resource limits of one script execution. The sandbox enforces CPU, memory, processes, file size and wall time
 * (cgroup v2 and rlimits); the worker enforces the wall time again from outside and caps what it reads back.
 *
 * @param cpuSeconds     CPU time (RLIMIT_CPU), default 5
 * @param wallTime       wall-clock time, default 10 s; the worker kills the jail after it as well
 * @param memoryBytes    cgroup memory.max of the jail, default 256 MiB
 * @param maxProcesses   cgroup pids.max of the jail, default 16
 * @param tmpBytes       size of the jail's private {@code /tmp} (tmpfs), default 16 MiB
 * @param stdoutBytes    largest result the worker reads back, default 1 MiB (more = failure, never truncation)
 * @param stderrBytes    script diagnostics kept for the incident, default 16 KiB (the rest is dropped)
 * @param sourceBytes    largest script accepted, default 256 KiB
 */
public record ScriptLimits(int cpuSeconds, Duration wallTime, long memoryBytes, int maxProcesses, long tmpBytes,
                           int stdoutBytes, int stderrBytes, int sourceBytes) {

    public static final ScriptLimits DEFAULTS = new ScriptLimits(5, Duration.ofSeconds(10), 256L << 20, 16, 16L << 20,
            1 << 20, 16 << 10, 256 << 10);

    public ScriptLimits {
        if (cpuSeconds < 1 || wallTime == null || wallTime.toMillis() < 100 || memoryBytes < (16L << 20)
                || maxProcesses < 1 || tmpBytes < 0 || stdoutBytes < 1 || stderrBytes < 0 || sourceBytes < 1) {
            throw new IllegalArgumentException("script limits out of range: cpu >= 1 s, wall >= 100 ms, memory >= 16 MiB,"
                    + " processes >= 1, output and source >= 1 byte");
        }
    }

    /**
     * Limits from the environment ({@code SANGAM_SCRIPT_CPU_SECONDS}, {@code SANGAM_SCRIPT_WALL_TIME} (ISO-8601,
     * e.g. PT10S), {@code SANGAM_SCRIPT_MEMORY_BYTES}, {@code SANGAM_SCRIPT_MAX_PROCESSES}, {@code SANGAM_SCRIPT_TMP_BYTES},
     * {@code SANGAM_SCRIPT_STDOUT_BYTES}, {@code SANGAM_SCRIPT_STDERR_BYTES}, {@code SANGAM_SCRIPT_SOURCE_BYTES}),
     * each defaulting to {@link #DEFAULTS}.
     */
    public static ScriptLimits fromEnv(Map<String, String> env) {
        Function<String, String> v = k -> {
            String s = env.get(k);
            return s == null || s.isBlank() ? null : s.strip();
        };
        ScriptLimits d = DEFAULTS;
        return new ScriptLimits(
                v.apply("SANGAM_SCRIPT_CPU_SECONDS") == null ? d.cpuSeconds() : Integer.parseInt(v.apply("SANGAM_SCRIPT_CPU_SECONDS")),
                v.apply("SANGAM_SCRIPT_WALL_TIME") == null ? d.wallTime() : Duration.parse(v.apply("SANGAM_SCRIPT_WALL_TIME")),
                v.apply("SANGAM_SCRIPT_MEMORY_BYTES") == null ? d.memoryBytes() : Long.parseLong(v.apply("SANGAM_SCRIPT_MEMORY_BYTES")),
                v.apply("SANGAM_SCRIPT_MAX_PROCESSES") == null ? d.maxProcesses() : Integer.parseInt(v.apply("SANGAM_SCRIPT_MAX_PROCESSES")),
                v.apply("SANGAM_SCRIPT_TMP_BYTES") == null ? d.tmpBytes() : Long.parseLong(v.apply("SANGAM_SCRIPT_TMP_BYTES")),
                v.apply("SANGAM_SCRIPT_STDOUT_BYTES") == null ? d.stdoutBytes() : Integer.parseInt(v.apply("SANGAM_SCRIPT_STDOUT_BYTES")),
                v.apply("SANGAM_SCRIPT_STDERR_BYTES") == null ? d.stderrBytes() : Integer.parseInt(v.apply("SANGAM_SCRIPT_STDERR_BYTES")),
                v.apply("SANGAM_SCRIPT_SOURCE_BYTES") == null ? d.sourceBytes() : Integer.parseInt(v.apply("SANGAM_SCRIPT_SOURCE_BYTES")));
    }
}
