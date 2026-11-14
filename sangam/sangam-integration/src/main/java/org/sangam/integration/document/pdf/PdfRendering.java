// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import org.sangam.core.layer.Hashes;

/**
 * The one entry point for PDF output (M-081): render, then validate, then hand out the bytes. A rendering that fails
 * validation is never returned ({@link Invalid}); with no renderer or no validator installed every call fails with
 * {@link Unavailable}, which callers map to {@code 422 FORMAT_NOT_AVAILABLE}. Bound to the document-template bridge,
 * the registry document port and the audit export ({@code PortsDocumentExtrasBridge}, {@code RegistryPortBridges},
 * {@code JdbcAuditExportAdapter}).
 */
public final class PdfRendering {

    /** The output of a successful render: conformant bytes and their SHA-256. */
    public record Rendered(byte[] content, String sha256, String renderer, PdfRenderer.Report validation) {
        public static final String CONTENT_TYPE = "application/pdf";
    }

    /**
     * No renderer or validator is installed or ready, or the validator service gave no answer (owner decision 114: fail
     * closed, never an unvalidated PDF).
     */
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /**
     * The document could not be rendered as given (a {@code RENDER} finding, for example text that no configured font
     * covers), or the rendered bytes failed the validation step; nothing was handed out.
     */
    public static final class Invalid extends RuntimeException {
        private final transient PdfRenderer.Report report;

        public Invalid(PdfRenderer.Report report) {
            super("the rendered PDF failed " + report.validator() + " validation: " + report.findings());
            this.report = report;
        }

        public PdfRenderer.Report report() {
            return report;
        }
    }

    private final PdfRenderer renderer;
    private final PdfRenderer.Validator validator;
    private final String unavailableReason;

    public PdfRendering(PdfRenderer renderer, PdfRenderer.Validator validator) {
        this(renderer, validator, null);
    }

    private PdfRendering(PdfRenderer renderer, PdfRenderer.Validator validator, String loadFailure) {
        this.renderer = renderer;
        this.validator = validator;
        String notReady = renderer == null ? null : renderer.notReadyReason();
        String validatorNotReady = validator == null ? null : validator.notReadyReason();
        this.unavailableReason = loadFailure != null ? loadFailure
                : renderer == null ? "no PDF/A renderer is installed (M-081: add the renderer module)"
                : validator == null ? "the PDF renderer " + renderer.name() + " has no validator installed; PDF output"
                + " stays off until the validation step is available"
                : !renderer.conformance().containsAll(PdfRenderer.REQUIRED) ? "the PDF renderer " + renderer.name()
                + " does not claim " + PdfRenderer.REQUIRED
                : notReady != null ? "the PDF renderer " + renderer.name() + " is not ready: " + notReady
                : validatorNotReady != null ? "the PDF validator is not ready: " + validatorNotReady
                : null;
    }

    /** A rendering with nothing installed (every call fails with {@link Unavailable}). */
    public static PdfRendering unavailable() {
        return new PdfRendering(null, null);
    }

    /**
     * The renderer and validator found on the class path. More than one renderer (or validator) is a packaging error
     * and fails, rather than one being picked by class-path order. A provider that cannot be loaded (its optional
     * library, for example PDFBox, is not on the class path) leaves PDF output off with that reason; it never breaks
     * the caller.
     */
    public static PdfRendering installed() {
        PdfRenderer renderer;
        PdfRenderer.Validator validator;
        try {
            renderer = single(ServiceLoader.load(PdfRenderer.class, PdfRendering.class.getClassLoader()), "PdfRenderer");
            validator = single(ServiceLoader.load(PdfRenderer.Validator.class, PdfRendering.class.getClassLoader()),
                    "PdfRenderer.Validator");
        } catch (java.util.ServiceConfigurationError | LinkageError e) {
            return new PdfRendering(null, null, "the installed PDF renderer or validator cannot be loaded ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "); is its library on the class path?");
        }
        return new PdfRendering(renderer, validator, null);
    }

    static <T> T single(Iterable<T> found, String what) {
        List<T> all = new ArrayList<>();
        found.forEach(all::add);
        if (all.size() > 1) {
            throw new IllegalStateException("more than one " + what + " is installed: "
                    + all.stream().map(o -> o.getClass().getName()).toList());
        }
        return all.isEmpty() ? null : all.get(0);
    }

    public boolean available() {
        return unavailableReason == null;
    }

    /** Why PDF output is off, or {@code null} when it is available. */
    public String unavailableReason() {
        return unavailableReason;
    }

    public Rendered render(PdfDocument document) {
        if (!available()) {
            throw new Unavailable(unavailableReason);
        }
        byte[] pdf;
        try {
            pdf = renderer.render(document);
        } catch (IllegalArgumentException e) {
            // the document cannot be rendered as given (for example a character that no configured font covers)
            throw new Invalid(new PdfRenderer.Report(false, renderer.name(), List.of("RENDER: " + e.getMessage())));
        }
        if (pdf == null || pdf.length == 0) {
            throw new Invalid(new PdfRenderer.Report(false, renderer.name(), List.of("RENDER: no bytes")));
        }
        PdfRenderer.Report report = validator.validate(pdf, Set.copyOf(PdfRenderer.REQUIRED));
        if (report == null || !report.ok()) {
            throw new Invalid(report == null ? new PdfRenderer.Report(false, "none", List.of("VALIDATE: no report")) : report);
        }
        return new Rendered(pdf, Hashes.hex(Hashes.sha256(pdf)), renderer.name(), report);
    }
}
