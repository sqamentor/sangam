// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.pdfbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The XMP metadata packet of a Sangam PDF (M-081). It carries the PDF/A identification ({@code pdfaid:part=2},
 * {@code pdfaid:conformance=B}, ISO 19005-2 6.6.4) and the PDF/UA identification ({@code pdfuaid:part=1}, ISO 14289-1
 * 5). {@code pdfuaid} is not one of the schemas PDF/A-2 predefines, so the packet also describes it with a PDF/A
 * extension schema (ISO 19005-2 6.6.2.3). The title, author, subject, producer, creator tool and dates mirror the
 * document information dictionary exactly, as PDF/A requires. Written as text so every byte is under our control.
 */
final class PdfXmp {

    static final String CREATOR_TOOL = "Sangam";

    private PdfXmp() {
    }

    /** XMP date (ISO 8601, UTC, seconds). */
    static String date(Instant at) {
        return DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS));
    }

    static byte[] packet(String title, String author, String subject, String producer, Instant created) {
        String d = date(created);
        StringBuilder x = new StringBuilder(2048);
        x.append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
                .append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
                .append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
                .append("<rdf:Description rdf:about=\"\"")
                .append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
                .append(" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"")
                .append(" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\"")
                .append(" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"")
                .append(" xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\"")
                .append(" xmlns:pdfaExtension=\"http://www.aiim.org/pdfa/ns/extension/\"")
                .append(" xmlns:pdfaSchema=\"http://www.aiim.org/pdfa/ns/schema#\"")
                .append(" xmlns:pdfaProperty=\"http://www.aiim.org/pdfa/ns/property#\">\n")
                .append("<dc:format>application/pdf</dc:format>\n")
                .append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(esc(title))
                .append("</rdf:li></rdf:Alt></dc:title>\n");
        if (author != null && !author.isBlank()) {
            x.append("<dc:creator><rdf:Seq><rdf:li>").append(esc(author)).append("</rdf:li></rdf:Seq></dc:creator>\n");
        }
        if (subject != null && !subject.isBlank()) {
            x.append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(esc(subject))
                    .append("</rdf:li></rdf:Alt></dc:description>\n");
        }
        x.append("<xmp:CreateDate>").append(d).append("</xmp:CreateDate>\n")
                .append("<xmp:ModifyDate>").append(d).append("</xmp:ModifyDate>\n")
                .append("<xmp:MetadataDate>").append(d).append("</xmp:MetadataDate>\n")
                .append("<xmp:CreatorTool>").append(CREATOR_TOOL).append("</xmp:CreatorTool>\n")
                .append("<pdf:Producer>").append(esc(producer)).append("</pdf:Producer>\n")
                .append("<pdfaid:part>2</pdfaid:part>\n")
                .append("<pdfaid:conformance>B</pdfaid:conformance>\n")
                .append("<pdfuaid:part>1</pdfuaid:part>\n")
                .append("<pdfaExtension:schemas><rdf:Bag><rdf:li rdf:parseType=\"Resource\">\n")
                .append("<pdfaSchema:schema>PDF/UA Universal Accessibility Schema</pdfaSchema:schema>\n")
                .append("<pdfaSchema:namespaceURI>http://www.aiim.org/pdfua/ns/id/</pdfaSchema:namespaceURI>\n")
                .append("<pdfaSchema:prefix>pdfuaid</pdfaSchema:prefix>\n")
                .append("<pdfaSchema:property><rdf:Seq><rdf:li rdf:parseType=\"Resource\">\n")
                .append("<pdfaProperty:name>part</pdfaProperty:name>\n")
                .append("<pdfaProperty:valueType>Integer</pdfaProperty:valueType>\n")
                .append("<pdfaProperty:category>internal</pdfaProperty:category>\n")
                .append("<pdfaProperty:description>Indicates, which part of ISO 14289 standard is followed")
                .append("</pdfaProperty:description>\n")
                .append("</rdf:li></rdf:Seq></pdfaSchema:property>\n")
                .append("</rdf:li></rdf:Bag></pdfaExtension:schemas>\n")
                .append("</rdf:Description>\n</rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>");
        return x.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        b.append(ch);
                    }
                }
            }
        }
        return b.toString();
    }
}
