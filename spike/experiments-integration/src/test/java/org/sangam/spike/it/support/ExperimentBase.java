package org.sangam.spike.it.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Base of every experiment class: one {@link Env} (fresh database + engine) per class, scenarios in declaration order,
 * results merged into {@code results/<experiment>.json} under the engine's key after the class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith({EngineSelection.class, ScenarioRecorder.class})
public abstract class ExperimentBase {

    protected Env env;
    /** Measurements of the scenario that is running; copied into the result by {@link ScenarioRecorder}. */
    protected final Map<String, Object> m = new LinkedHashMap<>();
    final List<Map<String, Object>> scenarios = new ArrayList<>();
    /** Per-engine notes and findings written next to the scenarios. */
    protected final Map<String, Object> notes = new LinkedHashMap<>();
    private long startedAt;

    protected abstract EngineKind kind();

    protected abstract String experiment();

    /** Key of this run in the result file; the engine label unless a supplementary run overrides it. */
    protected String resultKey() {
        return kind().label();
    }

    /** Experiment-specific setup after the environment exists. */
    protected void setUp() throws Exception {
    }

    @BeforeAll
    final void createEnvironment() throws Exception {
        startedAt = System.currentTimeMillis();
        try {
            env = Env.create(kind(), experiment());
            setUp();
        } catch (Exception | Error e) {
            notes.put("setupError", e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    @BeforeEach
    final void clearMeasurements() {
        m.clear();
    }

    @AfterAll
    final void writeResults() {
        try {
            notes.put("wallClockSeconds", (System.currentTimeMillis() - startedAt) / 1000.0);
            Results.write(experiment(), resultKey(), scenarios, notes);
        } finally {
            if (env != null) {
                env.close();
            }
        }
    }
}
