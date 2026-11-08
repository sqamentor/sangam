// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FC-40 file-system store: immutable keys, checksums, key validation, no temp leftovers. */
class FileSystemDocumentStoreTest {

    @TempDir
    Path dir;

    @Test
    void putGetDeleteAndImmutableKeys() throws IOException {
        FileSystemDocumentStore s = new FileSystemDocumentStore(dir);
        assertEquals("fs", s.type());
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        var obj = s.put("t1/2026/11/id/v1", new ByteArrayInputStream(data), "text/plain");
        assertEquals(5, obj.sizeBytes());
        assertEquals(HexFormat.of().formatHex(FileSystemDocumentStore.sha256().digest(data)), obj.sha256Hex());
        assertTrue(s.exists("t1/2026/11/id/v1"));
        try (InputStream in = s.get("t1/2026/11/id/v1").orElseThrow()) {
            assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThrows(FileAlreadyExistsException.class,
                () -> s.put("t1/2026/11/id/v1", new ByteArrayInputStream(new byte[1]), null));
        s.delete("t1/2026/11/id/v1");
        assertFalse(s.exists("t1/2026/11/id/v1"));
        assertTrue(s.get("t1/2026/11/id/v1").isEmpty());
        s.delete("t1/2026/11/id/v1");   // idempotent
    }

    @Test
    void rejectsTraversalAndAbsoluteKeys() throws IOException {
        FileSystemDocumentStore s = new FileSystemDocumentStore(dir.resolve("root"));
        for (String bad : new String[] {"../x", "a/../../x", "/etc/passwd", "a\\b", "C:/x", "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> s.put(bad, new ByteArrayInputStream(new byte[0]), null), bad);
            assertThrows(IllegalArgumentException.class, () -> s.exists(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> s.get(null));
    }

    @Test
    void failedUploadLeavesNoTempFile() throws IOException {
        FileSystemDocumentStore s = new FileSystemDocumentStore(dir);
        InputStream broken = new InputStream() {
            int n;

            @Override
            public int read() throws IOException {
                if (n++ > 3) {
                    throw new IOException("client went away");
                }
                return 'a';
            }
        };
        assertThrows(IOException.class, () -> s.put("t/k", broken, null));
        assertFalse(s.exists("t/k"));
        try (var files = Files.walk(dir)) {
            assertFalse(files.anyMatch(Files::isRegularFile), "temp upload removed");
        }
    }
}
