// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;

/**
 * Owner decisions 267 to 269: set, 409 on a second document, explicit replace, refusal of a caller who holds no open
 * task (or lacks task.write), an unknown type ignored, and the slot named in the stored request (the audit payload).
 */
class DocumentSlotsTest {

    static final RequestContext HOLDER = new RequestContext("t1", "officer-1", Set.of(), null, null, "key-1", null,
            "127.0.0.1", "OIDC");
    static final RequestContext OTHER = new RequestContext("t1", "clerk-9", Set.of(), null, null, "key-2", null,
            "127.0.0.1", "OIDC");
    static final byte[] PDF_BYTES = "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** Instance pi-1 of a pack with NOTING to notingDocumentId; officer-1 holds an open task there. */
    final class FakeInstances implements DocumentSlots.Instances {
        final Map<String, String> vars = new HashMap<>();
        final List<String> events = new ArrayList<>();
        int transactions;

        @Override
        public Optional<String> slotFor(String tenantId, String processInstanceId, String documentType) {
            return "t1".equals(tenantId) && "pi-1".equals(processInstanceId) && "NOTING".equals(documentType)
                    ? Optional.of("notingDocumentId") : Optional.empty();
        }

        @Override
        public String processInstanceOfTask(String tenantId, String taskId) {
            return "task-1".equals(taskId) ? "pi-1" : null;
        }

        @Override
        public boolean holdsOpenTask(String tenantId, String processInstanceId, String principalId) {
            return "pi-1".equals(processInstanceId) && "officer-1".equals(principalId);
        }

        @Override
        public Optional<String> slotValue(String processInstanceId, String slot) {
            return Optional.ofNullable(vars.get(processInstanceId + "/" + slot));
        }

        @Override
        public void setSlot(String processInstanceId, String slot, String documentId) {
            events.add("set " + slot + "=" + documentId);
            vars.put(processInstanceId + "/" + slot, documentId);
        }

        @Override
        public void lock(String processInstanceId) {
            events.add("lock " + processInstanceId);
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            transactions++;
            return work.get();
        }
    }

    /** Stores every request; version ids are the base id plus "-v2". */
    static final class FakeDocuments implements DocumentPort {
        final List<UploadRequest> stored = new ArrayList<>();
        int next = 1;

        @Override
        public Document upload(RequestContext ctx, UploadRequest r) {
            stored.add(r);
            return doc("doc-" + next++, r.contentType());
        }

        @Override
        public Document newVersion(RequestContext ctx, String documentId, UploadRequest r) {
            stored.add(r);
            return doc(documentId + "-v2", r.contentType());
        }

        @Override
        public Document newVersion(RequestContext ctx, String documentId, int expectedVersion, UploadRequest r) {
            stored.add(r);
            return doc(documentId + "-v" + (expectedVersion + 1), r.contentType());
        }

        @Override
        public Page<Document> list(RequestContext ctx, DocumentFilter filter, Page.PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Document get(RequestContext ctx, String documentId) {
            return doc(documentId, "application/pdf");
        }

        @Override
        public Download content(RequestContext ctx, String documentId, Integer version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(RequestContext ctx, String documentId, String reason) {
            throw new UnsupportedOperationException();
        }

        static Document doc(String id, String contentType) {
            return new Document(id, "t1", "note.pdf", contentType, 8, "ab", 1, null, "pi-1", null, "NOT_SCANNED",
                    "officer-1", null, Map.of());
        }
    }

    static PermissionPort granting(String... granted) {
        Set<String> g = Set.of(granted);
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return g.contains(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return g;
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
    }

    final FakeInstances instances = new FakeInstances();
    final FakeDocuments documents = new FakeDocuments();
    final DocumentSlots slots = new DocumentSlots(instances, granting(Permissions.TASK_WRITE));

    static DocumentPort.UploadRequest pdfOn(String pi, String taskId) {
        return new DocumentPort.UploadRequest("note.pdf", "application/pdf", PDF_BYTES, taskId, pi, null, null);
    }

    @Test
    void aNotingPdfOnTheInstanceFillsTheSlotAndNamesItInTheStoredRequest() {
        DocumentPort.Document d = slots.upload(HOLDER, documents, pdfOn("pi-1", null), "NOTING", false);
        assertEquals("doc-1", d.id());
        assertEquals(Optional.of("doc-1"), instances.slotValue("pi-1", "notingDocumentId"));
        assertEquals(List.of("lock pi-1", "set notingDocumentId=doc-1"), instances.events, "locked before the check");
        Map<String, Object> meta = documents.stored.get(0).metadata();
        assertEquals("NOTING", meta.get(DocumentSlots.META_DOCUMENT_TYPE));
        assertEquals("notingDocumentId", meta.get(DocumentSlots.META_SLOT));
        assertEquals(1, instances.transactions);
    }

    @Test
    void anUploadOnATaskOfTheInstanceFillsTheSlotToo() {
        slots.upload(HOLDER, documents, pdfOn(null, "task-1"), "NOTING", false);
        assertEquals(Optional.of("doc-1"), instances.slotValue("pi-1", "notingDocumentId"));
    }

    @Test
    void aSecondDocumentOfTheSameTypeIsRefused409AndNothingIsStored() {
        slots.upload(HOLDER, documents, pdfOn("pi-1", null), "NOTING", false);
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> slots.upload(HOLDER, documents, pdfOn("pi-1", null), "NOTING", false));
        assertEquals(409, p.status());
        assertEquals(DocumentSlots.OCCUPIED, p.code());
        assertEquals(1, documents.stored.size(), "the refused document was not stored");
        assertEquals(Optional.of("doc-1"), instances.slotValue("pi-1", "notingDocumentId"));
    }

    @Test
    void anExplicitReplaceWritesTheNewDocumentIntoTheSlot() {
        slots.upload(HOLDER, documents, pdfOn("pi-1", null), "NOTING", false);
        DocumentPort.Document d = slots.upload(HOLDER, documents, pdfOn("pi-1", null), "NOTING", true);
        assertEquals("doc-2", d.id());
        assertEquals(Optional.of("doc-2"), instances.slotValue("pi-1", "notingDocumentId"));
    }

    @Test
    void aNewVersionOfTheHeldDocumentNeedsNoReplaceButAnotherDocumentDoes() {
        slots.upload(HOLDER, documents, pdfOn("pi-1", null), "NOTING", false);
        DocumentPort.UploadRequest v = new DocumentPort.UploadRequest("note.pdf", "application/pdf", PDF_BYTES, null,
                null, null, null);
        DocumentPort.Document d = slots.newVersion(HOLDER, documents, "doc-1", null, v, "NOTING", false);
        assertEquals(Optional.of(d.id()), instances.slotValue("pi-1", "notingDocumentId"));
        assertEquals("notingDocumentId", documents.stored.get(1).metadata().get(DocumentSlots.META_SLOT));

        ApiProblem p = assertThrows(ApiProblem.class,
                () -> slots.newVersion(HOLDER, documents, "doc-other", null, v, "NOTING", false));
        assertEquals(409, p.status());
        assertEquals(DocumentSlots.OCCUPIED, p.code());
    }

    @Test
    void aCallerWhoHoldsNoOpenTaskOfTheInstanceIsRefusedBeforeAnythingIsStored() {
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> slots.upload(OTHER, documents, pdfOn("pi-1", null), "NOTING", false));
        assertEquals(403, p.status());
        assertEquals(DocumentSlots.NOT_TASK_HOLDER, p.code());
        assertTrue(documents.stored.isEmpty());
        assertTrue(instances.vars.isEmpty());
        assertFalse(instances.events.contains("lock pi-1"), "refused before the lock");
    }

    @Test
    void theTaskHolderWithoutTaskWriteIsRefusedToo() {
        DocumentSlots readOnly = new DocumentSlots(instances, granting(Permissions.DOCUMENT_WRITE));
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> readOnly.upload(HOLDER, documents, pdfOn("pi-1", null), "NOTING", true));
        assertEquals(403, p.status());
        assertEquals(DocumentSlots.NOT_TASK_HOLDER, p.code());
        assertTrue(documents.stored.isEmpty());
    }

    @Test
    void anUnknownTypeIsIgnoredAndTheDocumentStoredAsBefore() {
        DocumentPort.Document d = slots.upload(OTHER, documents, pdfOn("pi-1", null), "INVOICE", false);
        assertEquals("doc-1", d.id());
        assertTrue(instances.vars.isEmpty());
        assertTrue(instances.events.isEmpty());
        assertNull(documents.stored.get(0).metadata(), "the request is passed unchanged");
    }

    @Test
    void noTypeANonPdfOrNoProcessInstanceLeavesTheSlotAlone() {
        slots.upload(OTHER, documents, pdfOn("pi-1", null), null, false);
        slots.upload(OTHER, documents, pdfOn("pi-1", null), "  ", false);
        slots.upload(HOLDER, documents, new DocumentPort.UploadRequest("note.png", "image/png", PDF_BYTES, null,
                "pi-1", null, null), "NOTING", false);
        slots.upload(HOLDER, documents, new DocumentPort.UploadRequest("note.pdf", "application/pdf", PDF_BYTES,
                null, null, "case-1", null), "NOTING", false);
        slots.upload(HOLDER, documents, pdfOn(null, "task-unknown"), "NOTING", false);
        assertEquals(5, documents.stored.size());
        assertTrue(instances.vars.isEmpty());
        assertEquals(0, instances.transactions);
    }

    @Test
    void theContentTypeMayCarryParameters() {
        assertTrue(DocumentSlots.pdf("Application/PDF; charset=binary"));
        assertFalse(DocumentSlots.pdf("application/pdfx"));
        assertFalse(DocumentSlots.pdf(null));
    }
}
