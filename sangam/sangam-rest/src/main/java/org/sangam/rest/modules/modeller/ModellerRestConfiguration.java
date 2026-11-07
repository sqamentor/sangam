// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** REST exposure of sangam-modeller. Active when sangam-server's {@code ModellerWiring} created the services. */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.modeller.lease.EditLeases")
public class ModellerRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = "org.sangam.modeller.lease.EditLeases")
    @Import({ModellerController.class, ServiceMocksController.class, ModellerProblemAdvice.class})
    static class Controllers {
    }
}
