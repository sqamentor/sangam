// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.continuation;

import org.operaton.bpm.engine.ProcessEngineException;

/**
 * Decision 149: the instance cannot be continued as new (REST 409 {@value #CODE}). Nothing changed. For an instance that
 * was already continued, {@link #successorId()} names its successor.
 */
public final class ContinuationNotAllowedException extends ProcessEngineException {

    public static final String CODE = "CONTINUATION_NOT_ALLOWED";

    /** Why the continuation was refused. */
    public enum Reason { SUB_INSTANCE, SUSPENDED, ALREADY_CONTINUED, ENDED, REQUEST_ID_REUSED }

    private final Reason reason;
    private final String successorId;

    public ContinuationNotAllowedException(Reason reason, String instanceId, String successorId) {
        super(CODE + " (" + reason + "): process instance " + instanceId
                + (successorId == null ? "" : " was continued as " + successorId));
        this.reason = reason;
        this.successorId = successorId;
    }

    public String code() {
        return CODE;
    }

    public Reason reason() {
        return reason;
    }

    /** The successor of an already continued instance, else null. */
    public String successorId() {
        return successorId;
    }
}
