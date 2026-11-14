// SPDX-License-Identifier: Apache-2.0
package org.sangam.ocrsidecar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.sangam.integration.document.ocr.RemoteOcrClient;
import org.sangam.integration.sidecar.SidecarSettings;

/**
 * Entry point of the OCR image ({@code deploy/docker/ocr.Dockerfile}). Settings from the environment variables with
 * the prefix {@value #PREFIX}: the shared sidecar settings ({@link SidecarSettings}: {@code _BIND}, {@code _PORT}
 * default {@value #DEFAULT_PORT}, {@code _THREADS}, {@code _MAX_BYTES} default 32 MiB, {@code _PROCESSING_TIMEOUT_MS}
 * default 120 s, {@code _TRANSPORT}, {@code _TLS_KEYSTORE}, {@code _TLS_KEYSTORE_PASSWORD_FILE}, {@code _TOKEN_FILE})
 * and:
 * <ul>
 *   <li>{@value #TESSERACT_ENV}: the program, default {@code tesseract};</li>
 *   <li>{@value #LANGUAGES_ENV}: the offered language codes, default {@value #DEFAULT_LANGUAGES}; each must be
 *       installed for the program, or the start-up stops;</li>
 *   <li>{@value #MAX_PIXELS_ENV}: the largest width times height, default {@value #DEFAULT_MAX_PIXELS};</li>
 *   <li>{@value #TEMP_ENV}: the folder for the per-request temporary files, default {@code java.io.tmpdir}.</li>
 * </ul>
 * Without a token file the service starts, reports {@code NOT_READY} and refuses every call. No secret is printed.
 */
public final class OcrSidecarMain {

    public static final String PREFIX = "SANGAM_OCR";
    public static final String TESSERACT_ENV = "SANGAM_OCR_TESSERACT";
    public static final String LANGUAGES_ENV = "SANGAM_OCR_LANGUAGES";
    public static final String MAX_PIXELS_ENV = "SANGAM_OCR_MAX_PIXELS";
    public static final String TEMP_ENV = "SANGAM_OCR_TEMP";
    public static final int DEFAULT_PORT = 8086;
    public static final long DEFAULT_MAX_BYTES = 32L * 1024 * 1024;
    public static final Duration DEFAULT_PROCESSING_TIMEOUT = Duration.ofSeconds(120);
    public static final String DEFAULT_LANGUAGES = "eng,hin";
    public static final long DEFAULT_MAX_PIXELS = 100_000_000L;
    /** Recognised text larger than this is an error. */
    static final long MAX_TEXT_BYTES = 4L * 1024 * 1024;

    private OcrSidecarMain() {
    }

    /** The OCR-specific settings read from an environment map; invalid values throw {@link IllegalArgumentException}. */
    record Settings(SidecarSettings sidecar, String tesseract, List<String> languages, long maxPixels, Path temp) {
        static Settings from(Map<String, String> env) {
            SidecarSettings sidecar = SidecarSettings.from(PREFIX, env, DEFAULT_PORT, DEFAULT_MAX_BYTES,
                    DEFAULT_PROCESSING_TIMEOUT);
            String tesseract = env.getOrDefault(TESSERACT_ENV, "tesseract").strip();
            if (tesseract.isEmpty()) {
                throw new IllegalArgumentException(TESSERACT_ENV + " is blank");
            }
            List<String> languages = new ArrayList<>();
            for (String l : env.getOrDefault(LANGUAGES_ENV, DEFAULT_LANGUAGES).split(",", -1)) {
                String code = l.strip().toLowerCase(Locale.ROOT);
                if (!RemoteOcrClient.LANGUAGE.matcher(code).matches() || languages.contains(code)) {
                    throw new IllegalArgumentException(LANGUAGES_ENV + " must list distinct codes such as eng,hin");
                }
                languages.add(code);
            }
            long maxPixels;
            String px = env.get(MAX_PIXELS_ENV);
            try {
                maxPixels = px == null || px.isBlank() ? DEFAULT_MAX_PIXELS : Long.parseLong(px.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(MAX_PIXELS_ENV + " is not a number");
            }
            if (maxPixels < 1) {
                throw new IllegalArgumentException(MAX_PIXELS_ENV + " must be positive");
            }
            String temp = env.get(TEMP_ENV);
            Path tempDir = Path.of(temp == null || temp.isBlank() ? System.getProperty("java.io.tmpdir") : temp.strip());
            return new Settings(sidecar, tesseract, List.copyOf(languages), maxPixels, tempDir);
        }
    }

    public static void main(String[] args) throws Exception {
        Settings s = Settings.from(System.getenv());
        if (!Files.isDirectory(s.temp()) || !Files.isWritable(s.temp())) {
            throw new IllegalStateException("the temporary folder " + s.temp() + " is not a writable folder");
        }
        TesseractEngine engine = new TesseractEngine(s.tesseract(), s.temp(), MAX_TEXT_BYTES);
        List<String> installed = engine.installedLanguages();
        for (String l : s.languages()) {
            if (!installed.contains(l)) {
                throw new IllegalStateException("the language " + l + " is offered (" + LANGUAGES_ENV
                        + ") but not installed for " + engine.name() + "; installed: " + installed);
            }
        }
        OcrService service = new OcrService(engine, s.languages(), s.maxPixels(), s.sidecar().open());
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.close();
            stopped.countDown();
        }, "ocr-sidecar-shutdown"));
        String notReady = s.sidecar().authenticator().notReadyReason();
        System.out.println("sangam-ocr-sidecar listening on " + s.sidecar().bind() + ":" + service.port() + " ("
                + s.sidecar().transport().name().toLowerCase(Locale.ROOT) + ") with " + engine.name() + " for "
                + s.languages() + (notReady == null ? "" : "; NOT READY: " + notReady));
        stopped.await();
    }
}
