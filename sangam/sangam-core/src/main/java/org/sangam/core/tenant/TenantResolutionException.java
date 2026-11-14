// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.tenant;

import java.util.Objects;

import org.sangam.core.security.SangamSecurityException;

/**
 * A fail-closed tenant-resolution refusal (owner decision 145): 403 with the {@link TenantRefusal#code()}. Thrown before
 * any connector call or side effect; inside an engine command it rolls the command back. The message names the code,
 * the operation and the caller's fixed context label only, never a tenant id, variable value or credential.
 */
public final class TenantResolutionException extends SangamSecurityException {

    private final TenantRefusal refusal;
    private final TenantOperation operation;
    private final String context;

    public TenantResolutionException(TenantRefusal refusal, TenantOperation operation, String context, String reason) {
        super(Objects.requireNonNull(refusal, "refusal").code(), 403,
                refusal.code() + ": " + context + " (" + (operation == null ? "resolved tenant check" : operation) + "): "
                        + reason);
        this.refusal = refusal;
        this.operation = operation;
        this.context = context;
    }

    public TenantRefusal refusal() {
        return refusal;
    }

    /** The operation kind that was being resolved, or null for a check on an already resolved tenant. */
    public TenantOperation operation() {
        return operation;
    }

    /** The caller's fixed context label (for example "pack connector call"). */
    public String context() {
        return context;
    }
}
