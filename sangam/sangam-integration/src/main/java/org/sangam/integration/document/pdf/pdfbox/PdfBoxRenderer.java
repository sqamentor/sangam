// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.pdfbox;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;
import org.apache.pdfbox.util.Version;
import org.sangam.core.layer.Hashes;
import org.sangam.integration.document.pdf.PdfDocument;
import org.sangam.integration.document.pdf.PdfRenderer;

/**
 * The Apache PDFBox implementation of {@link PdfRenderer} (M-081), found by {@link java.util.ServiceLoader}. It writes
 * the PDF/A-2b and PDF/UA-1 features itself:
 * <ul>
 *   <li>every font embedded (configured TrueType files only, {@link PdfFontSet}); an sRGB output intent (the JDK's
 *       sRGB ICC profile); XMP with {@code pdfaid} 2/B and {@code pdfuaid} 1 ({@link PdfXmp}) that mirrors the
 *       document information dictionary; a trailer {@code /ID}; no encryption, JavaScript, actions or external
 *       content; PDF 1.7;</li>
 *   <li>{@code /MarkInfo /Marked true}, a structure tree in reading order with every content item tagged or marked as
 *       an artifact ({@link PdfTaggedLayout}), {@code /Lang}, {@code /ViewerPreferences /DisplayDocTitle true}, page
 *       {@code /Tabs /S}.</li>
 * </ul>
 * Fonts: {@value #FONTS_PROPERTY} (system property) or {@value #FONTS_ENV} (environment), a list of {@code .ttf} paths
 * separated by commas or the platform path separator; the deployment ships Noto Sans and Noto Sans Devanagari
 * ({@code deploy/fonts}, OFL-1.1, owner decisions 70, 94). {@link PdfFontSet} picks the font per character by script
 * and shapes each word; every glyph shown gets a Unicode value ({@link PdfToUnicode}). Without a usable font the
 * renderer reports {@link #notReadyReason()} and PDF output stays off. The creation date is the document metadata
 * entry {@code createdAt} (ISO-8601 instant) when given, else the clock. Other metadata entries are not written in
 * 0.1.0. Devanagari shaping is the fontbox GSUB support for the script ({@code GsubWorkerForDevanagari}).
 *
 * <p>This class claims the conformance; it does not prove it. {@code PdfRendering} hands bytes out only after a
 * separate {@link PdfRenderer.Validator} has read them back.
 */
public final class PdfBoxRenderer implements PdfRenderer {

    public static final String FONTS_PROPERTY = "sangam.pdf.fonts";
    public static final String FONTS_ENV = "SANGAM_PDF_FONTS";
    static final String CREATED_AT = "createdAt";

    private final List<Path> fonts;
    private final Clock clock;
    private final String notReady;

    /** ServiceLoader constructor: fonts from {@value #FONTS_PROPERTY} or {@value #FONTS_ENV}, the UTC system clock. */
    public PdfBoxRenderer() {
        this(fontPaths(System.getProperty(FONTS_PROPERTY, System.getenv(FONTS_ENV))), Clock.systemUTC());
    }

    public PdfBoxRenderer(List<Path> fonts, Clock clock) {
        this.fonts = List.copyOf(Objects.requireNonNull(fonts, "fonts"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.notReady = PdfFontSet.problem(this.fonts);
    }

    /** The font list of a {@value #FONTS_PROPERTY} value; empty for {@code null} or blank. */
    static List<Path> fontPaths(String value) {
        List<Path> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String p : value.split("[," + Pattern.quote(File.pathSeparator) + "]")) {
            if (!p.isBlank()) {
                out.add(Path.of(p.strip()));
            }
        }
        return out;
    }

    @Override
    public String name() {
        return "pdfbox-" + Version.getVersion();
    }

    @Override
    public Set<Conformance> conformance() {
        return REQUIRED;
    }

    @Override
    public String notReadyReason() {
        return notReady;
    }

    @Override
    public byte[] render(PdfDocument document) {
        if (notReady != null) {
            throw new IllegalStateException("the PDF renderer is not ready: " + notReady);
        }
        Instant created = createdAt(document);
        try (PDDocument doc = new PDDocument()) {
            doc.setVersion(1.7f);
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            PDStructureTreeRoot tree = new PDStructureTreeRoot();
            catalog.setStructureTreeRoot(tree);
            PdfFontSet fontSet = PdfFontSet.load(doc, fonts);
            PdfTaggedLayout layout = new PdfTaggedLayout(doc, fontSet, tree, document.language());
            layout.write(document.blocks());
            layout.finish(tree);

            PDMarkInfo mark = new PDMarkInfo();
            mark.setMarked(true);
            catalog.setMarkInfo(mark);
            catalog.setLanguage(document.language());
            PDViewerPreferences prefs = new PDViewerPreferences();
            prefs.setDisplayDocTitle(true);
            catalog.setViewerPreferences(prefs);

            String producer = "Sangam (Apache PDFBox " + Version.getVersion() + ")";
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle(document.title());
            if (document.author() != null && !document.author().isBlank()) {
                info.setAuthor(document.author());
            }
            if (document.subject() != null && !document.subject().isBlank()) {
                info.setSubject(document.subject());
            }
            info.setCreator(PdfXmp.CREATOR_TOOL);
            info.setProducer(producer);
            info.setCreationDate(calendar(created));
            info.setModificationDate(calendar(created));
            doc.setDocumentInformation(info);

            PDMetadata xmp = new PDMetadata(doc);
            xmp.importXMPMetadata(PdfXmp.packet(document.title(), document.author(), document.subject(), producer, created));
            catalog.setMetadata(xmp);

            PDOutputIntent intent = new PDOutputIntent(doc,
                    new ByteArrayInputStream(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData()));
            intent.setInfo("sRGB IEC61966-2.1");
            intent.setOutputCondition("sRGB IEC61966-2.1");
            intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
            intent.setRegistryName("http://www.color.org");
            catalog.addOutputIntent(intent);

            byte[] id = Arrays.copyOf(Hashes.sha256((document.title() + "|" + PdfXmp.date(created))
                    .getBytes(StandardCharsets.UTF_8)), 16);
            COSArray ids = new COSArray();
            ids.add(new COSString(id));
            ids.add(new COSString(id));
            doc.getDocument().getTrailer().setItem(COSName.ID, ids);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return PdfToUnicode.apply(out.toByteArray(), fontSet.toUnicode());
        } catch (IOException e) {
            throw new UncheckedIOException("PDF rendering failed", e);
        }
    }

    private Instant createdAt(PdfDocument document) {
        String v = document.metadata().get(CREATED_AT);
        if (v == null || v.isBlank()) {
            return clock.instant().truncatedTo(ChronoUnit.SECONDS);
        }
        try {
            return Instant.parse(v.strip()).truncatedTo(ChronoUnit.SECONDS);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("PDF metadata createdAt is not an ISO-8601 instant: " + v, e);
        }
    }

    private static Calendar calendar(Instant at) {
        GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(at.toEpochMilli());
        return c;
    }
}
