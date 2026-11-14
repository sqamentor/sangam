// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;

/**
 * Owner decisions 267, 268 and 269: the document route fills a DSC step's document slot. A PDF uploaded or versioned on
 * a process instance with a {@code documentType} for which the instance's pack declares a slot (manifest
 * {@code documentSlots}) is written into that slot, a case variable the DSC gate captures when the signing task is
 * created. The rules:
 * <ul>
 *   <li>No documentType, a type the pack declares no slot for, a document that is not a PDF, or a scope that is not a
 *       process instance: the slot is not touched and the document is stored as before.</li>
 *   <li>Only a caller with {@value Permissions#TASK_WRITE} who holds an open task of that instance (as assignee) may set
 *       or replace a slot; anyone else is refused {@code 403 DOCUMENT_SLOT_NOT_TASK_HOLDER} before anything is
 *       stored.</li>
 *   <li>A slot that already holds another document is refused {@code 409 DOCUMENT_SLOT_OCCUPIED} unless the caller
 *       passes {@code replaceSlot=true}. A new version of the document the slot holds is not another document.</li>
 *   <li>The check, the store and the slot write run in one transaction under a lock on the instance, so two uploads of
 *       the same type cannot both pass the check. The audit event of the store ({@code DOCUMENT_UPLOADED},
 *       {@code DOCUMENT_VERSION_ADDED}) carries {@code slot} and {@code document_type} through the request metadata
 *       keys {@value #META_DOCUMENT_TYPE} and {@value #META_SLOT}.</li>
 * </ul>
 * A slot changed after the signing task was created is refused at completion by the DSC gate, by design.
 */
public final class DocumentSlots {

    public static final String NOT_TASK_HOLDER = "DOCUMENT_SLOT_NOT_TASK_HOLDER";
    public static final String OCCUPIED = "DOCUMENT_SLOT_OCCUPIED";
    /** Request metadata key: the document type the caller named. */
    public static final String META_DOCUMENT_TYPE = "documentType";
    /** Request metadata key: the slot the document was written into. */
    public static final String META_SLOT = "documentSlot";
    static final String PDF = "application/pdf";

    /** What the rules need from the engine and the packs; the server implements it. */
    public interface Instances {

        /** The slot the pack of this process instance (of this tenant) declares for the type; empty when none. */
        Optional<String> slotFor(String tenantId, String processInstanceId, String documentType);

        /** The process instance of a task of this tenant, or null. */
        String processInstanceOfTask(String tenantId, String taskId);

        /** Whether the principal is the assignee of an open task of this instance. */
        boolean holdsOpenTask(String tenantId, String processInstanceId, String principalId);

        /** The document id the slot holds, empty when unset or blank. */
        Optional<String> slotValue(String processInstanceId, String slot);

        void setSlot(String processInstanceId, String slot, String documentId);

        /** Blocks other slot writers of this instance until the current transaction ends. */
        void lock(String processInstanceId);

        /** Joins the current transaction or starts one. */
        <T> T inTransaction(Supplier<T> work);
    }

    private final Instances instances;
    private final PermissionPort permissions;

    public DocumentSlots(Instances instances, PermissionPort permissions) {
        this.instances = instances;
        this.permissions = permissions;
    }

    /** {@link DocumentPort#upload} with the slot rules. */
    public DocumentPort.Document upload(RequestContext ctx, DocumentPort documents, DocumentPort.UploadRequest r,
                                        String documentType, boolean replaceSlot) {
        String type = blankToNull(documentType);
        if (type == null || r == null || !pdf(r.contentType()) || r.caseInstanceId() != null) {
            return documents.upload(ctx, r);
        }
        String pi = r.processInstanceId() != null ? r.processInstanceId()
                : r.taskId() != null ? instances.processInstanceOfTask(ctx.tenantId(), r.taskId()) : null;
        if (pi == null) {
            return documents.upload(ctx, r);
        }
        return instances.inTransaction(() -> {
            Optional<String> slot = instances.slotFor(ctx.tenantId(), pi, type);
            if (slot.isEmpty()) {
                return documents.upload(ctx, r);
            }
            claim(ctx, pi, slot.get(), null, replaceSlot);
            DocumentPort.Document d = documents.upload(ctx, withSlot(r, type, slot.get()));
            instances.setSlot(pi, slot.get(), d.id());
            return d;
        });
    }

    /** {@link DocumentPort#newVersion} (with or without the expected version) with the slot rules. */
    public DocumentPort.Document newVersion(RequestContext ctx, DocumentPort documents, String documentId,
                                            Integer expectedVersion, DocumentPort.UploadRequest r,
                                            String documentType, boolean replaceSlot) {
        String type = blankToNull(documentType);
        if (type == null || r == null) {
            return version(ctx, documents, documentId, expectedVersion, r);
        }
        return instances.inTransaction(() -> {
            DocumentPort.Document current = documents.get(ctx, documentId);
            String contentType = r.contentType() != null ? r.contentType() : current.contentType();
            String pi = current.processInstanceId();
            Optional<String> slot = pi == null || !pdf(contentType) ? Optional.empty()
                    : instances.slotFor(ctx.tenantId(), pi, type);
            if (slot.isEmpty()) {
                return version(ctx, documents, documentId, expectedVersion, r);
            }
            claim(ctx, pi, slot.get(), documentId, replaceSlot);
            DocumentPort.Document d = version(ctx, documents, documentId, expectedVersion, withSlot(r, type, slot.get()));
            instances.setSlot(pi, slot.get(), d.id());
            return d;
        });
    }

    /** Authorisation first, then the lock and the occupancy check; {@code versionOf} is the document being versioned. */
    private void claim(RequestContext ctx, String pi, String slot, String versionOf, boolean replaceSlot) {
        if (!permissions.has(ctx, Permissions.TASK_WRITE)
                || !instances.holdsOpenTask(ctx.tenantId(), pi, ctx.principalId())) {
            throw ApiProblem.forbidden(NOT_TASK_HOLDER, "only the holder of an open task of process instance " + pi
                    + " with " + Permissions.TASK_WRITE + " may set the document slot " + slot);
        }
        instances.lock(pi);
        Optional<String> held = instances.slotValue(pi, slot);
        if (held.isPresent() && !replaceSlot && !held.get().equals(versionOf)) {
            throw ApiProblem.conflict(OCCUPIED, "the document slot " + slot + " of process instance " + pi
                    + " already holds document " + held.get() + "; pass replaceSlot=true to replace it");
        }
    }

    private static DocumentPort.Document version(RequestContext ctx, DocumentPort documents, String documentId,
                                                 Integer expectedVersion, DocumentPort.UploadRequest r) {
        return expectedVersion == null ? documents.newVersion(ctx, documentId, r)
                : documents.newVersion(ctx, documentId, expectedVersion, r);
    }

    private static DocumentPort.UploadRequest withSlot(DocumentPort.UploadRequest r, String type, String slot) {
        Map<String, Object> m = new LinkedHashMap<>(r.metadata() == null ? Map.of() : r.metadata());
        m.put(META_DOCUMENT_TYPE, type);
        m.put(META_SLOT, slot);
        return new DocumentPort.UploadRequest(r.name(), r.contentType(), r.content(), r.taskId(),
                r.processInstanceId(), r.caseInstanceId(), m);
    }

    static boolean pdf(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        return (semi >= 0 ? ct.substring(0, semi) : ct).trim().equals(PDF);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
