// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import javax.sql.DataSource;

import org.sangam.engine.SangamEngineConfigurer;

/** Carries the configurer from {@link EngineConfigurationCustomizer} to the adapter bean, built after the engine. */
public final class EngineConfigurerHolder {

    private volatile SangamEngineConfigurer configurer;
    private volatile DataSource prepared;
    private volatile String jdbcUrl;

    void set(SangamEngineConfigurer configurer, DataSource prepared, String jdbcUrl) {
        this.configurer = configurer;
        this.prepared = prepared;
        this.jdbcUrl = jdbcUrl;
    }

    public SangamEngineConfigurer configurer() {
        if (configurer == null) {
            throw new IllegalStateException("sangam-engine was not applied: the Operaton starter's "
                    + "ProcessEngineConfigurationImpl bean was not post-processed (is the starter on the class path?)");
        }
        return configurer;
    }

    public DataSource prepared() {
        return prepared;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }
}
