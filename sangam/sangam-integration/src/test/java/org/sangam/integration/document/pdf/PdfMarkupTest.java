// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** M-081: the PDF template markup gives PDF/UA structure, and variable values cannot add structure. */
class PdfMarkupTest {

    @Test
    void parsesHeadingsParagraphsAndTables() {
        String t = """
                # Sanction order
                Order no {{no}}
                dated {{date}}.

                ## Items
                | Item | Amount |
                |------|-------:|
                | {{item}} | {{amount}} |
                ### Note
                Last line""";
        List<PdfDocument.Block> b = PdfMarkup.blocks(t);
        assertEquals(6, b.size());
        assertEquals(new PdfDocument.Heading(1, "Sanction order"), b.get(0));
        assertEquals(new PdfDocument.Paragraph("Order no {{no}} dated {{date}}."), b.get(1));
        assertEquals(new PdfDocument.Heading(2, "Items"), b.get(2));
        PdfDocument.Table table = assertInstanceOf(PdfDocument.Table.class, b.get(3));
        assertEquals(List.of("Item", "Amount"), table.header());
        assertEquals(List.of(List.of("{{item}}", "{{amount}}")), table.rows());
        assertEquals(new PdfDocument.Heading(3, "Note"), b.get(4));
        assertEquals(new PdfDocument.Paragraph("Last line"), b.get(5));
    }

    @Test
    void variablesAreFilledAfterTheStructureIsFixed() {
        Map<String, String> vars = Map.of("{{x}}", "# not a heading\n| not | a table |");
        PdfDocument d = PdfMarkup.parse("T", "hi", "{{x}}", s -> vars.getOrDefault(s, s));
        assertEquals(1, d.blocks().size());
        PdfDocument.Paragraph p = assertInstanceOf(PdfDocument.Paragraph.class, d.blocks().get(0));
        assertEquals("# not a heading\n| not | a table |", p.text(), "a value stays text inside its block");
        assertEquals("hi", d.language());
    }

    @Test
    void emptyTemplateHasNoBlocks() {
        assertEquals(List.of(), PdfMarkup.blocks(""));
        assertEquals(List.of(), PdfMarkup.blocks(null));
        assertEquals(List.of(new PdfDocument.Paragraph("#hashtag")), PdfMarkup.blocks("#hashtag"),
                "a hash without a space is text");
    }
}
