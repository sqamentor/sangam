// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The renderer-neutral content of a PDF (M-081): document metadata plus an ordered list of structural blocks. The
 * blocks are what a PDF/UA renderer tags ({@code H1}..{@code H3}, {@code P}, {@code Table}/{@code TR}/{@code TH}/{@code TD}),
 * so a document built from them has a logical structure and reading order by construction. {@code language} is the BCP
 * 47 tag written to the catalog's {@code /Lang} (PDF/UA-1 §7.2); {@code title} becomes {@code dc:title} and is shown
 * as the window title ({@code DisplayDocTitle}).
 *
 * @param metadata extra XMP / document-information entries (for example the business key or a template version)
 */
public record PdfDocument(String title, String language, String author, String subject, List<Block> blocks,
                          Map<String, String> metadata) {

    public PdfDocument {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("a PDF/UA document needs a title");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("a PDF/UA document needs a natural language (BCP 47 tag)");
        }
        blocks = List.copyOf(blocks == null ? List.of() : blocks);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    /** A structural block. */
    public sealed interface Block permits Heading, Paragraph, Table {
    }

    /** A heading of level 1 to 3. */
    public record Heading(int level, String text) implements Block {
        public Heading {
            if (level < 1 || level > 3) {
                throw new IllegalArgumentException("heading level must be 1, 2 or 3");
            }
            text = Objects.requireNonNullElse(text, "");
        }
    }

    public record Paragraph(String text) implements Block {
        public Paragraph {
            text = Objects.requireNonNullElse(text, "");
        }
    }

    /** A table with one header row (tagged {@code TH} with column scope) and data rows. */
    public record Table(List<String> header, List<List<String>> rows) implements Block {
        public Table {
            header = List.copyOf(header == null ? List.of() : header.stream().map(h -> Objects.requireNonNullElse(h, "")).toList());
            List<List<String>> copy = new ArrayList<>();
            for (List<String> r : rows == null ? List.<List<String>>of() : rows) {
                copy.add(r.stream().map(c -> Objects.requireNonNullElse(c, "")).toList());
            }
            rows = List.copyOf(copy);
        }
    }

    /** The same document with every text (title, subject, block texts, cells) passed through {@code f}. */
    public PdfDocument mapText(UnaryOperator<String> f) {
        List<Block> out = new ArrayList<>(blocks.size());
        for (Block b : blocks) {
            out.add(switch (b) {
                case Heading h -> new Heading(h.level(), f.apply(h.text()));
                case Paragraph p -> new Paragraph(f.apply(p.text()));
                case Table t -> new Table(t.header().stream().map(f).toList(),
                        t.rows().stream().map(r -> r.stream().map(f).toList()).toList());
            });
        }
        Map<String, String> meta = new LinkedHashMap<>(metadata);
        return new PdfDocument(f.apply(title), language, author, subject == null ? null : f.apply(subject), out, meta);
    }
}
