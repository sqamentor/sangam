// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.pdfbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.Character.UnicodeScript;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

/**
 * M-081 (owner decisions 70, 94): the text-to-glyph path with the fonts the deployment ships ({@code deploy/fonts}):
 * Latin from Noto Sans and Devanagari from Noto Sans Devanagari whatever the configured order, joiners and spaces kept
 * with their word, Devanagari shaped, every shown glyph given a Unicode value, uncovered text refused.
 */
class PdfFontSetTest {

    static final Path FONTS = Path.of("..", "deploy", "fonts");
    static final Path LATIN = FONTS.resolve("NotoSans-Regular.ttf");
    static final Path DEVANAGARI = FONTS.resolve("NotoSansDevanagari-Regular.ttf");

    static List<Path> noto() {
        assertTrue(Files.isRegularFile(LATIN), "the deployment font " + LATIN.toAbsolutePath() + " is in the repository");
        assertTrue(Files.isRegularFile(DEVANAGARI), "the deployment font " + DEVANAGARI.toAbsolutePath() + " is in the repository");
        return List.of(LATIN, DEVANAGARI);
    }

    static boolean devanagariFont(PDType0Font f) {
        return f.getName().contains("Devanagari");
    }

    @Test
    void theShippedFontsOverlapAndCoverageDecidesWhichDrawsAScript() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            int[] latin = PdfFontSet.scriptCoverage(PDType0Font.load(doc, LATIN.toFile()).getCmapLookup());
            int[] deva = PdfFontSet.scriptCoverage(PDType0Font.load(doc, DEVANAGARI.toFile()).getCmapLookup());
            int l = UnicodeScript.LATIN.ordinal();
            int d = UnicodeScript.DEVANAGARI.ordinal();
            assertTrue(deva[l] > 0 && latin[d] > 0, "each shipped font carries some of the other script");
            assertTrue(latin[l] > deva[l], "Noto Sans covers more Latin");
            assertTrue(deva[d] > latin[d], "Noto Sans Devanagari covers more Devanagari");
        }
    }

    @Test
    void fontboxShapingLanguagesMapToTheirScripts() {
        assertEquals(UnicodeScript.DEVANAGARI, PdfFontSet.script(org.apache.fontbox.ttf.model.Language.DEVANAGARI));
        assertEquals(UnicodeScript.LATIN, PdfFontSet.script(org.apache.fontbox.ttf.model.Language.LATIN));
        assertEquals(null, PdfFontSet.script(org.apache.fontbox.ttf.model.Language.DFLT));
        assertEquals(null, PdfFontSet.script(null));
    }

    @Test
    void latinComesFromNotoSansAndDevanagariFromNotoSansDevanagariInEitherOrder() throws Exception {
        List<Path> fonts = noto();
        for (List<Path> order : List.of(fonts, List.of(fonts.get(1), fonts.get(0)))) {
            try (PDDocument doc = new PDDocument()) {
                PdfFontSet set = PdfFontSet.load(doc, order);
                List<PdfFontSet.Run> runs = set.runs("Office कार्यालय", false);
                assertEquals(2, runs.size(), runs.toString());
                assertEquals("Office ", runs.get(0).text(), "the space takes the script of the word before it");
                assertFalse(devanagariFont(runs.get(0).font()), runs.get(0).font().getName());
                assertEquals("कार्यालय", runs.get(1).text());
                assertTrue(devanagariFont(runs.get(1).font()), runs.get(1).font().getName());
            }
        }
    }

    @Test
    void textBeforeTheFirstLetterTakesTheScriptThatFollows() {
        UnicodeScript[] s = PdfFontSet.resolvedScripts("(क) a".codePoints().toArray());
        assertArrayEquals(new UnicodeScript[] {UnicodeScript.DEVANAGARI, UnicodeScript.DEVANAGARI,
                UnicodeScript.DEVANAGARI, UnicodeScript.DEVANAGARI, UnicodeScript.LATIN}, s);
        assertArrayEquals(new UnicodeScript[] {null, null}, PdfFontSet.resolvedScripts("12".codePoints().toArray()));
    }

    @Test
    void devanagariIsShapedAndEveryShownGlyphHasAUnicodeValue() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PdfFontSet set = PdfFontSet.load(doc, noto());
            String text = "क्षत्रिय स्त्री र्क क्त्र किताब";
            List<PdfFontSet.Run> runs = set.runs(text, true);
            assertEquals(1, runs.size(), "Devanagari words and the spaces between them stay in one run");
            PdfFontSet.Run run = runs.get(0);
            assertFalse(run.direct(), "conjuncts and half forms are substituted glyphs");
            assertEquals(text, run.text());
            Map<Integer, String> values = set.toUnicode().get(run.font().getName());
            assertNotNull(values);
            for (int g : run.glyphs()) {
                String v = values.get(g);
                assertNotNull(v, "glyph " + g + " has a Unicode value");
                assertFalse(v.isEmpty());
                assertTrue(v.codePoints().allMatch(cp -> cp != 0 && cp != 0xFEFF && cp != 0xFFFE), v);
            }
        }
    }

    @Test
    void plainLatinIsShownOneGlyphPerCharacter() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PdfFontSet set = PdfFontSet.load(doc, noto());
            PdfFontSet.Run run = set.runs("Receipt 12", true).get(0);
            assertEquals("Receipt 12".length(), run.glyphs().size());
            assertTrue(run.direct(), "Latin is not run through the font's Devanagari GSUB rules");
            assertTrue(set.width("Receipt", 11) > 0);
            assertTrue(set.width("Receipt Receipt", 11) > 2 * set.width("Receipt", 11) - 0.01f);
        }
    }

    @Test
    void aCharacterThatNoConfiguredFontCoversIsRefused() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PdfFontSet set = PdfFontSet.load(doc, noto());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> set.runs("தமிழ்", false));
            assertTrue(e.getMessage().contains("U+0BA4"), e.getMessage());
        }
    }

    @Test
    void twoFontsWithTheSamePostScriptNameAreRefused() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            assertThrows(IllegalStateException.class, () -> PdfFontSet.load(doc, List.of(LATIN, LATIN)));
        }
    }

    @Test
    void aBaseCharacterStaysWithItsMarksWhenAWordIsBroken() {
        assertEquals(List.of("कि", " ", "क्", "ष"), PdfFontSet.units("कि क्ष"));
        assertEquals(List.of("é", "a"), PdfFontSet.units("éa"));
        assertEquals(List.of("क्‍", "ष"), PdfFontSet.units("क्‍ष"));
    }

    @Test
    void theToUnicodeCMapHasEveryGlyphAndRefusesInvalidValues() {
        String cmap = new String(PdfToUnicode.cmap(Map.of(3, "A", 700, "क्ष")), StandardCharsets.US_ASCII);
        assertTrue(cmap.contains("2 beginbfchar"), cmap);
        assertTrue(cmap.contains("<0003> <0041>"), cmap);
        assertTrue(cmap.contains("<02BC> <0915094D0937>"), cmap);
        assertThrows(IllegalArgumentException.class, () -> PdfToUnicode.cmap(Map.of(1, "﻿")));
        assertThrows(IllegalArgumentException.class, () -> PdfToUnicode.cmap(Map.of(1, "")));
        assertEquals("NotoSans-Regular", PdfToUnicode.postScriptName("ABCDEF+NotoSans-Regular"));
        assertEquals("NotoSans-Regular", PdfToUnicode.postScriptName("NotoSans-Regular"));
    }

    @Test
    void textCleaningDropsWhatPdfUaForbidsAsUnicode() {
        assertEquals("a b", PdfTaggedLayout.clean("﻿a\tb￾"));
    }
}
