// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Hashes;

/** M-081: the render-then-validate contract and its fail-closed paths, with a fake renderer and validator. */
class PdfRenderingTest {

    private static final PdfDocument DOC = new PdfDocument("Order", "en", null, null,
            List.of(new PdfDocument.Paragraph("text")), Map.of());

    private static PdfRenderer renderer(Set<PdfRenderer.Conformance> claims, byte[] out) {
        return new PdfRenderer() {
            @Override
            public String name() {
                return "fake-1";
            }

            @Override
            public Set<Conformance> conformance() {
                return claims;
            }

            @Override
            public byte[] render(PdfDocument document) {
                return out;
            }
        };
    }

    private static final byte[] PDF = "%PDF-1.7 fake".getBytes(StandardCharsets.US_ASCII);
    private static final PdfRenderer.Validator PASS = (pdf, req) -> new PdfRenderer.Report(true, "fake-validator", List.of());
    private static final PdfRenderer.Validator FAIL = (pdf, req) -> new PdfRenderer.Report(false, "fake-validator",
            List.of("6.2.11.4.1: font not embedded"));

    @Test
    void nothingInstalledIsUnavailable() {
        PdfRendering r = PdfRendering.unavailable();
        assertFalse(r.available());
        assertTrue(r.unavailableReason().contains("no PDF/A renderer"));
        assertThrows(PdfRendering.Unavailable.class, () -> r.render(DOC));
    }

    @Test
    void aRendererWithoutValidatorStaysOff() {
        PdfRendering r = new PdfRendering(renderer(PdfRenderer.REQUIRED, PDF), null);
        assertFalse(r.available(), "the validation step is mandatory");
        assertThrows(PdfRendering.Unavailable.class, () -> r.render(DOC));
    }

    @Test
    void aValidatorThatIsNotReadyKeepsOutputOffWithItsReason() {
        PdfRenderer.Validator notReady = new PdfRenderer.Validator() {
            @Override
            public PdfRenderer.Report validate(byte[] pdf, Set<PdfRenderer.Conformance> required) {
                throw new AssertionError("a validator that is not ready is never called");
            }

            @Override
            public String notReadyReason() {
                return "no validator service configured";
            }
        };
        PdfRendering r = new PdfRendering(renderer(PdfRenderer.REQUIRED, PDF), notReady);
        assertFalse(r.available());
        assertEquals("the PDF validator is not ready: no validator service configured", r.unavailableReason());
        assertThrows(PdfRendering.Unavailable.class, () -> r.render(DOC));
    }

    @Test
    void aValidatorWithNoAnswerAtRenderTimeIsUnavailableNotInvalid() {
        PdfRenderer.Validator silent = (pdf, req) -> {
            throw new PdfRendering.Unavailable("the PDF validator service did not answer");
        };
        PdfRendering r = new PdfRendering(renderer(PdfRenderer.REQUIRED, PDF), silent);
        assertTrue(r.available());
        PdfRendering.Unavailable e = assertThrows(PdfRendering.Unavailable.class, () -> r.render(DOC));
        assertEquals("the PDF validator service did not answer", e.getMessage());
    }

    @Test
    void aRendererClaimingLessThanRequiredStaysOff() {
        PdfRendering r = new PdfRendering(renderer(Set.of(PdfRenderer.Conformance.PDF_A_2B), PDF), PASS);
        assertFalse(r.available());
    }

    @Test
    void validBytesAreHandedOutWithTheirHash() {
        PdfRendering r = new PdfRendering(renderer(PdfRenderer.REQUIRED, PDF), PASS);
        assertTrue(r.available());
        assertNull(r.unavailableReason());
        PdfRendering.Rendered out = r.render(DOC);
        assertArrayEquals(PDF, out.content());
        assertEquals(Hashes.hex(Hashes.sha256(PDF)), out.sha256());
        assertEquals("fake-1", out.renderer());
        assertTrue(out.validation().ok());
    }

    @Test
    void invalidBytesAreNeverHandedOut() {
        PdfRendering r = new PdfRendering(renderer(PdfRenderer.REQUIRED, PDF), FAIL);
        PdfRendering.Invalid e = assertThrows(PdfRendering.Invalid.class, () -> r.render(DOC));
        assertEquals(List.of("6.2.11.4.1: font not embedded"), e.report().findings());
        PdfRendering empty = new PdfRendering(renderer(PdfRenderer.REQUIRED, new byte[0]), PASS);
        assertThrows(PdfRendering.Invalid.class, () -> empty.render(DOC));
        PdfRendering noReport = new PdfRendering(renderer(PdfRenderer.REQUIRED, PDF), (pdf, req) -> null);
        assertThrows(PdfRendering.Invalid.class, () -> noReport.render(DOC));
    }

    @Test
    void theInstalledRenderingStaysOffWithoutFonts() {
        // The PDFBox renderer and the remote validator client are both registered (M-081, decisions 95 and 114); without
        // a configured font the renderer is not ready, and its reason is reported before the validator's. A blank
        // property wins over the environment variable, so the result does not depend on the machine.
        String before = System.getProperty("sangam.pdf.fonts");
        try {
            System.setProperty("sangam.pdf.fonts", " ");
            PdfRendering installed = PdfRendering.installed();
            assertFalse(installed.available());
            assertTrue(installed.unavailableReason().contains("no font is configured"), installed.unavailableReason());
        } finally {
            if (before == null) {
                System.clearProperty("sangam.pdf.fonts");
            } else {
                System.setProperty("sangam.pdf.fonts", before);
            }
        }
    }

    @Test
    void aDocumentTheRendererCannotDrawIsInvalidNotAnError() {
        PdfRenderer refuses = new PdfRenderer() {
            @Override
            public String name() {
                return "fake-2";
            }

            @Override
            public Set<Conformance> conformance() {
                return REQUIRED;
            }

            @Override
            public byte[] render(PdfDocument document) {
                throw new IllegalArgumentException("no configured PDF font has a glyph for U+0BA4");
            }
        };
        PdfRendering.Invalid e = assertThrows(PdfRendering.Invalid.class, () -> new PdfRendering(refuses, PASS).render(DOC));
        assertEquals(List.of("RENDER: no configured PDF font has a glyph for U+0BA4"), e.report().findings());
        assertEquals("fake-2", e.report().validator());
    }

    @Test
    void aRendererThatIsNotReadyKeepsPdfOutputOffWithItsReason() {
        PdfRenderer notReady = new PdfRenderer() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public Set<Conformance> conformance() {
                return REQUIRED;
            }

            @Override
            public String notReadyReason() {
                return "no font is configured";
            }

            @Override
            public byte[] render(PdfDocument document) {
                throw new AssertionError("must not render");
            }
        };
        PdfRendering r = new PdfRendering(notReady, PASS);
        assertFalse(r.available());
        assertTrue(r.unavailableReason().contains("no font is configured"), r.unavailableReason());
        assertThrows(PdfRendering.Unavailable.class, () -> r.render(DOC));
    }

    @Test
    void moreThanOneImplementationIsAPackagingError() {
        assertThrows(IllegalStateException.class, () -> PdfRendering.single(List.of("a", "b"), "PdfRenderer"));
        assertEquals("a", PdfRendering.single(List.of("a"), "PdfRenderer"));
        assertNull(PdfRendering.single(List.of(), "PdfRenderer"));
    }

    @Test
    void aDocumentNeedsTitleAndLanguage() {
        assertThrows(IllegalArgumentException.class, () -> new PdfDocument(" ", "en", null, null, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new PdfDocument("t", null, null, null, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new PdfDocument.Heading(4, "x"));
        assertThrows(IllegalArgumentException.class, () -> new PdfRenderer.Report(true, "v", List.of("x")));
    }
}
