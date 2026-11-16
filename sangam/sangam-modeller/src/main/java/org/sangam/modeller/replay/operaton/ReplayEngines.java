// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay.operaton;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.operaton.bpm.dmn.engine.DmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.core.time.EngineTimeZone;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.feel.FeelEngineIdentity;
import org.sangam.engine.replay.CaptureListener;
import org.sangam.feel.SangamFeel;

/**
 * M-136 (decision 151): the isolated Sangam engines a capture and a replay run on. One engine per run, built on a scratch
 * database the caller supplies (in-memory or a scratch schema, never a production database), with the run's
 * {@link CaptureListener} registered through {@link SangamEngineSettings#enableReplayCapture}; the engine itself refuses
 * a listener when the host's environment resolution says production ({@link CaptureListener#requireAllowed}). No
 * further plugin is installed, so the engine has no connector, mail, payment or event channel to reach.
 */
public final class ReplayEngines {

    /** Starts one isolated engine for one capture or replay run. */
    @FunctionalInterface
    public interface Factory {
        /**
         * @param listener the run's capture listener (never null)
         * @return the started engine and the behaviour-relevant configuration it runs with
         */
        Started start(CaptureListener listener);
    }

    /**
     * A started engine. {@link #close()} closes it and returns the engine clock to system time.
     *
     * @param engine        the engine adapter
     * @param configuration behaviour-relevant settings (recorded in the fixture, checked at replay)
     */
    public record Started(OperatonEngineAdapter engine, Map<String, String> configuration) implements AutoCloseable {
        public Started {
            Objects.requireNonNull(engine, "engine");
            configuration = Map.copyOf(configuration);
        }

        @Override
        public void close() {
            try {
                engine.setClock(null);
            } finally {
                engine.close();
            }
        }
    }

    private ReplayEngines() {
    }

    /**
     * Engines on scratch databases: one fresh database from {@code scratchDatabase} per run (its schema created by the
     * engine), settings from {@code settings} (a new object per run; the factory adds schema creation, several engines
     * per JVM and the capture listener).
     *
     * @param databaseLabel the JDBC URL or label the engine reports
     * @param production    the host's own production resolution ({@code ProductionEnvironment} / {@code EnvironmentPolicy});
     *                      true makes every start fail
     */
    public static Factory scratch(Supplier<DataSource> scratchDatabase, String databaseLabel,
                                  Supplier<SangamEngineSettings> settings, boolean production) {
        Objects.requireNonNull(scratchDatabase, "scratchDatabase");
        Objects.requireNonNull(settings, "settings");
        return listener -> {
            SangamEngineSettings s = Objects.requireNonNull(settings.get(), "settings");
            s.setEngineSchemaUpdate("true"); // a scratch database owned by this run
            s.setAllowMultipleEnginesPerJvm(true);
            s.enableReplayCapture(Objects.requireNonNull(listener, "listener"), production);
            Map<String, String> configuration = configuration(s);
            OperatonEngineAdapter engine = SangamEngineFactory.build(scratchDatabase.get(), s, databaseLabel, List.of());
            return new Started(engine, configuration);
        };
    }

    /** The settings a replay must share with its capture to behave the same, as fixture configuration text. */
    static Map<String, String> configuration(SangamEngineSettings s) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("engine.history", String.valueOf(s.getHistory()));
        c.put("engine.timeZone", EngineTimeZone.ZONE.getId());
        c.put("engine.maxLease", String.valueOf(s.getMaxLease()));
        c.put("engine.maxJobExecutionsPerCall", Integer.toString(s.getMaxJobExecutionsPerCall()));
        c.put("engine.maxExecutionsPerJobDefinitionPerCall", Integer.toString(s.getMaxExecutionsPerJobDefinitionPerCall()));
        c.put("engine.expiryPolicy", s.getExpiryPolicy() == null ? "default" : s.getExpiryPolicy().getClass().getName());
        return c;
    }

    /** {@code operaton <version>} of the operaton-engine on the class path. */
    public static String engineVersion() {
        try (InputStream in = ProcessEngine.class.getClassLoader()
                .getResourceAsStream("META-INF/maven/org.operaton.bpm/operaton-engine/pom.properties")) {
            String v = null;
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                v = p.getProperty("version");
            }
            if (v == null || v.isBlank()) {
                throw new IllegalStateException("decision 151: the operaton-engine version is not on the class path; a "
                        + "fixture cannot name the engine it ran on");
            }
            return "operaton " + v;
        } catch (IOException e) {
            throw new IllegalStateException("decision 151: cannot read the operaton-engine version", e);
        }
    }

    /** The FEEL evaluator identity a Sangam engine installs (R10 {@link FeelEngineIdentity}). */
    public static String evaluatorVersion() {
        DefaultDmnEngineConfiguration dmn = (DefaultDmnEngineConfiguration) DmnEngineConfiguration
                .createDefaultDmnEngineConfiguration();
        SangamFeel.install(dmn);
        return FeelEngineIdentity.of(dmn);
    }

    /** The FEEL evaluator identity the started engine actually runs. */
    static String evaluatorOf(OperatonEngineAdapter engine) {
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) engine.processEngine()
                .getProcessEngineConfiguration();
        return FeelEngineIdentity.of((DefaultDmnEngineConfiguration) cfg.getDmnEngineConfiguration());
    }
}
