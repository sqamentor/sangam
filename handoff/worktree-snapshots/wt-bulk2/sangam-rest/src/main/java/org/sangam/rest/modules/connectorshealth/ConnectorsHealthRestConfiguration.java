// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.connectorshealth;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * REST exposure of sangam-connectors-health: FHIR subscription hooks and CDS Hooks services. Active when the module
 * is on the class path and sangam-server bound {@link HealthHooksPort} ({@code HealthConnectorsWiring}).
 */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.connectors.health.HealthConnectors")
public class ConnectorsHealthRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = "org.sangam.rest.modules.connectorshealth.HealthHooksPort")
    @Import({HealthHooksController.class, HealthHooksProblemAdvice.class})
    static class Controllers {
    }
}
