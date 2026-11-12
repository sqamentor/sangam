// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.pdfbox;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.COSWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;

/**
 * The content stream of one page that shows glyph ids already shaped by {@link PdfFontSet} (M-081). PDFBox's
 * {@code showText} shapes again on its own and records no Unicode value for the glyphs that substitution produces;
 * showing the shaped glyphs here keeps the drawn glyphs and the {@code /ToUnicode} map from the same shaping.
 *
 * <p>Every other operator goes through a {@link PDPageContentStream} built on the page's own content stream and
 * resources (its public {@code (document, appearance, output)} constructor, with an appearance that carries the page
 * resources), so fonts are registered for subsetting and in the page resources as usual. The glyph strings and the
 * {@code /ActualText} spans are written to the same output in order.
 */
final class PdfGlyphStream implements Closeable {

    private final PDPageContentStream cs;
    private final OutputStream out;
    private PDType0Font font;
    private boolean inText;

    PdfGlyphStream(PDDocument doc, PDPage page) throws IOException {
        PDStream contents = new PDStream(doc);
        page.setContents(contents);
        PDResources resources = new PDResources();
        page.setResources(resources);
        PDAppearanceStream appearance = new PDAppearanceStream(doc);
        appearance.setResources(resources);
        this.out = contents.createOutputStream(COSName.FLATE_DECODE);
        this.cs = new PDPageContentStream(doc, appearance, out);
    }

    void beginMarkedContent(COSName tag, int mcid) throws IOException {
        cs.beginMarkedContent(tag, mcid);
    }

    void beginMarkedContent(COSName tag) throws IOException {
        cs.beginMarkedContent(tag);
    }

    void endMarkedContent() throws IOException {
        cs.endMarkedContent();
    }

    void beginText() throws IOException {
        cs.beginText();
        inText = true;
    }

    void endText() throws IOException {
        cs.endText();
        inText = false;
    }

    void newLineAtOffset(float x, float y) throws IOException {
        cs.newLineAtOffset(x, y);
    }

    void setFont(PDType0Font f, float size) throws IOException {
        cs.setFont(f, size);
        font = f;
    }

    void setLineWidth(float w) throws IOException {
        cs.setLineWidth(w);
    }

    void addRect(float x, float y, float w, float h) throws IOException {
        cs.addRect(x, y, w, h);
    }

    void stroke() throws IOException {
        cs.stroke();
    }

    /** {@code <glyphs> Tj} in the current font, which must be {@code f}; the glyphs join the font's subset. */
    void showGlyphs(PDType0Font f, List<Integer> glyphs) throws IOException {
        if (!inText) {
            throw new IllegalStateException("glyphs can only be shown between beginText and endText");
        }
        if (font != f) {
            throw new IllegalStateException("set the font before showing its glyphs");
        }
        ByteArrayOutputStream codes = new ByteArrayOutputStream(glyphs.size() * 2);
        for (int g : glyphs) {
            codes.writeBytes(f.encodeGlyphId(g));
        }
        f.addGlyphsToSubset(new HashSet<>(glyphs));
        COSWriter.writeString(codes.toByteArray(), out);
        out.write(" Tj\n".getBytes(StandardCharsets.US_ASCII));
    }

    /** Opens {@code /Span <</ActualText (text)>> BDC}: the exact text of the glyphs shown until the matching EMC. */
    void beginActualText(String text) throws IOException {
        out.write("/Span <</ActualText ".getBytes(StandardCharsets.US_ASCII));
        COSWriter.writeString(new COSString(text), out);
        out.write(">> BDC\n".getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public void close() throws IOException {
        cs.close();
    }
}
