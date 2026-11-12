// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf;

import java.util.List;
import java.util.Set;

/**
 * Renders a {@link PdfDocument} to PDF/A-2b + PDF/UA-1 bytes (M-081, R61 GOV-11, R42, R55). An implementation is found
 * with {@link java.util.ServiceLoader} ({@code META-INF/services/org.sangam.integration.document.pdf.PdfRenderer}) by
 * {@link PdfRendering#installed()}, so adding the implementation jar is the only step that turns PDF output on.
 *
 * <p>Contract for an implementation:
 * <ul>
 *   <li>PDF/A-2b (ISO 19005-2, level B): every font embedded (bundled fonts, including a Devanagari font, as a separate
 *       image layer), an sRGB output intent, XMP metadata with {@code pdfaid:part=2} and {@code pdfaid:conformance=B},
 *       no encryption, no JavaScript, no external content;</li>
 *   <li>PDF/UA-1 (ISO 14289-1): {@code /MarkInfo /Marked true}, a structure tree with the block tags in reading order,
 *       {@code /Lang}, {@code dc:title} with {@code DisplayDocTitle}, {@code pdfuaid:part=1};</li>
 *   <li>never iText or any AGPL library (licence gate, {@code licence-policy.json}); the planned implementation is
 *       Apache PDFBox (Apache-2.0).</li>
 * </ul>
 */
public interface PdfRenderer {

    /** What a renderer claims and a validator checks. */
    enum Conformance { PDF_A_2B, PDF_UA_1 }

    /** The two conformance levels every Sangam PDF must meet. */
    Set<Conformance> REQUIRED = Set.of(Conformance.PDF_A_2B, Conformance.PDF_UA_1);

    /** Implementation name and version for logs and audit, e.g. {@code pdfbox-3.0.x}. */
    String name();

    Set<Conformance> conformance();

    /**
     * Why this renderer cannot render now (for example no font is configured, and PDF/A needs every font embedded), or
     * {@code null} when it is ready. {@link PdfRendering} reports the reason instead of rendering.
     */
    default String notReadyReason() {
        return null;
    }

    byte[] render(PdfDocument document);

    /**
     * The validation step (M-081 acceptance): checks rendered bytes against the required conformance levels. An
     * implementation may validate with its own parser or delegate to a separate validator; it must not trust its own
     * claim without reading the bytes back.
     */
    interface Validator {
        Report validate(byte[] pdf, Set<Conformance> required);
    }

    /** @param findings rule id and message per failed check; empty when {@code ok} */
    record Report(boolean ok, String validator, List<String> findings) {
        public Report {
            findings = List.copyOf(findings == null ? List.of() : findings);
            if (ok && !findings.isEmpty()) {
                throw new IllegalArgumentException("a passing report has no findings");
            }
        }
    }
}
