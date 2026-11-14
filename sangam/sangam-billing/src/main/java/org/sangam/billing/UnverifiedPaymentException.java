// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

/**
 * A refused unverified-payment operation (RM-03, owner decision 141). Nothing was written. The code tells an adapter
 * how to answer (REST: {@link Code#NOT_PERMITTED} and {@link Code#CHANNEL_NOT_ENABLED} 403, {@link Code#DUPLICATE} and
 * {@link Code#INVALID_TRANSITION} 409, {@link Code#NOT_FOUND} 404, {@link Code#LIMIT_EXCEEDED} 422,
 * {@link Code#INVALID_REQUEST} 400).
 */
public final class UnverifiedPaymentException extends RuntimeException {

    public enum Code {
        /** No server-authenticated caller, a caller of another tenant, or the authorisation model refused it. */
        NOT_PERMITTED,
        /** The channel is not configured for unverified acceptance, or its operating policy is unresolved. */
        CHANNEL_NOT_ENABLED,
        /** A required field is missing or malformed (reason, evidence, payment details, currency). */
        INVALID_REQUEST,
        /** The amount exceeds the channel policy's limit. */
        LIMIT_EXCEEDED,
        /** The channel and payment reference were already accepted. */
        DUPLICATE,
        /** The record does not exist in the caller's tenant. */
        NOT_FOUND,
        /** The record is no longer pending, or another transition changed it first. */
        INVALID_TRANSITION
    }

    private final Code code;

    public UnverifiedPaymentException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public UnverifiedPaymentException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
