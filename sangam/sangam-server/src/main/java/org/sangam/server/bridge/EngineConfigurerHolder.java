// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import javax.sql.DataSource;

import org.sangam.engine.SangamEngineConfigurer;

/**
 * Carries the configurer from {@link EngineConfigurationCustomizer} to the adapter bean, built after the engine.
 *
 * <p>It also owns the configurer's D-10 engine slot for the Spring context: the starter, not Sangam, closes the engine,
 * so the slot is released when this bean is destroyed. The holder is created before the engine (the customizer needs
 * it), so Spring destroys it after the engine; a context whose engine failed to build releases the slot too.
 */
public final class EngineConfigurerHolder implements org.springframework.beans.factory.DisposableBean {

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

    /** Context shutdown: frees the engine slot (idempotent; also safe when the engine never started). */
    @Override
    public void destroy() {
        SangamEngineConfigurer c = configurer;
        if (c != null) {
            c.releaseEngineSlot();
        }
    }

    public DataSource prepared() {
        return prepared;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }
}
