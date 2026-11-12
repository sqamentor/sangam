// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.verapdf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.integration.document.pdf.PdfDocument;
import org.sangam.integration.document.pdf.PdfRenderer;
import org.sangam.integration.document.pdf.PdfRendering;
import org.sangam.integration.document.pdf.pdfbox.PdfBoxRenderer;

/**
 * M-081 acceptance (owner decisions 94, 95, 102): a document rendered by the PDFBox renderer with the shipped fonts
 * ({@code deploy/fonts}: Noto Sans, Noto Sans Devanagari) passes veraPDF against both PDF/A-2b and PDF/UA-1; a plain
 * PDF fails both; the installed rendering turns PDF output on only with the validator and the fonts.
 */
class VeraPdfValidatorTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T10:15:30Z"), ZoneOffset.UTC);
    static final Path FONTS = Path.of("..", "deploy", "fonts");

    @TempDir
    Path tmp;

    static List<Path> noto() {
        List<Path> fonts = List.of(FONTS.resolve("NotoSans-Regular.ttf"), FONTS.resolve("NotoSansDevanagari-Regular.ttf"));
        fonts.forEach(f -> assertTrue(Files.isRegularFile(f), "the deployment font " + f.toAbsolutePath() + " exists"));
        return fonts;
    }

    static PdfDocument mixedScript(int paragraphs) {
        List<PdfDocument.Block> blocks = new ArrayList<>();
        blocks.add(new PdfDocument.Heading(1, "Receipt रसीद R-7"));
        for (int i = 0; i < paragraphs; i++) {
            blocks.add(new PdfDocument.Paragraph("Paragraph " + i + ": the office कार्यालय received the क्षत्रिय स्त्री "
                    + "file on 12 November। Officer: श्री राम (र्क, क्त्र) and the seal S-100 & <copy>."));
        }
        blocks.add(new PdfDocument.Heading(2, "Items सूची"));
        blocks.add(new PdfDocument.Table(List.of("No", "विवरण", "Seal"),
                List.of(List.of("1", "कागज़ात", "S-100"), List.of("2", "Ledger", ""))));
        return new PdfDocument("Receipt रसीद R-7", "hi-IN", "Registry office", "Custody receipt", blocks, Map.of());
    }

    @Test
    void aRenderedMixedScriptSamplePassesVeraPdfForPdfA2bAndPdfUa1() {
        byte[] pdf = new PdfBoxRenderer(noto(), CLOCK).render(mixedScript(40));
        PdfRenderer.Report report = new VeraPdfValidator().validate(pdf, PdfRenderer.REQUIRED);
        assertTrue(report.ok(), String.join("\n", report.findings()));
        assertTrue(report.validator().startsWith("veraPDF"), report.validator());
    }

    @Test
    void aRenderedLatinSampleWithTheLatinFontAlonePassesToo() throws Exception {
        Path font = tmp.resolve("LiberationSans-Regular.ttf");
        try (InputStream in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            Files.copy(in, font);
        }
        PdfDocument latin = new PdfDocument("Receipt R-1", "en-IN", null, null, List.of(
                new PdfDocument.Heading(1, "Receipt R-1"),
                new PdfDocument.Paragraph("The item was received in good order and sealed."),
                new PdfDocument.Table(List.of("No", "Item"), List.of(List.of("1", "Ledger")))), Map.of());
        byte[] pdf = new PdfBoxRenderer(List.of(font), CLOCK).render(latin);
        PdfRenderer.Report report = new VeraPdfValidator().validate(pdf, PdfRenderer.REQUIRED);
        assertTrue(report.ok(), String.join("\n", report.findings()));
    }

    @Test
    void aPlainPdfFailsBothProfilesWithRuleIds() throws Exception {
        byte[] plain;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            plain = out.toByteArray();
        }
        PdfRenderer.Report report = new VeraPdfValidator().validate(plain, PdfRenderer.REQUIRED);
        assertFalse(report.ok());
        String all = String.join("\n", report.findings());
        assertTrue(all.contains("PDF_A_2B ISO_19005_2"), all);
        assertTrue(all.contains("PDF_UA_1 ISO_14289_1"), all);
        PdfRenderer.Report onlyA = new VeraPdfValidator().validate(plain, java.util.Set.of(PdfRenderer.Conformance.PDF_A_2B));
        assertTrue(onlyA.findings().stream().allMatch(f -> f.startsWith("PDF_A_2B")), onlyA.findings().toString());
    }

    @Test
    void bytesThatAreNotAPdfFailClosed() {
        PdfRenderer.Report report = new VeraPdfValidator().validate("not a pdf".getBytes(StandardCharsets.US_ASCII),
                PdfRenderer.REQUIRED);
        assertFalse(report.ok());
        assertFalse(new VeraPdfValidator().validate(new byte[0], PdfRenderer.REQUIRED).ok());
    }

    @Test
    void theWholePathRendersValidatesAndHandsOutTheBytes() {
        PdfRendering rendering = new PdfRendering(new PdfBoxRenderer(noto(), CLOCK), new VeraPdfValidator());
        assertTrue(rendering.available(), String.valueOf(rendering.unavailableReason()));
        PdfRendering.Rendered out = rendering.render(mixedScript(2));
        assertTrue(new String(out.content(), 0, 8, StandardCharsets.US_ASCII).startsWith("%PDF-1.7"));
        assertTrue(out.validation().ok());
        assertTrue(out.validation().validator().startsWith("veraPDF"));
    }

    @Test
    void textThatNoConfiguredFontCoversIsRefusedAsInvalid() {
        PdfRendering rendering = new PdfRendering(new PdfBoxRenderer(noto(), CLOCK), new VeraPdfValidator());
        PdfDocument tamil = new PdfDocument("T", "ta", null, null, List.of(new PdfDocument.Paragraph("தமிழ்")), Map.of());
        PdfRendering.Invalid e = org.junit.jupiter.api.Assertions.assertThrows(PdfRendering.Invalid.class,
                () -> rendering.render(tamil));
        assertTrue(e.report().findings().get(0).contains("U+0BA4"), e.report().findings().toString());
    }

    @Test
    void theInstalledRenderingIsOnOnlyWithTheValidatorAndTheFonts() {
        String before = System.getProperty(PdfBoxRenderer.FONTS_PROPERTY);
        try {
            System.setProperty(PdfBoxRenderer.FONTS_PROPERTY, " ");
            PdfRendering off = PdfRendering.installed();
            assertFalse(off.available());
            assertTrue(off.unavailableReason().contains("no font is configured"), off.unavailableReason());

            System.setProperty(PdfBoxRenderer.FONTS_PROPERTY, String.join(",",
                    noto().stream().map(p -> p.toAbsolutePath().toString()).toList()));
            PdfRendering on = PdfRendering.installed();
            assertNull(on.unavailableReason());
            assertTrue(on.available());
            assertTrue(on.render(mixedScript(1)).validation().ok());
        } finally {
            if (before == null) {
                System.clearProperty(PdfBoxRenderer.FONTS_PROPERTY);
            } else {
                System.setProperty(PdfBoxRenderer.FONTS_PROPERTY, before);
            }
        }
    }
}
