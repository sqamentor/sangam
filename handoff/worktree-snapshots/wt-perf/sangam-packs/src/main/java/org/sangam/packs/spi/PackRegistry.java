// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Catalogue of the packs on the class path (manifests listed in {@value #INDEX} and {@link ServiceLoader} packs),
 * with dependency ordering for deployment.
 */
public final class PackRegistry {

    /** Index file: one manifest path per line; {@code #} starts a comment. */
    public static final String INDEX = "META-INF/sangam/packs.idx";

    private final Map<String, SangamPack> packs = new LinkedHashMap<>();

    private PackRegistry() {
    }

    /** Discovers every pack visible to {@code loader}. */
    public static PackRegistry discover(ClassLoader loader) {
        PackRegistry r = new PackRegistry();
        try {
            Enumeration<URL> idx = loader.getResources(INDEX);
            while (idx.hasMoreElements()) {
                URL u = idx.nextElement();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(u.openStream(),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            r.add(ManifestPack.load(loader, line));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (SangamPack p : ServiceLoader.load(SangamPack.class, loader)) {
            r.add(p);
        }
        return r;
    }

    /** Discovers with this module's class loader. */
    public static PackRegistry discover() {
        return discover(PackRegistry.class.getClassLoader());
    }

    public void add(SangamPack p) {
        SangamPack old = packs.putIfAbsent(p.descriptor().id(), p);
        if (old != null && !old.descriptor().version().equals(p.descriptor().version())) {
            throw new IllegalStateException("two versions of pack " + p.descriptor().id() + " on the class path: "
                    + old.descriptor().version() + " and " + p.descriptor().version());
        }
    }

    public Collection<SangamPack> all() {
        return List.copyOf(packs.values());
    }

    public Optional<SangamPack> find(String id) {
        return Optional.ofNullable(packs.get(id));
    }

    /** Packs whose {@code covers} list contains {@code ref} (GP-xx, UP-xx, AC-xx ...). */
    public List<SangamPack> covering(String ref) {
        return packs.values().stream().filter(p -> p.descriptor().covers().contains(ref)).toList();
    }

    /** {@code ids} and everything they depend on, dependencies first. */
    public List<SangamPack> deploymentOrder(Collection<String> ids) {
        List<SangamPack> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (String id : ids) {
            visit(id, out, done, new HashSet<>());
        }
        return out;
    }

    /** Every pack, dependencies first. */
    public List<SangamPack> deploymentOrder() {
        return deploymentOrder(packs.keySet());
    }

    private void visit(String id, List<SangamPack> out, Set<String> done, Set<String> path) {
        if (done.contains(id)) {
            return;
        }
        if (!path.add(id)) {
            throw new IllegalStateException("pack dependency cycle at " + id);
        }
        SangamPack p = packs.get(id);
        if (p == null) {
            throw new IllegalStateException("pack not found: " + id);
        }
        for (String dep : p.descriptor().dependsOn()) {
            visit(dep, out, done, path);
        }
        done.add(id);
        out.add(p);
    }
}
