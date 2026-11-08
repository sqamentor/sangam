// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.plugin;

import java.nio.file.Path;
import java.util.Map;

/**
 * Optional callback for any service a plugin provides (connector, pack, delegate or function provider): the loader
 * calls {@link #init} once, after construction and before the service is registered.
 */
public interface PluginAware {

    void init(PluginContext context);

    /**
     * What the platform gives a plugin.
     *
     * @param pluginId   id from the plugin descriptor
     * @param version    plugin version
     * @param properties {@code sangam.plugins.config.<pluginId>.*} with the prefix removed (secrets are references,
     *                   resolved by the connector's own secret resolver, never plain values in configuration)
     * @param dataDir    a private, writable directory for this plugin
     */
    record PluginContext(String pluginId, String version, Map<String, String> properties, Path dataDir) {
        public PluginContext {
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }

        public String property(String key, String fallback) {
            String v = properties.get(key);
            return v == null || v.isBlank() ? fallback : v;
        }
    }
}
