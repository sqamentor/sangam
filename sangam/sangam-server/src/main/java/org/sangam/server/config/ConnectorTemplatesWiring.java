// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.List;

import org.sangam.modeller.template.ConnectorTemplates;
import org.sangam.modeller.template.ElementTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-067/M-068/M-072: installs the built-in connector element templates ({@link ConnectorTemplates}) for the tenants in
 * {@code sangam.modeller.connector-templates.tenants} (comma list). Opt-in; needs the modeller module
 * ({@code sangam.modules.modeller.enabled=true}), otherwise the list is refused at startup rather than silently ignored.
 * Installing is idempotent: an unchanged template is not published again.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modeller.connector-templates.tenants")
public class ConnectorTemplatesWiring {

    static final String KEY = "sangam.modeller.connector-templates.tenants";
    static final String ACTOR = "system:connector-templates";
    private static final Logger LOG = LoggerFactory.getLogger(ConnectorTemplatesWiring.class);

    @Bean
    public SmartInitializingSingleton sangamConnectorTemplatesInstall(ObjectProvider<ElementTemplates> templates,
                                                                     Environment env) {
        return () -> {
            ElementTemplates t = templates.getIfAvailable();
            if (t == null) {
                throw new IllegalStateException(KEY + " is set but the modeller module is not enabled "
                        + "(sangam.modules.modeller.enabled=true)");
            }
            for (String tenant : env.getProperty(KEY, String[].class, new String[0])) {
                if (!tenant.isBlank()) {
                    List<String> published = ConnectorTemplates.install(t, tenant.trim(), ACTOR);
                    LOG.info("connector templates for tenant {}: {} published", tenant.trim(), published.size());
                }
            }
        };
    }
}
