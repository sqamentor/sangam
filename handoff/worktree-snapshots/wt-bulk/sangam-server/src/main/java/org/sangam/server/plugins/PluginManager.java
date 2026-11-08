// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.Hashes;
import org.sangam.core.plugin.BpmnDelegateProvider;
import org.sangam.core.plugin.DmnFunctionProvider;
import org.sangam.core.plugin.PluginAware;
import org.sangam.core.plugin.ProfileBundleProvider;
import org.sangam.onboarding.deps.PlatformInfo;
import org.sangam.onboarding.deps.VersionRange;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signed plugin drop-in. Every {@code *.jar} in the plugin folder is handled at start-up, in name order:
 * <ol>
 *   <li>disabled in {@code plugins-state.json}? recorded and skipped;</li>
 *   <li>the bytes are read once and the detached signature {@code <jar>.sig} (base64) is verified against every key in
 *       the trusted-keys folder ({@code *.pub}, base64 or PEM X.509: Ed25519, RSA or EC). Unsigned or invalid is refused
 *       outside development. A signature that does not verify is refused everywhere;</li>
 *   <li>the verified bytes are copied to the plugin's private work folder, and only that copy is ever loaded;</li>
 *   <li>{@code META-INF/sangam/plugin.json} gives id, version and {@code requires}: checked against the platform;</li>
 *   <li>an isolated {@link PluginClassLoader} loads the services through {@link ServiceLoader}.</li>
 * </ol>
 * A refused or failing plugin never stops the server: it is reported through the admin API. Every decision goes to the
 * hash-chained audit file. Loaded code is never swapped: enable and disable take effect at the next restart.
 */
public final class PluginManager {

    private static final Logger LOG = LoggerFactory.getLogger(PluginManager.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String DESCRIPTOR = "META-INF/sangam/plugin.json";
    public static final String UI_MANIFEST = "ui/manifest.json";
    private static final long MAX_UI_BYTES = 64L * 1024 * 1024;

    /** Status: LOADED, DISABLED, REFUSED or FAILED. */
    public record PluginInfo(String id, String version, String name, String vendor, String file, String status,
                             String reason, String signedBy, boolean enabledAfterRestart, Map<String, Object> provides) {
    }

    /** Settings. {@code development} permits unsigned jars. */
    public record Settings(Path folder, Path trustedKeys, Path workFolder, boolean development,
                           Map<String, String> pluginProperties) {
    }

    private final Settings settings;
    private final PlatformInfo platform;
    private final ClassLoader parent;
    private final List<PluginInfo> plugins = new ArrayList<>();
    private final Map<String, UiBundle> uiBundles = new LinkedHashMap<>();
    private final List<SangamConnector> connectors = new ArrayList<>();
    private final List<SangamPack> packs = new ArrayList<>();
    private final List<JurisdictionPack> jurisdictions = new ArrayList<>();
    private final List<PluginDocuments> profiles = new ArrayList<>();
    private final Map<String, Object> delegates = new LinkedHashMap<>();
    private final Map<String, DmnFunctionProvider.DmnFunction> functions = new LinkedHashMap<>();
    private final List<ClassLoader> loaders = new ArrayList<>();
    private final Set<String> disabled = new LinkedHashSet<>();

    /** Profile resource texts from one plugin. */
    public record UiBundle(String id, String version, Map<String, Object> manifest, Map<String, byte[]> assets) {
    }

    public record PluginDocuments(String pluginId, List<String> texts) {
    }

    public PluginManager(Settings settings, PlatformInfo platform, ClassLoader parent) {
        this.settings = settings;
        this.platform = platform;
        this.parent = parent;
    }

    /** Loads everything in the folder. Called once, before any regular bean exists. */
    public synchronized void load() {
        if (!Files.isDirectory(settings.folder())) {
            LOG.info("No plugin folder at {}", settings.folder().toAbsolutePath());
            return;
        }
        readState();
        List<PublicKey> keys = new ArrayList<>();
        Map<PublicKey, String> keyNames = new LinkedHashMap<>();
        readTrustedKeys(keys, keyNames);
        List<Path> jars;
        try (Stream<Path> s = Files.list(settings.folder())) {
            jars = s.filter(p -> p.getFileName().toString().matches(".*\\.(jar|zip)$")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path jar : jars) {
            try {
                loadOne(jar, keys, keyNames);
            } catch (Exception | LinkageError e) {
                LOG.error("Plugin {} failed: {}", jar.getFileName(), e.toString());
                record(new PluginInfo(null, null, null, null, jar.getFileName().toString(), "FAILED", e.toString(), null,
                        true, Map.of()));
            }
        }
    }

    private void loadOne(Path jar, List<PublicKey> keys, Map<PublicKey, String> keyNames) throws IOException {
        String fileName = jar.getFileName().toString();
        byte[] bytes = Files.readAllBytes(jar);
        Path sig = jar.resolveSibling(fileName + ".sig");
        String signedBy = null;
        if (Files.exists(sig)) {
            signedBy = verify(bytes, Files.readString(sig, StandardCharsets.UTF_8).trim(), keys, keyNames);
            if (signedBy == null) {
                record(new PluginInfo(null, null, null, null, fileName, "REFUSED",
                        "the signature does not verify against a trusted key", null, true, Map.of()));
                return;
            }
        } else if (!settings.development()) {
            record(new PluginInfo(null, null, null, null, fileName, "REFUSED",
                    "unsigned plugins are refused outside development", null, true, Map.of()));
            return;
        }
        Path work = settings.workFolder().resolve(Hashes.hex(Hashes.sha256(bytes)).substring(0, 16));
        Files.createDirectories(work);
        Path verified = work.resolve(fileName);
        Files.write(verified, bytes);
        if (fileName.endsWith(".zip")) {
            loadUiOnly(fileName, verified, signedBy);
            return;
        }
        PluginClassLoader loader = new PluginClassLoader(fileName, url(verified), parent);
        JsonNode d;
        try (InputStream in = loader.getResourceAsStream(DESCRIPTOR)) {
            if (in == null) {
                loader.close();
                record(new PluginInfo(null, null, null, null, fileName, "REFUSED", "no " + DESCRIPTOR, signedBy, true, Map.of()));
                return;
            }
            d = JSON.readTree(in);
        }
        String id = d.path("id").asText("");
        String version = d.path("version").asText("");
        String name = d.path("name").asText(id);
        String vendor = d.path("vendor").asText("");

        String refusal = id.isBlank() ? "descriptor has no id" : plugins.stream().anyMatch(p -> id.equals(p.id()) && "LOADED".equals(p.status()))
                ? "another jar already provides plugin " + id : compatibility(d.path("requires"));
        if (refusal != null) {
            loader.close();
            record(new PluginInfo(id, version, name, vendor, fileName, "REFUSED", refusal, signedBy, true, Map.of()));
            return;
        }
        if (disabled.contains(id)) {
            loader.close();
            record(new PluginInfo(id, version, name, vendor, fileName, "DISABLED", "disabled by an administrator", signedBy,
                    false, Map.of()));
            return;
        }
        UiBundle ui = readUi(verified, id, version);
        Map<String, Object> provides = services(id, version, loader);
        if (ui != null) {
            uiBundles.put(id, ui);
            provides.put("ui", ui.manifest());
        }
        loaders.add(loader);
        record(new PluginInfo(id, version, name, vendor, fileName, "LOADED", null, signedBy, true, provides));
        LOG.info("Plugin {}@{} loaded (signed by {}): {}", id, version, signedBy, provides);
    }

    /** A UI-only bundle: the same signature, compatibility, enable/disable and audit rules, and no code on the server. */
    private void loadUiOnly(String fileName, Path verified, String signedBy) throws IOException {
        UiBundle ui = readUi(verified, null, null);
        if (ui == null) {
            record(new PluginInfo(null, null, null, null, fileName, "REFUSED", "no " + UI_MANIFEST, signedBy, true, Map.of()));
            return;
        }
        JsonNode m = JSON.valueToTree(ui.manifest());
        String id = ui.id();
        String refusal = plugins.stream().anyMatch(p -> id.equals(p.id()) && "LOADED".equals(p.status()))
                ? "another bundle already provides plugin " + id : compatibility(m.path("requires"));
        String name = m.path("name").asText(id);
        String vendor = m.path("vendor").asText("");
        if (refusal != null) {
            record(new PluginInfo(id, ui.version(), name, vendor, fileName, "REFUSED", refusal, signedBy, true, Map.of()));
        } else if (disabled.contains(id)) {
            record(new PluginInfo(id, ui.version(), name, vendor, fileName, "DISABLED", "disabled by an administrator", signedBy, false, Map.of()));
        } else {
            uiBundles.put(id, ui);
            record(new PluginInfo(id, ui.version(), name, vendor, fileName, "LOADED", null, signedBy, true,
                    new LinkedHashMap<>(Map.of("ui", ui.manifest()))));
        }
    }

    /** Reads and checks ui/manifest.json and the assets; null when the file has no UI. Assets stay in memory, immutable. */
    private static UiBundle readUi(Path file, String expectedId, String expectedVersion) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            java.util.zip.ZipEntry me = zip.getEntry(UI_MANIFEST);
            if (me == null) {
                return null;
            }
            JsonNode m;
            try (InputStream in = zip.getInputStream(me)) {
                m = JSON.readTree(in);
            }
            String id = m.path("id").asText("");
            String version = m.path("version").asText("");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") || version.isBlank()) {
                throw new IllegalArgumentException(UI_MANIFEST + " needs a safe id and a version");
            }
            if (expectedId != null && (!expectedId.equals(id) || !expectedVersion.equals(version))) {
                throw new IllegalArgumentException(UI_MANIFEST + " must carry the plugin's id and version");
            }
            Map<String, byte[]> assets = new LinkedHashMap<>();
            long total = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith("ui/") || n.equals(UI_MANIFEST)) {
                    continue;
                }
                String path = n.substring(3);
                if (path.contains("..") || path.startsWith("/") || path.contains("\\") || contentType(path) == null) {
                    throw new IllegalArgumentException("UI asset not allowed: " + n);
                }
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] b = in.readNBytes((int) (MAX_UI_BYTES - total + 1));
                    total += b.length;
                    if (total > MAX_UI_BYTES) {
                        throw new IllegalArgumentException("UI bundle is larger than " + MAX_UI_BYTES + " bytes");
                    }
                    assets.put(path, b);
                }
            }
            String entry = m.path("entry").asText("");
            if (!assets.containsKey(entry)) {
                throw new IllegalArgumentException(UI_MANIFEST + ": entry '" + entry + "' is not in the bundle");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> manifest = JSON.convertValue(m, LinkedHashMap.class);
            return new UiBundle(id, version, Map.copyOf(manifest), Map.copyOf(assets));
        }
    }

    /** Allowed asset types; null refuses the file. HTML is not allowed: a bundle extends the shell, it is not a page. */
    public static String contentType(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        String ext = p.substring(p.lastIndexOf('.') + 1);
        return switch (ext) {
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json", "map" -> "application/json";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            default -> null;
        };
    }

    private String compatibility(JsonNode q) {
        if (!VersionRange.parse(q.path("sangam").asText("*")).contains(platform.sangamVersion())) {
            return "needs platform " + q.path("sangam").asText() + ", this is " + platform.sangamVersion();
        }
        if (!VersionRange.parse(q.path("engine").asText("*")).contains(platform.engineVersion())) {
            return "needs engine " + q.path("engine").asText() + ", this is " + platform.engineVersion();
        }
        var it = q.path("modules").fields();
        while (it.hasNext()) {
            var e = it.next();
            String have = platform.modules().get(e.getKey());
            if (have == null || !VersionRange.parse(e.getValue().asText("*")).contains(have)) {
                return "needs module " + e.getKey() + " " + e.getValue().asText() + ", found " + have;
            }
        }
        return null;
    }

    private Map<String, Object> services(String id, String version, ClassLoader loader) throws IOException {
        Path data = settings.workFolder().resolve("data").resolve(id);
        Files.createDirectories(data);
        PluginAware.PluginContext ctx = new PluginAware.PluginContext(id, version,
                propertiesOf(id), data);
        // collected first: a plugin that fails half way contributes nothing
        List<SangamConnector> cs = init(ServiceLoader.load(SangamConnector.class, loader), ctx);
        List<SangamPack> ps = new ArrayList<>(PackRegistry.discover(loader).all());
        ps.forEach(p -> initOne(p, ctx));
        List<JurisdictionPack> js = new ArrayList<>(JurisdictionRegistry.discover(loader).all());
        Map<String, Object> ds = new LinkedHashMap<>();
        for (BpmnDelegateProvider p : init(ServiceLoader.load(BpmnDelegateProvider.class, loader), ctx)) {
            ds.putAll(p.delegates());
        }
        for (Map.Entry<String, Object> e : ds.entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof org.operaton.bpm.engine.delegate.JavaDelegate
                    || v instanceof org.operaton.bpm.engine.delegate.ExecutionListener
                    || v instanceof org.operaton.bpm.engine.delegate.TaskListener)) {
                throw new IllegalArgumentException("delegate " + e.getKey() + " is not a JavaDelegate or listener");
            }
            if (delegates.containsKey(e.getKey())) {
                throw new IllegalArgumentException("delegate name already in use: " + e.getKey());
            }
        }
        Map<String, DmnFunctionProvider.DmnFunction> fs = new LinkedHashMap<>();
        for (DmnFunctionProvider p : init(ServiceLoader.load(DmnFunctionProvider.class, loader), ctx)) {
            fs.putAll(p.functions());
        }
        List<String> docs = new ArrayList<>();
        for (ProfileBundleProvider p : init(ServiceLoader.load(ProfileBundleProvider.class, loader), ctx)) {
            for (String r : p.profileResources()) {
                docs.add(text(loader, r));
            }
        }
        try (InputStream in = loader.getResourceAsStream(ProfileBundleProvider.DEFAULT_INDEX)) {
            if (in != null) {
                String base = "";
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        docs.add(text(loader, base + line));
                    }
                }
            }
        }
        connectors.addAll(cs);
        packs.addAll(ps);
        jurisdictions.addAll(js);
        delegates.putAll(ds);
        functions.putAll(fs);
        if (!docs.isEmpty()) {
            profiles.add(new PluginDocuments(id, docs));
        }
        Map<String, Object> provides = new LinkedHashMap<>();
        provides.put("connectors", cs.stream().map(c -> c.descriptor().id()).toList());
        provides.put("packs", ps.stream().map(p -> p.descriptor().coordinates()).toList());
        provides.put("jurisdictions", js.stream().map(JurisdictionPack::code).toList());
        provides.put("profileDocuments", docs.size());
        provides.put("delegates", List.copyOf(ds.keySet()));
        provides.put("dmnFunctions", List.copyOf(fs.keySet()));
        return provides;
    }

    /** Keys under {@code <id>.} with the prefix removed; matched by prefix, so an id may contain dots. */
    private Map<String, String> propertiesOf(String id) {
        Map<String, String> out = new LinkedHashMap<>();
        settings.pluginProperties().forEach((k, v) -> {
            if (k.startsWith(id + ".")) {
                out.put(k.substring(id.length() + 1), v);
            }
        });
        return out;
    }

    private static String text(ClassLoader loader, String path) throws IOException {
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("profile resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static <T> List<T> init(Iterable<T> services, PluginAware.PluginContext ctx) {
        List<T> out = new ArrayList<>();
        for (T s : services) {
            initOne(s, ctx);
            out.add(s);
        }
        return out;
    }

    private static void initOne(Object s, PluginAware.PluginContext ctx) {
        if (s instanceof PluginAware a) {
            a.init(ctx);
        }
    }

    static String verify(byte[] data, String signatureBase64, List<PublicKey> keys, Map<PublicKey, String> names) {
        byte[] sig;
        try {
            sig = Base64.getMimeDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (PublicKey k : keys) {
            try {
                Signature s = Signature.getInstance(switch (k.getAlgorithm()) {
                    case "RSA" -> "SHA256withRSA";
                    case "EC" -> "SHA256withECDSA";
                    default -> "Ed25519";
                });
                s.initVerify(k);
                s.update(data);
                if (s.verify(sig)) {
                    return names.get(k);
                }
            } catch (GeneralSecurityException e) {
                LOG.debug("key {} does not verify: {}", names.get(k), e.toString());
            }
        }
        return null;
    }

    private void readTrustedKeys(List<PublicKey> keys, Map<PublicKey, String> names) {
        if (!Files.isDirectory(settings.trustedKeys())) {
            return;
        }
        try (Stream<Path> s = Files.list(settings.trustedKeys())) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".pub")).sorted().toList()) {
                PublicKey k = parseKey(Files.readString(p, StandardCharsets.UTF_8));
                if (k == null) {
                    LOG.error("Trusted key {} cannot be read", p);
                } else {
                    keys.add(k);
                    names.put(k, p.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static PublicKey parseKey(String text) {
        String b64 = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (String alg : List.of("Ed25519", "RSA", "EC")) {
            try {
                return KeyFactory.getInstance(alg).generatePublic(new X509EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                // next algorithm
            }
        }
        return null;
    }

    private static java.net.URL url(Path p) {
        try {
            return p.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ------------------------------------------------------------------ admin

    /** Takes effect at the next restart. */
    public synchronized PluginInfo setEnabled(String id, boolean enabled, String actor) {
        int i = indexOf(id);
        if (i < 0) {
            throw new IllegalArgumentException("no plugin " + id);
        }
        if (enabled) {
            disabled.remove(id);
        } else {
            disabled.add(id);
        }
        writeState();
        PluginInfo p = plugins.get(i);
        PluginInfo next = new PluginInfo(p.id(), p.version(), p.name(), p.vendor(), p.file(), p.status(), p.reason(),
                p.signedBy(), enabled, p.provides());
        plugins.set(i, next);
        audit(enabled ? "ENABLED" : "DISABLED", id, p.version(), actor, "takes effect at the next restart");
        return next;
    }

    private int indexOf(String id) {
        for (int i = 0; i < plugins.size(); i++) {
            if (id.equals(plugins.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    public synchronized List<PluginInfo> plugins() {
        return List.copyOf(plugins);
    }

    /** The audit trail, oldest first (each line carries the hash of the previous one). */
    public synchronized List<String> auditTrail() {
        Path f = settings.folder().resolve("plugins-audit.log");
        try {
            return Files.exists(f) ? Files.readAllLines(f, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void record(PluginInfo p) {
        plugins.add(p);
        audit(p.status(), p.id() == null ? p.file() : p.id(), p.version(), "system",
                p.reason() == null ? "signed by " + p.signedBy() : p.reason());
    }

    private void audit(String action, String id, String version, String actor, String detail) {
        Path f = settings.folder().resolve("plugins-audit.log");
        try {
            List<String> old = Files.exists(f) ? Files.readAllLines(f, StandardCharsets.UTF_8) : List.of();
            String prev = old.isEmpty() ? "" : old.get(old.size() - 1);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("at", Instant.now().toString());
            e.put("action", action);
            e.put("plugin", id);
            e.put("version", version);
            e.put("actor", actor);
            e.put("detail", detail);
            e.put("prev", Hashes.hex(Hashes.sha256(prev)));
            Files.writeString(f, JSON.writeValueAsString(e) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("Plugin audit trail not writable: {}", e.toString());
        }
    }

    private void readState() {
        Path f = settings.folder().resolve("plugins-state.json");
        if (Files.exists(f)) {
            try {
                JSON.readTree(f.toFile()).path("disabled").forEach(x -> disabled.add(x.asText()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void writeState() {
        Path f = settings.folder().resolve("plugins-state.json");
        Path tmp = settings.folder().resolve("plugins-state.json.tmp");
        try {
            Files.writeString(tmp, JSON.writeValueAsString(Map.of("disabled", disabled)), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ what was loaded

    public List<UiBundle> uiBundles() {
        return uiBundles.values().stream().filter(u -> !disabled.contains(u.id())).toList();
    }

    public java.util.Optional<UiBundle> uiBundle(String id) {
        return disabled.contains(id) ? java.util.Optional.empty() : java.util.Optional.ofNullable(uiBundles.get(id));
    }

    public List<SangamConnector> connectors() {
        return List.copyOf(connectors);
    }

    public List<SangamPack> packs() {
        return List.copyOf(packs);
    }

    public List<JurisdictionPack> jurisdictions() {
        return List.copyOf(jurisdictions);
    }

    public List<PluginDocuments> profileDocuments() {
        return List.copyOf(profiles);
    }

    public Map<String, Object> delegates() {
        return Map.copyOf(delegates);
    }

    public Map<String, DmnFunctionProvider.DmnFunction> dmnFunctions() {
        return Map.copyOf(functions);
    }

    public List<ClassLoader> classLoaders() {
        return List.copyOf(loaders);
    }
}
