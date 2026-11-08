// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.security;

/**
 * Security failures with the normative error codes of SANGAM.md X.4.0.4 / X.5.2. The REST layer maps
 * {@link #httpStatus()} and {@link #code()} to an RFC 9457 problem document; no SQL or stack trace is exposed (R28).
 */
public class SangamSecurityException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public SangamSecurityException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** 403 {@code ACTION_NOT_PERMITTED}: object policy, revoked grant, seat no longer held (CX-1, K3, K7). */
    public static class AccessDenied extends SangamSecurityException {
        private final long policyVersion;

        public AccessDenied(String message, long policyVersion) {
            super("ACTION_NOT_PERMITTED", 403, message);
            this.policyVersion = policyVersion;
        }

        public long policyVersion() {
            return policyVersion;
        }
    }

    /** 401 {@code UNAUTHENTICATED} / {@code SESSION_EXPIRED} / {@code NOT_LINKED}. */
    public static class Unauthenticated extends SangamSecurityException {
        public Unauthenticated(String code, String message) {
            super(code, 401, message);
        }
    }

    /** 403 {@code TENANT_MISMATCH}: a request tenant outside the identity's tenant (VI.4 rule 6). */
    public static class TenantMismatch extends SangamSecurityException {
        public TenantMismatch(String message) {
            super("TENANT_MISMATCH", 403, message);
        }
    }

    /** 422 {@code SOD_VIOLATION} (CX-8, K5). */
    public static class SodViolation extends SangamSecurityException {
        public SodViolation(String message) {
            super("SOD_VIOLATION", 422, message);
        }
    }

    /** 422 {@code POWER_LIMIT_EXCEEDED} (K6, IX.5 rule 3). */
    public static class PowerLimitExceeded extends SangamSecurityException {
        public PowerLimitExceeded(String message) {
            super("POWER_LIMIT_EXCEEDED", 422, message);
        }
    }

    /** 409 {@code APPROVAL_INVALIDATED}: the material payload changed after approval (K4, VI.4 rule 5). */
    public static class ApprovalInvalidated extends SangamSecurityException {
        public ApprovalInvalidated(String message) {
            super("APPROVAL_INVALIDATED", 409, message);
        }
    }

    /** 400 {@code POLICY_REJECTED}: a list-visible policy that does not compile to SQL (VI.4 rule 1). */
    public static class PolicyRejected extends SangamSecurityException {
        public PolicyRejected(String message) {
            super("POLICY_REJECTED", 400, message);
        }
    }

    /** 409 {@code ADMIN_CONFLICT}: an admin change that is blocked by current state (AJ-02 step 4, AJ-01 step 10). */
    public static class AdminConflict extends SangamSecurityException {
        public AdminConflict(String code, String message) {
            super(code, 409, message);
        }
    }
}
