// SPDX-License-Identifier: Apache-2.0
// semgrep --test cases for sangam-runtime-exec.yaml (not compiled; outside the Maven reactor)
class RuntimeExecCases {

    interface CommandRunner {
        void exec(String command);
    }

    Process direct(String cmd) throws Exception {
        // ruleid: sangam-runtime-exec
        return Runtime.getRuntime().exec(new String[] {"sh", "-c", cmd});
    }

    Process throughALocal(String cmd) throws Exception {
        Runtime rt = Runtime.getRuntime();
        // ruleid: sangam-runtime-exec
        return rt.exec(new String[] {cmd});
    }

    void unrelatedExec(CommandRunner runner) {
        // ok: sangam-runtime-exec
        runner.exec("not a process");
    }

    long memory() {
        // ok: sangam-runtime-exec
        return Runtime.getRuntime().totalMemory();
    }
}
