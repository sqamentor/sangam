// SPDX-License-Identifier: Apache-2.0
package org.sangam.ocrsidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Tesseract process wrapper, against a stand-in program (a POSIX shell script written by the test) so the test
 * needs no Tesseract install: fixed arguments, language joining, non-zero exit, output limit, kill on interruption and
 * clean-up of the temporary folder. The real program is exercised in the image (ocr.Dockerfile, test phase).
 */
class TesseractEngineTest {

    private static final String SCRIPT = """
            #!/bin/sh
            case "$1" in
              --version) echo "tesseract 5.3.4"; echo " leptonica-1.82.0"; exit 0 ;;
              --list-langs) printf 'List of available languages in "/usr/share/tessdata/" (3):\\neng\\nhin\\nosd\\n'; exit 0 ;;
            esac
            [ "$2" = "stdout" ] && [ "$3" = "-l" ] || exit 9
            content=$(cat "$1")
            case "$content" in
              BAD) exit 1 ;;
              SLOW) sleep 30 ;;
              BIG) i=0; while [ $i -lt 200 ]; do echo "0123456789012345678901234567890123456789"; i=$((i+1)); done ;;
              *) echo "read with $4" ;;
            esac
            """;

    @TempDir
    Path dir;

    private Path program;
    private Path temp;

    @BeforeEach
    void stage() throws IOException {
        assumeTrue(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"), "POSIX shell");
        program = Files.writeString(dir.resolve("fake-tesseract"), SCRIPT, StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        temp = Files.createDirectory(dir.resolve("tmp"));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void theVersionAndTheInstalledLanguagesAreProbed() throws Exception {
        TesseractEngine e = new TesseractEngine(program.toString(), temp, 1024);
        assertEquals("tesseract 5.3.4", e.name());
        assertEquals(List.of("eng", "hin", "osd"), e.installedLanguages());
    }

    @Test
    void textIsReadWithTheJoinedLanguagesAndTheFolderIsRemoved() throws Exception {
        TesseractEngine e = new TesseractEngine(program.toString(), temp, 1024);
        assertEquals("read with eng+hin\n", e.recognise(ascii("IMAGE"), List.of("eng", "hin")));
        try (var left = Files.list(temp)) {
            assertEquals(0, left.count(), "no temporary file is left");
        }
    }

    @Test
    void aFailingExitIsUnreadableAndTooMuchTextIsAnError() throws Exception {
        TesseractEngine e = new TesseractEngine(program.toString(), temp, 1024);
        assertThrows(OcrEngine.Unreadable.class, () -> e.recognise(ascii("BAD"), List.of("eng")));
        assertThrows(IOException.class, () -> e.recognise(ascii("BIG"), List.of("eng")));
        try (var left = Files.list(temp)) {
            assertEquals(0, left.count());
        }
    }

    @Test
    void anInterruptedRecognitionKillsTheProcess() throws Exception {
        TesseractEngine e = new TesseractEngine(program.toString(), temp, 1024);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            long start = System.nanoTime();
            Future<String> f = pool.submit(() -> e.recognise(ascii("SLOW"), List.of("eng")));
            Thread.sleep(500);
            f.cancel(true);
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "the waiting thread ended");
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 20, "the process did not run on");
            try (var left = Files.list(temp)) {
                assertEquals(0, left.count());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aProgramThatIsNotTesseractStopsTheStart() throws IOException {
        Path other = Files.writeString(dir.resolve("other"), "#!/bin/sh\necho hello\n", StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rwx------"));
        assertThrows(IOException.class, () -> new TesseractEngine(other.toString(), temp, 1024));
        assertThrows(IOException.class, () -> new TesseractEngine(dir.resolve("absent").toString(), temp, 1024));
    }
}
