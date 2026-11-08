// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.content;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.support.EngineTx;

/** FC-40 content service on H2 with the file-system store: versions, checksums, tenant isolation, scan gate, erase. */
class ContentServiceH2Test {

    @TempDir
    Path dir;

    private PortsH2Engine h2;
    private Transactions tx;
    private FileSystemDocumentStore store;

    @BeforeEach
    void setUp() throws IOException {
        h2 = PortsH2Engine.start(null);
        tx = EngineTx.transactions(h2.engine);
        store = new FileSystemDocumentStore(dir.resolve("store"));
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void createVersionReadAndScope() throws IOException {
        ContentService cs = new ContentService(Dialect.POSTGRESQL, tx, store, null, false);
        var item = cs.create("t1", "letter.txt", "text/plain", "process", "pi-1", null, bytes("v1"), "alice");
        assertEquals(1, item.currentVersion());
        assertEquals("t1", item.tenantId());
        assertEquals("alice", item.createdBy());
        var v2 = cs.addVersion(item.id(), bytes("version two"), "bob");
        assertEquals(2, v2.version());
        assertEquals(11, v2.sizeBytes());
        assertEquals(ContentService.ScanState.NOT_SCANNED, v2.scanState());
        assertEquals("version two", read(cs.open(item.id(), 0)), "0 = current version");
        assertEquals("v1", read(cs.open(item.id(), 1)));
        assertEquals(List.of(1, 2), cs.versions(item.id()).stream().map(ContentService.ContentVersion::version).toList());
        assertTrue(cs.versions(item.id()).get(0).storeKey().startsWith("t1/"), "keys are tenant-prefixed");
        assertEquals(1, cs.byScope("t1", "process", "pi-1").size());
        assertTrue(cs.byScope("t2", "process", "pi-1").isEmpty(), "tenant isolation of scope listing");
        assertThrows(IllegalArgumentException.class, () -> cs.open(item.id(), 7));
    }

    @Test
    void tenantCheckedOpenHidesOtherTenantsItems() throws IOException {
        ContentService cs = new ContentService(Dialect.POSTGRESQL, tx, store, null, false);
        var item = cs.create("t1", "a.txt", "text/plain", "case", "c1", null, bytes("secret"), "u");
        assertEquals("secret", read(cs.open("t1", item.id(), 0)));
        var ex = assertThrows(IllegalArgumentException.class, () -> cs.open("t2", item.id(), 0));
        assertTrue(ex.getMessage().contains("not found"), "reported as not found, not as forbidden");
        assertThrows(IllegalArgumentException.class, () -> cs.open("t1", "no-such-id", 0));
    }

    @Test
    void tamperedObjectFailsChecksumAtEndOfStream() throws IOException {
        ContentService cs = new ContentService(Dialect.POSTGRESQL, tx, store, null, false);
        var item = cs.create("t1", "a.txt", "text/plain", "process", "p", null, bytes("original"), "u");
        String key = cs.versions(item.id()).get(0).storeKey();
        Files.writeString(store.resolve(key), "tampered", StandardCharsets.UTF_8);
        IOException ex = assertThrows(IOException.class, () -> read(cs.open(item.id(), 1)));
        assertTrue(ex.getMessage().contains("checksum"));
    }

    @Test
    void logicalDeleteThenEraseRemovesObjectsAndRows() throws IOException {
        ContentService cs = new ContentService(Dialect.POSTGRESQL, tx, store, null, false);
        var item = cs.create("t1", "a.txt", "text/plain", "process", "p", null, bytes("one"), "u");
        cs.addVersion(item.id(), bytes("two"), "u");
        List<String> keys = cs.orphanCandidates("t1");
        assertEquals(2, keys.size());
        assertTrue(cs.orphanCandidates("t2").isEmpty());
        cs.delete(item.id());
        assertTrue(cs.item(item.id()).orElseThrow().deleted());
        assertTrue(cs.byScope("t1", "process", "p").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> cs.open(item.id(), 0));
        assertThrows(IllegalArgumentException.class, () -> cs.addVersion(item.id(), bytes("x"), "u"));
        assertTrue(keys.stream().allMatch(store::exists), "logical delete keeps the objects");
        cs.erase(item.id());
        assertTrue(cs.item(item.id()).isEmpty());
        assertTrue(cs.versions(item.id()).isEmpty());
        assertTrue(keys.stream().noneMatch(store::exists));
    }

    @Test
    void scannerGateBlocksUncleanDownloadsUntilRescanned() throws IOException {
        ContentService.ContentScanner scanner = (t, n, m, in) -> new String(in.readAllBytes(), StandardCharsets.UTF_8)
                .contains("EICAR") ? ContentService.ScanState.INFECTED : ContentService.ScanState.CLEAN;
        ContentService cs = new ContentService(Dialect.POSTGRESQL, tx, store, scanner, true);
        var clean = cs.create("t1", "ok.txt", "text/plain", "process", "p", null, bytes("fine"), "u");
        assertEquals("fine", read(cs.open(clean.id(), 0)));
        var bad = cs.create("t1", "bad.txt", "text/plain", "process", "p", null, bytes("EICAR test"), "u");
        assertEquals(ContentService.ScanState.INFECTED, cs.version(bad.id(), 1).orElseThrow().scanState());
        assertThrows(SecurityException.class, () -> cs.open(bad.id(), 0));
        cs.updateScan(bad.id(), 1, ContentService.ScanState.CLEAN);
        assertEquals("EICAR test", read(cs.open(bad.id(), 0)));

        ContentService.ContentScanner failing = (t, n, m, in) -> {
            throw new IOException("scanner down");
        };
        ContentService pending = new ContentService(Dialect.POSTGRESQL, tx, store, failing, true);
        var p = pending.create("t1", "p.txt", "text/plain", "process", "p", null, bytes("x"), "u");
        assertEquals(ContentService.ScanState.PENDING, pending.version(p.id(), 1).orElseThrow().scanState());
        assertThrows(SecurityException.class, () -> pending.open(p.id(), 0));
    }

    @Test
    void oversizedUploadIsRefusedAndLeavesNoMetadataOrObject() throws IOException {
        ContentService cs = new ContentService(Dialect.POSTGRESQL, tx, store, null, false, 4);
        var ex = assertThrows(IllegalArgumentException.class,
                () -> cs.create("t1", "big.bin", "application/octet-stream", "process", "p", null, bytes("12345"), "u"));
        assertTrue(ex.getMessage().toLowerCase().contains("document") || ex.getMessage().contains("4"), ex.getMessage());
        assertTrue(cs.orphanCandidates("t1").isEmpty());
        try (var files = Files.walk(dir.resolve("store"))) {
            assertFalse(files.anyMatch(Files::isRegularFile), "no partial object left in the store");
        }
        var ok = cs.create("t1", "small.bin", "application/octet-stream", "process", "p", null, bytes("1234"), "u");
        assertEquals(4, cs.version(ok.id(), 1).orElseThrow().sizeBytes());
        assertThrows(IllegalArgumentException.class, () -> cs.create("t1", "n", "text/plain", "process", "p", null, null, "u"));
    }

    @Test
    void outputTemplateRendersEscapesAndStoresDocument() throws IOException {
        ContentService cs = new ContentService(Dialect.POSTGRESQL, tx, store, null, false);
        OutputTemplateRenderer r = new OutputTemplateRenderer(cs);
        Map<String, Object> vars = Map.of("name", "<b>A&B</b>", "applicant", Map.of("city", "X'Y"));
        assertEquals("Dear &lt;b&gt;A&amp;B&lt;/b&gt; of X&#39;Y", r.render("Dear {{name}} of {{ applicant.city }}", vars, true, false));
        assertEquals("Dear <b>A&B</b>", r.render("Dear {{name}}", vars, false, false), "text templates are not escaped");
        assertEquals("cost $1 \\ ok", r.render("cost {{v}} \\ ok", Map.of("v", "$1"), false, false), "replacement is literal");
        assertThrows(IllegalArgumentException.class, () -> r.render("{{missing}}", vars, false, false));
        assertEquals("[]", r.render("[{{missing}}]", vars, false, true));
        assertEquals("[]", r.render("[{{name.deeper}}]", vars, false, true), "path through a non-map is missing");

        var doc = r.renderToDocument("t1", "order.html", "<p>{{name}}</p>", vars, true, "process", "pi-9", "u");
        assertEquals("text/html", doc.mimeType());
        assertEquals("<p>&lt;b&gt;A&amp;B&lt;/b&gt;</p>", read(cs.open("t1", doc.id(), 0)));
        assertThrows(IllegalArgumentException.class,
                () -> r.renderToDocument("t1", "x.txt", "{{missing}}", vars, false, "process", "pi-9", "u"));
        assertEquals(1, cs.byScope("t1", "process", "pi-9").size(), "a failed render stores nothing");
    }
}
