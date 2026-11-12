// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.pdfbox;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;

/**
 * Writes the {@code /ToUnicode} CMap of every embedded font from the glyph values {@link PdfFontSet} recorded (M-081,
 * PDF/UA-1 7.21.7: every glyph shown maps to Unicode; no value is U+0000, U+FEFF or U+FFFE). PDFBox builds the map
 * when it subsets a font on save and covers only the glyphs that have a {@code cmap} entry, so the saved document is
 * read back and each font's map is replaced by the complete one. The character codes are the glyph ids of the
 * original font (Identity-H; PDFBox keeps them as CIDs when subsetting).
 */
final class PdfToUnicode {

    private PdfToUnicode() {
    }

    /** {@code pdf} with the {@code /ToUnicode} map of every Type 0 font replaced; a font without a map is an error. */
    static byte[] apply(byte[] pdf, Map<String, Map<Integer, String>> maps) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Set<COSDictionary> done = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PDPage page : doc.getPages()) {
                COSDictionary fonts = page.getResources() == null ? null
                        : page.getResources().getCOSObject().getCOSDictionary(COSName.FONT);
                if (fonts == null) {
                    continue;
                }
                for (COSName key : fonts.keySet()) {
                    COSDictionary font = fonts.getCOSDictionary(key);
                    if (font == null || !done.add(font) || !COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))) {
                        continue;
                    }
                    String name = postScriptName(font.getNameAsString(COSName.BASE_FONT));
                    Map<Integer, String> map = maps.get(name);
                    if (map == null || map.isEmpty()) {
                        throw new IllegalStateException("no Unicode values were recorded for the embedded font " + name);
                    }
                    font.setItem(COSName.TO_UNICODE,
                            new PDStream(doc, new ByteArrayInputStream(cmap(map)), COSName.FLATE_DECODE));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 1024);
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** The font name without the six-letter subset tag ({@code ABCDEF+NotoSans-Regular} is {@code NotoSans-Regular}). */
    static String postScriptName(String baseFont) {
        if (baseFont != null && baseFont.length() > 7 && baseFont.charAt(6) == '+'
                && baseFont.substring(0, 6).chars().allMatch(c -> c >= 'A' && c <= 'Z')) {
            return baseFont.substring(7);
        }
        return baseFont;
    }

    /** A ToUnicode CMap (two-byte codes, UTF-16BE values) for {@code map}; invalid values are refused. */
    static byte[] cmap(Map<Integer, String> map) {
        List<Map.Entry<Integer, String>> entries = new ArrayList<>(new TreeMap<>(map).entrySet());
        StringBuilder b = new StringBuilder(64 + entries.size() * 24);
        b.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
                .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
                .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
                .append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        for (int from = 0; from < entries.size(); from += 100) {
            List<Map.Entry<Integer, String>> block = entries.subList(from, Math.min(entries.size(), from + 100));
            b.append(block.size()).append(" beginbfchar\n");
            for (Map.Entry<Integer, String> e : block) {
                b.append(String.format(Locale.ROOT, "<%04X> <", e.getKey())).append(hex(valid(e.getKey(), e.getValue())))
                        .append(">\n");
            }
            b.append("endbfchar\n");
        }
        b.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        return b.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String valid(int glyph, String value) {
        if (glyph < 0 || glyph > 0xFFFF) {
            throw new IllegalArgumentException("glyph id out of range: " + glyph);
        }
        if (value == null || value.isEmpty() || value.indexOf('\u0000') >= 0 || value.indexOf('﻿') >= 0
                || value.indexOf('￾') >= 0) {
            throw new IllegalArgumentException("glyph " + glyph + " has no valid Unicode value");
        }
        return value;
    }

    private static String hex(String value) {
        StringBuilder h = new StringBuilder();
        for (byte x : value.getBytes(StandardCharsets.UTF_16BE)) {
            h.append(String.format(Locale.ROOT, "%02X", x & 0xFF));
        }
        return h.toString();
    }
}
