// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

/**
 * The handle a durable step's body and lookup receive ({@link TaskContext#step}): the step's name, its stable
 * idempotency key and a slot for the provider's reference.
 */
public final class Step {

    private final String name;
    private final String operationId;
    private final String previousState;
    private volatile String providerRef;

    Step(String name, String operationId, String previousState) {
        this.name = name;
        this.operationId = operationId;
        this.previousState = previousState;
    }

    /** The step name, unique within the work's operation. */
    public String name() { return name; }

    /** The work's stable operationId (R36). */
    public String operationId() { return operationId; }

    /**
     * Idempotency key for the remote call: {@code operationId + ":" + name}. Stable across retries, redelivery and
     * lease expiry, and different for each step of the same operation.
     */
    public String idempotencyKey() { return operationId + ":" + name; }

    /**
     * What an earlier attempt recorded for this step: null on its first run, {@code "INTENT"} when that attempt's
     * outcome is unknown, {@code "FAILED"} when it failed without effect.
     */
    public String previousState() { return previousState; }

    /** Records the provider's reference with the outcome (e.g. a payment id); later lookups can use it. */
    public void providerRef(String ref) { this.providerRef = ref; }

    public String providerRef() { return providerRef; }
}
