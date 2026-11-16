// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.sangam.core.history.HistoryArchiveGate;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.ops.archive.HistoryArchives;
import org.sangam.ports.content.FileSystemDocumentStore;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * RM-52 (decision 386): the history archive of this server and the {@link HistoryArchiveGate} that every purge of
 * engine history asks (the engine's own deletes and cleanup through {@code SangamEngineSettings}, the hierarchy
 * retention, a partition drop).
 *
 * <ul>
 *   <li>{@code sangam.history.archive.enabled}: off by default. Off, the gate is
 *       {@link HistoryArchiveGate#notConfigured()}: no history is purged at all, by any path, and the start-up says
 *       so.</li>
 *   <li>{@code .store-root}: the archive document store (decision 386(1)); archives are stored under
 *       {@code <tenant>/records/<retention class>/history/<root>/}.</li>
 *   <li>{@code .retention-class} (default {@code RECORDS_HISTORY}) and {@code .retention-days} (required): the
 *       records-retention class and how long it keeps an archive. It must be longer than every engine history
 *       retention: the start-up refuses a value not longer than {@code sangam.ops.retention-json}'s longest entry, and
 *       each archive refuses a tree whose definitions keep history as long ({@code historyTimeToLive}).</li>
 *   <li>{@code .signing.private-key-ref}, {@code .signing.public-key-ref}: secret references ({@code secret://...})
 *       to the archive key (Ed25519, base64 PKCS#8 / X.509; a key of its own, decision 37);
 *       {@code .signing.trusted-public-key-refs}: further trusted public keys (retired keys whose archives are kept).
 *       A literal key, or a reference that does not resolve, fails the start-up.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class HistoryArchiveWiring {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryArchiveWiring.class);
    static final String PREFIX = "sangam.history.archive.";

    @Bean
    public HistoryArchiveGate sangamHistoryArchiveGate(Environment env, ObjectProvider<SecretResolver> secrets,
                                                       ObjectProvider<TxSupport> tx) {
        if (!env.getProperty(PREFIX + "enabled", Boolean.class, false)) {
            LOG.warn("RM-52: no history archive is configured ({}enabled=false), so no engine history is purged by"
                    + " any path (decision 386)", PREFIX);
            return HistoryArchiveGate.notConfigured();
        }
        return archives(env, secrets.getIfAvailable(SecretResolver::fromEnvironment),
                new AuditChain(tx.getObject().dialect()));
    }

    static HistoryArchives archives(Environment env, SecretResolver secrets, AuditChain audit) {
        String root = env.getProperty(PREFIX + "store-root");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException(PREFIX + "store-root is required with " + PREFIX + "enabled=true");
        }
        Integer days = env.getProperty(PREFIX + "retention-days", Integer.class);
        if (days == null) {
            throw new IllegalStateException(PREFIX + "retention-days is required with " + PREFIX + "enabled=true");
        }
        HistoryArchives.Settings settings = new HistoryArchives.Settings(
                env.getProperty(PREFIX + "retention-class", "RECORDS_HISTORY"), days, version(),
                env.getProperty("operaton.bpm.database.table-prefix", ""));
        HistoryArchives.requireLongerThanEngine(settings, OpsWiring.longestRetentionDays(env));
        String privateKey = resolve(secrets, "signing.private-key-ref", env.getProperty(PREFIX + "signing.private-key-ref"));
        String publicKey = resolve(secrets, "signing.public-key-ref", env.getProperty(PREFIX + "signing.public-key-ref"));
        AuditCheckpoints.Signer signer = AuditCheckpoints.ed25519(privateKey, publicKey);
        List<String> trusted = new ArrayList<>(List.of(publicKey));
        for (String ref : env.getProperty(PREFIX + "signing.trusted-public-key-refs", "").split(",")) {
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

    private static String version() {
        String v = HistoryArchiveWiring.class.getPackage().getImplementationVersion();
        return v == null ? "development" : v;
    }
}
