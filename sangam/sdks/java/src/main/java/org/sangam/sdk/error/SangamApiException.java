// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.error;

/** A non-2xx answer from the Sangam API, carrying the parsed problem+json body when present. */
public class SangamApiException extends SangamException {

    private final int status;
    private final Problem problem;

    public SangamApiException(int status, Problem problem, String message) {
        super(message);
        this.status = status;
        this.problem = problem;
    }

    public int status() {
        return status;
    }

    /** Parsed problem body; never null (a synthetic one is built when the server sent no problem+json). */
    public Problem problem() {
        return problem;
    }

    /** 408, 425, 429, 500, 502, 503, 504: the same request (same Idempotency-Key) may be repeated. */
    public boolean isRetryable() {
        return status == 408 || status == 425 || status == 429 || status == 500 || status == 502
                || status == 503 || status == 504;
    }
}
