// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.sync;

/**
 * Policy the server hands a device at registration for its encrypted local queue ({@link LocalQueueContract}).
 *
 * @param maxItems         queue capacity; the client refuses new captures when full (never drops queued work)
 * @param maxAgeHours      queued packets older than this are flagged by the server on upload (late sync)
 * @param wipeOnRevoke     the client must destroy its queue key when the server reports the device REVOKED/LOST
 * @param requireSignature every packet must carry a valid device signature; unsigned packets are flagged
 * @param maxOfflineHours  the client must sync at least this often or stop offering capture
 */
public record QueuePolicy(int maxItems, int maxAgeHours, boolean wipeOnRevoke, boolean requireSignature, int maxOfflineHours) {

    public QueuePolicy {
        maxItems = maxItems <= 0 ? 500 : maxItems;
        maxAgeHours = maxAgeHours <= 0 ? 72 : maxAgeHours;
        maxOfflineHours = maxOfflineHours <= 0 ? 168 : maxOfflineHours;
    }

    public static QueuePolicy defaults() {
        return new QueuePolicy(0, 0, true, false, 0);
    }
}
