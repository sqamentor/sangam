// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.security;

import java.util.Collection;
import java.util.Locale;

/**
 * SL-109: the one production predicate of the platform. An installation is in production when
 * {@code sangam.security.production=true} (what the Helm chart and the server image set: {@code SANGAM_SECURITY_PRODUCTION})
 * <b>or</b> when an active Spring profile contains {@code prod} (the older trigger, kept so that no guard becomes
 * weaker for an installation that relies on a {@code prod} profile). Every production guard asks this class; a guard
 * test ({@code ProductionPredicateGuardTest}) fails on any other profile- or property-based production check.
 * Framework-free, so modules without Spring use it too.
 */
public final class ProductionMode {

    /** The production switch. */
    public static final String PROPERTY = "sangam.security.production";

    private ProductionMode() {
    }

    /**
     * @param productionFlag the value of {@link #PROPERTY}
     * @param activeProfiles the active profiles (may be null)
     */
    public static boolean isProduction(boolean productionFlag, Collection<String> activeProfiles) {
        return productionFlag || isProductionProfile(activeProfiles);
    }

    /** True when any profile contains {@code prod} (case-insensitive). */
    public static boolean isProductionProfile(Collection<String> activeProfiles) {
        if (activeProfiles == null) {
            return false;
        }
        for (String p : activeProfiles) {
            if (isProductionProfile(p)) {
                return true;
            }
        }
        return false;
    }

    /** True when the profile name, or any name of a comma-separated list, contains {@code prod} (case-insensitive). */
    public static boolean isProductionProfile(String profiles) {
        return profiles != null && profiles.toLowerCase(Locale.ROOT).contains("prod");
    }
}
