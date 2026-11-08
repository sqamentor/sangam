// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.config;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * REST exposure of sangam-config ({@code /api/v1/config}). Active when the module is on the class path and
 * sangam-server's {@code ConfigWiring} created its services ({@code ConfigAdminService}, {@code ConfigConnector}).
 */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.config.admin.ConfigAdminService")
public class ConfigRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = {"org.sangam.config.admin.ConfigAdminService", "org.sangam.config.api.ConfigConnector",
            "org.sangam.config.profile.ProfileResolver"})
    @Import({ConfigController.class, ConfigProblemAdvice.class})
    static class Controllers {
    }
}
