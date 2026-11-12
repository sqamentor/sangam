// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The text form of a PDF template (format {@code PDFA} in the template registry): a small, fixed markup so a template
 * author gives the document its structure (PDF/UA tags) without HTML.
 * <ul>
 *   <li>{@code # }, {@code ## }, {@code ### } at the start of a line: a heading of level 1, 2 or 3;</li>
 *   <li>consecutive lines starting with {@code |}: a table, the first row being the header; a row made only of
 *       {@code |}, {@code -}, {@code :} and spaces is a separator and is skipped;</li>
 *   <li>other lines: paragraph text; an empty line ends a paragraph and lines of one paragraph are joined with a
 *       space.</li>
 * </ul>
 * The structure is parsed from the template first and placeholders are filled in afterwards, block by block
 * ({@link #parse(String, String, String, UnaryOperator)}), so a variable value can never add a heading or a table.
 */
public final class PdfMarkup {

    private PdfMarkup() {
    }

    /** Parses {@code template} and fills each text with {@code fill} (for example the output-template renderer). */
    public static PdfDocument parse(String title, String language, String template, UnaryOperator<String> fill) {
        PdfDocument structure = new PdfDocument(title, language, null, null, blocks(template), Map.of());
        return fill == null ? structure : structure.mapText(fill);
    }

    static List<PdfDocument.Block> blocks(String template) {
        List<PdfDocument.Block> out = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        List<List<String>> table = new ArrayList<>();
        for (String raw : (template == null ? "" : template).split("\\R", -1)) {
            String line = raw.strip();
            if (line.startsWith("|")) {
                flushParagraph(paragraph, out);
                if (!isSeparator(line)) {
                    table.add(cells(line));
                }
                continue;
            }
            flushTable(table, out);
            int level = headingLevel(line);
            if (level > 0) {
                flushParagraph(paragraph, out);
                out.add(new PdfDocument.Heading(level, line.substring(level + 1).strip()));
            } else if (line.isEmpty()) {
                flushParagraph(paragraph, out);
            } else {
                paragraph.add(line);
            }
        }
        flushTable(table, out);
        flushParagraph(paragraph, out);
        return out;
    }

    private static int headingLevel(String line) {
        for (int level = 3; level >= 1; level--) {
            if (line.startsWith("#".repeat(level) + " ")) {
                return level;
            }
        }
        return 0;
    }

    private static boolean isSeparator(String line) {
        return line.chars().allMatch(c -> c == '|' || c == '-' || c == ':' || c == ' ');
    }

    private static List<String> cells(String line) {
        String s = line.substring(1);
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        return Arrays.stream(s.split("\\|", -1)).map(String::strip).toList();
    }

    private static void flushParagraph(List<String> lines, List<PdfDocument.Block> out) {
        if (!lines.isEmpty()) {
            out.add(new PdfDocument.Paragraph(String.join(" ", lines)));
            lines.clear();
        }
    }

    private static void flushTable(List<List<String>> rows, List<PdfDocument.Block> out) {
        if (!rows.isEmpty()) {
            out.add(new PdfDocument.Table(rows.get(0), new ArrayList<>(rows.subList(1, rows.size()))));
            rows.clear();
        }
    }
}
