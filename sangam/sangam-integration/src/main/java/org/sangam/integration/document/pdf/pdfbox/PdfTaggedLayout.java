// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.pdfbox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDTableAttributeObject;
import org.sangam.integration.document.pdf.PdfDocument;

/**
 * Lays the blocks of a {@link PdfDocument} out on A4 pages and tags every piece of content (PDF/UA-1): each text line
 * is a marked-content sequence with an inline MCID that belongs to its structure element ({@code H1}..{@code H3},
 * {@code P}, {@code Table}/{@code TR}/{@code TH}/{@code TD} under one {@code Document} element), in reading order;
 * table rules are marked as artifacts; the parent tree maps every page's MCIDs back to their elements. Header cells
 * carry {@code /Scope /Column}. Text is shown as the glyphs {@link PdfFontSet} shaped ({@link PdfGlyphStream}); a run
 * whose glyphs are not one per character carries its exact text as {@code /ActualText}. Not thread-safe: one
 * instance per rendering.
 */
final class PdfTaggedLayout {

    static final float MARGIN = 56f;
    static final float BODY = 11f;
    static final float LEADING = 1.35f;
    static final float CELL_PAD = 4f;
    private static final float[] HEADING = {18f, 15f, 13f};

    private final PDDocument doc;
    private final PdfFontSet fonts;
    private final PDStructureElement root;
    private final List<COSArray> parentTreeByPage = new ArrayList<>();

    private PDPage page;
    private PdfGlyphStream cs;
    private int mcid;
    private float y;

    PdfTaggedLayout(PDDocument doc, PdfFontSet fonts, PDStructureTreeRoot tree, String language) {
        this.doc = doc;
        this.fonts = fonts;
        this.root = new PDStructureElement("Document", tree);
        this.root.setLanguage(language);
        tree.appendKid(root);
    }

    float contentWidth() {
        return PDRectangle.A4.getWidth() - 2 * MARGIN;
    }

    void write(List<PdfDocument.Block> blocks) throws IOException {
        newPage();
        for (PdfDocument.Block b : blocks) {
            switch (b) {
                case PdfDocument.Heading h -> text(root, "H" + h.level(), h.text(), HEADING[h.level() - 1], BODY * 0.6f);
                case PdfDocument.Paragraph p -> text(root, "P", p.text(), BODY, BODY * 0.6f);
                case PdfDocument.Table t -> table(t);
            }
        }
        cs.close();
    }

    /** Writes the parent tree (page index -> element per MCID) into the structure tree root. */
    void finish(PDStructureTreeRoot tree) {
        COSArray nums = new COSArray();
        for (int i = 0; i < parentTreeByPage.size(); i++) {
            nums.add(COSInteger.get(i));
            nums.add(parentTreeByPage.get(i));
        }
        COSDictionary parentTree = new COSDictionary();
        parentTree.setItem(COSName.NUMS, nums);
        tree.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
        tree.setParentTreeNextKey(parentTreeByPage.size());
    }

    // ------------------------------------------------------------------------------------------------ blocks

    private void text(PDStructureNode parent, String tag, String text, float size, float spaceAfter) throws IOException {
        List<String> lines = wrap(clean(text), size, contentWidth());
        if (lines.isEmpty()) {
            return;
        }
        PDStructureElement e = element(parent, tag);
        float lineHeight = size * LEADING;
        for (String line : lines) {
            if (y - lineHeight < MARGIN) {
                newPage();
            }
            y -= lineHeight;
            line(e, tag, line, MARGIN, y + (lineHeight - size) / 2, size);
        }
        y -= spaceAfter;
    }

    private void table(PdfDocument.Table t) throws IOException {
        int cols = Math.max(t.header().size(), t.rows().stream().mapToInt(List::size).max().orElse(0));
        if (cols == 0) {
            return;
        }
        PDStructureElement table = element(root, "Table");
        float colWidth = contentWidth() / cols;
        if (!t.header().isEmpty()) {
            row(table, t.header(), cols, colWidth, true);
        }
        for (List<String> r : t.rows()) {
            row(table, r, cols, colWidth, false);
        }
        y -= BODY * 0.6f;
    }

    private void row(PDStructureElement table, List<String> cells, int cols, float colWidth, boolean header)
            throws IOException {
        float lineHeight = BODY * LEADING;
        List<List<String>> wrapped = new ArrayList<>(cols);
        int maxLines = 1;
        for (int i = 0; i < cols; i++) {
            List<String> l = wrap(clean(i < cells.size() ? cells.get(i) : ""), BODY, colWidth - 2 * CELL_PAD);
            wrapped.add(l);
            maxLines = Math.max(maxLines, l.size());
        }
        float height = maxLines * lineHeight + 2 * CELL_PAD;
        if (y - height < MARGIN) {
            newPage();
        }
        PDStructureElement tr = element(table, "TR");
        float top = y;
        for (int i = 0; i < cols; i++) {
            PDStructureElement cell = element(tr, header ? "TH" : "TD");
            if (header) {
                PDTableAttributeObject scope = new PDTableAttributeObject();
                scope.setScope(PDTableAttributeObject.SCOPE_COLUMN);
                cell.addAttribute(scope);
            }
            float x = MARGIN + i * colWidth + CELL_PAD;
            float lineY = top - CELL_PAD;
            for (String l : wrapped.get(i)) {
                lineY -= lineHeight;
                line(cell, header ? "TH" : "TD", l, x, lineY + (lineHeight - BODY) / 2, BODY);
            }
        }
        cs.beginMarkedContent(COSName.ARTIFACT);
        cs.setLineWidth(0.5f);
        for (int i = 0; i < cols; i++) {
            cs.addRect(MARGIN + i * colWidth, top - height, colWidth, height);
        }
        cs.stroke();
        cs.endMarkedContent();
        y = top - height;
    }

    // ------------------------------------------------------------------------------------------------ primitives

    private PDStructureElement element(PDStructureNode parent, String type) {
        PDStructureElement e = new PDStructureElement(type, parent);
        e.setPage(page);
        parent.appendKid(e);
        return e;
    }

    /** One line of text as one marked-content sequence owned by {@code e}. */
    private void line(PDStructureElement e, String tag, String text, float x, float baseline, float size)
            throws IOException {
        if (text.isEmpty()) {
            return;
        }
        int id = mcid++;
        cs.beginMarkedContent(COSName.getPDFName(tag), id);
        cs.beginText();
        cs.newLineAtOffset(x, baseline);
        for (PdfFontSet.Run r : fonts.runs(text, true)) {
            cs.setFont(r.font(), size);
            if (!r.direct()) {
                cs.beginActualText(r.text());
            }
            cs.showGlyphs(r.font(), r.glyphs());
            if (!r.direct()) {
                cs.endMarkedContent();
            }
        }
        cs.endText();
        cs.endMarkedContent();
        PDMarkedContentReference mcr = new PDMarkedContentReference();
        mcr.setMCID(id);
        mcr.setPage(page);
        e.appendKid(mcr);
        parentTreeByPage.get(parentTreeByPage.size() - 1).add(e);
    }

    private void newPage() throws IOException {
        if (cs != null) {
            cs.close();
        }
        page = new PDPage(PDRectangle.A4);
        page.setStructParents(parentTreeByPage.size());
        page.getCOSObject().setName(COSName.getPDFName("Tabs"), "S");
        doc.addPage(page);
        parentTreeByPage.add(new COSArray());
        cs = new PdfGlyphStream(doc, page);
        mcid = 0;
        y = PDRectangle.A4.getHeight() - MARGIN;
    }

    /**
     * Control characters (tab, carriage return, other C0/C1) become spaces; PDF text has no such glyphs. U+FEFF, the
     * non-characters U+FFFE/U+FFFF and unpaired surrogates are dropped: PDF/UA forbids them as Unicode values.
     */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                b.append(' ');
            } else if (cp != 0xFEFF && cp != 0xFFFE && cp != 0xFFFF
                    && !(cp <= 0xFFFF && Character.isSurrogate((char) cp))) {
                b.appendCodePoint(cp);
            }
        });
        return b.toString().strip();
    }

    /**
     * Greedy word wrap at {@code width}; a word longer than a line is broken between characters (a base character
     * stays with its marks, {@link PdfFontSet#units}).
     */
    List<String> wrap(String text, float size, float width) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" +")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fonts.width(candidate, size) <= width) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (fonts.width(word, size) <= width) {
                line.append(word);
                continue;
            }
            StringBuilder piece = new StringBuilder();
            for (String unit : PdfFontSet.units(word)) {
                if (!piece.isEmpty() && fonts.width(piece + unit, size) > width) {
                    out.add(piece.toString());
                    piece.setLength(0);
                }
                piece.append(unit);
            }
            line.append(piece);
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }
}
