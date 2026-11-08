// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

/**
 * Thrown by {@link TaskContext#throwIfCancelled()} (or by the handler) to stop work cooperatively.
 * If the server requested cancel, the worker acknowledges it ({@code cancel-ack}, VI.3.1); if the lease was lost
 * or the worker is shutting down, the work is abandoned or unlocked.
 */
public class TaskCancelledException extends RuntimeException {
    public TaskCancelledException(String message) {
        super(message);
    }
}
