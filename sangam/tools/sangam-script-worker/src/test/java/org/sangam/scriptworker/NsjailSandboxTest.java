// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The isolation flags of the jail command line; the escape tests prove them in the image. */
class NsjailSandboxTest {

    private static List<String> cmd(Language l) {
        return NsjailSandbox.defaults().command(l, ScriptLimits.DEFAULTS);
    }

    private static String after(List<String> c, String flag) {
        int i = c.indexOf(flag);
        assertTrue(i >= 0 && i + 1 < c.size(), flag + " missing");
        return c.get(i + 1);
    }

    @Test
    void nothingThatWeakensTheJailIsEverPassed() {
        for (Language l : Language.values()) {
            List<String> c = cmd(l);
            for (String forbidden : List.of("--disable_clone_newnet", "-N", "--keep_env", "-e", "--chroot", "-c",
                    "--disable_clone_newpid", "--disable_clone_newns", "--disable_clone_newuser", "--keep_caps",
                    "--disable_no_new_privs", "--bindmount", "-B", "--disable_proc", "--proc_rw")) {
                assertFalse(c.contains(forbidden), l + ": " + forbidden);
            }
            assertTrue(c.contains("--iface_no_lo"));
            assertTrue(c.contains("--use_cgroupv2"));
            assertTrue(c.contains("--really_quiet"));
        }
    }

    @Test
    void limitsReachTheJail() {
        List<String> c = cmd(Language.PYTHON);
        assertEquals("o", after(c, "--mode"));
        assertEquals("65534", after(c, "--user"));
        assertEquals("65534", after(c, "--group"));
        assertEquals("10", after(c, "--time_limit"));
        assertEquals("5", after(c, "--rlimit_cpu"));
        assertEquals(Long.toString(256L << 20), after(c, "--cgroup_mem_max"));
        assertEquals("16", after(c, "--cgroup_pids_max"));
        assertEquals(Long.toString(16L << 20), after(c, "--tmpfs_size"));
        assertEquals("0", after(c, "--rlimit_core"));
        assertEquals(NsjailSandbox.DEFAULT_POLICY.toString(), after(c, "--seccomp_policy"));
    }

    @Test
    void everyMountIsReadOnlyExceptThePrivateTmp() {
        List<String> c = cmd(Language.JAVASCRIPT);
        assertEquals(List.of("/usr", "/etc/ld.so.cache", NsjailSandbox.DEFAULT_RUNTIME + ":/runtime"), values(c, "--bindmount_ro"));
        assertEquals(List.of("/tmp"), values(c, "--tmpfsmount"));
    }

    @Test
    void onlyTheNamedEnvironmentEntersTheJail() {
        List<String> py = cmd(Language.PYTHON);
        assertEquals(List.of("HOME=/tmp", "LANG=C.UTF-8", "TZ=UTC", "PYTHONDONTWRITEBYTECODE=1", "PYTHONHASHSEED=0"),
                values(py, "--env"));
        for (String e : values(cmd(Language.JAVASCRIPT), "--env")) {
            assertFalse(e.startsWith("SANGAM_"), e);
        }
    }

    @Test
    void theInterpreterRunsTheHarnessAfterTheSeparator() {
        List<String> py = cmd(Language.PYTHON);
        assertEquals(List.of("--", "/usr/bin/python3", "-I", "-S", "/runtime/runner.py"),
                py.subList(py.indexOf("--"), py.size()));
        List<String> js = cmd(Language.JAVASCRIPT);
        assertEquals(List.of("--", "/usr/bin/node", "--max-old-space-size=192", "/runtime/runner.js"),
                js.subList(js.indexOf("--"), js.size()));
    }

    @Test
    void aSubSecondWallLimitRoundsUp() {
        ScriptLimits l = new ScriptLimits(1, Duration.ofMillis(1500), 64L << 20, 4, 0, 1, 0, 1);
        assertEquals("2", after(NsjailSandbox.defaults().command(Language.PYTHON, l), "--time_limit"));
    }

    @Test
    void limitsComeFromTheEnvironment() {
        ScriptLimits l = ScriptLimits.fromEnv(Map.of("SANGAM_SCRIPT_WALL_TIME", "PT3S", "SANGAM_SCRIPT_MAX_PROCESSES", "4"));
        assertEquals(Duration.ofSeconds(3), l.wallTime());
        assertEquals(4, l.maxProcesses());
        assertEquals(ScriptLimits.DEFAULTS.memoryBytes(), l.memoryBytes());
        assertEquals(ScriptLimits.DEFAULTS, ScriptLimits.fromEnv(Collections.emptyMap()));
    }

    private static List<String> values(List<String> c, String flag) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < c.size() && !c.get(i).equals("--"); i++) {
            if (c.get(i).equals(flag)) {
                out.add(c.get(i + 1));
            }
        }
        return out;
    }
}
