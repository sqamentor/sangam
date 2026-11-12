// SPDX-License-Identifier: Apache-2.0
// semgrep --test cases for sangam-print-stack-trace.yaml (not compiled; outside the Maven reactor)
class PrintStackTraceCases {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PrintStackTraceCases.class);

    void toStderr(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // ruleid: sangam-print-stack-trace
            e.printStackTrace();
        }
    }

    void toAStream(Runnable r, java.io.PrintStream out) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // ruleid: sangam-print-stack-trace
            e.printStackTrace(out);
        }
    }

    void throughTheLogger(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // ok: sangam-print-stack-trace
            log.error("task failed", e);
        }
    }
}
