// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Escape tests of the script sandbox (ADR-D-8, owner decision 53): real nsjail, node and python3, so they run only in
 * the script-worker image ({@code mvn -f tools/sangam-script-worker/pom.xml -Pscript-sandbox verify} inside it). Each
 * test is an attack a hostile script would try; the assertion is that it fails and the worker is unharmed.
 *
 * <p>Outside the image the test fails in {@link #theImageHasTheSandbox()} instead of passing silently: the profile is
 * meant to be run only there.
 */
class ScriptSandboxEscapeIT {

    private static final ScriptLimits LIMITS = new ScriptLimits(2, java.time.Duration.ofSeconds(4), 64L << 20, 8,
            4L << 20, 64 << 10, 16 << 10, 64 << 10);
    private static final ScriptRunner RUNNER = new ScriptRunner(NsjailSandbox.defaults(), LIMITS);

    @BeforeAll
    static void theImageHasTheSandbox() {
        for (var p : List.of(NsjailSandbox.DEFAULT_NSJAIL, NsjailSandbox.DEFAULT_POLICY,
                NsjailSandbox.DEFAULT_RUNTIME.resolve("runner.js"), NsjailSandbox.DEFAULT_RUNTIME.resolve("runner.py"))) {
            assertTrue(Files.isRegularFile(p), "run the escape tests inside the script-worker image: missing " + p);
        }
    }

    private static ScriptOutcome py(String script) {
        return RUNNER.run(new ScriptRequest(Language.PYTHON, script, "out", Map.of("a", 2)), Map.of(), () -> null);
    }

    private static ScriptOutcome js(String script) {
        return RUNNER.run(new ScriptRequest(Language.JAVASCRIPT, script, "out", Map.of("a", 2)), Map.of(), () -> null);
    }

    private static Object value(ScriptOutcome o) {
        return assertInstanceOf(ScriptOutcome.Completed.class, o, String.valueOf(o)).variables().get("out");
    }

    // ---- the protocol works at all (otherwise every "it failed" below proves nothing) ----

    @Test
    void ordinaryScriptsRun() {
        assertEquals(4, ((Number) value(js("a * 2"))).intValue());
        assertEquals(new BigDecimal("0.30"), value(py("from decimal import Decimal\nDecimal('0.10') * 3")));
        ScriptOutcome.Completed c = assertInstanceOf(ScriptOutcome.Completed.class,
                js("execution.setVariable('total', a + 1)"));
        assertEquals(3, ((Number) c.variables().get("total")).intValue());
        ScriptOutcome.BpmnErrorRaised b = assertInstanceOf(ScriptOutcome.BpmnErrorRaised.class,
                py("raise BpmnError('REJECTED', 'no', {'why': 'x'})"));
        assertEquals("REJECTED", b.code());
    }

    // ---- network ----

    @Test
    void noNetworkAtAll() {
        assertInstanceOf(ScriptOutcome.ScriptError.class,
                py("import socket\nsocket.create_connection(('1.1.1.1', 53), 2)"));
        assertInstanceOf(ScriptOutcome.ScriptError.class, py("import socket\nsocket.getaddrinfo('example.org', 443)"));
        assertInstanceOf(ScriptOutcome.ScriptError.class,
                py("import socket\ns = socket.socket()\ns.bind(('127.0.0.1', 0))"), "not even a loopback interface");
        assertEquals(List.of(), value(py("import socket\n[n for i, n in socket.if_nameindex() if n != 'lo']")));
    }

    // ---- file system ----

    @Test
    void onlyThePrivateTmpIsWritable() {
        for (String path : List.of("/usr/evil", "/runtime/runner.py", "/etc/evil", "/evil")) {
            assertInstanceOf(ScriptOutcome.ScriptError.class, py("open('" + path + "', 'w').write('x')"), path);
        }
        assertEquals("x", value(py("open('/tmp/f', 'w').write('x')\nopen('/tmp/f').read()")));
    }

    @Test
    void secretsOfTheHostAndTheWorkerAreNotThere() {
        for (String path : List.of("/etc/shadow", "/etc/passwd", "/root", "/home", "/opt/sangam", "/var/run/secrets",
                "/run/secrets", "/var/run/docker.sock")) {
            assertEquals(Boolean.FALSE, value(py("import os\nos.path.exists('" + path + "')")), path);
        }
        assertEquals(Boolean.FALSE, value(py("'SANGAM' in open('/proc/1/environ').read()")),
                "pid 1 of the jail is the interpreter, not the worker");
    }

    @Test
    void theWorkerEnvironmentDoesNotEnterTheJail() {
        Object env = value(py("import os\nsorted(os.environ)"));
        assertTrue(env instanceof List<?> l && l.stream().noneMatch(k -> String.valueOf(k).startsWith("SANGAM_")
                || String.valueOf(k).contains("TOKEN") || String.valueOf(k).contains("SECRET")), String.valueOf(env));
    }

    // ---- processes and the kernel ----

    @Test
    void theJailHasItsOwnPidNamespace() {
        Object pids = value(py("import os\nsorted(int(p) for p in os.listdir('/proc') if p.isdigit())"));
        assertTrue(pids instanceof List<?> l && l.size() <= LIMITS.maxProcesses(), "only the jail's processes: " + pids);
        assertEquals(Boolean.FALSE, value(py("import os\n'java' in open('/proc/1/cmdline').read()")));
    }

    @Test
    void kernelSurfaceCallsAreDenied() {
        // x86_64 numbers: ptrace 101, mount 165, unshare 272, setns 308, bpf 321, keyctl 250; each must fail
        String probe = "import ctypes\nlibc = ctypes.CDLL(None, use_errno=True)\n"
                + "[libc.syscall(n, 0, 0, 0, 0, 0) for n in (101, 165, 272, 308, 321, 250)]";
        Object results = value(py(probe));
        assertTrue(results instanceof List<?> l && l.stream().allMatch(r -> ((Number) r).intValue() == -1),
                String.valueOf(results));
        assertInstanceOf(ScriptOutcome.ScriptError.class, py("import os\nos.unshare(os.CLONE_NEWUSER)"),
                "no nested namespaces");
    }

    @Test
    void privilegesCannotBeRegained() {
        assertEquals(List.of(65534, 65534), value(py("import os\n[os.getuid(), os.getgid()]")));
        assertInstanceOf(ScriptOutcome.ScriptError.class, py("import os\nos.setuid(0)"));
        assertEquals("1", String.valueOf(value(py(
                "[l.split()[1] for l in open('/proc/self/status') if l.startswith('NoNewPrivs')][0]"))));
    }

    // ---- limits ----

    @Test
    void aForkBombHitsThePidLimitAndTheWorkerSurvives() {
        ScriptOutcome o = py("import os\nwhile True:\n    os.fork()");
        assertFalse(o instanceof ScriptOutcome.Completed, String.valueOf(o));
        assertEquals(4, ((Number) value(js("a * 2"))).intValue(), "the next script runs normally");
    }

    @Test
    void memoryIsCapped() {
        ScriptOutcome py = py("b = b'x' * (512 << 20)");   // written, not lazily zeroed pages
        assertTrue(py instanceof ScriptOutcome.ScriptError || py instanceof ScriptOutcome.LimitExceeded, String.valueOf(py));
        ScriptOutcome js = js("const a = []; for (;;) a.push(new Array(1e6).fill(1));");
        assertTrue(js instanceof ScriptOutcome.ScriptError || js instanceof ScriptOutcome.LimitExceeded, String.valueOf(js));
    }

    @Test
    void cpuAndWallTimeAreCapped() {
        assertInstanceOf(ScriptOutcome.LimitExceeded.class, py("while True:\n    pass"));
        assertInstanceOf(ScriptOutcome.LimitExceeded.class, js("for (;;) {}"));
        assertInstanceOf(ScriptOutcome.LimitExceeded.class, py("import time\ntime.sleep(60)"), "sleeping is wall time");
    }

    @Test
    void outputFloodsAreBounded() {
        assertInstanceOf(ScriptOutcome.LimitExceeded.class, py("import sys\nsys.__stdout__.write('x' * (2 << 20))"));
        ScriptOutcome noisy = py("import sys\nfor _ in range(10000):\n    print('y' * 100)\n1");
        assertEquals(1, ((Number) value(noisy)).intValue(), "print goes to stderr and is dropped beyond its cap");
        assertInstanceOf(ScriptOutcome.ScriptError.class, py("open('/tmp/big', 'w').write('z' * (64 << 20))"),
                "the private /tmp is size-limited");
    }

    @Test
    void javascriptHasNoModuleLoaderNoProcessAndNoCodeFromStrings() {
        assertEquals("undefined", value(js("typeof require + ''")));
        assertEquals("undefined", value(js("typeof process + ''")));
        assertInstanceOf(ScriptOutcome.ScriptError.class, js("this.constructor.constructor('return process')()"));
        assertInstanceOf(ScriptOutcome.ScriptError.class, js("eval('1')"));
    }
}
