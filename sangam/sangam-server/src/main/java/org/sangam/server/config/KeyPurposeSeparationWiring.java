// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.List;

import org.sangam.integration.connector.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Owner decision 37 (Q-04): the start is refused by name when one key serves two purposes
 * ({@link KeyPurposeSeparation}) or two environments ({@link KeyEnvironmentSeparation}, owner decision 385 (3)). Always
 * on, in every environment: a shared key is refused also outside production, and production refuses to start without
 * the other environments' fingerprint list.
 */
@Configuration(proxyBeanMethods = false)
public class KeyPurposeSeparationWiring {

    private static final Logger LOG = LoggerFactory.getLogger(KeyPurposeSeparationWiring.class);

    /**
     * The outcome of the start-up check: the number of key entries compared, this environment's name (or null) and the
     * number of other environments' fingerprints compared.
     */
    public record Checked(int keys, String environment, int foreignFingerprints) {
    }

    @Bean
    public Checked sangamKeyPurposeSeparation(Environment env, ObjectProvider<SecretResolver> secrets) {
        List<KeyPurposeSeparation.Entry> entries = KeyPurposeSeparation.entries(env,
                secrets.getIfAvailable(SecretResolver::fromEnvironment));
        int n = KeyPurposeSeparation.check(entries);
        KeyEnvironmentSeparation.Result r = KeyEnvironmentSeparation.check(env, entries,
                ProductionEnvironment.isProduction(env));
        LOG.info("Owner decisions 37 and 385 (3): {} configured key(s) checked, none serves two purposes; environment {}:"
                + " {} own fingerprint(s), {} of other environments compared, none shared", n,
                r.environment() == null ? "(unnamed)" : r.environment(), r.own(), r.foreign());
        return new Checked(n, r.environment(), r.foreign());
    }
}
