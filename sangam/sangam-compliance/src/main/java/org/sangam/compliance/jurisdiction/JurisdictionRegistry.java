// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.jurisdiction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The jurisdiction packs available to this deployment. Packs are discovered from every
 * {@value #INDEX} on the class path (one pack path per line, {@code #} comments), so a pack in its own jar is picked
 * up by adding the jar; a deployer can also load one from a file ({@link #load(Path)}).
 */
public final class JurisdictionRegistry {

    public static final String INDEX = "META-INF/sangam/jurisdictions.idx";

    private final Map<String, JurisdictionPack> packs = new ConcurrentHashMap<>();

    public static JurisdictionRegistry discover(ClassLoader loader) {
        JurisdictionRegistry r = new JurisdictionRegistry();
        try {
            Enumeration<URL> idx = loader.getResources(INDEX);
            while (idx.hasMoreElements()) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(idx.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        try (InputStream in = loader.getResourceAsStream(line)) {
                            if (in == null) {
                                throw new IllegalStateException("jurisdiction pack not found: " + line);
                            }
                            r.add(JurisdictionPack.parse(in));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return r;
    }

    public static JurisdictionRegistry discover() {
        return discover(JurisdictionRegistry.class.getClassLoader());
    }

    public JurisdictionPack load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            JurisdictionPack p = JurisdictionPack.parse(in);
            add(p);
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void add(JurisdictionPack p) {
        packs.put(p.code(), p);
    }

    public Optional<JurisdictionPack> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(packs.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    /** The named pack, or the only installed pack when {@code code} is blank. */
    public JurisdictionPack require(String code) {
        if (code == null || code.isBlank()) {
            if (packs.size() == 1) {
                return packs.values().iterator().next();
            }
            throw new IllegalStateException("sangam.compliance.jurisdiction must name one of " + packs.keySet());
        }
        return find(code).orElseThrow(() -> new IllegalStateException("no jurisdiction pack " + code + "; installed: " + packs.keySet()));
    }

    public Collection<JurisdictionPack> all() {
        return List.copyOf(packs.values());
    }
}
