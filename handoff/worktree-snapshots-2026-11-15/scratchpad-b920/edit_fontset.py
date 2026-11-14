import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()


def rep(a, b):
    global s
    assert s.count(a) == 1, a
    s = s.replace(a, b)


rep(""" * <p><b>Font choice by script.</b> Every font has a home script: the Unicode script with the most characters in its
 * {@code cmap} (Noto Sans: Latin; Noto Sans Devanagari: Devanagari, owner decisions 70 and 94). A letter is drawn
 * with the first configured font whose home script is the letter's script and that has a glyph for it, else with the
 * first font that has a glyph, so the configured order does not decide which font draws Latin or Devanagari text.
 * Characters of no script of their own (spaces, digits, punctuation, joiners, combining marks) take the script of the
 * text around them and stay in the same font when it covers them, so they do not split a word. A character that no
 * font covers is refused, never drawn as a missing glyph.
 *
 * <p><b>Shaping and Unicode mapping.</b> Each word is shaped with the font's own {@code GSUB} rules (fontbox
 * {@link GsubWorker}: Devanagari conjuncts, half forms, reph and matra order; Latin ligatures), and the resulting glyph
 * ids are what the content stream shows.""", """ * <p><b>Font choice by script.</b> A character is drawn with the font that covers the most characters of its
 * script among the fonts that have a glyph for it; equal coverage goes to the earlier configured font. The shipped
 * fonts both carry some Latin and some Devanagari, so this is what puts Latin on Noto Sans and Devanagari on Noto Sans
 * Devanagari (owner decisions 70 and 94) whatever the configured order. Characters of no script of their own (spaces,
 * digits, punctuation, danda, joiners, combining marks) take the script of the text around them, so they stay in the
 * font of their word. A character that no font covers is refused, never drawn as a missing glyph.
 *
 * <p><b>Shaping and Unicode mapping.</b> A word is shaped with the font's {@code GSUB} rules (fontbox
 * {@link GsubWorker}: Devanagari conjuncts, half forms, reph and matra order) when the script fontbox selected for
 * the font is the word's script; other words are shown one {@code cmap} glyph per character. The resulting glyph ids
 * are what the content stream shows.""")
rep("""        final GsubWorker gsub;
        final UnicodeScript home;
        final Map<Integer, String> unicode = new TreeMap<>();

        Face(PDType0Font font) {
            this.font = font;
            this.name = font.getName();
            this.cmap = font.getCmapLookup();
            GsubData data = font.getGsubData();
            this.gsub = data == null || data == GsubData.NO_DATA_FOUND ? null
                    : new GsubWorkerFactory().getGsubWorker(cmap, data);
            this.home = homeScript(cmap);
        }""", """        final GsubWorker gsub;
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
        }""")
rep("""import org.apache.fontbox.ttf.model.GsubData;
""", """import org.apache.fontbox.ttf.model.GsubData;
import org.apache.fontbox.ttf.model.Language;
""")
rep("""    private Face choose(int cp, UnicodeScript script, Face previous) {
        Face firstCovering = null;
        for (Face f : faces) {
            if (!f.covers(cp)) {
                continue;
            }
            if (script != null && f.home == script) {
                return f;
            }
            if (firstCovering == null) {
                firstCovering = f;
            }
        }
        if (firstCovering == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "no configured PDF font has a glyph for U+%04X; add a font that covers it", cp));
        }
        return previous != null && previous.covers(cp) ? previous : firstCovering;
    }""", """    private Face choose(int cp, UnicodeScript script, Face previous) {
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
    }""")
rep("""    /** The script with the most characters in the font's {@code cmap} (Basic Multilingual Plane), or COMMON. */
    static UnicodeScript homeScript(CmapLookup cmap) {
        int[] counts = new int[UnicodeScript.values().length];
        for (int cp = 0x20; cp <= 0xFFFF; cp++) {
            if (Character.isSurrogate((char) cp) || cmap.getGlyphId(cp) <= 0) {
                continue;
            }
            UnicodeScript s = UnicodeScript.of(cp);
            if (!weak(s)) {
                counts[s.ordinal()]++;
            }
        }
        UnicodeScript best = UnicodeScript.COMMON;
        int max = 0;
        for (UnicodeScript s : UnicodeScript.values()) {
            if (counts[s.ordinal()] > max) {
                max = counts[s.ordinal()];
                best = s;
            }
        }
        return best;
    }""", """    /** How many characters of each script (by ordinal) the font's {@code cmap} covers in the Basic Multilingual Plane. */
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
    }""")
rep("""                out.add(shape(current, cps, start, i, record));
                start = i;""", """                out.add(shape(current, cps, scripts, start, i, record));
                start = i;""")
rep("""            out.add(shape(current, cps, start, cps.length, record));""",
    """            out.add(shape(current, cps, scripts, start, cps.length, record));""")
rep("""    /** Shapes {@code cps[from, to)} with {@code f}: whitespace one glyph per character, every word through GSUB. */
    private static Run shape(Face f, int[] cps, int from, int to, boolean record) {""", """    /**
     * Shapes {@code cps[from, to)} with {@code f}: whitespace one glyph per character, a word through the font's GSUB
     * rules when they are for the word's script, else one {@code cmap} glyph per character.
     */
    private static Run shape(Face f, int[] cps, UnicodeScript[] scripts, int from, int to, boolean record) {""")
rep("""            List<Integer> shaped = f.gsub == null ? in : f.gsub.applyTransforms(new ArrayList<>(in));""",
    """            List<Integer> shaped = f.gsub == null || f.gsubScript != scripts[i] ? in
                    : f.gsub.applyTransforms(new ArrayList<>(in));""")
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("ok")
