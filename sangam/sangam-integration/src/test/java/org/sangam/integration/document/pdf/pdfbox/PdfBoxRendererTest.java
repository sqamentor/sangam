// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.pdfbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.integration.document.pdf.PdfDocument;
import org.sangam.integration.document.pdf.PdfRenderer;
import org.sangam.integration.document.pdf.PdfRendering;

/**
 * M-081: the PDFBox renderer writes the PDF/A-2b and PDF/UA-1 features and reads back clean through
 * {@link PdfStructureCheck}; it is not ready without a usable font; text no font covers is refused. The test font is
 * the Liberation Sans file that ships inside the pdfbox jar, copied to a temporary file.
 */
class PdfBoxRendererTest {

    static final Instant CREATED = Instant.parse("2026-11-12T10:15:30Z");
    static final Clock CLOCK = Clock.fixed(CREATED, ZoneOffset.UTC);

    @TempDir
    Path tmp;

    Path font() throws Exception {
        Path f = tmp.resolve("LiberationSans-Regular.ttf");
        try (InputStream in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(in, "the pdfbox jar carries its fallback font");
            Files.copy(in, f);
        }
        return f;
    }

    static PdfDocument document(int paragraphs) {
        List<PdfDocument.Block> blocks = new ArrayList<>();
        blocks.add(new PdfDocument.Heading(1, "Receipt R-1"));
        for (int i = 0; i < paragraphs; i++) {
            blocks.add(new PdfDocument.Paragraph("Paragraph " + i + ": the item was received in good order\tand sealed. "
                    + "A long sentence that has to wrap over more than one line of the page so the layout is exercised."));
        }
        blocks.add(new PdfDocument.Heading(2, "Items"));
        blocks.add(new PdfDocument.Table(List.of("No", "Item", "Seal"),
                List.of(List.of("1", "Laptop & charger <boxed>", "S-100"), List.of("2", "Ledger", ""))));
        return new PdfDocument("Receipt R-1 & copy", "en-IN", "Registry office", "Custody receipt", blocks, Map.of());
    }

    @Test
    void withoutAUsableFontTheRendererIsNotReady() throws Exception {
        assertTrue(new PdfBoxRenderer(List.of(), CLOCK).notReadyReason().contains("no font is configured"));
        assertTrue(new PdfBoxRenderer(List.of(tmp.resolve("missing.ttf")), CLOCK).notReadyReason().contains("not a readable file"));
        Path otf = Files.writeString(tmp.resolve("x.otf"), "x");
        assertTrue(new PdfBoxRenderer(List.of(otf), CLOCK).notReadyReason().contains("TrueType"));
        PdfBoxRenderer notReady = new PdfBoxRenderer(List.of(), CLOCK);
        assertThrows(IllegalStateException.class, () -> notReady.render(document(1)));
        assertFalse(new PdfRendering(notReady, new PdfStructureCheck()).available());
        assertNull(new PdfBoxRenderer(List.of(font()), CLOCK).notReadyReason());
    }

    @Test
    void theFontListIsSplitOnCommasAndThePathSeparator() {
        assertEquals(List.of(), PdfBoxRenderer.fontPaths(null));
        assertEquals(List.of(), PdfBoxRenderer.fontPaths("  "));
        assertEquals(List.of(Path.of("a.ttf"), Path.of("b.ttf"), Path.of("c.ttf")),
                PdfBoxRenderer.fontPaths("a.ttf, b.ttf" + File.pathSeparator + "c.ttf"));
    }

    @Test
    void theOutputCarriesThePdfAAndPdfUaFeaturesAndReadsBackClean() throws Exception {
        PdfBoxRenderer r = new PdfBoxRenderer(List.of(font()), CLOCK);
        byte[] pdf = r.render(document(60));
        PdfRenderer.Report report = new PdfStructureCheck().validate(pdf, PdfRenderer.REQUIRED);
        assertTrue(report.ok(), report.findings().toString());
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(1.7f, doc.getVersion(), 0.001f);
            assertTrue(doc.getNumberOfPages() > 1, "60 paragraphs need more than one page");
            assertEquals("en-IN", doc.getDocumentCatalog().getLanguage());
            assertEquals("Receipt R-1 & copy", doc.getDocumentInformation().getTitle());
            assertEquals(CREATED, doc.getDocumentInformation().getCreationDate().toInstant());
            String xmp = new String(doc.getDocumentCatalog().getMetadata().exportXMPMetadata().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("Receipt R-1 &amp; copy"), xmp);
            assertTrue(xmp.contains("<xmp:CreateDate>2026-11-12T10:15:30Z</xmp:CreateDate>"), xmp);
            assertTrue(xmp.contains("<pdfaSchema:prefix>pdfuaid</pdfaSchema:prefix>"), "pdfuaid is described for PDF/A");

            PDStructureElement root = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
            assertEquals("Document", root.getStructureType());
            List<String> types = root.getKids().stream().filter(k -> k instanceof PDStructureElement)
                    .map(k -> ((PDStructureElement) k).getStructureType()).toList();
            assertEquals("H1", types.get(0));
            assertEquals("P", types.get(1));
            assertEquals(List.of("H2", "Table"), types.subList(types.size() - 2, types.size()));
            PDStructureElement table = (PDStructureElement) root.getKids().get(root.getKids().size() - 1);
            PDStructureElement headerRow = (PDStructureElement) table.getKids().get(0);
            PDStructureElement th = (PDStructureElement) headerRow.getKids().get(0);
            assertEquals("TH", th.getStructureType());
            assertTrue(String.valueOf(th.getCOSObject().getDictionaryObject(COSName.getPDFName("A"))).contains("Column"),
                    "header cells have column scope");

            COSDictionary parentTree = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject()
                    .getCOSDictionary(COSName.PARENT_TREE);
            COSArray nums = parentTree.getCOSArray(COSName.NUMS);
            assertEquals(2 * doc.getNumberOfPages(), nums.size(), "one parent-tree entry per page");
            int i = 0;
            for (PDPage page : doc.getPages()) {
                assertEquals(i++, page.getStructParents());
                assertEquals("S", page.getCOSObject().getNameAsString(COSName.getPDFName("Tabs")));
            }
        }
    }

    @Test
    void theCreationDateComesFromTheDocumentMetadataWhenGiven() throws Exception {
        PdfBoxRenderer r = new PdfBoxRenderer(List.of(font()), CLOCK);
        PdfDocument d = new PdfDocument("T", "en", null, null, List.of(new PdfDocument.Paragraph("x")),
                Map.of(PdfBoxRenderer.CREATED_AT, "2025-01-02T03:04:05Z"));
        try (PDDocument doc = Loader.loadPDF(r.render(d))) {
            assertEquals(Instant.parse("2025-01-02T03:04:05Z"), doc.getDocumentInformation().getCreationDate().toInstant());
        }
        PdfDocument bad = new PdfDocument("T", "en", null, null, List.of(), Map.of(PdfBoxRenderer.CREATED_AT, "yesterday"));
        assertThrows(IllegalArgumentException.class, () -> r.render(bad));
    }

    @Test
    void textThatNoConfiguredFontCoversIsRefused() throws Exception {
        PdfBoxRenderer latinOnly = new PdfBoxRenderer(List.of(font()), CLOCK);
        PdfDocument devanagari = new PdfDocument("T", "hi", null, null,
                List.of(new PdfDocument.Paragraph("कार्य")), Map.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> latinOnly.render(devanagari));
        assertTrue(e.getMessage().contains("U+0915"), e.getMessage());
    }

    @Test
    void theStructureCheckFindsWhatAPlainPdfLacks() throws Exception {
        byte[] plain;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            plain = out.toByteArray();
        }
        PdfRenderer.Report report = new PdfStructureCheck().validate(plain, PdfRenderer.REQUIRED);
        assertFalse(report.ok());
        String all = String.join("\n", report.findings());
        for (String expected : List.of("XMP: no metadata stream", "OUTPUT_INTENT", "UA: /MarkInfo", "UA: no structure tree",
                "UA: no /Lang", "PAGE 1: no /StructParents")) {
            assertTrue(all.contains(expected), expected + " in " + all);
        }
        assertFalse(new PdfStructureCheck().validate("not a pdf".getBytes(StandardCharsets.US_ASCII), Set.of()).ok());
    }

    @Test
    void withTheStructureCheckAsValidatorTheWholePathRenders() throws Exception {
        PdfRendering rendering = new PdfRendering(new PdfBoxRenderer(List.of(font()), CLOCK), new PdfStructureCheck());
        assertTrue(rendering.available(), String.valueOf(rendering.unavailableReason()));
        PdfRendering.Rendered out = rendering.render(document(2));
        assertTrue(new String(out.content(), 0, 8, StandardCharsets.US_ASCII).startsWith("%PDF-1.7"));
        assertEquals(64, out.sha256().length());
    }

    /** A Latin and Devanagari sample (VeraPdfValidatorTest renders the same text). */
    static PdfDocument mixedScript() {
        return new PdfDocument("Receipt रसीद R-7", "hi-IN", "Registry office", null, List.of(
                new PdfDocument.Heading(1, "Receipt रसीद R-7"),
                new PdfDocument.Paragraph("The office कार्यालय received the क्षत्रिय स्त्री file on 12 November। "
                        + "Officer: श्री राम (र्क, क्त्र) and the seal S-100."),
                new PdfDocument.Table(List.of("No", "विवरण", "Seal"),
                        List.of(List.of("1", "कागज़ात", "S-100"), List.of("2", "Ledger", "")))), Map.of());
    }

    @Test
    void mixedScriptTextShowsEveryGlyphWithAUnicodeValueAndActualTextForShapedRuns() throws Exception {
        PdfBoxRenderer r = new PdfBoxRenderer(PdfFontSetTest.noto(), CLOCK);
        byte[] pdf = r.render(mixedScript());
        PdfRenderer.Report report = new PdfStructureCheck().validate(pdf, PdfRenderer.REQUIRED);
        assertTrue(report.ok(), report.findings().toString());
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Set<String> fontNames = new java.util.TreeSet<>();
            int shown = 0;
            boolean actualText = false;
            for (PDPage page : doc.getPages()) {
                org.apache.pdfbox.pdmodel.font.PDFont font = null;
                List<Object> operands = new ArrayList<>();
                for (Object token : new org.apache.pdfbox.pdfparser.PDFStreamParser(page).parse()) {
                    if (!(token instanceof org.apache.pdfbox.contentstream.operator.Operator op)) {
                        operands.add(token);
                        continue;
                    }
                    switch (op.getName()) {
                        case "Tf" -> {
                            font = page.getResources().getFont((COSName) operands.get(0));
                            fontNames.add(PdfToUnicode.postScriptName(font.getName()));
                        }
                        case "Tj" -> {
                            byte[] codes = ((org.apache.pdfbox.cos.COSString) operands.get(0)).getBytes();
                            assertEquals(0, codes.length % 2, "two-byte Identity-H codes");
                            for (int i = 0; i < codes.length; i += 2) {
                                int code = (codes[i] & 0xFF) << 8 | (codes[i + 1] & 0xFF);
                                String u = font.toUnicode(code);
                                assertNotNull(u, "code " + code + " of " + font.getName() + " maps to Unicode");
                                assertFalse(u.isEmpty());
                                assertTrue(u.indexOf('\u0000') < 0 && u.indexOf('﻿') < 0 && u.indexOf('￾') < 0, u);
                                shown++;
                            }
                        }
                        case "BDC" -> actualText |= operands.size() == 2 && operands.get(1) instanceof COSDictionary d
                                && d.containsKey(COSName.ACTUAL_TEXT);
                        default -> {
                        }
                    }
                    operands.clear();
                }
            }
            assertTrue(shown > 50, "glyphs were shown: " + shown);
            assertEquals(Set.of("NotoSans-Regular", "NotoSansDevanagari-Regular"), fontNames);
            assertTrue(actualText, "shaped Devanagari runs carry /ActualText");
        }
    }

    @Test
    void xmlSpecialAndControlCharactersAreEscapedInTheXmp() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot;", PdfXmp.esc("a & b <c> \"d\""));
        assertEquals("xy", PdfXmp.esc("x\u0001y"));
    }
}
