// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.sangam.integration.document.pdf.PdfDocument;
import org.sangam.integration.document.pdf.PdfRenderer;
import org.sangam.integration.document.pdf.PdfRendering;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.support.BlobStore;
import org.sangam.rest.support.DocumentStore;

/** M-081: the PDF/A audit export goes through the render-and-validate path and fails closed without a renderer. */
class JdbcAuditExportPdfTest {

    private static final byte[] PDF = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);

    private static AuditPort.AuditEntry entry(long seq) {
        return new AuditPort.AuditEntry("e" + seq, "B", "chain-1", seq, "TASK_COMPLETED", Instant.parse("2026-11-12T10:00:00Z"),
                "user-1", null, null, "pi-1", null, null, Map.of("k", "v"), false, null, false, "p" + seq, "h" + seq, null);
    }

    private static JdbcAuditExportAdapter adapter(PdfRendering pdf) {
        return new JdbcAuditExportAdapter(null, null, null, null, null, new DocumentStore(null), new BlobStore(null), pdf);
    }

    @Test
    void rendersOneTaggedTableOfTheEntries() {
        AtomicReference<PdfDocument> seen = new AtomicReference<>();
        PdfRenderer renderer = new PdfRenderer() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public Set<Conformance> conformance() {
                return REQUIRED;
            }

            @Override
            public byte[] render(PdfDocument document) {
                seen.set(document);
                return PDF;
            }
        };
        PdfRendering pdf = new PdfRendering(renderer, (bytes, req) -> new PdfRenderer.Report(true, "fake", List.of()));
        try (JdbcAuditExportAdapter a = adapter(pdf)) {
            byte[] out = a.pdfa("x1", Map.of("businessKey", "BK-1"), List.of(entry(1), entry(2)));
            assertArrayEquals(PDF, out);
        }
        PdfDocument d = seen.get();
        assertEquals("en", d.language());
        assertEquals("Audit export x1", d.title());
        PdfDocument.Table t = assertInstanceOf(PdfDocument.Table.class, d.blocks().get(d.blocks().size() - 1));
        assertEquals(List.of("Seq", "Occurred at", "Type", "Actor", "Chain", "Event id", "Entry hash"), t.header());
        assertEquals(2, t.rows().size());
        assertEquals(List.of("1", "2026-11-12T10:00:00Z", "TASK_COMPLETED", "user-1", "chain-1", "e1", "h1"), t.rows().get(0));
        assertTrue(d.blocks().contains(new PdfDocument.Paragraph("Scope: businessKey = BK-1")));
    }

    @Test
    void withoutARendererThePdfExportIsNotAvailable() {
        try (JdbcAuditExportAdapter a = adapter(PdfRendering.unavailable())) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> a.pdfa("x", Map.of("businessKey", "b"), List.of(entry(1))));
            assertEquals("FORMAT_NOT_AVAILABLE", p.code());
        }
    }

    @Test
    void anInvalidRenderingIsRefused() {
        PdfRenderer renderer = new PdfRenderer() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public Set<Conformance> conformance() {
                return REQUIRED;
            }

            @Override
            public byte[] render(PdfDocument document) {
                return PDF;
            }
        };
        PdfRendering pdf = new PdfRendering(renderer, (bytes, req) -> new PdfRenderer.Report(false, "fake", List.of("no /Lang")));
        try (JdbcAuditExportAdapter a = adapter(pdf)) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> a.pdfa("x", Map.of(), List.of()));
            assertEquals("PDF_INVALID", p.code());
        }
    }
}
