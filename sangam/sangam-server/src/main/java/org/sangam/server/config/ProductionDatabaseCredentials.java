// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SL-103: on a production profile the database credentials must be injected, never the defaults shipped in the
 * database profiles ({@code application-<db>.yaml}: {@code username: ${SANGAM_DB_USER:<default>}},
 * {@code password: ${SANGAM_DB_PASSWORD:<default>}}).
 *
 * <p>Generic: the rule reads the effective (highest-precedence) raw value of {@code spring.datasource.username} and
 * {@code spring.datasource.password}. When that value is a {@code ${VARIABLE:default}} placeholder, the variable is
 * the injection point: it must be set, and a password variable must not equal the shipped default. A value given
 * directly (for example {@code SPRING_DATASOURCE_PASSWORD}) must not be blank. A user name equal to the shipped
 * default is allowed: {@code sangam} is the documented application user (Helm example, Compose), and a user name is
 * not a secret. Messages name the variable and never contain a value.
 */
final class ProductionDatabaseCredentials {

    static final String USERNAME = "spring.datasource.username";
    static final String PASSWORD = "spring.datasource.password";

    /** {@code ${NAME:default}} with a plain (non-nested) default; group 1 = variable, group 2 = default. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+):([^}]*)}$");

    private ProductionDatabaseCredentials() {
    }

    /** Violations of the production credential rule; empty when not production. */
    static List<String> violations(Environment env) {
        if (!ProductionEnvironment.isProduction(env)) { // SL-109
            return List.of();
        }
        List<String> bad = new ArrayList<>();
        check(env, USERNAME, false, bad);
        check(env, PASSWORD, true, bad);
        return bad;
    }

    private static void check(Environment env, String key, boolean secret, List<String> bad) {
        String raw = rawValue(env, key);
        Matcher m = raw == null ? null : PLACEHOLDER.matcher(raw.trim());
        if (m != null && m.matches()) {
            String variable = m.group(1).trim();
            String given = env.getProperty(variable);
            if (given == null) {
                bad.add(variable + " is unset (" + key + " would use the default shipped in the database profile)");
            } else if (secret && given.equals(m.group(2))) {
                bad.add(variable + " equals the default shipped in the database profile");
            }
            return;
        }
        String effective;
        try {
            effective = env.getProperty(key);
        } catch (RuntimeException e) {
            effective = null;
        }
        if (effective == null || effective.isBlank()) {
            bad.add(key + " is unset or blank");
        }
    }

    /** The unresolved value of the highest-precedence property source that defines the key, or null. */
    static String rawValue(Environment env, String key) {
        if (env instanceof ConfigurableEnvironment ce) {
            for (PropertySource<?> ps : ce.getPropertySources()) {
                Object v = ps.getProperty(key);
                if (v != null) {
                    return String.valueOf(v);
                }
            }
            return null;
        }
        return null;
    }
}
