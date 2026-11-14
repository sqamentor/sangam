// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.security.EnvironmentPolicy.ConfigurationError;
import org.sangam.core.security.EnvironmentPolicy.Mode;
import org.sangam.core.security.EnvironmentPolicy.Problem;
import org.sangam.core.security.EnvironmentPolicy.Signal;

/**
 * Decision 143 (RM-06): the full combination matrix of the production switch (missing, blank, invalid, true, false in
 * any case and with blanks) and the profile signals (none, neutral, development, production, mixed). Development-only
 * defaults only for an explicit false with a development profile; any production source wins; missing, invalid and
 * contradictory settings are configuration errors, and the guard answer for an error is "production". Written for the
 * test phase (not run here).
 */
class EnvironmentPolicyTest {

    static Map<String, Signal> profiles(Object... nameSignal) {
        Map<String, Signal> m = new LinkedHashMap<>();
        for (int i = 0; i < nameSignal.length; i += 2) {
            m.put((String) nameSignal[i], (Signal) nameSignal[i + 1]);
        }
        return m;
    }

    static final Map<String, Signal> NONE = profiles();
    static final Map<String, Signal> NEUTRAL = profiles("postgres", Signal.NEUTRAL, "oidc", Signal.NEUTRAL);
    static final Map<String, Signal> DEV = profiles("h2", Signal.DEVELOPMENT);
    static final Map<String, Signal> PROD = profiles("postgres", Signal.NEUTRAL, "prod-eu", Signal.PRODUCTION);
    static final Map<String, Signal> MIXED = profiles("test", Signal.DEVELOPMENT, "prod", Signal.PRODUCTION);

    static Mode mode(String flag, Map<String, Signal> p) {
        return EnvironmentPolicy.resolve(flag, "test source", p).mode();
    }

    static Problem problem(String flag, Map<String, Signal> p) {
        return assertThrows(ConfigurationError.class, () -> EnvironmentPolicy.resolve(flag, "test source", p)).problem();
    }

    @Test
    void aMissingOrBlankSwitchIsRefusedWhateverTheProfiles() {
        for (Map<String, Signal> p : java.util.List.of(NONE, NEUTRAL, DEV, PROD, MIXED)) {
            assertEquals(Problem.MISSING, problem(null, p), "missing with " + p);
            assertEquals(Problem.MISSING, problem("", p), "empty with " + p);
            assertEquals(Problem.MISSING, problem("   ", p), "blank with " + p);
        }
        assertEquals(Problem.MISSING, problem(null, null), "neither profile nor switch");
    }

    @Test
    void anInvalidSwitchIsRefused() {
        for (String bad : java.util.List.of("yes", "1", "0", "on", "prod", "tru", "false!", "null")) {
            assertEquals(Problem.INVALID, problem(bad, DEV), bad);
            assertEquals(Problem.INVALID, problem(bad, PROD), bad);
        }
    }

    @Test
    void trueIsProductionWhateverTheProfiles() {
        for (String t : java.util.List.of("true", "TRUE", " True ")) {
            assertEquals(Mode.PRODUCTION, mode(t, NONE));
            assertEquals(Mode.PRODUCTION, mode(t, NEUTRAL));
            assertEquals(Mode.PRODUCTION, mode(t, PROD));
            assertEquals(Mode.PRODUCTION, mode(t, MIXED));
        }
        EnvironmentPolicy.Resolution r = EnvironmentPolicy.resolve("true", "env", DEV);
        assertEquals(Mode.PRODUCTION, r.mode(), "production plus a development profile is production");
        assertFalse(r.developmentDefaultsAllowed(), "the development profile never re-enables development defaults");
        assertTrue(r.notes().get(0).contains("h2"), r.notes().toString());
    }

    @Test
    void productionWinsOverADevelopmentProfileAndNamesTheContradiction() {
        // decision 198: start as production with every guard on; the host warns with these profile names
        EnvironmentPolicy.Resolution r = EnvironmentPolicy.resolve("true", "env", MIXED);
        assertEquals(Mode.PRODUCTION, r.mode());
        assertEquals(java.util.List.of("test"), r.ignoredDevelopmentProfiles());
        assertEquals(java.util.List.of("h2"), EnvironmentPolicy.resolve("true", "env", DEV).ignoredDevelopmentProfiles());
        assertEquals(java.util.List.of(), EnvironmentPolicy.resolve("true", "env", PROD).ignoredDevelopmentProfiles(),
                "no contradiction, no warning");
        assertEquals(java.util.List.of(), EnvironmentPolicy.resolve("false", "env", DEV).ignoredDevelopmentProfiles(),
                "a development resolution ignores nothing");
    }

    @Test
    void falseWithAProductionProfileIsContradictory() {
        assertEquals(Problem.CONTRADICTORY, problem("false", PROD));
        assertEquals(Problem.CONTRADICTORY, problem("FALSE", MIXED), "development plus production profile with false");
    }

    @Test
    void falseIsDevelopmentOnlyWithADevelopmentProfile() {
        assertEquals(Mode.DEVELOPMENT, mode("false", DEV));
        assertEquals(Mode.DEVELOPMENT, mode(" false ", profiles("postgres", Signal.NEUTRAL, "test", Signal.DEVELOPMENT)));
        assertEquals(Mode.NON_PRODUCTION, mode("false", NEUTRAL), "staging: neither production nor development");
        assertEquals(Mode.NON_PRODUCTION, mode("false", NONE));
        assertTrue(EnvironmentPolicy.resolve("false", "x", DEV).developmentDefaultsAllowed());
        assertFalse(EnvironmentPolicy.resolve("false", "x", NEUTRAL).developmentDefaultsAllowed());
        assertFalse(EnvironmentPolicy.resolve("false", "x", NEUTRAL).production());
    }

    @Test
    void theGuardAnswersFailClosed() {
        assertTrue(EnvironmentPolicy.productionOrFailClosed(null, null, DEV), "missing: production for guards");
        assertTrue(EnvironmentPolicy.productionOrFailClosed("maybe", "x", DEV), "invalid: production for guards");
        assertTrue(EnvironmentPolicy.productionOrFailClosed("false", "x", PROD), "contradictory: production for guards");
        assertTrue(EnvironmentPolicy.productionOrFailClosed("true", "x", DEV));
        assertFalse(EnvironmentPolicy.productionOrFailClosed("false", "x", DEV));
        assertFalse(EnvironmentPolicy.developmentDefaultsAllowed(null, null, DEV));
        assertFalse(EnvironmentPolicy.developmentDefaultsAllowed("false", "x", PROD));
        assertFalse(EnvironmentPolicy.developmentDefaultsAllowed("true", "x", DEV));
        assertTrue(EnvironmentPolicy.developmentDefaultsAllowed("false", "x", DEV));
    }

    @Test
    void theDescriptionNamesModeSourceAndProfilesOnly() {
        String d = EnvironmentPolicy.resolve("false", "commandLineArgs", DEV).describe();
        assertTrue(d.contains("DEVELOPMENT") && d.contains("commandLineArgs") && d.contains("h2"), d);
        ConfigurationError e = assertThrows(ConfigurationError.class,
                () -> EnvironmentPolicy.resolve("false", "systemEnvironment", PROD));
        assertTrue(e.getMessage().contains("prod-eu") && e.getMessage().contains("systemEnvironment")
                && e.getMessage().contains("decision 143"), e.getMessage());
    }

    /** The one profile convention every host and module classifies with (decision 143, round 2 consumers). */
    @Test
    void theProfileConventionIsSharedAndProductionIsTestedFirst() {
        for (String dev : new String[] {"dev", "h2", "test", "local", "DEV", " H2 "}) {
            assertEquals(Signal.DEVELOPMENT, EnvironmentPolicy.signal(dev), dev);
        }
        for (String prod : new String[] {"prod", "PROD-eu", "production", "prod-dev", "preprod"}) {
            assertEquals(Signal.PRODUCTION, EnvironmentPolicy.signal(prod), prod);
        }
        for (String neutral : new String[] {"postgres", "oracle", "sqlserver", "keycloak", "staging", "", "development"}) {
            assertEquals(Signal.NEUTRAL, EnvironmentPolicy.signal(neutral), neutral);
        }
        assertEquals(Signal.NEUTRAL, EnvironmentPolicy.signal(null));

        Map<String, Signal> s = EnvironmentPolicy.signals(java.util.Arrays.asList("dev,postgres", null, " ", "prod-eu"));
        assertEquals(java.util.List.of("dev", "postgres", "prod-eu"), java.util.List.copyOf(s.keySet()));
        assertEquals(Signal.DEVELOPMENT, s.get("dev"));
        assertEquals(Signal.NEUTRAL, s.get("postgres"));
        assertEquals(Signal.PRODUCTION, s.get("prod-eu"));
        assertTrue(EnvironmentPolicy.signals(null).isEmpty());
        assertEquals(java.util.Set.of("dev", "h2", "test", "local"), EnvironmentPolicy.DEVELOPMENT_PROFILES);
    }

    /** Decision 198 through the shared classifier: true with a development profile is production, every guard on. */
    @Test
    void theSharedClassifierFeedsTheResolver() {
        assertTrue(EnvironmentPolicy.productionOrFailClosed("true", "x", EnvironmentPolicy.signals(java.util.List.of("h2"))));
        assertTrue(EnvironmentPolicy.productionOrFailClosed(null, "x", EnvironmentPolicy.signals(java.util.List.of("dev"))));
        assertFalse(EnvironmentPolicy.productionOrFailClosed("false", "x", EnvironmentPolicy.signals(java.util.List.of("dev"))));
        assertTrue(EnvironmentPolicy.developmentDefaultsAllowed("false", "x",
                EnvironmentPolicy.signals(java.util.List.of("local", "postgres"))));
        assertFalse(EnvironmentPolicy.developmentDefaultsAllowed("false", "x",
                EnvironmentPolicy.signals(java.util.List.of("staging"))));
    }
}
