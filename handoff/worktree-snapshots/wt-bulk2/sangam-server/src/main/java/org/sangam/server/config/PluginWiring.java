// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.store.JdbcConfigStore;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.onboarding.deps.PlatformInfo;
import org.sangam.server.plugins.PluginFeelEnginePlugin;
import org.sangam.server.plugins.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Signed plugin drop-in. The manager runs in a bean factory post-processor, before any regular bean, because engine
 * delegates must be beans when the engine starts and pack / jurisdiction registries must see plugin content.
 *
 * <p>Properties: {@code sangam.plugins.enabled} (default true), {@code sangam.plugins.folder} (default
 * {@code lib-ext/plugins}), {@code sangam.plugins.trusted-keys} (default {@code <folder>/trusted-keys}),
 * {@code sangam.plugins.work-folder} (default {@code <folder>/.work}), {@code sangam.plugins.config.<id>.*}. Unsigned
 * jars load only on the {@code h2}/{@code dev} profiles.
 */
@Configuration(proxyBeanMethods = false)
public class PluginWiring {

    private static final Logger LOG = LoggerFactory.getLogger(PluginWiring.class);

    @Bean
    public static BeanFactoryPostProcessor sangamPluginRegistrar(Environment env) {
        return factory -> {
            ClassLoader cl = PluginWiring.class.getClassLoader();
            Path folder = Path.of(env.getProperty("sangam.plugins.folder", "lib-ext/plugins"));
            boolean dev = Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.equals("h2") || p.equals("dev"))
                    && !env.getProperty("sangam.security.production", Boolean.class, false);
            Map<String, String> props = Binder.get(env).bind("sangam.plugins.config",
                    Bindable.mapOf(String.class, String.class)).orElse(Map.of());
            PluginManager.Settings settings = new PluginManager.Settings(folder,
                    Path.of(env.getProperty("sangam.plugins.trusted-keys", folder.resolve("trusted-keys").toString())),
                    Path.of(env.getProperty("sangam.plugins.work-folder", folder.resolve(".work").toString())), dev, props);
            PluginManager manager = new PluginManager(settings, platform(cl), cl);
            if (env.getProperty("sangam.plugins.enabled", Boolean.class, true)) {
                manager.load();
            }
            factory.registerSingleton("sangamPluginManager", manager);
            manager.delegates().forEach((name, delegate) -> {
                if (factory.containsBean(name)) {
                    throw new IllegalStateException("plugin delegate name is already a bean: " + name);
                }
                factory.registerSingleton(name, delegate);
            });
        };
    }

    static PlatformInfo platform(ClassLoader cl) {
        return PlatformInfo.detect(cl, "org.sangam", "org.operaton.bpm.engine.ProcessEngine", "0.1.0-SNAPSHOT", "2.2.0", Map.of());
    }

    @Bean
    public PluginFeelEnginePlugin sangamPluginFeelPlugin(PluginManager manager) {
        return new PluginFeelEnginePlugin(manager.dmnFunctions());
    }

    @Bean
    public InitializingBean sangamPluginConnectors(PluginManager manager, ObjectProvider<ConnectorRegistry> registry) {
        return () -> {
            ConnectorRegistry r = registry.getIfAvailable();
            if (r != null) {
                manager.connectors().forEach(r::register);
            } else if (!manager.connectors().isEmpty()) {
                LOG.warn("Plugin connectors ignored: sangam-integration is off");
            }
        };
    }

    /** Plugin profile documents, imported as release data when that version is not stored yet. */
    @Bean
    public ApplicationRunner sangamPluginProfiles(PluginManager manager, ObjectProvider<ConfigAdminService> admin,
                                                  ObjectProvider<JdbcConfigStore> store) {
        return args -> {
            if (admin.getIfAvailable() == null || store.getIfAvailable() == null) {
                return;
            }
            for (PluginManager.PluginDocuments p : manager.profileDocuments()) {
                List<ProfileDocument> fresh = new ArrayList<>();
                for (String text : p.texts()) {
                    ProfileDocument d = ProfileLoader.parse(text);
                    if (store.getObject().get(d.id(), d.version()).isEmpty()) {
                        fresh.add(d);
                    }
                }
                if (!fresh.isEmpty()) {
                    admin.getObject().importActive(fresh, "plugin:" + p.pluginId());
                }
            }
        };
    }
}
