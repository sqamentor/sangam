// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The isolation of owner decision 53 (Q-07 option A, ADR-D-8 script sandbox): every script runs as a child process
 * under nsjail (Apache-2.0), in one container that needs only Docker or Podman on a VM.
 *
 * <ul>
 *   <li><b>No network:</b> nsjail's default new network namespace, without even a loopback interface
 *       ({@code --iface_no_lo}); there is no flag here that keeps the host network.</li>
 *   <li><b>Own PID, mount, IPC, UTS and user namespaces</b> (nsjail defaults): the script cannot see or signal the
 *       worker, and runs as the unprivileged {@code nobody} (65534) inside.</li>
 *   <li><b>A minimal read-only file system:</b> no {@code --chroot}, so the root is an empty tmpfs that holds only
 *       {@code /usr} (interpreters and libraries), the harness directory and {@code /etc/ld.so.cache}, all read-only,
 *       plus a private size-limited {@code /tmp}. Nothing of the worker (its token, its configuration) is mounted.</li>
 *   <li><b>A clean environment:</b> nsjail passes no variable of the worker unless told to; only the few below.</li>
 *   <li><b>seccomp</b> ({@code --seccomp_policy}, kafel): kills the process on ptrace, mount, namespace, module,
 *       keyring, bpf and other kernel-surface calls (deploy/docker/script-worker/sangam-script.kafel).</li>
 *   <li><b>cgroup v2 limits</b> on memory, processes and CPU share, plus rlimits on CPU time, file size and open files,
 *       and a wall-clock limit; one CPU.</li>
 * </ul>
 * The flags are those of nsjail 3.4; the escape tests ({@code ScriptSandboxEscapeIT}) prove them in the image.
 */
public final class NsjailSandbox implements Sandbox {

    /** Where the image puts things (deploy/docker/script-worker.Dockerfile). */
    public static final Path DEFAULT_NSJAIL = Path.of("/usr/local/bin/nsjail");
    public static final Path DEFAULT_RUNTIME = Path.of("/opt/sangam/script-runtime");
    public static final Path DEFAULT_POLICY = Path.of("/opt/sangam/script-runtime/sangam-script.kafel");

    private final Path nsjail;
    private final Path runtimeDir;
    private final Path seccompPolicy;
    private final String node;
    private final String python;

    public NsjailSandbox(Path nsjail, Path runtimeDir, Path seccompPolicy, String node, String python) {
        this.nsjail = nsjail;
        this.runtimeDir = runtimeDir;
        this.seccompPolicy = seccompPolicy;
        this.node = node;
        this.python = python;
    }

    public static NsjailSandbox defaults() {
        return new NsjailSandbox(DEFAULT_NSJAIL, DEFAULT_RUNTIME, DEFAULT_POLICY, "/usr/bin/node", "/usr/bin/python3");
    }

    @Override
    public List<String> command(Language language, ScriptLimits limits) {
        long memMiB = Math.max(16, limits.memoryBytes() >> 20);
        List<String> c = new ArrayList<>(List.of(nsjail.toString(),
                "--mode", "o",
                "--really_quiet",
                "--hostname", "sandbox",
                "--user", "65534", "--group", "65534",
                "--iface_no_lo",
                "--max_cpus", "1",
                "--time_limit", Long.toString(Math.max(1, (limits.wallTime().toMillis() + 999) / 1000)),
                "--rlimit_cpu", Integer.toString(limits.cpuSeconds()),
                "--rlimit_as", "inf",
                "--rlimit_fsize", Long.toString(Math.max(1, limits.tmpBytes() >> 20)),
                "--rlimit_nofile", "64",
                "--rlimit_core", "0",
                "--use_cgroupv2",
                "--cgroup_mem_max", Long.toString(limits.memoryBytes()),
                "--cgroup_pids_max", Integer.toString(limits.maxProcesses()),
                "--cgroup_cpu_ms_per_sec", "1000",
                "--seccomp_policy", seccompPolicy.toString(),
                "--bindmount_ro", "/usr",
                "--bindmount_ro", "/etc/ld.so.cache",
                "--bindmount_ro", runtimeDir + ":/runtime",
                "--symlink", "/usr/bin:/bin",
                "--symlink", "/usr/lib:/lib",
                "--symlink", "/usr/lib64:/lib64",
                "--tmpfsmount", "/tmp",
                "--tmpfs_size", Long.toString(limits.tmpBytes()),
                "--cwd", "/tmp",
                "--env", "HOME=/tmp",
                "--env", "LANG=C.UTF-8",
                "--env", "TZ=UTC"));
        if (language == Language.PYTHON) {
            c.addAll(List.of("--env", "PYTHONDONTWRITEBYTECODE=1", "--env", "PYTHONHASHSEED=0",
                    "--", python, "-I", "-S", "/runtime/" + language.harness()));
        } else {
            // V8's heap stays below the cgroup limit, so an oversized script fails with a JS error, not a kill
            c.addAll(List.of("--", node, "--max-old-space-size=" + Math.max(8, memMiB * 3 / 4),
                    "/runtime/" + language.harness()));
        }
        return List.copyOf(c);
    }
}
