// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.integration.connector.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Owner decision 37 (Q-04): the start is refused by name when one key serves two purposes
 * ({@link KeyPurposeSeparation}). Always on, in every environment: a shared key is refused also outside production.
 */
@Configuration(proxyBeanMethods = false)
public class KeyPurposeSeparationWiring {

    private static final Logger LOG = LoggerFactory.getLogger(KeyPurposeSeparationWiring.class);

    /** The outcome of the start-up check: the number of key entries compared. */
    public record Checked(int keys) {
    }

    @Bean
    public Checked sangamKeyPurposeSeparation(Environment env, ObjectProvider<SecretResolver> secrets) {
        int n = KeyPurposeSeparation.check(env, secrets.getIfAvailable(SecretResolver::fromEnvironment));
        LOG.info("Owner decision 37: {} configured key(s) checked, none serves two purposes", n);
        return new Checked(n);
    }
}
