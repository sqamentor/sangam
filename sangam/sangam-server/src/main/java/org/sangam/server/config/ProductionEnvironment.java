// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.core.security.EnvironmentPolicy;
import org.sangam.core.security.ProductionMode;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * The Spring adapter of the platform's environment policy (decision 143, SL-109): it reads the production switch from
 * the Spring {@link Environment} (trusted deployment configuration only: command line, environment variables, system
 * properties, configuration files), classifies the Spring profiles into {@link EnvironmentPolicy.Signal}s, and asks the
 * framework-free {@link EnvironmentPolicy}. Spring profile names are interpreted here and nowhere else in the server.
 *
 * <ul>
 *   <li>{@link #DEVELOPMENT_PROFILES} ({@code dev}, {@code h2}, {@code test}, {@code local}) signal development;</li>
 *   <li>a profile naming production ({@link ProductionMode#isProductionProfile(String)}) signals production;</li>
 *   <li>every other profile (database, identity provider, feature profiles) is neutral.</li>
 * </ul>
 * Without an active profile, Spring's default profiles are in effect and are classified the same way.
 *
 * <p>{@link #isProduction} is the guard predicate every server production check uses; for a missing, invalid or
 * contradictory configuration it answers "production" (fail closed). The start itself is refused by
 * {@link EnvironmentPolicyWiring}, which calls {@link #resolve} before any other bean is created.
 */
public final class ProductionEnvironment {

    /** Recognised development and test profiles (decision 143): the platform convention of {@link EnvironmentPolicy}. */
    public static final Set<String> DEVELOPMENT_PROFILES = EnvironmentPolicy.DEVELOPMENT_PROFILES;

    /** Spring Boot's attached view of every source; it is not a source of its own. */
    private static final String ATTACHED = "configurationProperties";

    private ProductionEnvironment() {
    }

    /** The resolution; throws {@link EnvironmentPolicy.ConfigurationError} for a refused configuration. */
    public static EnvironmentPolicy.Resolution resolve(Environment env) {
        return EnvironmentPolicy.resolve(env.getProperty(ProductionMode.PROPERTY), flagSource(env), profileSignals(env));
    }

    /** Guard predicate: production, or (fail closed) a configuration that the start-up check refuses. */
    public static boolean isProduction(Environment env) {
        return EnvironmentPolicy.productionOrFailClosed(env.getProperty(ProductionMode.PROPERTY), flagSource(env),
                profileSignals(env));
    }

    /** Development-only defaults: only in a validated development or test environment (decision 143). */
    public static boolean developmentDefaultsAllowed(Environment env) {
        return EnvironmentPolicy.developmentDefaultsAllowed(env.getProperty(ProductionMode.PROPERTY), flagSource(env),
                profileSignals(env));
    }

    /** The active profiles (or, without one, the default profiles) with their signals. */
    public static Map<String, EnvironmentPolicy.Signal> profileSignals(Environment env) {
        String[] active = env.getActiveProfiles();
        String[] names = active.length > 0 ? active : env.getDefaultProfiles();
        return EnvironmentPolicy.signals(Arrays.asList(names));
    }

    static EnvironmentPolicy.Signal signal(String profile) {
        return EnvironmentPolicy.signal(profile);
    }

    /** Name of the first property source that sets the switch (for the log; never a value). */
    static String flagSource(Environment env) {
        if (env instanceof ConfigurableEnvironment ce) {
            for (PropertySource<?> s : ce.getPropertySources()) {
                if (!ATTACHED.equals(s.getName()) && s.containsProperty(ProductionMode.PROPERTY)) {
                    return s.getName();
                }
            }
        }
        return null;
    }
}
