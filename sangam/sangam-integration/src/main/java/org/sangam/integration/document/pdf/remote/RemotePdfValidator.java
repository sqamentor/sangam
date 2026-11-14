// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.remote;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.integration.document.pdf.PdfRenderer;
import org.sangam.integration.document.pdf.PdfRendering;
import org.sangam.integration.sidecar.SidecarClient;
import org.sangam.integration.sidecar.SidecarCredentials;

/**
 * The server side of the PDF validation step (M-081, owner decisions 114, 119 and 153): the rendered bytes are sent to
 * the separate validator service ({@code sangam-pdf-validator}, which runs veraPDF and is the only place Rhino exists)
 * and its report is read back. Found by {@link java.util.ServiceLoader} as the {@link PdfRenderer.Validator} of
 * {@link PdfRendering#installed()}.
 *
 * <p>Configuration, read like the renderer's fonts (a system property wins over the environment variable, so a blank
 * property switches the setting off whatever the environment says):
 * <ul>
 *   <li>{@value #URL_PROPERTY} / {@value #URL_ENV}: base URL of the validator service, {@code https} (plain
 *       {@code http} only with transport {@code mesh}), without credentials. Absent or blank: PDF output stays off
 *       ({@link #notReadyReason()}).</li>
 *   <li>{@value #TOKEN_FILE_PROPERTY} / {@value #TOKEN_FILE_ENV}: the mounted secret file whose first line is this
 *       server's token for the validator (owner decision 153). Absent: not ready; there is no unauthenticated mode.</li>
 *   <li>{@value #TRANSPORT_PROPERTY} / {@value #TRANSPORT_ENV}: {@code tls} (default) or {@code mesh} when an
 *       authenticated service mesh carries the traffic.</li>
 *   <li>{@value #TIMEOUT_PROPERTY} / {@value #TIMEOUT_ENV}: connect and answer timeout in milliseconds, default
 *       {@value #DEFAULT_TIMEOUT_MS}.</li>
 * </ul>
 *
 * <p>Wire shape (the validator service implements it with these constants):
 * {@code POST <url>/v1/validate?conformance=PDF_A_2B,PDF_UA_1} with {@code Authorization: Bearer <token>}, body the PDF
 * bytes as {@code application/pdf}; answer {@code 200 application/json}
 * {@code {"ok":bool,"validator":"veraPDF 1.30.4","checked":[...],"findings":[...]}}. Findings are only ever in a 200
 * report; authentication ({@code 401}), readiness ({@code 503}) and processing errors are separate statuses.
 *
 * <p>Fail closed: missing credentials, a timeout, a refused connection, refused credentials, any status other than 200,
 * a body that is not that shape, a passing report with findings, or a report for other conformance levels than the ones
 * asked for throws {@link PdfRendering.Unavailable} ({@code 422 FORMAT_NOT_AVAILABLE}); a pass is returned only when
 * the service said so. The token authenticates the server only: user permissions and tenant ownership are checked by
 * the server before it renders.
 */
public final class RemotePdfValidator implements PdfRenderer.Validator {

    public static final String URL_PROPERTY = "sangam.pdf.validator.url";
    public static final String URL_ENV = "SANGAM_PDF_VALIDATOR_URL";
    public static final String TIMEOUT_PROPERTY = "sangam.pdf.validator.timeout-ms";
    public static final String TIMEOUT_ENV = "SANGAM_PDF_VALIDATOR_TIMEOUT_MS";
    public static final String TOKEN_FILE_PROPERTY = "sangam.pdf.validator.token-file";
    public static final String TOKEN_FILE_ENV = "SANGAM_PDF_VALIDATOR_TOKEN_FILE";
    public static final String TRANSPORT_PROPERTY = "sangam.pdf.validator.transport";
    public static final String TRANSPORT_ENV = "SANGAM_PDF_VALIDATOR_TRANSPORT";
    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** The validation endpoint, relative to the configured base URL. */
    public static final String VALIDATE_PATH = "/v1/validate";
    /** Query parameter: the comma-separated {@link PdfRenderer.Conformance} names to check. */
    public static final String CONFORMANCE_PARAM = "conformance";
    public static final String PDF_CONTENT_TYPE = "application/pdf";
    public static final String REPORT_CONTENT_TYPE = "application/json";
    /** Report fields. */
    public static final String OK = "ok";
    public static final String VALIDATOR = "validator";
    public static final String CHECKED = "checked";
    public static final String FINDINGS = "findings";

    /** A report larger than this is not a report this client reads. */
    static final int MAX_REPORT_BYTES = 1 << 20;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URL_SETTING = URL_PROPERTY + " / " + URL_ENV;

    private final SidecarClient client;

    /** ServiceLoader constructor: configuration from the system properties or the environment. */
    public RemotePdfValidator() {
        this(fromEnvironment());
    }

    /** @param settings the validator URL, timeout, transport and credentials (blank URL: none) */
    public RemotePdfValidator(SidecarClient.Settings settings) {
        this.client = new SidecarClient("PDF validator", URL_SETTING, settings, Duration.ofMillis(DEFAULT_TIMEOUT_MS));
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
        String reason = client.notReadyReason();
        return reason == null ? null : reason + "; owner decisions 114 and 153 keep veraPDF out of the server";
    }

    /** The configured service base URL, or {@code null} when none is configured. */
    public String endpoint() {
        return client.endpoint();
    }

    public Duration timeout() {
        return client.timeout();
    }

    @Override
    public PdfRenderer.Report validate(byte[] pdf, Set<PdfRenderer.Conformance> required) {
        String notReady = notReadyReason();
        if (notReady != null) {
            throw new PdfRendering.Unavailable("the PDF validator is not ready: " + notReady);
        }
        if (required == null || required.isEmpty()) {
            throw new IllegalArgumentException("at least one conformance level is required");
        }
        if (pdf == null || pdf.length == 0) {
            return new PdfRenderer.Report(false, "remote", List.of("PARSE: no bytes"));
        }
        TreeSet<String> asked = new TreeSet<>();
        required.forEach(c -> asked.add(c.name()));
        try {
            SidecarClient.Answer answer = client.post(VALIDATE_PATH, CONFORMANCE_PARAM + "=" + String.join(",", asked),
                    PDF_CONTENT_TYPE, REPORT_CONTENT_TYPE, pdf, MAX_REPORT_BYTES);
            if (answer.status() != 200) {
                throw client.unusable("answered HTTP " + answer.status() + " instead of 200");
            }
            return report(answer.body(), asked);
        } catch (SidecarClient.CallFailed e) {
            throw new PdfRendering.Unavailable(e.getMessage()
                    + "; PDF output is refused rather than handed out unvalidated (owner decision 114)");
        }
    }

    private PdfRenderer.Report report(byte[] body, TreeSet<String> asked) throws SidecarClient.CallFailed {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw client.unusable("answered a body that is not JSON");
        }
        if (root == null || !root.isObject() || !root.path(OK).isBoolean() || !root.path(VALIDATOR).isTextual()
                || root.path(VALIDATOR).asText().isBlank() || !root.path(FINDINGS).isArray()
                || !root.path(CHECKED).isArray()) {
            throw client.unusable("answered a report without ok, validator, checked and findings");
        }
        TreeSet<String> checked = new TreeSet<>();
        for (JsonNode c : root.path(CHECKED)) {
            if (!c.isTextual()) {
                throw client.unusable("answered a report whose checked list is not text");
            }
            checked.add(c.asText());
        }
        if (!checked.equals(asked)) {
            throw client.unusable("checked " + checked + " but " + asked + " was asked for");
        }
        List<String> findings = new ArrayList<>();
        for (JsonNode f : root.path(FINDINGS)) {
            if (!f.isTextual()) {
                throw client.unusable("answered a report whose findings are not text");
            }
            findings.add(f.asText());
        }
        boolean ok = root.path(OK).booleanValue();
        if (ok && !findings.isEmpty()) {
            throw client.unusable("answered a passing report with findings");
        }
        return new PdfRenderer.Report(ok, root.path(VALIDATOR).asText(), findings);
    }
}
