// SPDX-License-Identifier: Apache-2.0
package org.sangam.pdfvalidator;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.sangam.integration.sidecar.SidecarSettings;

/**
 * Entry point of the validator image ({@code deploy/docker/pdf-validator.Dockerfile}). Settings come from the
 * environment variables with the prefix {@value #PREFIX} (see {@link SidecarSettings}):
 * <ul>
 *   <li>{@value #BIND_ENV} (default {@code 0.0.0.0}; keep the port on the private network only) and {@value #PORT_ENV}
 *       (default {@value #DEFAULT_PORT});</li>
 *   <li>{@value #THREADS_ENV} validations at once, {@value #MAX_BYTES_ENV} largest PDF (default 64 MiB),
 *       {@value #PROCESSING_TIMEOUT_ENV} longest validation (default 120 s);</li>
 *   <li>{@value #TRANSPORT_ENV} {@code tls} (default, with {@value #TLS_KEYSTORE_ENV} and
 *       {@value #TLS_KEYSTORE_PASSWORD_FILE_ENV}) or {@code mesh};</li>
 *   <li>{@value #TOKEN_FILE_ENV}: the mounted caller token file (owner decision 153). Without it the service starts,
 *       reports {@code NOT_READY} and refuses every validation.</li>
 * </ul>
 * An invalid setting stops the start-up with a message instead of falling back to a default. No secret is printed.
 */
public final class PdfValidatorMain {

    public static final String PREFIX = "SANGAM_PDF_VALIDATOR";
    public static final String PORT_ENV = "SANGAM_PDF_VALIDATOR_PORT";
    public static final String BIND_ENV = "SANGAM_PDF_VALIDATOR_BIND";
    public static final String THREADS_ENV = "SANGAM_PDF_VALIDATOR_THREADS";
    public static final String MAX_BYTES_ENV = "SANGAM_PDF_VALIDATOR_MAX_BYTES";
    public static final String PROCESSING_TIMEOUT_ENV = "SANGAM_PDF_VALIDATOR_PROCESSING_TIMEOUT_MS";
    public static final String TRANSPORT_ENV = "SANGAM_PDF_VALIDATOR_TRANSPORT";
    public static final String TOKEN_FILE_ENV = "SANGAM_PDF_VALIDATOR_TOKEN_FILE";
    public static final String TLS_KEYSTORE_ENV = "SANGAM_PDF_VALIDATOR_TLS_KEYSTORE";
    public static final String TLS_KEYSTORE_PASSWORD_FILE_ENV = "SANGAM_PDF_VALIDATOR_TLS_KEYSTORE_PASSWORD_FILE";
    public static final int DEFAULT_PORT = 8085;
    public static final Duration DEFAULT_PROCESSING_TIMEOUT = Duration.ofSeconds(120);

    private PdfValidatorMain() {
    }

    /** The settings read from an environment map; invalid values throw {@link IllegalArgumentException}. */
    static SidecarSettings settings(Map<String, String> env) {
        return SidecarSettings.from(PREFIX, env, DEFAULT_PORT, PdfValidatorService.DEFAULT_MAX_BYTES,
                DEFAULT_PROCESSING_TIMEOUT);
    }

    public static void main(String[] args) throws Exception {
        SidecarSettings s = settings(System.getenv());
        PdfValidatorService service = new PdfValidatorService(new VeraPdfValidator(), VeraPdfValidator.name(),
                s.open());
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.close();
            stopped.countDown();
        }, "pdf-validator-shutdown"));
        String notReady = s.authenticator().notReadyReason();
        System.out.println("sangam-pdf-validator listening on " + s.bind() + ":" + service.port() + " ("
                + s.transport().name().toLowerCase(java.util.Locale.ROOT) + ") with " + VeraPdfValidator.name()
                + (notReady == null ? "" : "; NOT READY: " + notReady));
        stopped.await();
    }
}
