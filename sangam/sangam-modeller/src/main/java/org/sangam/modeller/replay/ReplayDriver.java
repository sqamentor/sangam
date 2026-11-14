// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

/**
 * The engine side of a replay (decision 151), implemented by the engine adapter (W4-E), not in this module.
 *
 * <p>An implementation must:
 * <ul>
 *   <li>run on an isolated engine (in-memory or a scratch schema), never on a production database, and deploy exactly
 *       the fixture's models at the recorded versions (never resolve the latest definition);</li>
 *   <li>start the instance from {@link ReplayFixture#initialState()} as {@link RecordedInputs#tenant()};</li>
 *   <li>take every time value from {@link RecordedInputs} (FEEL clock, engine clock, timer due dates) and never read
 *       the system clock;</li>
 *   <li>pull stimuli with {@link RecordedInputs#nextStimulus()} and apply each (fire the timer, correlate the message,
 *       complete or fail the external task with the recorded outcome and retries) until none is left or the instance
 *       ends;</li>
 *   <li>route every live effect (connector call, payment, message send, production write) to
 *       {@link RecordedInputs#externalCall(String, String)}, which refuses it, and report effects only as
 *       {@link ReplayFixture.SideEffectIntent}s;</li>
 *   <li>let a {@link ReplayException} propagate unchanged (never retry with live data or the current time).</li>
 * </ul>
 */
public interface ReplayDriver {

    /** Engine name and version, as written in {@link ReplayFixture.Versions#engine()}. */
    String engineVersion();

    /** FEEL/DMN evaluator name and version, as written in {@link ReplayFixture.Versions#evaluator()}. */
    String evaluatorVersion();

    /**
     * Replays the fixture's scenario and reports what this run produced.
     *
     * @param fixture the validated fixture
     * @param inputs  the controlled clock and recorded inputs
     * @return the observation of this run, in the same form as {@link ReplayFixture#observed()}
     * @throws ReplayException when the replay cannot continue without live data
     */
    ReplayFixture.Observation replay(ReplayFixture fixture, RecordedInputs inputs);
}
