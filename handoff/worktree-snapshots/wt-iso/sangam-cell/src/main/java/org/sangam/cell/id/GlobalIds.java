// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.id;

import java.util.Optional;
import java.util.UUID;

import org.sangam.core.layer.Hashes;

/**
 * Cell-prefixed global identifiers (SCALE-ARCHITECTURE.md section 6).
 *
 * <ul>
 *   <li>Engine and product ids: {@code <cellId>.<uuidv7>}, at most 16 + 1 + 36 = 53 chars, which fits every
 *       {@code VARCHAR(64)} id column of Operaton and of the product schema. UUIDv7 keeps B-tree inserts
 *       append-mostly (no random-page split storm on hot id indexes).</li>
 *   <li>Business keys: {@code <tenantCode>/<localKey>}; the tenant code is unique nationally (control-plane
 *       registry), so a business key is unique across cells without cross-cell coordination and survives a tenant
 *       move unchanged (the cell prefix is NOT part of a business key, on purpose).</li>
 * </ul>
 * The id prefix records the <em>minting</em> cell, not the current home: after a tenant move, old ids keep their old
 * prefix and routing always goes through the tenant, never through the id prefix.
 */
public final class GlobalIds {

    public static final char SEPARATOR = '.';

    private GlobalIds() {
    }

    public static String newId(CellId cell) {
        return cell.value() + SEPARATOR + Hashes.uuidV7();
    }

    public static String newId(CellId cell, UUID uuid) {
        return cell.value() + SEPARATOR + uuid;
    }

    /** The minting cell of a global id, or empty for a legacy (unprefixed) id. */
    public static Optional<CellId> mintingCell(String globalId) {
        if (globalId == null) {
            return Optional.empty();
        }
        int i = globalId.indexOf(SEPARATOR);
        if (i <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(CellId.of(globalId.substring(0, i)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static String businessKey(String tenantCode, String localKey) {
        if (tenantCode == null || tenantCode.isBlank() || localKey == null || localKey.isBlank()) {
            throw new IllegalArgumentException("tenantCode and localKey are required");
        }
        if (localKey.startsWith(tenantCode + "/")) {
            return localKey;
        }
        return tenantCode + "/" + localKey;
    }
}
