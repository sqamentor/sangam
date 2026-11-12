// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.core.security.ProductionMode;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/** SL-109: {@link ProductionMode} read from a Spring {@link Environment}; every server production guard uses this. */
public final class ProductionEnvironment {

    private ProductionEnvironment() {
    }

    /** {@code sangam.security.production=true}, or an active profile containing {@code prod}. */
    public static boolean isProduction(Environment env) {
        return ProductionMode.isProduction(env.getProperty(ProductionMode.PROPERTY, Boolean.class, false),
                Arrays.asList(env.getActiveProfiles()));
    }
}
