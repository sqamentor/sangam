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

    void toSystemErr(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // ruleid: sangam-print-stack-trace
            e.printStackTrace(System.err);
        }
    }

    void toSystemOut(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // ruleid: sangam-print-stack-trace
            e.printStackTrace(System.out);
        }
    }

    void theCauseToStderr(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // ruleid: sangam-print-stack-trace
            e.getCause().printStackTrace();
        }
    }

    /** A trace captured as text (the SDK worker's failure report): nothing is written to stderr. */
    String capturedAsText(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        // ok: sangam-print-stack-trace
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    String capturedThroughAWriterVariable(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        // ok: sangam-print-stack-trace
        t.printStackTrace(pw);
        return sw.toString();
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
