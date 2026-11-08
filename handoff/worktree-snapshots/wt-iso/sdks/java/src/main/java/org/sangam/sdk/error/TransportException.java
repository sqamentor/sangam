// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.error;

/** Network failure (connect, reset, timeout) after the configured retries were used up. */
public final class TransportException extends SangamException {
    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
