// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import org.operaton.bpm.dmn.engine.DmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

import org.sangam.feel.SangamFeel;
import org.sangam.packs.engine.DecimalValueSerializer;
import org.sangam.packs.engine.LocalDateValueSerializer;

/**
 * The value and FEEL set-up of a pack test engine, the same as {@code SangamProcessEnginePlugin} gives a production
 * engine: exact decimals (decision 138), typed local dates (decision 333) and the production FEEL classes of
 * {@code sangam-feel} (integrator ruling option A: strict FEEL evaluation, strict null input, exact numbers, the DMN 1.6
 * transformer), and the test tenant's profile currency (owner decision M1). A pack test therefore evaluates its tables the way production does, with no mapper of its own.
 */
public final class PackTestEngines {

    private PackTestEngines() {
    }

    /** Applies the production value and FEEL set-up to {@code cfg}; call before {@code buildProcessEngine()}. */
    public static <T extends ProcessEngineConfigurationImpl> T configure(T cfg) {
        LocalDateValueSerializer.register(cfg);
        DecimalValueSerializer.register(cfg);
        DefaultDmnEngineConfiguration dmn = cfg.getDmnEngineConfiguration() != null ? cfg.getDmnEngineConfiguration()
                : (DefaultDmnEngineConfiguration) DmnEngineConfiguration.createDefaultDmnEngineConfiguration();
        SangamFeel.install(dmn);
        cfg.setDmnEngineConfiguration(dmn);
        // owner decision M1: production installs the tenant profile's currency (PacksWiring); the test tenant's
        // profile currency is PackTestTenant.CURRENCY. Other tenants have none here (the pack's money.currency applies).
        org.sangam.packs.port.TenantCurrencies.install(
                t -> PackTestTenant.ID.equals(t) ? PackTestTenant.CURRENCY : null);
        return cfg;
    }
}
