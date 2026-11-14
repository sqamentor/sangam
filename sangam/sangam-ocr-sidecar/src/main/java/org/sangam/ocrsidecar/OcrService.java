// SPDX-License-Identifier: Apache-2.0
package org.sangam.ocrsidecar;

import static org.sangam.integration.document.ocr.RemoteOcrClient.ENGINE;
import static org.sangam.integration.document.ocr.RemoteOcrClient.LANGUAGE;
import static org.sangam.integration.document.ocr.RemoteOcrClient.LANGUAGES;
import static org.sangam.integration.document.ocr.RemoteOcrClient.LANGUAGES_PARAM;
import static org.sangam.integration.document.ocr.RemoteOcrClient.RECOGNISE_PATH;
import static org.sangam.integration.document.ocr.RemoteOcrClient.TEXT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.sun.net.httpserver.HttpExchange;

import org.sangam.integration.sidecar.SidecarServer;

/**
 * The OCR sidecar's HTTP surface (owner decisions 167 and 153) on the shared {@link SidecarServer}: stateless, HTTPS
 * (or an authenticated mesh), every call authenticated before the image is read. It never stores or logs the image or
 * the text.
 * <ul>
 *   <li>{@code POST /v1/ocr?lang=eng,hin} with {@code Authorization: Bearer <token>}, body a PNG, JPEG or TIFF image
 *       with its media type: {@code 200 {"text","engine","languages"}}; {@code 401} missing or wrong credentials;
 *       {@code 400} missing, malformed or not offered language; {@code 405} another method; {@code 413} a body or a
 *       pixel count over the limit; {@code 415} a body that is not the declared image type; {@code 422} an image the
 *       engine cannot read; {@code 500} the engine cannot run; {@code 503} not ready, busy or timed out.</li>
 *   <li>{@code GET /health} (unauthenticated): {@code 200 {"status":"UP"}} or {@code 503 {"status":"NOT_READY"}}.</li>
 * </ul>
 */
public final class OcrService implements AutoCloseable {

    public static final String HEALTH_PATH = "/health";

    private static final System.Logger LOG = System.getLogger(OcrService.class.getName());

    private final OcrEngine engine;
    private final Set<String> offered;
    private final long maxPixels;
    private final SidecarServer server;

    /**
     * Registers the route on {@code server} and starts it; closing this service closes the server.
     *
     * @param offered   the language codes this sidecar accepts (each installed for the engine)
     * @param maxPixels the largest width times height accepted
     */
    OcrService(OcrEngine engine, List<String> offered, long maxPixels, SidecarServer server) {
        if (engine == null || offered == null || offered.isEmpty() || maxPixels < 1 || server == null) {
            throw new IllegalArgumentException("an engine, offered languages, a pixel limit and a server are required");
        }
        for (String l : offered) {
            if (l == null || !LANGUAGE.matcher(l).matches()) {
                throw new IllegalArgumentException("not a language code: " + l);
            }
        }
        if (!HEALTH_PATH.equals(SidecarServer.HEALTH_PATH)) {
            throw new IllegalStateException("the health path of the contract and of the server differ");
        }
        this.engine = engine;
        this.offered = Set.copyOf(offered);
        this.maxPixels = maxPixels;
        this.server = server;
        server.route("POST", RECOGNISE_PATH, this::answer);
        server.start();
    }

    public int port() {
        return server.port();
    }

    @Override
    public void close() {
        server.close();
    }

    private void answer(HttpExchange ex) throws IOException {
        List<String> languages;
        try {
            languages = languages(ex.getRequestURI().getRawQuery(), offered);
        } catch (IllegalArgumentException e) {
            send(ex, 400, Map.of("error", e.getMessage()));
            return;
        }
        byte[] image;
        try {
            image = server.body(ex);
        } catch (SidecarServer.TooLarge e) {
            send(ex, 413, Map.of("error", "the image is larger than " + server.limits().maxBytes() + " bytes"));
            return;
        }
        try {
            ImageCheck.check(image, ex.getRequestHeaders().getFirst("Content-Type"), maxPixels);
        } catch (ImageCheck.Refused e) {
            send(ex, e.status(), Map.of("error", e.getMessage()));
            return;
        }
        String text;
        try {
            text = server.process(() -> engine.recognise(image, languages));
        } catch (SidecarServer.Busy e) {
            ex.getResponseHeaders().set("Retry-After", "1");
            send(ex, 503, Map.of("error", "busy"));
            return;
        } catch (SidecarServer.TimedOut e) {
            send(ex, 503, Map.of("error", "the recognition did not finish within "
                    + server.limits().processingTimeout().toMillis() + " ms"));
            return;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof OcrEngine.Unreadable u) {
                send(ex, 422, Map.of("error", u.getMessage()));
            } else {
                LOG.log(System.Logger.Level.WARNING, "recognition failed: " + cause.getClass().getName());
                send(ex, 500, Map.of("error", "the engine failed"));
            }
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(ex, 503, Map.of("error", "stopping"));
            return;
        }
        if (text == null) {
            send(ex, 500, Map.of("error", "the engine gave no text"));
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(TEXT, text);
        out.put(ENGINE, engine.name());
        out.put(LANGUAGES, languages);
        send(ex, 200, out);
    }

    /** {@code lang=eng,hin}: required once, every code well-formed, distinct and offered; the order is kept. */
    static List<String> languages(String rawQuery, Set<String> offered) {
        String value = null;
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(LANGUAGES_PARAM)) {
                    if (value != null) {
                        throw new IllegalArgumentException(LANGUAGES_PARAM + " is given more than once");
                    }
                    value = pair.substring(eq + 1);
                }
            }
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(LANGUAGES_PARAM + " is required, for example eng,hin");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String code : value.split(",", -1)) {
            if (!LANGUAGE.matcher(code).matches()) {
                throw new IllegalArgumentException("not a language code: " + (code.length() > 16 ? code.substring(0, 16)
                        + "..." : code));
            }
            if (!offered.contains(code)) {
                throw new IllegalArgumentException("the language " + code + " is not offered; offered: "
                        + new java.util.TreeSet<>(offered));
            }
            if (!out.add(code)) {
                throw new IllegalArgumentException("the language " + code + " is given twice");
            }
        }
        return new ArrayList<>(out);
    }

    private static void send(HttpExchange ex, int status, Map<String, ?> body) throws IOException {
        SidecarServer.send(ex, status, body);
    }
}
