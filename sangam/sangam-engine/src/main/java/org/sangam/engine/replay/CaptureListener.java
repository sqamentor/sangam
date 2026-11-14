// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.replay;

import java.util.Collection;

import org.sangam.core.security.ProductionMode;

/**
 * Decision 151 (replay, integrator ruling 2): the engine-neutral capture SPI. A test or replay engine registers one
 * on {@code SangamEngineSettings.setCaptureListener}; the engine then reports every input it takes from outside the
 * model, in engine order, on the thread that takes it. The modeller-side adapter (W4-G) records each one through
 * {@code FixtureRecorder}. Never registered in production: {@link #requireAllowed} refuses it at engine start.
 *
 * <p>A listener that throws fails the engine command that took the input, so a capture never silently misses one.
 */
@FunctionalInterface
public interface CaptureListener {

    void onInput(CapturedInput input);

    /**
     * The run took an input the fixture cannot carry exactly (for example a double or an object variable). The
     * modeller adapter marks the capture unsupported ({@code FixtureRecorder.unsupported}); the default refuses, so a
     * listener that does not handle it never records an approximate fixture.
     */
    default void unsupported(String reason) {
        throw new IllegalStateException("decision 151: capture unsupported: " + reason);
    }

    /**
     * Start-up check: a capture listener is allowed only when the host has explicitly said this is not production
     * ({@code production == FALSE}, from the host's environment resolution) and no active profile names production
     * ({@link ProductionMode}). A missing switch refuses, like decision 143's environment policy.
     *
     * @throws IllegalStateException when a listener is set and either check fails
     */
    static void requireAllowed(CaptureListener listener, Boolean production, Collection<String> activeProfiles) {
        if (listener == null) {
            return;
        }
        if (production == null) {
            throw new IllegalStateException("decision 151: a replay capture listener is set but the host did not state "
                    + "that this engine is not production (SangamEngineSettings.production is unset)");
        }
        if (ProductionMode.isProduction(production, activeProfiles)) {
            throw new IllegalStateException("decision 151: a replay capture listener is never allowed in production");
        }
    }
}
