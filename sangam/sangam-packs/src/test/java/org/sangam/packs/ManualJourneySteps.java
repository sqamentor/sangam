// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.sangam.core.time.BusinessClock;

/**
 * Step definitions of {@code manual-journeys.feature} (M-116 / M-095): a named Gherkin list of the SL-32 journeys
 * that needed the scenario clock or the steering driver, run through {@link PackAcceptanceTest}'s own runner (one
 * in-memory engine with every pack deployed, the simulator, an isolated ledger, batch and audit per scenario), so the
 * two can never disagree. The engine is started once for the feature and closed after it.
 */
public class ManualJourneySteps {

    private PackAcceptanceTest.Scenario scenario;
    private Throwable failure;
    private BusinessClock businessClockBefore;

    @BeforeAll
    public static void startEngine() {
        PackAcceptanceTest.setUp();
    }

    @AfterAll
    public static void stopEngine() {
        PackAcceptanceTest.tearDown();
    }

    @Given("the acceptance scenario {string} of the pack {string}")
    public void theAcceptanceScenario(String journey, String pack) throws IOException {
        scenario = PackAcceptanceTest.scenarios().stream()
                .filter(s -> s.pack().equals(pack) && s.id().equals(journey)).findFirst()
                .orElseThrow(() -> new AssertionError("pack " + pack + " has no acceptance scenario " + journey));
    }

    @Then("it has a checkable outcome and no manual reason")
    public void itIsCheckable() {
        assertFalse(scenario.run().hasNonNull("manual"),
                () -> scenario.id() + " is manual again: " + scenario.run().path("manual").asText());
        assertTrue(PackAcceptanceTest.checkable(scenario), () -> scenario.id() + " has no expectState or run.expect");
    }

    @When("it is driven with its run steps on the scenario clock")
    public void itIsDriven() {
        businessClockBefore = BusinessClock.system();
        try {
            PackAcceptanceTest.execute(scenario);
        } catch (AssertionError | RuntimeException e) {
            failure = e;
        }
    }

    @Then("it reached the outcome its pack states")
    public void itReachedItsOutcome() {
        if (failure != null) {
            throw new AssertionError(scenario.pack() + "/" + scenario.id() + ": " + failure.getMessage(), failure);
        }
    }

    @Then("the scenario clock and the business clock are back where they were")
    public void theClocksAreBack() {
        assertEquals(0, PackAcceptanceTest.driver.offsetMillis(), "the scenario clock was reset");
        assertSame(businessClockBefore, BusinessClock.system(), "the business clock in place before is back");
    }
}
