// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host.conformance;

/**
 * The separate JVM of the {@code utc-jvm} case (decision 279): {@link QuarkusHostHarness} starts this with a non-UTC
 * default time zone and its own class path. It starts the Quarkus host once, with nothing else removed, and reports
 * through its exit code; a refusal is printed with every cause, so the parent's failure carries the refusal's text.
 * Written for the test phase (not run).
 */
public final class ForkedStart {

    /** Exit code: the host started (the fail-closed kit then reports a start without utc-jvm). */
    static final int STARTED = 0;

    /** Exit code: the host refused to start. */
    static final int REFUSED = 3;

    private ForkedStart() {
    }

    public static void main(String[] args) {
        System.out.println("user.timezone=" + System.getProperty("user.timezone") + " default="
                + java.util.TimeZone.getDefault().getID());
        QuarkusHostHarness host = new QuarkusHostHarness();
        int exit;
        try {
            host.start();
            System.out.println("STARTED");
            exit = STARTED;
        } catch (RuntimeException | Error e) {
            e.printStackTrace(System.out);
            exit = REFUSED;
        } finally {
            try {
                host.stop();
            } catch (RuntimeException e) {
                e.printStackTrace(System.out);
            }
        }
        System.out.flush();
        System.exit(exit);
    }
}
