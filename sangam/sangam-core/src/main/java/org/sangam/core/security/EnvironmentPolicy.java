// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owner decision 143 (RM-06, W4Q-35): the one environment-policy resolution of the platform, framework-free. Every
 * module that must know whether it runs in production, or whether development-only defaults (ephemeral signers,
 * random-pepper vaults, permit-all ports, static development tokens, demo trust settings) may be used, asks the
 * {@link Resolution} produced here.
 *
 * <p>Inputs come only from trusted deployment configuration, never from requests, tenant data or packs:
 * <ul>
 *   <li>the production switch {@value ProductionMode#PROPERTY} as raw text, with the name of the configuration source
 *       that supplied it (for the log), and</li>
 *   <li>the environment signals of the active host profiles. The platform's profile convention is
 *       {@link #signal(String)} ({@link #DEVELOPMENT_PROFILES} signal development, a name containing {@code prod}
 *       signals production, every other name is neutral); every host and module classifies through it (the Spring
 *       adapter {@code ProductionEnvironment} in sangam-server, sangam-rest, sangam-assembly, the registry), so no
 *       second list of profile names can drift. A host whose profile names differ maps them to these names first.</li>
 * </ul>
 *
 * <p>Rules, fail-closed precedence:
 * <ol>
 *   <li>a missing or blank switch is a configuration error ({@link Problem#MISSING}): an installation states its mode;
 *       neither a profile alone nor the absence of both is accepted;</li>
 *   <li>a switch that is not {@code true} or {@code false} (case-insensitive, surrounding blanks ignored) is a
 *       configuration error ({@link Problem#INVALID});</li>
 *   <li>{@code false} together with a profile that signals production is contradictory ({@link Problem#CONTRADICTORY}):
 *       the start is refused rather than guessed;</li>
 *   <li>{@code true} is {@link Mode#PRODUCTION} whatever the profiles say; a development profile next to it is recorded
 *       and ignored (its development-only defaults stay refused);</li>
 *   <li>{@code false} with at least one development profile is {@link Mode#DEVELOPMENT}: the only mode in which
 *       development-only defaults are allowed;</li>
 *   <li>{@code false} without a development profile is {@link Mode#NON_PRODUCTION} (for example a staging cluster):
 *       production guards are off, development-only defaults stay refused.</li>
 * </ol>
 * {@link #resolve} throws {@link ConfigurationError} for the three errors; callers that only need a yes/no answer for a
 * guard use {@link #productionOrFailClosed}, which answers "production" for a configuration error.
 */
public final class EnvironmentPolicy {

    /** What a host profile says about the environment, as classified by the host adapter. */
    public enum Signal {
        /** A recognised development or test profile. */
        DEVELOPMENT,
        /** A profile that names production. */
        PRODUCTION,
        /** Any other profile (database, identity provider, feature profiles): says nothing about the environment. */
        NEUTRAL
    }

    /** The resolved environment. */
    public enum Mode {
        PRODUCTION, NON_PRODUCTION, DEVELOPMENT
    }

    /** Why a configuration was refused. */
    public enum Problem {
        MISSING, INVALID, CONTRADICTORY
    }

    /** A refused environment configuration; the message names the setting and the sources, never a secret. */
    public static final class ConfigurationError extends IllegalStateException {
        private final Problem problem;

        ConfigurationError(Problem problem, String message) {
            super(message);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /**
     * The resolved environment and where it came from.
     *
     * @param mode         the resolved mode
     * @param flagSource   name of the configuration source of the production switch (never its secret content: the
     *                     switch is a boolean)
     * @param profiles     the host profiles with their signals, in the order given
     * @param notes        human-readable notes (for example an ignored development profile)
     */
    public record Resolution(Mode mode, String flagSource, Map<String, Signal> profiles, List<String> notes) {
        public Resolution {
            Objects.requireNonNull(mode, "mode");
            profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles == null ? Map.of() : profiles));
            notes = List.copyOf(notes == null ? List.of() : notes);
        }

        public boolean production() {
            return mode == Mode.PRODUCTION;
        }

        /** True only in a validated development or test environment (decision 143). */
        public boolean developmentDefaultsAllowed() {
            return mode == Mode.DEVELOPMENT;
        }

        /**
         * Decision 198: the development profiles active next to {@code true}. Production wins (every guard on); the
         * host logs a warning naming this contradiction. Empty outside production.
         */
        public List<String> ignoredDevelopmentProfiles() {
            return mode == Mode.PRODUCTION ? names(profiles, Signal.DEVELOPMENT) : List.of();
        }

        /** One log line: mode, source of the switch and the profiles; no values other than names. */
        public String describe() {
            StringBuilder s = new StringBuilder("environment ").append(mode)
                    .append(" (").append(ProductionMode.PROPERTY).append(" from ").append(flagSource)
                    .append("; profiles ").append(profiles.isEmpty() ? "none" : profiles).append(')');
            if (!notes.isEmpty()) {
                s.append(": ").append(String.join("; ", notes));
            }
            return s.toString();
        }
    }

    /** Recognised development and test profiles (decision 143), compared without case. */
    public static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "h2", "test", "local");

    private EnvironmentPolicy() {
    }

    /**
     * The platform's profile convention: a name naming production ({@link ProductionMode#isProductionProfile(String)})
     * is {@link Signal#PRODUCTION}, a name in {@link #DEVELOPMENT_PROFILES} is {@link Signal#DEVELOPMENT}, any other
     * name is {@link Signal#NEUTRAL}. Production is tested first, so a name such as {@code prod-dev} never relaxes.
     */
    public static Signal signal(String profile) {
        String p = profile == null ? "" : profile.trim();
        if (ProductionMode.isProductionProfile(p)) {
            return Signal.PRODUCTION;
        }
        return DEVELOPMENT_PROFILES.contains(p.toLowerCase(Locale.ROOT)) ? Signal.DEVELOPMENT : Signal.NEUTRAL;
    }

    /**
     * The signals of a list of profile names, in order; blank names are skipped, each name is trimmed, and a
     * comma-separated entry (for example {@code "dev,postgres"}) counts as its parts.
     */
    public static Map<String, Signal> signals(Collection<String> profiles) {
        Map<String, Signal> out = new LinkedHashMap<>();
        if (profiles == null) {
            return out;
        }
        for (String entry : profiles) {
            if (entry == null) {
                continue;
            }
            for (String n : entry.split(",")) {
                String p = n.trim();
                if (!p.isEmpty()) {
                    out.put(p, signal(p));
                }
            }
        }
        return out;
    }

    /**
     * Resolves the environment.
     *
     * @param rawFlag    the raw text of {@value ProductionMode#PROPERTY}, or null when no source sets it
     * @param flagSource the name of the configuration source that set it (null when unset)
     * @param profiles   active host profiles with their signals (null: none)
     * @throws ConfigurationError for a missing, invalid or contradictory configuration
     */
    public static Resolution resolve(String rawFlag, String flagSource, Map<String, Signal> profiles) {
        Map<String, Signal> p = profiles == null ? Map.of() : profiles;
        String source = flagSource == null || flagSource.isBlank() ? "unknown source" : flagSource;
        List<String> production = names(p, Signal.PRODUCTION);
        List<String> development = names(p, Signal.DEVELOPMENT);
        if (rawFlag == null || rawFlag.isBlank()) {
            throw new ConfigurationError(Problem.MISSING, ProductionMode.PROPERTY + " is not set (profiles " + describe(p)
                    + "): every installation must state its environment explicitly, true for production or false"
                    + " for development, test and staging (decision 143); the start is refused");
        }
        String v = rawFlag.trim().toLowerCase(Locale.ROOT);
        if (!v.equals("true") && !v.equals("false")) {
            throw new ConfigurationError(Problem.INVALID, ProductionMode.PROPERTY + " from " + source
                    + " must be true or false (decision 143); the start is refused");
        }
        List<String> notes = new ArrayList<>();
        if (v.equals("true")) {
            if (!development.isEmpty()) {
                notes.add("development profile(s) " + development + " ignored in production: development-only"
                        + " defaults stay refused");
            }
            return new Resolution(Mode.PRODUCTION, source, p, notes);
        }
        if (!production.isEmpty()) {
            throw new ConfigurationError(Problem.CONTRADICTORY, ProductionMode.PROPERTY + "=false from " + source
                    + " contradicts the production profile(s) " + production
                    + " (decision 143): set the switch to true or remove the profile; the start is refused");
        }
        return new Resolution(development.isEmpty() ? Mode.NON_PRODUCTION : Mode.DEVELOPMENT, source, p, notes);
    }

    /**
     * The guard answer: production for a production resolution and, fail-closed, for any configuration error. Use
     * {@link #resolve} where the start itself must be refused (the host's start-up check does).
     */
    public static boolean productionOrFailClosed(String rawFlag, String flagSource, Map<String, Signal> profiles) {
        try {
            return resolve(rawFlag, flagSource, profiles).production();
        } catch (ConfigurationError e) {
            return true;
        }
    }

    /** Development-only defaults: only for a valid {@link Mode#DEVELOPMENT} resolution; never on an error. */
    public static boolean developmentDefaultsAllowed(String rawFlag, String flagSource, Map<String, Signal> profiles) {
        try {
            return resolve(rawFlag, flagSource, profiles).developmentDefaultsAllowed();
        } catch (ConfigurationError e) {
            return false;
        }
    }

    private static List<String> names(Map<String, Signal> p, Signal s) {
        List<String> out = new ArrayList<>();
        p.forEach((k, v) -> {
            if (v == s) {
                out.add(k);
            }
        });
        return out;
    }

    private static String describe(Map<String, Signal> p) {
        return p.isEmpty() ? "none" : p.toString();
    }
}
