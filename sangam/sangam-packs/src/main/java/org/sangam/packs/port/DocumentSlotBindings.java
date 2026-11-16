// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

/**
 * Owner decision 385 (2), layer 1: whether the server's document route (decisions 267 and 268) bound a document into a
 * DSC slot. The DSC gate's create listener asks it before it captures the slot's document: a slot value that the route
 * did not bind (a variable written by a task completion, a variable set, a process start or a message) is treated as an
 * empty slot, so the signing step is refused at completion. {@code sangam-server} installs an adapter over the route's
 * audit events ({@code DOCUMENT_UPLOADED} and {@code DOCUMENT_VERSION_ADDED} with {@code slot} and
 * {@code document_type}); without one nothing is bound (fail closed).
 */
@FunctionalInterface
public interface DocumentSlotBindings {

    /** The default when no adapter is installed: no document is bound, so every DSC step with a slot is refused. */
    DocumentSlotBindings NONE = (tenantId, processInstances, slot, documentType, documentId) -> false;

    /**
     * @param processInstances the instance of the DSC task first, then each process instance that called it, nearest
     *                         first (the route may have bound the document on any of them: a call copies the slot,
     *                         {@code in variables="all"}). The DSC listener reads the chain from the engine's execution
     *                         tree in memory, since a gate created in the same command as its call is not yet in the
     *                         database
     * @return true only when the latest binding of {@code slot} made by the document route, on the nearest of these
     *         instances that has one, is {@code documentId} with {@code documentType}, in {@code tenantId}
     */
    boolean isBound(String tenantId, java.util.List<String> processInstances, String slot, String documentType,
                    String documentId);
}
