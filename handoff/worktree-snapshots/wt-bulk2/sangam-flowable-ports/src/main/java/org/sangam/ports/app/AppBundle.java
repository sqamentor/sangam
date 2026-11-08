// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.sangam.core.layer.Hashes;
import org.sangam.ports.support.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * App definition bundle (FLOWABLE-INTO-OPERATON FC-41, Flowable app engine {@code .bar}/{@code .zip} with an {@code .app} model): one archive
 * with a manifest {@code sangam-app.json} (Flowable {@code *.app} JSON is accepted too) and the BPMN, CMMN, DMN,
 * form, event and channel models that ship together. The bundle is the R43 executable package unit: every resource is
 * hashed and the bundle hash is the SHA-256 over the {@code name=hash} lines of the manifest and then every resource in
 * name order, which is what approval signs.
 *
 * <p>Reading is defensive: zip-slip names, nested archives, more than {@link #MAX_ENTRIES} entries and more than
 * {@link #MAX_BYTES} uncompressed bytes are rejected (zip-bomb guard).
 */
public record AppBundle(Manifest manifest, Map<String, byte[]> resources, Map<String, String> resourceHashes, String bundleSha256) {

    public static final int MAX_ENTRIES = 500;
    public static final long MAX_BYTES = 64L * 1024 * 1024;

    public enum Kind { BPMN, CMMN, DMN, FORM, EVENT, CHANNEL, OTHER }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(@JsonProperty("key") String key, @JsonProperty("name") String name,
                           @JsonProperty("description") String description, @JsonProperty("theme") String theme,
                           @JsonProperty("icon") String icon, @JsonProperty("usersAccess") List<String> usersAccess,
                           @JsonProperty("groupsAccess") List<String> groupsAccess) {
    }

    public static Kind kind(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".bpmn") || n.endsWith(".bpmn20.xml") || n.endsWith(".bpmn.xml")) {
            return Kind.BPMN;
        }
        if (n.endsWith(".cmmn") || n.endsWith(".cmmn.xml") || n.endsWith(".cmmn11.xml")) {
            return Kind.CMMN;
        }
        if (n.endsWith(".dmn") || n.endsWith(".dmn.xml") || n.endsWith(".dmn11.xml")) {
            return Kind.DMN;
        }
        if (n.endsWith(".form") || n.endsWith(".form.json")) {
            return Kind.FORM;
        }
        if (n.endsWith(".event") || n.endsWith(".event.json")) {
            return Kind.EVENT;
        }
        if (n.endsWith(".channel") || n.endsWith(".channel.json")) {
            return Kind.CHANNEL;
        }
        return Kind.OTHER;
    }

    public static AppBundle read(byte[] zip) throws IOException {
        if (zip == null || zip.length == 0) {
            throw new IOException("empty app bundle");
        }
        Map<String, byte[]> resources = new TreeMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            int count = 0;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw new IOException("app bundle has more than " + MAX_ENTRIES + " entries");
                }
                String name = e.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.contains(":") || name.equals("..") || name.length() > 512) {
                    throw new IOException("unsafe entry name in app bundle: " + name);
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".zip") || lower.endsWith(".bar") || lower.endsWith(".jar") || lower.endsWith(".war")) {
                    throw new IOException("nested archives are not allowed in an app bundle: " + name);
                }
                if (resources.containsKey(name)) {
                    throw new IOException("duplicate entry in app bundle: " + name);
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IOException("app bundle exceeds " + MAX_BYTES + " bytes uncompressed");
                    }
                    buf.write(chunk, 0, n);
                }
                resources.put(name, buf.toByteArray());
            }
        }
        return of(resources);
    }

    /** Builds a bundle from resources (directory deploys, packs). */
    public static AppBundle of(Map<String, byte[]> files) {
        Map<String, byte[]> resources = new TreeMap<>(files);
        String manifestName = "sangam-app.json";
        byte[] manifestBytes = resources.remove(manifestName);
        if (manifestBytes == null) {
            String appFile = resources.keySet().stream().filter(k -> k.toLowerCase(Locale.ROOT).endsWith(".app")).findFirst().orElse(null);
            if (appFile != null) {
                manifestName = appFile;
                manifestBytes = resources.remove(appFile);
            }
        }
        if (manifestBytes == null) {
            throw new IllegalArgumentException("app bundle has no sangam-app.json (or Flowable .app) manifest");
        }
        Manifest m = Json.read(new String(manifestBytes, StandardCharsets.UTF_8), Manifest.class);
        if (m.key() == null || !m.key().matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new IllegalArgumentException("app manifest needs a key of [A-Za-z0-9_.-]{1,128}");
        }
        Map<String, String> hashes = new TreeMap<>();
        // R43: the approver signs this hash, so it covers the manifest too (usersAccess / groupsAccess decide who may
        // use the app): an access change is a new bundle that needs a new approval, not a no-op redeploy
        StringBuilder lines = new StringBuilder();
        lines.append(manifestName).append('=').append(Hashes.hex(Hashes.sha256(manifestBytes))).append('\n');
        for (Map.Entry<String, byte[]> r : resources.entrySet()) {
            String h = Hashes.hex(Hashes.sha256(r.getValue()));
            hashes.put(r.getKey(), h);
            lines.append(r.getKey()).append('=').append(h).append('\n');
        }
        String bundle = Hashes.hex(Hashes.sha256(lines.toString().getBytes(StandardCharsets.UTF_8)));
        return new AppBundle(m, resources, hashes, bundle);
    }
}
