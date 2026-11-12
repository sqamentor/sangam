// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

/**
 * Thrown by a {@link StepCall} when the remote call certainly had no effect (it was refused before it was sent, or
 * the remote system rejected it as a business failure). The step is recorded {@code FAILED} with the message, so a
 * later attempt may run it again; the exception then propagates to the handler (R37).
 */
public class StepNoEffectException extends Exception {

    public StepNoEffectException(String message) {
        super(message);
    }

    public StepNoEffectException(String message, Throwable cause) {
        super(message, cause);
    }
}
