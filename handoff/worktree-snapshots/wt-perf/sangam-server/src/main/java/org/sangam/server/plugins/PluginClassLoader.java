// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.plugins;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.List;

/**
 * One class loader per plugin. The platform's API packages come from the parent, so a plugin shares their types;
 * everything else is child-first, so a plugin's private libraries cannot clash with the platform's or another plugin's.
 * Discovery resources ({@code META-INF/services}, {@code META-INF/sangam}) are the plugin's own only, so scanning a
 * plugin never finds the platform's services again.
 */
final class PluginClassLoader extends URLClassLoader {

    private static final List<String> SHARED = List.of("java.", "javax.", "jakarta.", "jdk.", "sun.", "org.sangam.",
            "org.operaton.", "org.slf4j.", "com.fasterxml.jackson.", "org.springframework.", "org.yaml.snakeyaml.");

    PluginClassLoader(String pluginId, URL jar, ClassLoader parent) {
        super("plugin-" + pluginId, new URL[]{jar}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                boolean shared = SHARED.stream().anyMatch(name::startsWith);
                if (!shared || isOwn(name)) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = shared ? super.loadClass(name, false) : getParent().loadClass(name);
                    }
                } else {
                    c = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    /** A plugin's own {@code org.sangam.*} classes (a third party's package may sit below it) unless the parent has them. */
    private boolean isOwn(String name) {
        String path = name.replace('.', '/') + ".class";
        return findResource(path) != null && getParent().getResource(path) == null;
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null || discovery(name) ? own : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return discovery(name) ? findResources(name) : super.getResources(name);
    }

    private static boolean discovery(String name) {
        return name.startsWith("META-INF/services/") || name.startsWith("META-INF/sangam/");
    }
}
