// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.integration.document.pdf.PdfDocument;
import org.sangam.integration.document.pdf.PdfRenderer;
import org.sangam.integration.document.pdf.PdfRendering;
import org.sangam.ports.content.ContentService;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryPorts;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RegistryPort;

/**
 * Owner decision 69: a registry document template with {@code format: PDFA} is refused with 422
 * FORMAT_NOT_AVAILABLE while PDF output is off (no silent HTML fallback), and with 422 PDF_INVALID when the rendering
 * fails validation. Nothing is stored in either case.
 */
class RegistryDocumentPdfTest {

    private static final RegistryContext CTX = new RegistryContext("t1", "user-1", null, null, null, ZoneOffset.UTC);

    private static RegistryPort templates(String format) {
        RegistryPort registry = mock(RegistryPort.class);
        Map<String, Object> entry = Map.of("id", "tpl-1", "key", "receipt", "name", "Receipt", "format", format,
                "body", Map.of("en", "# Receipt {{receiptNumber}}\nIssued."));
        when(registry.list(any(), eq("templates"), any(), any())).thenReturn(Page.of(List.of(entry), 1, false));
        return registry;
    }

    @Test
    void pdfaWithoutARendererIsRefusedAndNothingIsStored() {
        ContentService content = mock(ContentService.class);
        RegistryPorts.DocumentPort port = RegistryPortBridges.documents(content, templates("PDFA"), PdfRendering.unavailable());
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> port.render(CTX, "receipt", Map.of("receiptNumber", "R-1", "objectId", "o1")));
        assertEquals(422, p.status());
        assertEquals("FORMAT_NOT_AVAILABLE", p.code());
        assertTrue(p.detail().contains("receipt"), p.detail());
        verify(content, never()).create(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void aRenderingThatFailsValidationIsRefusedAsPdfInvalid() {
        ContentService content = mock(ContentService.class);
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
                return "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
            }
        };
        PdfRenderer.Validator failing = (pdf, required) -> new PdfRenderer.Report(false, "fake-validator",
                List.of("6.2.11.4.1: font not embedded"));
        RegistryPorts.DocumentPort port = RegistryPortBridges.documents(content, templates("PDFA"),
                new PdfRendering(renderer, failing));
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> port.render(CTX, "receipt", Map.of("receiptNumber", "R-1", "objectId", "o1")));
        assertEquals(422, p.status());
        assertEquals("PDF_INVALID", p.code());
        verify(content, never()).create(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any());
    }
}
