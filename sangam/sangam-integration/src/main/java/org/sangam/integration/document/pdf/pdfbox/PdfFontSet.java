// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.pdfbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.Character.UnicodeScript;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.gsub.GsubWorker;
import org.apache.fontbox.ttf.gsub.GsubWorkerFactory;
import org.apache.fontbox.ttf.model.GsubData;
import org.apache.fontbox.ttf.model.Language;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The embedded fonts of one document and the text-to-glyph path (M-081). PDF/A-2b needs every font embedded, so only
 * configured TrueType files are used, never the standard 14 fonts.
 *
 * <p><b>Font choice by script.</b> A character is drawn with the font that covers the most characters of its
 * script among the fonts that have a glyph for it; equal coverage goes to the earlier configured font. The shipped
 * fonts both carry some Latin and some Devanagari, so this is what puts Latin on Noto Sans and Devanagari on Noto Sans
 * Devanagari (owner decisions 70 and 94) whatever the configured order. Characters of no script of their own (spaces,
 * digits, punctuation, danda, joiners, combining marks) take the script of the text around them, so they stay in the
 * font of their word. A character that no font covers is refused, never drawn as a missing glyph.
 *
 * <p><b>Shaping and Unicode mapping.</b> A word is shaped with the font's {@code GSUB} rules (fontbox
 * {@link GsubWorker}: Devanagari conjuncts, half forms, reph and matra order) when the script fontbox selected for
 * the font is the word's script; other words are shown one {@code cmap} glyph per character. The resulting glyph ids
 * are what the content stream shows. PDF/UA-1 (ISO 14289-1, 7.21.7) needs a Unicode value for every glyph shown;
 * a substituted glyph has no {@code cmap} entry, so the set records one ({@link #toUnicode()}): the characters it
 * replaced, split by base character and its marks. The exact text of a shaped run is also written as
 * {@code /ActualText} by the layout, so text extraction and screen readers get the original string.
 */
final class PdfFontSet {

    /**
     * A piece of text drawn with one font.
     *
     * @param glyphs the glyph ids to show, after substitution
     * @param direct {@code true} when the glyphs are the {@code cmap} glyphs of the text, one per character, in order
     */
    record Run(PDType0Font font, String text, List<Integer> glyphs, boolean direct) {
    }

    private static final int ZWNJ = 0x200C;
    private static final int ZWJ = 0x200D;

    private static final class Face {
        final PDType0Font font;
        final String name;
        final CmapLookup cmap;
        final GsubWorker gsub;
        final UnicodeScript gsubScript;
        final int[] coverage;
        final Map<Integer, String> unicode = new TreeMap<>();

        Face(PDType0Font font) {
            this.font = font;
            this.name = font.getName();
            this.cmap = font.getCmapLookup();
            GsubData data = font.getGsubData();
            this.gsubScript = data == null || data == GsubData.NO_DATA_FOUND ? null : script(data.getLanguage());
            this.gsub = gsubScript == null ? null : new GsubWorkerFactory().getGsubWorker(cmap, data);
            this.coverage = scriptCoverage(cmap);
        }

        boolean covers(int cp) {
            return cmap.getGlyphId(cp) > 0;
        }
    }

    private final List<Face> faces;

    private PdfFontSet(List<Face> faces) {
        this.faces = faces;
    }

    /** Loads the font files into {@code doc} as subset, embedded Type 0 fonts. */
    static PdfFontSet load(PDDocument doc, List<Path> files) {
        List<Face> out = new ArrayList<>(files.size());
        Set<String> names = new HashSet<>();
        for (Path f : files) {
            Face face;
            try {
                face = new Face(PDType0Font.load(doc, f.toFile()));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot load the PDF font " + f, e);
            }
            if (!names.add(face.name)) {
                throw new IllegalStateException("two configured PDF fonts have the PostScript name " + face.name);
            }
            out.add(face);
        }
        return new PdfFontSet(List.copyOf(out));
    }

    /**
     * Why the configured font files cannot be used, or {@code null}: at least one readable TrueType ({@code .ttf}) file
     * is needed.
     */
    static String problem(List<Path> files) {
        if (files.isEmpty()) {
            return "no font is configured (" + PdfBoxRenderer.FONTS_PROPERTY + " or " + PdfBoxRenderer.FONTS_ENV
                    + "); PDF/A embeds every font, so at least one TrueType file is needed";
        }
        for (Path f : files) {
            if (!Files.isRegularFile(f) || !Files.isReadable(f)) {
                return "the PDF font " + f + " is not a readable file";
            }
            if (!f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttf")) {
                return "the PDF font " + f + " is not a TrueType (.ttf) file";
            }
        }
        return null;
    }

    /**
     * The Unicode value of every glyph shown so far, per font (key: the font's PostScript name before subsetting,
     * value: glyph id to text). {@link PdfToUnicode} writes these maps as the fonts' {@code /ToUnicode} CMaps.
     */
    Map<String, Map<Integer, String>> toUnicode() {
        Map<String, Map<Integer, String>> out = new LinkedHashMap<>();
        for (Face f : faces) {
            if (!f.unicode.isEmpty()) {
                out.put(f.name, Map.copyOf(f.unicode));
            }
        }
        return out;
    }

    /** {@code text} as runs of one font each, shaped; {@code record} keeps the glyphs' Unicode values for drawing. */
    List<Run> runs(String text, boolean record) {
        int[] cps = text.codePoints().toArray();
        UnicodeScript[] scripts = resolvedScripts(cps);
        List<Run> out = new ArrayList<>();
        Face current = null;
        int start = 0;
        for (int i = 0; i < cps.length; i++) {
            Face f = choose(cps[i], scripts[i], current);
            if (current != null && f != current) {
                out.add(shape(current, cps, scripts, start, i, record));
                start = i;
            }
            current = f;
        }
        if (current != null) {
            out.add(shape(current, cps, scripts, start, cps.length, record));
        }
        return out;
    }

    /** Width of {@code text} in points at {@code size}, from the advance widths of the shaped glyphs. */
    float width(String text, float size) {
        float w = 0;
        for (Run r : runs(text, false)) {
            for (int g : r.glyphs()) {
                try {
                    w += r.font().getWidthFromFont(g) / 1000f * size;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return w;
    }

    /**
     * {@code text} cut into its smallest pieces that may be split between lines: a base character with the marks and
     * joiners that follow it, so a word that is longer than a line is never broken inside a character.
     */
    static List<String> units(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder unit = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (!unit.isEmpty() && !attaches(cp)) {
                out.add(unit.toString());
                unit.setLength(0);
            }
            unit.appendCodePoint(cp);
        });
        if (!unit.isEmpty()) {
            out.add(unit.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ font choice

    private Face choose(int cp, UnicodeScript script, Face previous) {
        Face best = null;
        for (Face f : faces) {
            if (f.covers(cp) && (best == null
                    || script != null && f.coverage[script.ordinal()] > best.coverage[script.ordinal()])) {
                best = f;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "no configured PDF font has a glyph for U+%04X; add a font that covers it", cp));
        }
        if (script == null && previous != null && previous.covers(cp)) {
            return previous;
        }
        return best;
    }

    /** The script of each character; characters of no script of their own take the script of the text around them. */
    static UnicodeScript[] resolvedScripts(int[] cps) {
        UnicodeScript[] out = new UnicodeScript[cps.length];
        UnicodeScript last = null;
        UnicodeScript first = null;
        for (int i = 0; i < cps.length; i++) {
            UnicodeScript s = UnicodeScript.of(cps[i]);
            if (!weak(s)) {
                last = s;
                if (first == null) {
                    first = s;
                }
            }
            out[i] = last;
        }
        for (int i = 0; i < out.length && out[i] == null; i++) {
            out[i] = first;
        }
        return out;
    }

    static boolean weak(UnicodeScript s) {
        return s == UnicodeScript.COMMON || s == UnicodeScript.INHERITED || s == UnicodeScript.UNKNOWN;
    }

    /** How many characters of each script (by ordinal) the font's {@code cmap} covers in the Basic Multilingual Plane. */
    static int[] scriptCoverage(CmapLookup cmap) {
        int[] counts = new int[UnicodeScript.values().length];
        for (int cp = 0x20; cp <= 0xFFFF; cp++) {
            if (!Character.isSurrogate((char) cp) && cmap.getGlyphId(cp) > 0) {
                counts[UnicodeScript.of(cp).ordinal()]++;
            }
        }
        return counts;
    }

    /** The Unicode script of a fontbox GSUB language, or {@code null} when fontbox has no shaping for it. */
    static UnicodeScript script(Language language) {
        if (language == null) {
            return null;
        }
        return switch (language) {
            case BENGALI -> UnicodeScript.BENGALI;
            case DEVANAGARI -> UnicodeScript.DEVANAGARI;
            case GUJARATI -> UnicodeScript.GUJARATI;
            case LATIN -> UnicodeScript.LATIN;
            default -> null;
        };
    }

    private static boolean attaches(int cp) {
        int t = Character.getType(cp);
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK
                || t == Character.ENCLOSING_MARK || cp == ZWJ || cp == ZWNJ;
    }

    // ------------------------------------------------------------------------------------------------ shaping

    /**
     * Shapes {@code cps[from, to)} with {@code f}: whitespace one glyph per character, a word through the font's GSUB
     * rules when they are for the word's script, else one {@code cmap} glyph per character.
     */
    private static Run shape(Face f, int[] cps, UnicodeScript[] scripts, int from, int to, boolean record) {
        List<Integer> glyphs = new ArrayList<>();
        boolean direct = true;
        int i = from;
        while (i < to) {
            if (Character.isWhitespace(cps[i])) {
                int g = f.cmap.getGlyphId(cps[i]);
                glyphs.add(g);
                if (record) {
                    f.unicode.putIfAbsent(g, new String(Character.toChars(cps[i])));
                }
                i++;
                continue;
            }
            int j = i;
            while (j < to && !Character.isWhitespace(cps[j])) {
                j++;
            }
            List<Integer> in = new ArrayList<>(j - i);
            for (int k = i; k < j; k++) {
                in.add(f.cmap.getGlyphId(cps[k]));
            }
            List<Integer> shaped = f.gsub == null || f.gsubScript != scripts[i] ? in
                    : f.gsub.applyTransforms(new ArrayList<>(in));
            if (!shaped.equals(in)) {
                direct = false;
            }
            if (record) {
                attribute(f, cps, i, j, in, shaped);
            }
            glyphs.addAll(shaped);
            i = j;
        }
        return new Run(f.font, new String(cps, from, to - from), List.copyOf(glyphs), direct);
    }

    /**
     * Records a Unicode value for every glyph of one shaped word. A {@code cmap} glyph maps to its character. The
     * glyphs that substitution produced take the characters whose glyphs it consumed, one piece (base character and
     * its marks) each in reading order, the last such glyph taking whatever is left; every glyph gets a non-empty value.
     */
    private static void attribute(Face f, int[] cps, int from, int to, List<Integer> in, List<Integer> shaped) {
        for (int k = from; k < to; k++) {
            f.unicode.putIfAbsent(in.get(k - from), new String(Character.toChars(cps[k])));
        }
        Set<Integer> inputs = new HashSet<>(in);
        List<Integer> produced = shaped.stream().filter(g -> !inputs.contains(g)).distinct().toList();
        if (produced.isEmpty()) {
            return;
        }
        Set<Integer> kept = new HashSet<>(shaped);
        List<String> consumed = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (int k = from; k < to; k++) {
            if (piece.length() > 0 && !attaches(cps[k])) {
                consumed.add(piece.toString());
                piece.setLength(0);
            }
            if (!kept.contains(in.get(k - from))) {
                piece.appendCodePoint(cps[k]);
            }
        }
        if (piece.length() > 0) {
            consumed.add(piece.toString());
        }
        String word = new String(cps, from, to - from);
        Map<Integer, String> values = new HashMap<>();
        for (int n = 0; n < produced.size(); n++) {
            String value;
            if (consumed.isEmpty()) {
                value = word;
            } else if (n < produced.size() - 1) {
                value = consumed.get(Math.min(n, consumed.size() - 1));
            } else {
                value = n < consumed.size() ? String.join("", consumed.subList(n, consumed.size()))
                        : consumed.get(consumed.size() - 1);
            }
            values.put(produced.get(n), value);
        }
        values.forEach(f.unicode::putIfAbsent);
    }
}
