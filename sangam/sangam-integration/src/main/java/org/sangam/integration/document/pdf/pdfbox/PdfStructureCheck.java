// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.pdfbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.sangam.integration.document.pdf.PdfRenderer;

/**
 * A read-back check of the PDF/A-2b and PDF/UA-1 features the Sangam renderer writes (M-081). It parses the bytes
 * and checks: no encryption; XMP with {@code pdfaid:part=2}, {@code pdfaid:conformance=B} and (for PDF/UA)
 * {@code pdfuaid:part=1}; a {@code GTS_PDFA1} output intent with an ICC profile; every font on every page embedded; a
 * trailer {@code /ID}; no document JavaScript or open action; and for PDF/UA {@code /MarkInfo /Marked true}, a
 * structure tree, {@code /Lang}, a title shown as the window title, and pages that carry {@code /StructParents}.
 *
 * <p><b>This is not an ISO conformance validator.</b> It checks a fixed list of features, not the whole of
 * ISO 19005-2 or ISO 14289-1 (for example glyph widths, colour use or the Matterhorn checks). It is therefore NOT
 * registered as the {@link PdfRenderer.Validator} service: PDF output stays off until the owner chooses the validator
 * (W3-E report, M-081). Tests use it to read the renderer's output back.
 */
public final class PdfStructureCheck implements PdfRenderer.Validator {

    public static final String NAME = "sangam-structure-check";

    @Override
    public PdfRenderer.Report validate(byte[] pdf, Set<PdfRenderer.Conformance> required) {
        List<String> f = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDDocumentCatalog cat = doc.getDocumentCatalog();
            if (doc.isEncrypted()) {
                f.add("ENCRYPTED: PDF/A forbids encryption");
            }
            String xmp = xmp(cat);
            if (xmp == null) {
                f.add("XMP: no metadata stream");
            } else {
                if (required.contains(PdfRenderer.Conformance.PDF_A_2B)
                        && (!xmp.contains("<pdfaid:part>2</pdfaid:part>") || !xmp.contains("<pdfaid:conformance>B</pdfaid:conformance>"))) {
                    f.add("XMP: no PDF/A-2b identification");
                }
                if (required.contains(PdfRenderer.Conformance.PDF_UA_1) && !xmp.contains("<pdfuaid:part>1</pdfuaid:part>")) {
                    f.add("XMP: no PDF/UA-1 identification");
                }
            }
            boolean intent = false;
            for (PDOutputIntent oi : cat.getOutputIntents()) {
                intent |= "GTS_PDFA1".equals(oi.getCOSObject().getNameAsString(COSName.S)) && oi.getDestOutputIntent() != null;
            }
            if (!intent) {
                f.add("OUTPUT_INTENT: no GTS_PDFA1 output intent with an ICC profile");
            }
            if (!(doc.getDocument().getTrailer().getDictionaryObject(COSName.ID) instanceof COSArray ids) || ids.size() != 2) {
                f.add("TRAILER: no /ID");
            }
            if (cat.getNames() != null && cat.getNames().getJavaScript() != null) {
                f.add("JAVASCRIPT: document JavaScript is present");
            }
            if (cat.getCOSObject().containsKey(COSName.OPEN_ACTION)) {
                f.add("ACTION: an open action is present");
            }
            int pageNo = 0;
            for (PDPage page : doc.getPages()) {
                pageNo++;
                fonts(page.getResources(), pageNo, f);
                if (required.contains(PdfRenderer.Conformance.PDF_UA_1) && page.getStructParents() < 0) {
                    f.add("PAGE " + pageNo + ": no /StructParents");
                }
            }
            if (required.contains(PdfRenderer.Conformance.PDF_UA_1)) {
                if (cat.getMarkInfo() == null || !cat.getMarkInfo().isMarked()) {
                    f.add("UA: /MarkInfo /Marked is not true");
                }
                if (cat.getStructureTreeRoot() == null || cat.getStructureTreeRoot().getKids().isEmpty()) {
                    f.add("UA: no structure tree");
                }
                if (cat.getLanguage() == null || cat.getLanguage().isBlank()) {
                    f.add("UA: no /Lang");
                }
                String title = doc.getDocumentInformation().getTitle();
                if (title == null || title.isBlank() || xmp == null || !xmp.contains("<dc:title>")) {
                    f.add("UA: no document title");
                }
                if (cat.getViewerPreferences() == null || !cat.getViewerPreferences().displayDocTitle()) {
                    f.add("UA: /DisplayDocTitle is not true");
                }
            }
        } catch (IOException e) {
            f.add("PARSE: " + e.getMessage());
        }
        return new PdfRenderer.Report(f.isEmpty(), NAME, f);
    }

    private static void fonts(PDResources res, int pageNo, List<String> findings) throws IOException {
        if (res == null) {
            return;
        }
        for (COSName n : res.getFontNames()) {
            PDFont font = res.getFont(n);
            if (font != null && !font.isEmbedded()) {
                findings.add("FONT page " + pageNo + ": " + font.getName() + " is not embedded");
            }
        }
    }

    private static String xmp(PDDocumentCatalog cat) throws IOException {
        if (cat.getMetadata() == null) {
            return null;
        }
        try (InputStream in = cat.getMetadata().exportXMPMetadata()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
