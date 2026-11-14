// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.sangam.assembly.engine.EngineAssembly;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.tenant.WorkTenants;

/**
 * The engine settings of the Quarkus host. The Spring host binds the whole {@code sangam.engine.*} prefix onto
 * {@link SangamEngineSettings}; this host has no such binder, so it binds what it supports and refuses the rest:
 *
 * <ul>
 *   <li>the host-neutral settings of {@link EngineAssembly#applyConfiguration} (job executor switch, SQL Server RCSI
 *       check, CMMN event listeners, test mode refused in production);</li>
 *   <li>{@value WorkTenants#SETTING}: the mandatory system audit scope (decision 144), default
 *       {@value #DEFAULT_SYSTEM_AUDIT_SCOPE} as in the standalone server;</li>
 *   <li>{@value #APPLY_PRODUCT_SCHEMA} (owner decision 287 b, default {@code true}): the engine applies the product
 *       schema before it starts; this host has no schema step of its own, so {@code false} leaves
 *       {@code schema-before-engine} missing and the start is refused ({@code EngineAssembly.checkBeforeDatabase});</li>
 *   <li>{@value #EXECUTOR_PARTITIONING} (owner decision 286, default {@code false}): the partitioned job acquisition
 *       with its leases; only then are the job leases needed;</li>
 *   <li>any other {@code sangam.engine.*} key, and any {@code SANGAM_ENGINE_*} environment form that is not the form of a
 *       supported key, is refused at start-up ({@link #unsupported}), never silently ignored, so an operator setting
 *       never looks applied when it is not.</li>
 * </ul>
 * Every other setting keeps the {@link SangamEngineSettings} default (the plain-Java embedding's defaults).
 */
public final class QuarkusEngineSettings {

    /** The default every host uses ({@link EngineAssembly#DEFAULT_SYSTEM_AUDIT_SCOPE}, decision 144). */
    public static final String DEFAULT_SYSTEM_AUDIT_SCOPE = EngineAssembly.DEFAULT_SYSTEM_AUDIT_SCOPE;

    static final String ENGINE_PREFIX = "sangam.engine.";

    /** Owner decision 287 (b): the product schema before the engine (default on, as the plain-Java embedding). */
    static final String APPLY_PRODUCT_SCHEMA = "sangam.engine.apply-product-schema";

    /** Owner decision 286: executor partitioning (default off); the job leases are needed only while it is on. */
    static final String EXECUTOR_PARTITIONING = "sangam.engine.executor-partitioning";

    /** The {@code sangam.engine.*} keys this host binds. */
    static final Set<String> SUPPORTED = Set.of(WorkTenants.SETTING, APPLY_PRODUCT_SCHEMA, EXECUTOR_PARTITIONING);

    private QuarkusEngineSettings() {
    }

    /**
     * Settings from the Quarkus configuration.
     *
     * @param names    every configuration key Quarkus knows (to find unsupported {@code sangam.engine.*} keys)
     * @param profiles the active Quarkus profiles
     * @throws IllegalStateException when a {@code sangam.engine.*} key is set that this host does not bind
     */
    public static SangamEngineSettings from(SangamProperties properties, Iterable<String> names, List<String> profiles) {
        Set<String> refused = unsupported(names);
        if (!refused.isEmpty()) {
            throw new IllegalStateException("the Quarkus host does not bind " + refused + " (it binds "
                    + new TreeSet<>(SUPPORTED) + "); remove them or use the standalone server: an engine setting is"
                    + " never ignored silently");
        }
        SangamEngineSettings settings = new SangamEngineSettings();
        // host-neutral settings, the system audit scope with its default included (EngineAssembly owns both)
        EngineAssembly.applyConfiguration(settings, properties, profiles);
        settings.setApplyProductSchema(properties.getBoolean(APPLY_PRODUCT_SCHEMA, true));
        settings.setExecutorPartitioning(properties.getBoolean(EXECUTOR_PARTITIONING, false));
        return settings;
    }

    /**
     * The engine settings among {@code names} that this host does not bind, sorted: a {@code sangam.engine.*} property
     * other than {@link #SUPPORTED}, and an environment form of an engine setting ({@code SANGAM_ENGINE_*}) that is not
     * the environment form of a supported key.
     */
    static Set<String> unsupported(Iterable<String> names) {
        Set<String> supportedEnv = new TreeSet<>();
        for (String s : SUPPORTED) {
            supportedEnv.add(environmentForm(s));
        }
        Set<String> out = new TreeSet<>();
        for (String n : names) {
            if (n == null) {
                continue;
            }
            if (n.startsWith(ENGINE_PREFIX)) {
                if (!SUPPORTED.contains(n)) {
                    out.add(n);
                }
            } else if (isEnvironmentName(n) && environmentForm(n).startsWith(ENGINE_ENV_PREFIX)
                    && !supportedEnv.contains(environmentForm(n))) {
                out.add(n);
            }
        }
        return out;
    }

    /** {@code SANGAM_ENGINE_}: the environment form of {@value #ENGINE_PREFIX}. */
    static final String ENGINE_ENV_PREFIX = environmentForm(ENGINE_PREFIX);

    /**
     * The environment form of a property name by the MicroProfile Config / SmallRye rule: every character that is
     * neither a letter, a digit nor {@code _} becomes {@code _}, then upper case. SmallRye matches an environment
     * variable to a property by the exact name, by this form without the upper-casing, and by this form; all three
     * meet here because the comparison is on the upper-case form.
     */
    static String environmentForm(String name) {
        StringBuilder b = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            b.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        return b.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /** A name with no character outside letters, digits and {@code _}: how an environment variable reaches Config. */
    private static boolean isEnvironmentName(String n) {
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return !n.isEmpty();
    }
}
