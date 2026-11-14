// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.integration.document.pdf.PdfRendering;
import org.sangam.integration.document.pdf.pdfbox.PdfBoxRenderer;
import org.sangam.integration.document.pdf.remote.RemotePdfValidator;
import org.sangam.integration.sidecar.SidecarClient;
import org.sangam.integration.sidecar.SidecarCredentials;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.content.OutputTemplateRenderer;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DocumentExtrasPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;

/**
 * Owner decisions 114 and 153, RM-26 acceptance, SL-178: the server has no in-process PDF validator. With the renderer
 * and its fonts in place but no validator service configured, no service credentials, or a configured service that does
 * not answer, a PDF/A document is refused with {@code 422 FORMAT_NOT_AVAILABLE} and nothing is stored. The token is
 * synthetic and made by the test.
 */
class PdfValidatorServiceAbsentTest {

    @TempDir
    Path dir;

    private static final RequestContext CTX = new RequestContext("t1", "user-1", Set.of(), null, null, null, null, null, "TEST");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T10:15:30Z"), ZoneOffset.UTC);
    private static final Path FONTS = Path.of("..", "deploy", "fonts");

    private static List<Path> fonts() {
        List<Path> f = List.of(FONTS.resolve("NotoSans-Regular.ttf"), FONTS.resolve("NotoSansDevanagari-Regular.ttf"));
        f.forEach(p -> assertTrue(Files.isRegularFile(p), "the deployment font " + p.toAbsolutePath() + " exists"));
        return f;
    }

    private static RegistryPort templates() {
        RegistryPort registry = mock(RegistryPort.class);
        Map<String, Object> entry = Map.of("id", "tpl-1", "key", "order", "name", "Order", "format", "PDFA",
                "body", Map.of("en", "# Order {{no}}\nFor {{businessKey}}."));
        when(registry.list(any(), org.mockito.ArgumentMatchers.eq("templates"), any(), any()))
                .thenReturn(Page.of(List.of(entry), 1, false));
        return registry;
    }

    @Test
    void theInstalledRenderingWithFontsButNoValidatorServiceAnswers422() {
        String url = System.getProperty(RemotePdfValidator.URL_PROPERTY);
        String fontsBefore = System.getProperty(PdfBoxRenderer.FONTS_PROPERTY);
        try {
            System.setProperty(RemotePdfValidator.URL_PROPERTY, " ");
            System.setProperty(PdfBoxRenderer.FONTS_PROPERTY, String.join(",",
                    fonts().stream().map(p -> p.toAbsolutePath().toString()).toList()));
            PdfRendering installed = PdfRendering.installed();
            assertFalse(installed.available());
            assertTrue(installed.unavailableReason().contains("no PDF validator service is configured"),
                    installed.unavailableReason());

            PortsDocumentExtrasBridge b = new PortsDocumentExtrasBridge(null, null, null, null, templates(),
                    mock(AuditPort.class), installed);
            ApiProblem p = assertThrows(ApiProblem.class,
                    () -> b.generate(CTX, new DocumentExtrasPort.GenerateRequest("tpl-1", "BK-1", "en")));
            assertEquals("FORMAT_NOT_AVAILABLE", p.code());
            assertTrue(p.detail().contains("no PDF validator service is configured"), p.detail());
        } finally {
            restore(RemotePdfValidator.URL_PROPERTY, url);
            restore(PdfBoxRenderer.FONTS_PROPERTY, fontsBefore);
        }
    }

    @Test
    void aConfiguredServiceWithoutServerCredentialsIs422() {
        PdfRendering pdf = new PdfRendering(new PdfBoxRenderer(fonts(), CLOCK), new RemotePdfValidator(
                new SidecarClient.Settings("https://validator.internal:8443", "5000", null, null)));
        assertFalse(pdf.available());
        PortsDocumentExtrasBridge b = new PortsDocumentExtrasBridge(null, null, null, null, templates(),
                mock(AuditPort.class), pdf);
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> b.generate(CTX, new DocumentExtrasPort.GenerateRequest("tpl-1", "BK-1", "en")));
        assertEquals("FORMAT_NOT_AVAILABLE", p.code());
        assertTrue(p.detail().contains("credentials"), p.detail());
    }

    @Test
    void aConfiguredServiceThatDoesNotAnswerIs422AndNothingIsStored() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        Path token = Files.writeString(dir.resolve("validator-token"),
                Base64.getUrlEncoder().withoutPadding().encodeToString(random) + "\n", StandardCharsets.US_ASCII);
        PdfRendering pdf = new PdfRendering(new PdfBoxRenderer(fonts(), CLOCK), new RemotePdfValidator(
                new SidecarClient.Settings("http://127.0.0.1:" + port, Long.toString(Duration.ofSeconds(5).toMillis()),
                        "mesh", SidecarCredentials.tokenFile(token))));
        assertTrue(pdf.available(), String.valueOf(pdf.unavailableReason()));

        RuntimeService runtime = mock(RuntimeService.class, RETURNS_DEEP_STUBS);
        ProcessInstance pi = mock(ProcessInstance.class);
        when(pi.getId()).thenReturn("pi-1");
        when(runtime.createProcessInstanceQuery().processInstanceBusinessKey("BK-1").tenantIdIn("t1").listPage(0, 1))
                .thenReturn(List.of(pi));
        when(runtime.createProcessInstanceQuery().processInstanceId("pi-1").count()).thenReturn(1L);
        when(runtime.getVariables("pi-1")).thenReturn(Map.of("no", "42"));
        ContentService content = mock(ContentService.class);
        PortsDocumentBridge documents = mock(PortsDocumentBridge.class);
        when(documents.content()).thenReturn(content);
        AuditPort audit = mock(AuditPort.class);

        PortsDocumentExtrasBridge b = new PortsDocumentExtrasBridge(documents, new OutputTemplateRenderer(null), runtime,
                mock(HistoryService.class), templates(), audit, pdf);
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> b.generate(CTX, new DocumentExtrasPort.GenerateRequest("tpl-1", "BK-1", "en")));
        assertEquals("FORMAT_NOT_AVAILABLE", p.code());
        assertTrue(p.detail().contains("cannot be reached"), p.detail());
        verify(content, never()).create(any(), any(), any(), any(), any(), any(), any(InputStream.class), any());
        verify(audit, never()).record(any(), any(), any(), any());
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
