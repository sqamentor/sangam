// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.ocr;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.integration.sidecar.SidecarClient;
import org.sangam.integration.sidecar.SidecarCredentials;

/**
 * The server side of OCR (M-103, owner decisions 167 and 153): image bytes go to the separate Sangam OCR sidecar
 * ({@code sangam-ocr-sidecar}, Tesseract inside its own image) and the recognised text is read back. Nothing is wired by
 * default; a caller builds it from {@link #fromEnvironment()} when OCR is wanted, so the server never needs the sidecar.
 *
 * <p>Configuration (a system property wins over the environment variable):
 * <ul>
 *   <li>{@value #URL_PROPERTY} / {@value #URL_ENV}: base URL, {@code https} (plain {@code http} only with transport
 *       {@code mesh}). Absent: not ready.</li>
 *   <li>{@value #TOKEN_FILE_PROPERTY} / {@value #TOKEN_FILE_ENV}: the mounted secret file whose first line is this
 *       server's token for the sidecar. Absent: not ready; there is no unauthenticated mode.</li>
 *   <li>{@value #TRANSPORT_PROPERTY} / {@value #TRANSPORT_ENV}: {@code tls} (default) or {@code mesh}.</li>
 *   <li>{@value #TIMEOUT_PROPERTY} / {@value #TIMEOUT_ENV}: connect and answer timeout, default
 *       {@value #DEFAULT_TIMEOUT_MS} ms.</li>
 * </ul>
 *
 * <p>Wire shape (the sidecar implements it with these constants): {@code POST <url>/v1/ocr?lang=eng,hin} with
 * {@code Authorization: Bearer <token>}, body the image bytes with their media type ({@link OcrPort#MEDIA_TYPES});
 * answer {@code 200 application/json} {@code {"text":"...","engine":"tesseract 5.3.4","languages":["eng","hin"]}}.
 * {@code 400}, {@code 413}, {@code 415} and {@code 422} refuse the input ({@link OcrRejected}); {@code 401},
 * {@code 503}, any other status, a timeout or an answer of another shape is {@link OcrUnavailable}.
 */
public final class RemoteOcrClient implements OcrPort {

    public static final String URL_PROPERTY = "sangam.ocr.url";
    public static final String URL_ENV = "SANGAM_OCR_URL";
    public static final String TIMEOUT_PROPERTY = "sangam.ocr.timeout-ms";
    public static final String TIMEOUT_ENV = "SANGAM_OCR_TIMEOUT_MS";
    public static final String TOKEN_FILE_PROPERTY = "sangam.ocr.token-file";
    public static final String TOKEN_FILE_ENV = "SANGAM_OCR_TOKEN_FILE";
    public static final String TRANSPORT_PROPERTY = "sangam.ocr.transport";
    public static final String TRANSPORT_ENV = "SANGAM_OCR_TRANSPORT";
    public static final long DEFAULT_TIMEOUT_MS = 120_000;

    /** The recognition endpoint, relative to the configured base URL. */
    public static final String RECOGNISE_PATH = "/v1/ocr";
    /** Query parameter: comma-separated language codes. */
    public static final String LANGUAGES_PARAM = "lang";
    public static final String RESULT_CONTENT_TYPE = "application/json";
    /** Answer fields. */
    public static final String TEXT = "text";
    public static final String ENGINE = "engine";
    public static final String LANGUAGES = "languages";
    /** A language code: Tesseract traineddata names such as {@code eng}, {@code hin}, {@code chi_sim}. */
    public static final Pattern LANGUAGE = Pattern.compile("[a-z]{3}(_[a-z]{2,8})?");

    /** An answer larger than this is not an answer this client reads. */
    static final int MAX_ANSWER_BYTES = 8 << 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SidecarClient client;

    public RemoteOcrClient(SidecarClient.Settings settings) {
        this.client = new SidecarClient("OCR", URL_PROPERTY + " / " + URL_ENV, settings,
                Duration.ofMillis(DEFAULT_TIMEOUT_MS));
    }

    /** The settings from the system properties, falling back to the environment variables. */
    public static SidecarClient.Settings fromEnvironment() {
        String tokenFile = setting(TOKEN_FILE_PROPERTY, TOKEN_FILE_ENV);
        SidecarCredentials credentials = tokenFile == null || tokenFile.isBlank()
                ? SidecarCredentials.none(TOKEN_FILE_PROPERTY + " / " + TOKEN_FILE_ENV)
                : SidecarCredentials.tokenFile(Path.of(tokenFile.strip()));
        return new SidecarClient.Settings(setting(URL_PROPERTY, URL_ENV), setting(TIMEOUT_PROPERTY, TIMEOUT_ENV),
                setting(TRANSPORT_PROPERTY, TRANSPORT_ENV), credentials);
    }

    private static String setting(String property, String env) {
        return System.getProperty(property, System.getenv(env));
    }

    @Override
    public String notReadyReason() {
        return client.notReadyReason();
    }

    public String endpoint() {
        return client.endpoint();
    }

    @Override
    public OcrResult recognise(byte[] image, String mediaType, List<String> languages) {
        String notReady = notReadyReason();
        if (notReady != null) {
            throw new OcrUnavailable("OCR is not ready: " + notReady);
        }
        if (image == null || image.length == 0) {
            throw new OcrRejected("no image bytes", 0);
        }
        if (mediaType == null || !MEDIA_TYPES.contains(mediaType)) {
            throw new OcrRejected("the media type must be one of " + MEDIA_TYPES, 0);
        }
        if (languages == null || languages.isEmpty() || !languages.stream().allMatch(l -> l != null
                && LANGUAGE.matcher(l).matches()) || languages.stream().distinct().count() != languages.size()) {
            throw new OcrRejected("languages must be distinct engine codes such as eng or hin", 0);
        }
        SidecarClient.Answer answer;
        try {
            answer = client.post(RECOGNISE_PATH, LANGUAGES_PARAM + "=" + String.join(",", languages), mediaType,
                    RESULT_CONTENT_TYPE, image, MAX_ANSWER_BYTES);
        } catch (SidecarClient.CallFailed e) {
            int s = e.status();
            if (s == 400 || s == 413 || s == 415 || s == 422) {
                throw new OcrRejected(e.getMessage(), s);
            }
            throw new OcrUnavailable(e.getMessage() + "; no text is returned rather than a guess");
        }
        if (answer.status() != 200) {
            throw unusable("answered HTTP " + answer.status() + " instead of 200");
        }
        return result(answer.body(), languages);
    }

    private OcrResult result(byte[] body, List<String> asked) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw unusable("answered a body that is not JSON");
        }
        if (root == null || !root.isObject() || !root.path(TEXT).isTextual() || !root.path(ENGINE).isTextual()
                || root.path(ENGINE).asText().isBlank() || !root.path(LANGUAGES).isArray()) {
            throw unusable("answered a result without text, engine and languages");
        }
        List<String> used = new ArrayList<>();
        for (JsonNode l : root.path(LANGUAGES)) {
            if (!l.isTextual()) {
                throw unusable("answered languages that are not text");
            }
            used.add(l.asText());
        }
        if (!used.equals(asked)) {
            throw unusable("used the languages " + used + " but " + asked + " was asked for");
        }
        return new OcrResult(root.path(TEXT).asText(), root.path(ENGINE).asText(), used);
    }

    private OcrUnavailable unusable(String problem) {
        return new OcrUnavailable(client.unusable(problem).getMessage() + "; no text is returned rather than a guess");
    }
}
