// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

/**
 * M-095 Gherkin runner: the Cucumber JUnit Platform engine runs every {@code .feature} file in this package's class
 * path folder ({@code manual-journeys.feature}) with the glue named in {@code junit-platform.properties}
 * ({@link ManualJourneySteps}). {@code @Cucumber} is deprecated in favour of {@code @Suite} from
 * {@code junit-platform-suite}, which is not in the approved download batch; it is still supported by the engine
 * (FeatureFileResolver), and moving to {@code @Suite} is a one-line change once that artifact is approved.
 */
@SuppressWarnings("deprecation")
@io.cucumber.junit.platform.engine.Cucumber
public class ManualJourneysCucumberTest {
}
