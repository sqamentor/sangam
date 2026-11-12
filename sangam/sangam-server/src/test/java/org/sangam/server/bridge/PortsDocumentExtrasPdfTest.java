// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.integration.document.pdf.PdfDocument;
import org.sangam.integration.document.pdf.PdfRenderer;
import org.sangam.integration.document.pdf.PdfRendering;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.content.OutputTemplateRenderer;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DocumentExtrasPort;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;

/** M-081: PDFA document templates render through PdfRendering, and are refused (422) without a renderer. */
class PortsDocumentExtrasPdfTest {

    private static final RequestContext CTX = new RequestContext("t1", "user-1", Set.of(), null, null, null, null, null, "TEST");
    private static final byte[] PDF = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);

    private static RegistryPort templates(String format) {
        RegistryPort registry = mock(RegistryPort.class);
        Map<String, Object> entry = Map.of("id", "tpl-1", "key", "order", "name", "Order", "format", format,
                "body", Map.of("en", "# Order {{no}}\nFor {{businessKey}}."));
        when(registry.list(any(), eq("templates"), any(), any())).thenReturn(Page.of(List.of(entry), 1, false));
        return registry;
    }

    @Test
    void pdfaWithoutRendererIsFormatNotAvailable() {
        PortsDocumentExtrasBridge b = new PortsDocumentExtrasBridge(null, null, null, null, templates("PDFA"),
                mock(AuditPort.class), PdfRendering.unavailable());
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> b.generate(CTX, new DocumentExtrasPort.GenerateRequest("tpl-1", "BK-1", "en")));
        assertEquals("FORMAT_NOT_AVAILABLE", p.code());
        assertTrue(p.detail().contains("no PDF/A renderer"), p.detail());
    }

    @Test
    void docxStaysNotAvailable() {
        PortsDocumentExtrasBridge b = new PortsDocumentExtrasBridge(null, null, null, null, templates("DOCX"),
                mock(AuditPort.class), PdfRendering.unavailable());
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> b.generate(CTX, new DocumentExtrasPort.GenerateRequest("tpl-1", "BK-1", "en")));
        assertEquals("FORMAT_NOT_AVAILABLE", p.code());
    }

    @Test
    void pdfaRendersFillsAndStoresValidatedBytes() {
        AtomicReference<PdfDocument> rendered = new AtomicReference<>();
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
                rendered.set(document);
                return PDF;
            }
        };
        PdfRendering pdf = new PdfRendering(renderer, (bytes, req) -> new PdfRenderer.Report(true, "fake", List.of()));

        RuntimeService runtime = mock(RuntimeService.class, RETURNS_DEEP_STUBS);
        ProcessInstance pi = mock(ProcessInstance.class);
        when(pi.getId()).thenReturn("pi-1");
        when(runtime.createProcessInstanceQuery().processInstanceBusinessKey("BK-1").tenantIdIn("t1").listPage(0, 1))
                .thenReturn(List.of(pi));
        when(runtime.createProcessInstanceQuery().processInstanceId("pi-1").count()).thenReturn(1L);
        when(runtime.getVariables("pi-1")).thenReturn(Map.of("no", "42"));

        ContentService content = mock(ContentService.class);
        ContentService.ContentItem item = new ContentService.ContentItem("doc-1", "t1", "Order-BK-1-en.pdf",
                "application/pdf", "bpmn", "pi-1", null, 1, false, "user-1", Instant.EPOCH);
        when(content.create(eq("t1"), anyString(), eq("application/pdf"), eq("bpmn"), eq("pi-1"), any(), any(InputStream.class),
                eq("user-1"))).thenReturn(item);
        PortsDocumentBridge documents = mock(PortsDocumentBridge.class);
        when(documents.content()).thenReturn(content);
        DocumentPort.Document doc = new DocumentPort.Document("doc-1", "t1", "Order-BK-1-en.pdf", "application/pdf", PDF.length,
                "abc", 1, null, "pi-1", null, "NOT_SCANNED", "user-1", Instant.EPOCH, Map.of());
        when(documents.map(item)).thenReturn(doc);
        AuditPort audit = mock(AuditPort.class);

        PortsDocumentExtrasBridge b = new PortsDocumentExtrasBridge(documents, new OutputTemplateRenderer(null), runtime,
                mock(HistoryService.class), templates("PDFA"), audit, pdf);
        DocumentPort.Document out = b.generate(CTX, new DocumentExtrasPort.GenerateRequest("tpl-1", "BK-1", "en"));

        assertSame(doc, out);
        PdfDocument d = rendered.get();
        assertEquals("en", d.language());
        assertEquals(new PdfDocument.Heading(1, "Order 42"), d.blocks().get(0));
        assertInstanceOf(PdfDocument.Paragraph.class, d.blocks().get(1));
        assertEquals(new PdfDocument.Paragraph("For BK-1."), d.blocks().get(1));
        verify(content).create(eq("t1"), eq("Order-BK-1-en.pdf"), eq("application/pdf"), eq("bpmn"), eq("pi-1"), any(),
                any(InputStream.class), eq("user-1"));
        verify(audit).record(eq(CTX), eq("pi-1"), eq("DOCUMENT_ISSUED"), any());
    }
}
