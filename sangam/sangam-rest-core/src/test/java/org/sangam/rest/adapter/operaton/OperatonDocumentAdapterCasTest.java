// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.impl.persistence.entity.AttachmentEntity;
import org.operaton.bpm.engine.task.Attachment;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;

/**
 * Owner decision 228 on Operaton attachments: the compare-and-set new version is refused when the attachment is not at
 * the expected version, already has a successor, or was written by someone else between the read and the claim. The
 * in-memory {@link TaskService} keeps what Operaton does: {@code saveAttachment} re-reads the row, copies name and
 * description, and increments the revision when they changed. The row lock and the transaction rollback themselves need
 * a real engine and database (integration test, not here).
 */
class OperatonDocumentAdapterCasTest {

    private static final RequestContext CTX = new RequestContext("t1", "user-1", Set.of(), null, null, null, null, null,
            "TEST");
    private static final byte[] PDF = "%PDF-1.7 signed".getBytes(StandardCharsets.US_ASCII);

    private final Map<String, AttachmentEntity> rows = new LinkedHashMap<>();
    private final List<String> audited = new ArrayList<>();
    private Runnable beforeSave = () -> { };
    private int ids;

    private static AttachmentEntity copy(AttachmentEntity a) {
        AttachmentEntity c = new AttachmentEntity();
        c.setId(a.getId());
        c.setRevision(a.getRevision());
        c.setName(a.getName());
        c.setDescription(a.getDescription());
        c.setType(a.getType());
        c.setTaskId(a.getTaskId());
        c.setProcessInstanceId(a.getProcessInstanceId());
        c.setCreateTime(a.getCreateTime());
        return c;
    }

    private TaskService tasks() {
        return (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(), new Class<?>[]{TaskService.class},
                (p, m, a) -> switch (m.getName()) {
                    case "createAttachment" -> {
                        if (!(a[5] instanceof InputStream)) {
                            throw new AssertionError("only content attachments are used");
                        }
                        AttachmentEntity e = new AttachmentEntity();
                        e.setId("att-" + (++ids));
                        e.setRevision(1);
                        e.setType((String) a[0]);
                        e.setTaskId((String) a[1]);
                        e.setProcessInstanceId((String) a[2]);
                        e.setName((String) a[3]);
                        e.setDescription((String) a[4]);
                        e.setCreateTime(new Date(1_000L * ids));
                        rows.put(e.getId(), e);
                        yield copy(e);
                    }
                    case "getAttachment" -> {
                        AttachmentEntity e = rows.get((String) a[0]);
                        yield e == null ? null : copy(e);
                    }
                    case "saveAttachment" -> {
                        beforeSave.run();
                        Attachment in = (Attachment) a[0];
                        AttachmentEntity row = rows.get(in.getId());   // Operaton re-reads the row by id
                        if (!Objects.equals(row.getName(), in.getName())
                                || !Objects.equals(row.getDescription(), in.getDescription())) {
                            row.setName(in.getName());
                            row.setDescription(in.getDescription());
                            row.setRevision(row.getRevision() + 1);
                        }
                        yield null;
                    }
                    case "getProcessInstanceAttachments" -> rows.values().stream()
                            .filter(e -> a[0].equals(e.getProcessInstanceId())).map(e -> (Attachment) copy(e)).toList();
                    case "getTaskAttachments" -> rows.values().stream()
                            .filter(e -> a[0].equals(e.getTaskId())).map(e -> (Attachment) copy(e)).toList();
                    default -> throw new AssertionError("not used: " + m.getName());
                });
    }

    private AuditPort audit() {
        return (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(), new Class<?>[]{AuditPort.class},
                (p, m, a) -> {
                    if (!"record".equals(m.getName())) {
                        throw new AssertionError("not used: " + m.getName());
                    }
                    audited.add((String) a[2]);
                    return "evt-" + audited.size();
                });
    }

    private static TxSupport tx() {
        return new TxSupport(null, null) {
            @Override
            public <T> T inTxWithStatus(Function<TxStatus, T> work) {
                return work.apply(new TxStatus() {
                    @Override
                    public void setRollbackOnly() {
                    }

                    @Override
                    public boolean isRollbackOnly() {
                        return false;
                    }
                });
            }

            @Override
            public <T> T inReadTx(org.sangam.core.api.ReadSupplier<T> work) {
                return work.read();
            }

            @Override
            public <T> T withReadConnection(org.sangam.core.api.ReadWork<T> work) {
                return work.read(connection());
            }

            @Override
            public <T> T inNewTx(Supplier<T> work) {
                return work.get();
            }

            @Override
            public <T> T inNewReadTx(org.sangam.core.api.ReadWork<T> work) {
                return work.read(connection());
            }

            @Override
            public Connection connection() {
                throw new AssertionError("not used");
            }

            @Override
            public void afterCommit(Runnable work) {
                work.run();
            }
        };
    }

    private final OperatonDocumentAdapter adapter = new OperatonDocumentAdapter(tasks(), tx(), audit());

    private static DocumentPort.UploadRequest upload(String name, String pi) {
        return new DocumentPort.UploadRequest(name, "application/pdf", PDF, null, pi, null, Map.of());
    }

    private static int conflict(Runnable r) {
        ApiProblem p = assertThrows(ApiProblem.class, r::run);
        assertEquals(DocumentPort.VERSION_CONFLICT, p.code());
        return p.status();
    }

    @Test
    void theExpectedVersionGetsTheNextOneAndClaimsItsPredecessor() {
        DocumentPort.Document v1 = adapter.upload(CTX, upload("approval.pdf", "pi-1"));
        DocumentPort.Document v2 = adapter.newVersion(CTX, v1.id(), 1, upload("approval.pdf", null));
        assertEquals(2, v2.version());
        assertEquals(v2.id(), OperatonDocumentAdapter.meta(rows.get(v1.id())).get("next"));
        assertEquals(2, rows.get(v1.id()).getRevision(), "the claim wrote the predecessor's row");
        assertEquals(List.of("DOCUMENT_UPLOADED", "DOCUMENT_UPLOADED"), audited);
    }

    @Test
    void aStaleVersionOrAVersionWithASuccessorIsRefusedBeforeAnythingIsStored() {
        DocumentPort.Document v1 = adapter.upload(CTX, upload("approval.pdf", "pi-1"));
        assertEquals(409, conflict(() -> adapter.newVersion(CTX, v1.id(), 2, upload("approval.pdf", null))));
        assertEquals(1, rows.size(), "nothing stored");
        DocumentPort.Document v2 = adapter.newVersion(CTX, v1.id(), upload("edited.pdf", null));
        assertEquals(2, v2.version());
        assertEquals(409, conflict(() -> adapter.newVersion(CTX, v1.id(), 1, upload("approval.pdf", null))),
                "version 1 has a successor");
        assertEquals(2, rows.size());
        assertEquals(3, adapter.newVersion(CTX, v2.id(), 2, upload("approval.pdf", null)).version());
    }

    @Test
    void aLegacySuccessorWithoutTheClaimIsStillSeen() {
        DocumentPort.Document v1 = adapter.upload(CTX, upload("approval.pdf", "pi-1"));
        DocumentPort.Document v2 = adapter.newVersion(CTX, v1.id(), upload("edited.pdf", null));
        AttachmentEntity first = rows.get(v1.id());
        Map<String, Object> m = OperatonDocumentAdapter.meta(first);
        m.remove("next");   // a version added before decision 228 names its predecessor only through "previous"
        first.setDescription(OperatonDocumentAdapter.write(m));
        assertNull(OperatonDocumentAdapter.meta(rows.get(v1.id())).get("next"));
        assertEquals(409, conflict(() -> adapter.newVersion(CTX, v1.id(), 1, upload("approval.pdf", null))));
        assertEquals(2, v2.version());
    }

    @Test
    void aWriterThatCommittedBetweenTheReadAndTheClaimWins() {
        DocumentPort.Document v1 = adapter.upload(CTX, upload("approval.pdf", "pi-1"));
        beforeSave = () -> {
            beforeSave = () -> { };
            AttachmentEntity row = rows.get(v1.id());   // another transaction committed its claim meanwhile
            Map<String, Object> m = OperatonDocumentAdapter.meta(row);
            m.put("next", "att-other");
            row.setDescription(OperatonDocumentAdapter.write(m));
            row.setRevision(row.getRevision() + 1);
        };
        // the first saveAttachment is the new attachment's own; the race is on the predecessor's claim
        Runnable original = beforeSave;
        beforeSave = new Runnable() {
            private int calls;

            @Override
            public void run() {
                if (++calls == 2) {
                    original.run();
                }
            }
        };
        assertEquals(409, conflict(() -> adapter.newVersion(CTX, v1.id(), 1, upload("approval.pdf", null))));
    }
}
