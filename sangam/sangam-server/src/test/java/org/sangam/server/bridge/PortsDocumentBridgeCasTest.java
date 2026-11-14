// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.TaskService;
import org.sangam.ports.content.ContentService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Owner decision 228: {@link PortsDocumentBridge#newVersion(RequestContext, String, int, DocumentPort.UploadRequest)}
 * goes through the content service's compare-and-set and answers 409 {@value DocumentPort#VERSION_CONFLICT} when the
 * document has moved on; nothing is audited for a refused write. (The atomic UPDATE itself is covered by
 * ContentServiceH2Test.)
 */
class PortsDocumentBridgeCasTest {

    private static final RequestContext CTX = new RequestContext("t1", "user-1", Set.of(), null, null, null, null, null,
            "TEST");
    private static final Instant AT = Instant.parse("2026-11-13T10:00:00Z");
    private static final byte[] SIGNED = "%PDF-1.7 signed".getBytes(StandardCharsets.US_ASCII);

    private final ContentService content = mock(ContentService.class);
    private final AuditPort audit = mock(AuditPort.class);
    private final TxSupport tx = mock(TxSupport.class);
    private final PortsDocumentBridge bridge = new PortsDocumentBridge(content, mock(TaskService.class),
            mock(HistoryService.class), audit, mock(RestProperties.class), tx);

    private static ContentService.ContentItem item(int version) {
        return new ContentService.ContentItem("d1", "t1", "approval.pdf", "application/pdf", "bpmn", "pi-1", "task-1",
                version, false, "user-0", AT);
    }

    private static ContentService.ContentVersion version(int v) {
        return new ContentService.ContentVersion("d1", v, "t1/2026/11/d1/v" + v + "-x", SIGNED.length, "ab".repeat(32),
                ContentService.ScanState.NOT_SCANNED, "user-1", AT);
    }

    private static DocumentPort.UploadRequest upload() {
        return new DocumentPort.UploadRequest("approval.pdf", "application/pdf", SIGNED, null, null, null,
                Map.of("signatureType", "DSC"));
    }

    @Test
    void aMovedDocumentIsAConflictAndNothingIsAudited() {
        when(content.item("d1")).thenReturn(Optional.of(item(2)));
        when(content.addVersion(eq("d1"), eq(1), any(InputStream.class), eq("user-1")))
                .thenThrow(new ContentService.VersionConflict("d1", 1));
        ApiProblem p = assertThrows(ApiProblem.class, () -> bridge.newVersion(CTX, "d1", 1, upload()));
        assertEquals(409, p.status());
        assertEquals(DocumentPort.VERSION_CONFLICT, p.code());
        verify(audit, never()).record(any(), anyString(), anyString(), anyMap());
        verify(content, never()).addVersion(anyString(), any(InputStream.class), anyString());
    }

    @Test
    void theExpectedVersionGetsTheNextOne() {
        when(content.item("d1")).thenReturn(Optional.of(item(1)), Optional.of(item(2)));
        when(content.addVersion(eq("d1"), eq(1), any(InputStream.class), eq("user-1"))).thenReturn(version(2));
        when(content.version("d1", 2)).thenReturn(Optional.of(version(2)));
        when(tx.withConnection(any())).thenReturn(false);
        DocumentPort.Document d = bridge.newVersion(CTX, "d1", 1, upload());
        assertEquals(2, d.version());
        verify(audit).record(eq(CTX), eq("pi-1"), eq("DOCUMENT_VERSION_ADDED"), anyMap());
    }

    @Test
    void theUnconditionalVersionKeepsItsOwnPath() {
        when(content.item("d1")).thenReturn(Optional.of(item(1)), Optional.of(item(2)));
        when(content.addVersion(eq("d1"), any(InputStream.class), eq("user-1"))).thenReturn(version(2));
        when(content.version("d1", 2)).thenReturn(Optional.of(version(2)));
        when(tx.withConnection(any())).thenReturn(false);
        assertEquals(2, bridge.newVersion(CTX, "d1", upload()).version());
        verify(content, never()).addVersion(anyString(), anyInt(), any(InputStream.class), anyString());
    }
}
