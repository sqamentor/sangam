// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.config.api.ConfigConnector;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.config.locale.ProfileBusinessClock;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.MasterStore;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.PackConnectorBindings;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * SL-123: every pack connector id the config connector's javadoc names is bound to it, {@code config.crosswalk}
 * ({@code crosswalk.map}) included; each named operation is one the connector declares.
 */
class ConfigConnectorRegistrationTest {

    @Test
    void everyConfigPackConnectorIdIsBoundToTheConfigConnector() throws Exception {
        ProfileResolver resolver = new ProfileResolver(new InMemoryProfileRepository(), LayerModel.open());
        ConfigConnector connector = new ConfigConnector(resolver, new ClassificationRegistry(new MasterStore.InMemory()),
                new NumberSeries.InMemory(), new ProfileBusinessClock(resolver, Clock.systemUTC()));
        ConnectorRegistry registry = new ConnectorRegistry();
        PackConnectorBindings bindings = new PackConnectorBindings();
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("registry", registry, "bindings", bindings));
        new ConfigWiring().sangamConfigConnectorRegistration(connector, beans.getBeanProvider(ConnectorRegistry.class),
                beans.getBeanProvider(PackConnectorBindings.class)).afterPropertiesSet();

        assertEquals(List.of("config.profile", "config.documentType", "config.classification", "config.series",
                "config.crosswalk"), ConfigWiring.PACK_CONNECTOR_IDS);
        for (String id : ConfigWiring.PACK_CONNECTOR_IDS) {
            assertEquals("config", bindings.binding(id).orElseThrow(() -> new AssertionError(id + " unbound")).connectorId(), id);
        }
        assertTrue(registry.find("any-tenant", "config").isPresent());
        assertTrue(connector.descriptor().operations().contains("crosswalk.map"), "the crosswalk operation exists");
    }
}
