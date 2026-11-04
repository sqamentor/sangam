// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.sangam.core.layer.Transactions;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.port.AdminPort;
import org.sangam.security.SangamSecurity;
import org.sangam.security.crypto.MasterKeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * sangam-security's data-at-rest encryption and module role templates (WS-SECURITY delivery, final wave).
 * <ul>
 *   <li>{@link FieldEncryptor} = {@code security.newFieldEncryptor(tx, kek, production)}. The key-encryption key
 *       ({@link MasterKeyProvider}) comes from {@code sangam.security.kek.keys.<id>=<base64>} with
 *       {@code sangam.security.kek.current-id}, or from a PKCS#12 store ({@code sangam.security.kek.pkcs12.path},
 *       {@code .store-password}, {@code .alias}, {@code .key-password}). The ephemeral development key is used only on
 *       the {@code h2}/{@code dev} profiles. With {@code sangam.security.production=true} and no key, start-up fails;
 *       on other profiles without a key no encryptor is bound and a warning is logged. sangam-rest binds the
 *       encryptor to form drafts and the sensitive REST_DOCUMENT kinds automatically.</li>
 *   <li>Tenant onboarding: after {@code AdminPort.createTenant}, {@code security.defaults().install(c, admin, true)}
 *       installs the module role templates and the person-conditioned self-service grants for the new tenant (the
 *       explicit endpoint is {@code POST /api/v1/admin/security/module-defaults}).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.security.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityKeysWiring {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityKeysWiring.class);

    @Bean
    public FieldEncryptor sangamFieldEncryptor(SangamSecurity security, DataSource dataSource, Environment env) {
        boolean production = env.getProperty("sangam.security.production", Boolean.class, false);
        boolean dev = Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.equals("h2") || p.equals("dev"));
        MasterKeyProvider kek = masterKey(env);
        if (kek == null) {
            if (production || !dev) {
                throw new IllegalStateException("No key-encryption key configured: set sangam.security.kek.keys.<id> +"
                        + " sangam.security.kek.current-id, or sangam.security.kek.pkcs12.*; the development key is"
                        + " allowed only on the h2/dev profiles");
            }
            LOG.warn("Using the ephemeral DEVELOPMENT key-encryption key: encrypted data does not survive a restart");
            kek = MasterKeyProvider.ephemeralForDevelopment();
        }
        return security.newFieldEncryptor(Transactions.of(dataSource), kek, production);
    }

    static MasterKeyProvider masterKey(Environment env) {
        String p12 = env.getProperty("sangam.security.kek.pkcs12.path");
        if (p12 != null && !p12.isBlank()) {
            String storePw = env.getProperty("sangam.security.kek.pkcs12.store-password", "");
            String keyPw = env.getProperty("sangam.security.kek.pkcs12.key-password", storePw);
            return MasterKeyProvider.ofKeyStore(Path.of(p12), storePw.toCharArray(),
                    env.getProperty("sangam.security.kek.pkcs12.alias", "sangam-kek"), keyPw.toCharArray());
        }
        Map<String, String> keys = Binder.get(env).bind("sangam.security.kek.keys",
                org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        if (keys.isEmpty()) {
            return null;
        }
        String current = env.getProperty("sangam.security.kek.current-id", keys.keySet().iterator().next());
        if (!keys.containsKey(current)) {
            throw new IllegalStateException("sangam.security.kek.current-id " + current + " is not among the keys");
        }
        return MasterKeyProvider.ofBase64(current, new LinkedHashMap<>(keys));
    }

    /** Installs the module role templates and self-service grants for every tenant created through the API. */
    @Bean
    public static BeanPostProcessor sangamModuleDefaultsOnboarding(ObjectProvider<SangamSecurity> security,
                                                                   ObjectProvider<DataSource> dataSource) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof AdminPort admin)) {
                    return bean;
                }
                return java.lang.reflect.Proxy.newProxyInstance(AdminPort.class.getClassLoader(),
                        new Class<?>[]{AdminPort.class}, (proxy, m, args) -> {
                            Object result;
                            try {
                                result = m.invoke(admin, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                            if (m.getName().equals("createTenant") && result instanceof AdminPort.Tenant t) {
                                installDefaults(security.getIfAvailable(), dataSource.getIfAvailable(), t.id());
                            }
                            return result;
                        });
            }
        };
    }

    static void installDefaults(SangamSecurity security, DataSource ds, String tenant) {
        if (security == null || ds == null || tenant == null) {
            return;
        }
        SecurityIdentity admin = SecurityIdentity.system(tenant, "tenant-onboarding");
        Transactions.of(ds).inTransaction(c -> {
            security.defaults().install(c, admin, true);
            return null;
        });
        LOG.info("Installed module role templates and self-service grants for tenant {}", tenant);
    }
}
