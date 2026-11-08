// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.sangam.ports.app.AppBundle;

/** FW-45 archive attacks (FLOWABLE-WEAKNESS-AUDIT §5, FWT-049): each one refused. */
class FwtAppBundleTest {

    static final byte[] MANIFEST = "{\"key\":\"app1\"}".getBytes(StandardCharsets.UTF_8);
    static final byte[] BPMN = "<definitions/>".getBytes(StandardCharsets.UTF_8);

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static void refused(byte[] zip, String messagePart) {
        IOException e = assertThrows(IOException.class, () -> AppBundle.read(zip));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }

    static Map<String, byte[]> base() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("sangam-app.json", MANIFEST);
        return m;
    }

    // FWT-049
    @Test
    void fwt049_controlBundleIsRead() throws IOException {
        Map<String, byte[]> m = base();
        m.put("p.bpmn", BPMN);
        assertEquals(1, AppBundle.read(zip(m)).resources().size());
    }

    // FWT-049
    @Test
    void fwt049_zipSlipRefused() throws IOException {
        Map<String, byte[]> m = base();
        m.put("../x.bpmn", BPMN);
        refused(zip(m), "unsafe entry name");
        Map<String, byte[]> m2 = base();
        m2.put("a/../../x.bpmn", BPMN);
        refused(zip(m2), "unsafe entry name");
    }

    // FWT-049
    @Test
    void fwt049_nestedZipRefused() throws IOException {
        Map<String, byte[]> m = base();
        m.put("inner.zip", zip(Map.of("p.bpmn", BPMN)));
        refused(zip(m), "nested archives");
    }

    // FWT-049
    @Test
    void fwt049_duplicateEntryRefused() throws IOException {
        Map<String, byte[]> m = base();
        m.put("a.bpmn", BPMN);
        m.put("b.bpmn", BPMN);
        byte[] z = zip(m);
        // ZipOutputStream refuses to write duplicates: rename b.bpmn to a.bpmn in the raw archive (local + central headers)
        String s = new String(z, StandardCharsets.ISO_8859_1).replace("b.bpmn", "a.bpmn");
        refused(s.getBytes(StandardCharsets.ISO_8859_1), "duplicate entry");
    }

    // FWT-049
    @Test
    void fwt049_sixHundredEntriesRefused() throws IOException {
        Map<String, byte[]> m = base();
        for (int i = 0; i < 600; i++) {
            m.put("f" + i + ".form", "{}".getBytes(StandardCharsets.UTF_8));
        }
        refused(zip(m), "more than " + AppBundle.MAX_ENTRIES + " entries");
    }

    // FWT-049
    @Test
    void fwt049_seventyMBUncompressedRefused() throws IOException {
        Map<String, byte[]> m = base();
        m.put("big.bpmn", new byte[70 * 1000 * 1000]);
        byte[] z = zip(m);
        assertTrue(z.length < 1024 * 1024, "precondition: a small zip bomb, " + z.length + " bytes");
        refused(z, "exceeds " + AppBundle.MAX_BYTES + " bytes uncompressed");
    }
}
