// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.custody;

import java.time.Instant;

/**
 * Current custody state of one tracked item (a kernel object of kind {@code CUSTODY_ITEM} plus its
 * {@code REG_CUSTODY_ITEM} row).
 *
 * @param custodyState {@code IN_CUSTODY}, {@code IN_TRANSIT}, {@code DISPOSED} or {@code RELEASED}
 * @param sealStatus   {@code NONE}, {@code INTACT} or {@code BROKEN}
 * @param headHash     hex of the last custody event hash (null before the first event)
 */
public record CustodyItem(String objectId, String typeKey, String objectNumber, String objectState, String label,
                          String labelKind, String description, String caseRef, String containerId, boolean container,
                          String sealNo, String sealStatus, String custodianRef, String locationRef,
                          String custodyState, String pendingTransferId, long eventCount, String headHash,
                          Instant lastEventAt, long version) {

    public boolean terminal() {
        return CustodyService.DISPOSED.equals(custodyState) || CustodyService.RELEASED.equals(custodyState);
    }
}
