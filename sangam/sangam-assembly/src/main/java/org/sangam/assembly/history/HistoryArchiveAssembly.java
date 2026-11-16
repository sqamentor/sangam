// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.history;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.assembly.host.SangamProperties;
import org.sangam.core.history.HistoryArchiveGate;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.ops.archive.HistoryArchives;
import org.sangam.ports.content.FileSystemDocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RM-52 (decision 386): the history archive of a host, assembled in plain Java so that every host reads the same keys
 * the same way (the Spring host's {@code HistoryArchiveWiring} delegates here).
 *
 * <ul>
 *   <li>{@code sangam.history.archive.enabled}: off by default. Off, the gate is {@link HistoryArchiveGate#notConfigured()}:
 *       no engine history is purged by any path.</li>
 *   <li>{@code .store-root}: the archive document store (a file-system store, decision 386(1)), which enforces the
 *       retention class ({@code org.sangam.ops.archive.RetentionClassStore}).</li>
 *   <li>{@code .retention-class} (default {@code RECORDS_HISTORY}) and {@code .retention-days} (required): longer than
 *       the longest hierarchy retention of {@code sangam.ops.retention-json}, or the start-up is refused.</li>
 *   <li>{@code .signing.private-key-ref}, {@code .signing.public-key-ref}, {@code .signing.trusted-public-key-refs}:
 *       secret references ({@code secret://...}) only; a literal or an unresolved reference fails the start-up.</li>
 * </ul>
 */
public final class HistoryArchiveAssembly {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryArchiveAssembly.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String PREFIX = "sangam.history.archive.";
    public static final String DEFAULT_CLASS = "RECORDS_HISTORY";

    private HistoryArchiveAssembly() {
    }

    /** The gate of this host: the archives when enabled, otherwise the gate that refuses every purge. */
    public static HistoryArchiveGate gate(SangamProperties p, SecretResolver secrets, AuditChain audit,
                                          String sangamVersion) {
        if (!p.getBoolean(PREFIX + "enabled", false)) {
            LOG.warn("RM-52: no history archive is configured ({}enabled=false), so no engine history is purged by"
                    + " any path (decision 386)", PREFIX);
            return HistoryArchiveGate.notConfigured();
        }
        return archives(p, secrets, audit, sangamVersion);
    }

    /** The configured archives; every missing, short or literal setting fails the start-up. */
    public static HistoryArchives archives(SangamProperties p, SecretResolver secrets, AuditChain audit,
                                           String sangamVersion) {
        String root = p.get(PREFIX + "store-root");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException(PREFIX + "store-root is required with " + PREFIX + "enabled=true");
        }
        String days = p.get(PREFIX + "retention-days");
        if (days == null || days.isBlank()) {
            throw new IllegalStateException(PREFIX + "retention-days is required with " + PREFIX + "enabled=true");
        }
        HistoryArchives.Settings settings = new HistoryArchives.Settings(p.get(PREFIX + "retention-class", DEFAULT_CLASS),
                p.getInt(PREFIX + "retention-days", 0), sangamVersion == null ? "development" : sangamVersion,
                p.get("operaton.bpm.database.table-prefix", ""));
        HistoryArchives.requireLongerThanEngine(settings, longestRetentionDays(p));
        String privateKey = resolve(secrets, "signing.private-key-ref", p.get(PREFIX + "signing.private-key-ref"));
        String publicKey = resolve(secrets, "signing.public-key-ref", p.get(PREFIX + "signing.public-key-ref"));
        AuditCheckpoints.Signer signer = AuditCheckpoints.ed25519(privateKey, publicKey);
        List<String> trusted = new ArrayList<>(List.of(publicKey));
        for (String ref : p.get(PREFIX + "signing.trusted-public-key-refs", "").split(",")) {
            if (!ref.isBlank()) {
                trusted.add(resolve(secrets, "signing.trusted-public-key-refs", ref.trim()));
            }
        }
        FileSystemDocumentStore store;
        try {
            store = new FileSystemDocumentStore(Path.of(root));
        } catch (IOException e) {
            throw new IllegalStateException("RM-52: cannot open the history archive store at " + root, e);
        }
        LOG.info("RM-52 history archive: store {}, class {} ({} days), key {}, {} trusted key(s)", root,
                settings.retentionClass(), settings.retentionDays(), signer.keyId(), trusted.size());
        return new HistoryArchives(store, audit, signer, AuditCheckpoints.ed25519Keys(trusted), settings);
    }

    /** The hierarchy retention of {@code sangam.ops.retention-json} (definition key to ISO-8601 duration). */
    public static Map<String, Duration> retention(SangamProperties p) {
        String json = p.get("sangam.ops.retention-json");
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, String> raw;
        try {
            raw = JSON.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("invalid sangam.ops JSON property: " + e.getMessage(), e);
        }
        Map<String, Duration> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, Duration.parse(v)));
        return out;
    }

    /** The longest configured hierarchy retention, in whole days rounded up (0 when none). */
    public static int longestRetentionDays(SangamProperties p) {
        long seconds = retention(p).values().stream().mapToLong(Duration::getSeconds).max().orElse(0);
        return (int) Math.min(Integer.MAX_VALUE, (seconds + 86_399) / 86_400);
    }

    /** The value behind a key reference; refuses a missing reference or a literal (keys are never configuration). */
    static String resolve(SecretResolver secrets, String property, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException(PREFIX + property + " is required with " + PREFIX + "enabled=true");
        }
        if (!ref.startsWith("secret://")) {
            throw new IllegalStateException(PREFIX + property + " must be a secret reference (secret://...), never the key");
        }
        return secrets.require(ref);
    }
}
