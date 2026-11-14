// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import java.util.List;
import java.util.Objects;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.quarkus.runtime.configuration.ConfigUtils;

import org.eclipse.microprofile.config.Config;
import org.sangam.assembly.host.SangamProperties;

/**
 * Config-source seam of the Quarkus host (design note section 10.1): {@link SangamProperties} over the MicroProfile
 * {@link Config} Quarkus builds (application.properties, environment, system properties, in Quarkus' own order). A key
 * Quarkus does not know, or holds as an empty value, is absent ({@code null}); the defaults and the parsing stay in
 * {@link SangamProperties}. The Quarkus host has no {@code sangam-defaults.yaml} source (the convenience capability
 * {@code defaults-source} is reported absent).
 *
 * <p>Decision 279: the seam is this optional bean. The host reads its configuration only through it, so without it
 * (an operator's {@code quarkus.arc.exclude-types}) {@code config-source} is missing and the start is refused.
 */
@Singleton
public class QuarkusConfigSource {

    private final SangamProperties properties;
    private final Iterable<String> names;
    private final List<String> profiles;

    @Inject
    public QuarkusConfigSource(Config config) {
        this(over(config), config.getPropertyNames(), ConfigUtils.getProfiles());
    }

    /** A seam over explicit values (tests and the host's own constructors). */
    QuarkusConfigSource(SangamProperties properties, Iterable<String> names, List<String> profiles) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.names = Objects.requireNonNull(names, "names");
        this.profiles = List.copyOf(profiles);
    }

    /** The host's configuration as {@link SangamProperties}. */
    public static SangamProperties over(Config config) {
        Objects.requireNonNull(config, "config");
        return key -> config.getOptionalValue(key, String.class).orElse(null);
    }

    public SangamProperties properties() {
        return properties;
    }

    /** Every configuration key Quarkus knows (to refuse unbound engine settings by name). */
    public Iterable<String> names() {
        return names;
    }

    /** The active Quarkus profiles. */
    public List<String> profiles() {
        return profiles;
    }
}
