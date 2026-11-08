// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.error;

/** Root of all SDK exceptions. */
public class SangamException extends RuntimeException {
    public SangamException(String message) {
        super(message);
    }

    public SangamException(String message, Throwable cause) {
        super(message, cause);
    }
}
