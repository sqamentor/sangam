// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.flow;

/** A refused onboarding command, with the HTTP status and stable code the REST layer returns. */
public final class OnboardingException extends RuntimeException {

    private final int status;
    private final String code;

    public OnboardingException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
