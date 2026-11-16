// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

/**
 * Owner decision 385 (2), layer 1: whether the server's document route (decisions 267 and 268) bound a document into a
 * DSC slot. The DSC gate's create listener asks it before it captures the slot's document: a slot value that the route
 * did not bind (a variable written by a task completion, a variable set, a process start or a message) is treated as an
 * empty slot, so the signing step is refused at completion. {@code sangam-server} installs an adapter over the route's
 * audit events ({@code DOCUMENT_UPLOADED} and {@code DOCUMENT_VERSION_ADDED} with {@code slot} and
 * {@code document_type}); without one nothing is bound (fail closed).
 *
 * <p>Owner decisions 387 (9) and 390, option (a): a binding serves one gate instance. When a gate completes over a
 * verified signature, the listener {@linkplain #consume consumes} the binding: the adapter records that this gate
 * instance (the process instance of the DSC task) signed it, on the chain where the binding was made. A binding older
 * than a consumption by another gate instance is no longer bound, so a gate reached again (a return loop, a new call)
 * signs only a document bound after that consumption; the levels of one gate instance still pass. Every adapter
 * implements both methods; there is no default.
 */
public interface DocumentSlotBindings {

    /** The default when no adapter is installed: no document is bound, so every DSC step with a slot is refused. */
    DocumentSlotBindings NONE = new DocumentSlotBindings() {
        @Override
        public boolean isBound(String tenantId, java.util.List<String> processInstances, String slot,
                               String documentType, String documentId) {
            return false;
        }

        @Override
        public void consume(String tenantId, java.util.List<String> processInstances, String slot,
                            String documentType, String documentId, String taskId) {
            // nothing is bound here, so a gate never gets as far as consuming: reaching this is a broken caller
            throw new IllegalStateException("no document slot binding is installed: nothing can be consumed");
        }
    };

    /**
     * @param processInstances the instance of the asking task first, then each process instance that called it, nearest
     *                         first (the route may have bound the document on any of them: a call copies the slot,
     *                         {@code in variables="all"}). The DSC listener reads the chain from the engine's execution
     *                         tree in memory, since a gate created in the same command as its call is not yet in the
     *                         database. The first instance is the asking gate instance: its own consumptions do not
     *                         end the binding (decision 390)
     * @return true only when the latest binding of {@code slot} made by the document route, on the nearest of these
     *         instances that has one, is {@code documentId} with {@code documentType}, in {@code tenantId}, and no gate
     *         instance other than the first consumed it after it was made
     */
    boolean isBound(String tenantId, java.util.List<String> processInstances, String slot, String documentType,
                    String documentId);

    /**
     * Decision 390: records that the gate instance {@code processInstances.get(0)} signed the binding of {@code slot}
     * (task {@code taskId}), in the caller's transaction when there is one (the engine command of the completion), so
     * the record commits or rolls back with the completion. Throws when the document is not bound for this gate
     * instance, or when the record cannot be written; the completion then fails (fail closed).
     */
    void consume(String tenantId, java.util.List<String> processInstances, String slot, String documentType,
                 String documentId, String taskId);
}
