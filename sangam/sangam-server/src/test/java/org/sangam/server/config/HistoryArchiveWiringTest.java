// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.core.history.HistoryArchiveGate;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.ops.archive.HistoryArchives;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;

/**
 * RM-52 wiring (decision 386): off, the gate refuses every purge; on, it needs a store, a retention longer than the
 * configured hierarchy retention, and the archive key as secret references only.
 */
class HistoryArchiveWiringTest {

    @TempDir
    Path dir;

    static MockEnvironment env() {
        MockEnvironment env = new MockEnvironment();
        env.setConversionService(new ApplicationConversionService());
        return env;
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    SecretResolver keys() throws Exception {
        KeyPair k = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return SecretResolver.of(Map.of("secret://archive/private", b64(k.getPrivate().getEncoded()),
                "secret://archive/public", b64(k.getPublic().getEncoded())));
    }

    MockEnvironment on() {
        return env().withProperty("sangam.history.archive.enabled", "true")
                .withProperty("sangam.history.archive.store-root", dir.resolve("store").toString())
                .withProperty("sangam.history.archive.retention-days", "3650")
                .withProperty("sangam.history.archive.signing.private-key-ref", "secret://archive/private")
                .withProperty("sangam.history.archive.signing.public-key-ref", "secret://archive/public");
    }

    @Test
    void offTheGateRefusesEveryTree() {
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        HistoryArchiveGate g = new HistoryArchiveWiring().sangamHistoryArchiveGate(env(),
                none.getBeanProvider(SecretResolver.class), none.getBeanProvider(org.sangam.rest.support.TxSupport.class));
        String refusal = g.refusal(null, "t", "root-1");
        assertNotNull(refusal);
        assertTrue(refusal.startsWith(HistoryArchiveGate.NOT_CONFIGURED), refusal);
    }

    @Test
    void onItStoresUnderTheRecordsClassWithTheReferencedKey() throws Exception {
        HistoryArchives a = HistoryArchiveWiring.archives(on(), keys(), new AuditChain(Dialect.POSTGRESQL));
        assertEquals("RECORDS_HISTORY", a.settings().retentionClass());
        assertEquals(3650, a.settings().retentionDays());
        assertTrue(Files.isDirectory(dir.resolve("store")));
        HistoryArchives custom = HistoryArchiveWiring.archives(on().withProperty("sangam.history.archive.retention-class",
                "STATE_ARCHIVE_30Y"), keys(), new AuditChain(Dialect.POSTGRESQL));
        assertEquals("STATE_ARCHIVE_30Y", custom.settings().retentionClass());
    }

    @Test
    void aMissingOrShortOrLiteralSettingFailsTheStartUp() throws Exception {
        SecretResolver keys = keys();
        AuditChain audit = new AuditChain(Dialect.POSTGRESQL);
        assertThrows(IllegalStateException.class, () -> HistoryArchiveWiring.archives(
                on().withProperty("sangam.history.archive.store-root", ""), keys, audit));
        assertThrows(IllegalStateException.class, () -> HistoryArchiveWiring.archives(env()
                .withProperty("sangam.history.archive.enabled", "true")
                .withProperty("sangam.history.archive.store-root", dir.toString()), keys, audit), "retention-days required");
        IllegalStateException shorter = assertThrows(IllegalStateException.class, () -> HistoryArchiveWiring.archives(
                on().withProperty("sangam.ops.retention-json", "{\"gov.file\":\"P3650D\"}"), keys, audit));
        assertTrue(shorter.getMessage().contains("not longer"), shorter.getMessage());
        assertSame(HistoryArchives.class, HistoryArchiveWiring.archives(
                on().withProperty("sangam.ops.retention-json", "{\"gov.file\":\"P3649D\"}"), keys, audit).getClass());
        IllegalStateException literal = assertThrows(IllegalStateException.class, () -> HistoryArchiveWiring.archives(
                on().withProperty("sangam.history.archive.signing.private-key-ref", "MC4CAQAwBQYDK2VwBCIEI"), keys, audit));
        assertTrue(literal.getMessage().contains("secret reference"), literal.getMessage());
        assertThrows(IllegalStateException.class, () -> HistoryArchiveWiring.archives(
                on().withProperty("sangam.history.archive.signing.trusted-public-key-refs", "secret://archive/missing"),
                keys, audit), "an unresolved trusted key fails the start-up");
        assertThrows(IllegalArgumentException.class, () -> HistoryArchiveWiring.archives(
                on().withProperty("sangam.history.archive.retention-class", "records"), keys, audit));
    }
}
