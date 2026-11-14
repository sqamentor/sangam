// SPDX-License-Identifier: Apache-2.0
package org.sangam.pdfvalidator;

import static org.sangam.integration.document.pdf.remote.RemotePdfValidator.CHECKED;
import static org.sangam.integration.document.pdf.remote.RemotePdfValidator.CONFORMANCE_PARAM;
import static org.sangam.integration.document.pdf.remote.RemotePdfValidator.FINDINGS;
import static org.sangam.integration.document.pdf.remote.RemotePdfValidator.OK;
import static org.sangam.integration.document.pdf.remote.RemotePdfValidator.VALIDATE_PATH;
import static org.sangam.integration.document.pdf.remote.RemotePdfValidator.VALIDATOR;

import java.io.IOException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

import com.sun.net.httpserver.HttpExchange;

import org.sangam.integration.document.pdf.PdfRenderer;
import org.sangam.integration.sidecar.SidecarServer;

/**
 * The validator service (owner decisions 114 and 153, RM-26, SL-178) on the shared {@link SidecarServer}: stateless,
 * HTTPS (or an authenticated mesh), every call authenticated before the PDF is read. It never stores or logs the bytes
 * it is sent.
 * <ul>
 *   <li>{@code POST /v1/validate?conformance=PDF_A_2B,PDF_UA_1} with {@code Authorization: Bearer <token>}, body the PDF
 *       bytes: {@code 200} with the report {@code {"ok","validator","checked","findings"}} (the wire shape of
 *       {@code RemotePdfValidator}); {@code 401} for missing or wrong credentials; {@code 400} for a missing or unknown
 *       conformance level; {@code 405} for another method; {@code 413} for a body over the limit; {@code 500} when the
 *       validator itself fails; {@code 503} while not ready (no usable caller tokens), busy (all slots taken) or when
 *       the validation timed out. Only a {@code 200} carries findings; the client refuses the PDF on every other
 *       status.</li>
 *   <li>{@code GET /health} (unauthenticated): {@code 200 {"status":"UP"}} or {@code 503 {"status":"NOT_READY"}}.</li>
 * </ul>
 */
public final class PdfValidatorService implements AutoCloseable {

    public static final String HEALTH_PATH = "/health";
    /** Default largest accepted PDF, in bytes. */
    public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

    private static final System.Logger LOG = System.getLogger(PdfValidatorService.class.getName());

    private final PdfRenderer.Validator validator;
    private final SidecarServer server;

    /**
     * Registers the validation route on {@code server} and starts it; closing this service closes the server.
     *
     * @param validatorName kept for the start-up log only (the health answer carries no detail)
     */
    public PdfValidatorService(PdfRenderer.Validator validator, String validatorName, SidecarServer server) {
        if (validator == null || validatorName == null || validatorName.isBlank() || server == null) {
            throw new IllegalArgumentException("a validator, its name and a server are required");
        }
        if (!HEALTH_PATH.equals(SidecarServer.HEALTH_PATH)) {
            throw new IllegalStateException("the health path of the contract and of the server differ");
        }
        this.validator = validator;
        this.server = server;
        server.route("POST", VALIDATE_PATH, this::answer);
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
        Set<PdfRenderer.Conformance> required;
        try {
            required = conformance(ex.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            send(ex, 400, Map.of("error", e.getMessage()));
            return;
        }
        byte[] pdf;
        try {
            pdf = server.body(ex);
        } catch (SidecarServer.TooLarge e) {
            send(ex, 413, Map.of("error", "the PDF is larger than " + server.limits().maxBytes() + " bytes"));
            return;
        }
        PdfRenderer.Report report;
        try {
            report = server.process(() -> validator.validate(pdf, required));
        } catch (SidecarServer.Busy e) {
            ex.getResponseHeaders().set("Retry-After", "1");
            send(ex, 503, Map.of("error", "busy"));
            return;
        } catch (SidecarServer.TimedOut e) {
            send(ex, 503, Map.of("error", "the validation did not finish within "
                    + server.limits().processingTimeout().toMillis() + " ms"));
            return;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            LOG.log(System.Logger.Level.WARNING, "validation failed: " + cause.getClass().getName());
            send(ex, 500, Map.of("error", "the validator failed"));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(ex, 503, Map.of("error", "stopping"));
            return;
        }
        if (report == null) {
            send(ex, 500, Map.of("error", "the validator gave no report"));
            return;
        }
        TreeSet<String> checked = new TreeSet<>();
        required.forEach(c -> checked.add(c.name()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(OK, report.ok());
        out.put(VALIDATOR, report.validator());
        out.put(CHECKED, List.copyOf(checked));
        out.put(FINDINGS, report.findings());
        send(ex, 200, out);
    }

    /** {@code conformance=PDF_A_2B,PDF_UA_1}: required, every name a {@link PdfRenderer.Conformance}. */
    static Set<PdfRenderer.Conformance> conformance(String rawQuery) {
        String value = null;
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(CONFORMANCE_PARAM)) {
                    if (value != null) {
                        throw new IllegalArgumentException(CONFORMANCE_PARAM + " is given more than once");
                    }
                    value = pair.substring(eq + 1);
                }
            }
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(CONFORMANCE_PARAM + " is required, for example PDF_A_2B,PDF_UA_1");
        }
        Set<PdfRenderer.Conformance> out = EnumSet.noneOf(PdfRenderer.Conformance.class);
        for (String name : value.split(",", -1)) {
            try {
                out.add(PdfRenderer.Conformance.valueOf(name.strip()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown conformance level " + name.strip()
                        + "; known: " + List.of(PdfRenderer.Conformance.values()));
            }
        }
        return out;
    }

    private static void send(HttpExchange ex, int status, Map<String, ?> body) throws IOException {
        SidecarServer.send(ex, status, body);
    }
}
