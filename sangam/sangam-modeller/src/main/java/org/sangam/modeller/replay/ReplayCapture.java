// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

/**
 * The capture side of a decision 151 scenario, implemented by the engine adapter (W4-E): run the scenario live on an
 * isolated engine (in-memory or a scratch schema, synthetic data, no live connectors, payments, messages or production
 * writes), record every nondeterministic input through a {@link FixtureRecorder}, and return the fixture.
 *
 * <p>Stimuli from outside the engine (timer firings, message deliveries, external-task results including failures,
 * timeouts and retries) are produced by the scenario itself, so the live run is reproducible as a scenario; the
 * fixture records them exactly as the engine consumed them.
 */
@FunctionalInterface
public interface ReplayCapture {

    /** Runs the scenario once and returns its fixture (possibly marked unsupported). */
    ReplayFixture capture();
}
