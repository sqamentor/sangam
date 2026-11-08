// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

/** FC-41 app bundle: manifest, resource kinds, R43 bundle hash, defensive zip reading. */
class AppBundleTest {

    static final String MANIFEST = "{\"key\":\"claims\",\"name\":\"Claims\",\"groupsAccess\":[\"adjusters\"]}";

    static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> files(String... kv) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    void resourceKindsByFileName() {
        assertEquals(AppBundle.Kind.BPMN, AppBundle.kind("a/b.BPMN20.xml"));
        assertEquals(AppBundle.Kind.BPMN, AppBundle.kind("x.bpmn"));
        assertEquals(AppBundle.Kind.CMMN, AppBundle.kind("x.cmmn11.xml"));
        assertEquals(AppBundle.Kind.DMN, AppBundle.kind("x.dmn"));
        assertEquals(AppBundle.Kind.FORM, AppBundle.kind("x.form.json"));
        assertEquals(AppBundle.Kind.EVENT, AppBundle.kind("x.event"));
        assertEquals(AppBundle.Kind.CHANNEL, AppBundle.kind("x.channel.json"));
        assertEquals(AppBundle.Kind.OTHER, AppBundle.kind("readme.txt"));
    }

    @Test
    void readsAZipWithManifestHashesEveryResourceAndSkipsDirectories() throws Exception {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("sangam-app.json", MANIFEST);
        e.put("models/", "");
        e.put("models/p.bpmn", "<p/>");
        e.put("paid.event", "{\"key\":\"paid\"}");
        AppBundle b = AppBundle.read(zip(e));
        assertEquals("claims", b.manifest().key());
        assertEquals(List.of("adjusters"), b.manifest().groupsAccess());
        assertEquals(List.of("models/p.bpmn", "paid.event"), List.copyOf(b.resources().keySet()), "manifest is not a resource");
        assertEquals(64, b.resourceHashes().get("models/p.bpmn").length());
        assertEquals(64, b.bundleSha256().length());
    }

    @Test
    void aFlowableAppModelIsAcceptedAsManifest() {
        AppBundle b = AppBundle.of(files("claims.app", "{\"key\":\"claims\",\"name\":\"From Flowable\"}", "p.bpmn", "<p/>"));
        assertEquals("From Flowable", b.manifest().name());
        assertFalse(b.resources().containsKey("claims.app"));
    }

    @Test
    void theBundleHashIsIndependentOfEntryOrderAndChangesWithAnyResource() {
        AppBundle one = AppBundle.of(files("sangam-app.json", MANIFEST, "a.bpmn", "<a/>", "b.dmn", "<b/>"));
        Map<String, byte[]> reversed = new TreeMap<>(java.util.Comparator.reverseOrder());
        reversed.putAll(files("sangam-app.json", MANIFEST, "a.bpmn", "<a/>", "b.dmn", "<b/>"));
        assertEquals(one.bundleSha256(), AppBundle.of(reversed).bundleSha256());
        assertNotEquals(one.bundleSha256(), AppBundle.of(files("sangam-app.json", MANIFEST, "a.bpmn", "<a />", "b.dmn", "<b/>")).bundleSha256());
        assertNotEquals(one.bundleSha256(), AppBundle.of(files("sangam-app.json", MANIFEST, "a2.bpmn", "<a/>", "b.dmn", "<b/>")).bundleSha256(),
                "a renamed resource is a different bundle");
    }

    /**
     * R43: the approver signs the bundle hash. The manifest carries {@code usersAccess / groupsAccess} (who may use
     * the app), so a manifest change must change the hash; otherwise an access change is neither re-approved nor
     * deployed (the unchanged hash makes the redeploy return the old app deployment).
     */
    @Test
    void theBundleHashCoversTheManifest() {
        AppBundle original = AppBundle.of(files("sangam-app.json", MANIFEST, "a.bpmn", "<a/>"));
        AppBundle widened = AppBundle.of(files("sangam-app.json", MANIFEST.replace("[\"adjusters\"]", "[\"adjusters\",\"everyone\"]"),
                "a.bpmn", "<a/>"));
        assertNotEquals(original.bundleSha256(), widened.bundleSha256());
        assertEquals(original.bundleSha256(), AppBundle.of(files("sangam-app.json", MANIFEST, "a.bpmn", "<a/>")).bundleSha256(), "stable");
    }

    @Test
    void manifestIsRequiredAndItsKeyIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> AppBundle.of(files("a.bpmn", "<a/>")));
        assertThrows(IllegalArgumentException.class, () -> AppBundle.of(files("sangam-app.json", "{\"name\":\"no key\"}")));
        assertThrows(IllegalArgumentException.class, () -> AppBundle.of(files("sangam-app.json", "{\"key\":\"has space\"}")));
        assertThrows(IllegalArgumentException.class, () -> AppBundle.of(files("sangam-app.json", "{\"key\":\"" + "k".repeat(129) + "\"}")));
    }

    @Test
    void unsafeNestedDuplicateAndEmptyArchivesAreRejected() throws Exception {
        for (String bad : new String[]{"../evil.bpmn", "/abs.bpmn", "a/../../x.bpmn", "C:/x.bpmn", "..\\win.bpmn"}) {
            IOException e = assertThrows(IOException.class, () -> AppBundle.read(zip(Map.of("sangam-app.json", MANIFEST, bad, "x"))), bad);
            assertTrue(e.getMessage().contains("unsafe"), e.getMessage());
        }
        assertThrows(IOException.class, () -> AppBundle.read(zip(Map.of("sangam-app.json", MANIFEST, "inner.zip", "x"))));
        assertThrows(IOException.class, () -> AppBundle.read(zip(Map.of("sangam-app.json", MANIFEST, "lib/x.JAR", "x"))));
        assertThrows(IOException.class, () -> AppBundle.read(new byte[0]));
        assertThrows(IOException.class, () -> AppBundle.read(null));

        // duplicate names: written raw, as ZipOutputStream refuses them
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("sangam-app.json"));
            z.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("a.bpmn"));
            z.write(1);
            z.putNextEntry(new ZipEntry("A.bpmn"));
            z.write(2);
        }
        byte[] bytes = out.toByteArray();
        String s = new String(bytes, StandardCharsets.ISO_8859_1).replace("A.bpmn", "a.bpmn");
        IOException dup = assertThrows(IOException.class, () -> AppBundle.read(s.getBytes(StandardCharsets.ISO_8859_1)));
        assertTrue(dup.getMessage().contains("duplicate"), dup.getMessage());
    }

    @Test
    void tooManyEntriesAreRejected() throws Exception {
        Map<String, String> many = new LinkedHashMap<>();
        many.put("sangam-app.json", MANIFEST);
        for (int i = 0; i < AppBundle.MAX_ENTRIES; i++) {
            many.put("r" + i + ".txt", "x");
        }
        IOException e = assertThrows(IOException.class, () -> AppBundle.read(zip(many)));
        assertTrue(e.getMessage().contains("entries"));
    }
}
