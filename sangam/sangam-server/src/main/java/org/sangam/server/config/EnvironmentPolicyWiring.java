// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.core.security.EnvironmentPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Decision 143 (RM-06): the start-up check of the environment policy. It runs as a bean-factory post-processor, so
 * before any other bean of the server is created: a missing, invalid or contradictory
 * {@code sangam.security.production} refuses the start with a clear configuration error, and a valid configuration is
 * logged once (mode, the configuration source of the switch, the profiles; no secrets). A development environment is
 * labelled as such in the log.
 */
@Configuration(proxyBeanMethods = false)
public class EnvironmentPolicyWiring {

    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentPolicyWiring.class);

    @Bean
    public static StartupCheck sangamEnvironmentPolicyCheck() {
        return new StartupCheck();
    }

    /** Resolves the environment policy before the singletons are created. */
    public static final class StartupCheck implements BeanFactoryPostProcessor, EnvironmentAware {
        private Environment env;

        @Override
        public void setEnvironment(Environment environment) {
            this.env = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            check(env);
        }

        /** Resolves and logs; throws {@link EnvironmentPolicy.ConfigurationError} for a refused configuration. */
        static EnvironmentPolicy.Resolution check(Environment env) {
            EnvironmentPolicy.Resolution r = ProductionEnvironment.resolve(env);
            if (!r.ignoredDevelopmentProfiles().isEmpty()) {
                // decision 198: production wins; the contradiction is named, never silently resolved
                LOG.warn("CONTRADICTORY environment configuration: {}=true together with the development profile(s) {};"
                        + " starting as PRODUCTION with every production guard on and the development profiles ignored"
                        + " (decision 198). Remove the development profile(s) from this deployment: {}",
                        org.sangam.core.security.ProductionMode.PROPERTY, r.ignoredDevelopmentProfiles(), r.describe());
            } else if (r.developmentDefaultsAllowed()) {
                LOG.warn("DEVELOPMENT environment (demo data, development identities and development-only defaults"
                        + " allowed; never production): {}", r.describe());
            } else {
                LOG.info("Sangam {}", r.describe());
            }
            return r;
        }
    }
}
