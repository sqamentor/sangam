// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.core.history.HistoryArchiveGate;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.ops.archive.HistoryArchives;
import org.sangam.ops.archive.RetentionClassStore;

/**
 * RM-52 (decision 386) assembled in plain Java for every host: off, the gate refuses every purge; on, it is the
 * archives over a file-system store that enforces the retention class, with a retention longer than the hierarchy
 * retention and the archive key as secret references only. The Spring host's {@code HistoryArchiveWiring} delegates
 * here, so a plain-Java host reads the same keys the same way.
 */
class HistoryArchiveAssemblyTest {

    @TempDir
    Path dir;

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    SecretResolver keys() throws Exception {
        KeyPair k = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return SecretResolver.of(Map.of("secret://archive/private", b64(k.getPrivate().getEncoded()),
                "secret://archive/public", b64(k.getPublic().getEncoded())));
    }

    Map<String, String> on() {
        Map<String, String> m = new HashMap<>();
        m.put("sangam.history.archive.enabled", "true");
        m.put("sangam.history.archive.store-root", dir.resolve("store").toString());
        m.put("sangam.history.archive.retention-days", "3650");
        m.put("sangam.history.archive.signing.private-key-ref", "secret://archive/private");
        m.put("sangam.history.archive.signing.public-key-ref", "secret://archive/public");
        return m;
    }

    static SangamProperties props(Map<String, String> m) {
        return m::get;
    }

    static Map<String, String> with(Map<String, String> m, String key, String value) {
        Map<String, String> out = new HashMap<>(m);
        out.put(key, value);
        return out;
    }

    @Test
    void offTheGateRefusesEveryTree() throws Exception {
        HistoryArchiveGate g = HistoryArchiveAssembly.gate(props(Map.of()), keys(), new AuditChain(Dialect.POSTGRESQL), "t");
        String refusal = g.refusal(null, "t", "root-1");
        assertNotNull(refusal);
        assertTrue(refusal.startsWith(HistoryArchiveGate.NOT_CONFIGURED), refusal);
        assertTrue(HistoryArchiveAssembly.gate(props(with(on(), "sangam.history.archive.enabled", "off")), keys(),
                new AuditChain(Dialect.POSTGRESQL), "t").refusal(null, "t", "r").startsWith(HistoryArchiveGate.NOT_CONFIGURED));
    }

    @Test
    void onItIsTheArchivesOverAStoreThatEnforcesTheClass() throws Exception {
        HistoryArchiveGate g = HistoryArchiveAssembly.gate(props(on()), keys(), new AuditChain(Dialect.POSTGRESQL), "0.1.0");
        HistoryArchives a = assertInstanceOf(HistoryArchives.class, g);
        assertEquals("RECORDS_HISTORY", a.settings().retentionClass());
        assertEquals(3650, a.settings().retentionDays());
        assertEquals("0.1.0", a.settings().sangamVersion());
        assertTrue(Files.isDirectory(dir.resolve("store")));
        RetentionClassStore store = a.store();
        assertEquals("fs+retention", store.type());
        String key = "t1/records/RECORDS_HISTORY/history/root-1/aaaa.zip";
        store.put(key, new ByteArrayInputStream(new byte[] {1, 2, 3}), "application/zip");
        assertEquals("RETENTION_ACTIVE", assertThrows(RetentionClassStore.RetentionRefusedException.class,
                () -> store.delete(key)).code());
        assertTrue(Files.exists(dir.resolve("store").resolve(key)));
        HistoryArchives custom = HistoryArchiveAssembly.archives(props(with(on(), "sangam.history.archive.retention-class",
                "STATE_ARCHIVE_30Y")), keys(), new AuditChain(Dialect.POSTGRESQL), "t");
        assertEquals("STATE_ARCHIVE_30Y", custom.store().retentionClass());
    }

    @Test
    void aMissingOrShortOrLiteralSettingFailsTheStartUp() throws Exception {
        SecretResolver keys = keys();
        AuditChain audit = new AuditChain(Dialect.POSTGRESQL);
        assertThrows(IllegalStateException.class, () -> HistoryArchiveAssembly.archives(props(with(on(),
                "sangam.history.archive.store-root", " ")), keys, audit, "t"));
        Map<String, String> noDays = on();
        noDays.remove("sangam.history.archive.retention-days");
        assertThrows(IllegalStateException.class, () -> HistoryArchiveAssembly.archives(props(noDays), keys, audit, "t"));
        assertThrows(IllegalArgumentException.class, () -> HistoryArchiveAssembly.archives(props(with(on(),
                "sangam.history.archive.retention-days", "ten years")), keys, audit, "t"));
        IllegalStateException shorter = assertThrows(IllegalStateException.class, () -> HistoryArchiveAssembly.archives(
                props(with(on(), "sangam.ops.retention-json", "{\"gov.file\":\"P3650D\"}")), keys, audit, "t"));
        assertTrue(shorter.getMessage().contains("not longer"), shorter.getMessage());
        assertEquals(3650, HistoryArchiveAssembly.longestRetentionDays(props(Map.of("sangam.ops.retention-json",
                "{\"a\":\"P3649DT1S\",\"b\":\"P10D\"}"))), "whole days, rounded up");
        HistoryArchiveAssembly.archives(props(with(on(), "sangam.ops.retention-json", "{\"gov.file\":\"P3649D\"}")),
                keys, audit, "t");
        IllegalStateException literal = assertThrows(IllegalStateException.class, () -> HistoryArchiveAssembly.archives(
                props(with(on(), "sangam.history.archive.signing.private-key-ref", "literal-key-material-not-a-secret-ref")), keys, audit, "t"));
        assertTrue(literal.getMessage().contains("secret reference"), literal.getMessage());
        assertThrows(IllegalStateException.class, () -> HistoryArchiveAssembly.archives(props(with(on(),
                "sangam.history.archive.signing.trusted-public-key-refs", "secret://archive/missing")), keys, audit, "t"));
        assertThrows(IllegalArgumentException.class, () -> HistoryArchiveAssembly.archives(props(with(on(),
                "sangam.history.archive.retention-class", "records")), keys, audit, "t"));
    }
}
