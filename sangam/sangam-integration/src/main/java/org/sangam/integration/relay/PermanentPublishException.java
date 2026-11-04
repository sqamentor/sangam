// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

/**
 * Thrown by a {@link org.sangam.core.layer.Publisher} for a poison event: a failure that no retry can fix (the
 * destination rejects the payload with 4xx, the event cannot be serialised for this transport, the topic does not
 * exist). The relay parks (ordered) or skips (unordered) such a delivery at once instead of burning its retry budget,
 * and records it in the dead-letter store (VI.2.3 rule 5).
 */
public class PermanentPublishException extends Exception {

    public PermanentPublishException(String message) {
        super(message);
    }

    public PermanentPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
