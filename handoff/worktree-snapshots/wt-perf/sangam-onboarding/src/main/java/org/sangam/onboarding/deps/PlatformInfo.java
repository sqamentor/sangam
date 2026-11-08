// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.deps;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * What the running platform offers, for compatibility checks of packs and plugins.
 *
 * @param sangamVersion platform version
 * @param engineVersion workflow engine version
 * @param modules       installed module artifactId to version
 */
public record PlatformInfo(String sangamVersion, String engineVersion, Map<String, String> modules) {

    public PlatformInfo {
        modules = modules == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(modules));
    }

    /**
     * Detects the modules from the {@code META-INF/maven/<group>/<artifact>/pom.properties} files of the
     * {@code groupId} jars visible to {@code loader} (plus {@code extra}), the engine version from its jar manifest.
     */
    public static PlatformInfo detect(ClassLoader loader, String groupId, String engineMarkerClass,
                                      String fallbackSangam, String fallbackEngine, Map<String, String> extra) {
        Map<String, String> mods = new LinkedHashMap<>();
        // a directory listing is not portable inside jars: probe the known artifacts
        for (String a : KNOWN) {
            String v = pomVersion(loader, groupId, a);
            if (v != null) {
                mods.put(a, v);
            }
        }
        if (extra != null) {
            mods.putAll(extra);
        }
        String engine = null;
        try {
            Class<?> c = Class.forName(engineMarkerClass, false, loader);
            Package p = c.getPackage();
            engine = p == null ? null : p.getImplementationVersion();
        } catch (ClassNotFoundException ignored) {
            // engine not on the class path
        }
        String sangam = mods.getOrDefault("sangam-core", fallbackSangam);
        return new PlatformInfo(sangam == null ? fallbackSangam : sangam, engine == null ? fallbackEngine : engine, mods);
    }

    /** The module artifacts of the platform (probed through their pom.properties). */
    public static final java.util.List<String> KNOWN = java.util.List.of("sangam-core", "sangam-persistence",
            "sangam-engine", "sangam-security", "sangam-integration", "sangam-connectors-health",
            "sangam-flowable-ports", "sangam-rest", "sangam-packs", "sangam-compliance", "sangam-config",
            "sangam-ledger", "sangam-registry", "sangam-billing", "sangam-case", "sangam-programme",
            "sangam-resources", "sangam-geo", "sangam-field", "sangam-server", "sangam-cell", "sangam-onboarding");

    private static String pomVersion(ClassLoader loader, String group, String artifact) {
        try (InputStream in = loader.getResourceAsStream("META-INF/maven/" + group + "/" + artifact + "/pom.properties")) {
            if (in == null) {
                return null;
            }
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version");
        } catch (IOException e) {
            return null;
        }
    }
}
