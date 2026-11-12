// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

/**
 * A script language this worker runs, with the interpreter inside the sandbox and the harness that wraps the user
 * script (stdin JSON in, stdout JSON out). Node.js (MIT) and CPython (PSF) run as separate processes, never in the JVM.
 */
public enum Language {

    JAVASCRIPT("javascript", "runner.js"),
    PYTHON("python", "runner.py");

    private final String id;
    private final String harness;

    Language(String id, String harness) {
        this.id = id;
        this.harness = harness;
    }

    /** The topic suffix ({@code sangam.script.<id>}). */
    public String id() {
        return id;
    }

    /** File name of the harness in the runtime directory. */
    public String harness() {
        return harness;
    }
}
