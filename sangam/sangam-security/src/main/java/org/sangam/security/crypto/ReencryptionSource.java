// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.crypto;

import java.sql.Connection;
import java.util.List;

/**
 * One store of {@code FieldEncryptor} envelopes that the field re-encryption job ({@link FieldReencryptionJob}, M-085)
 * moves to the tenant's active key after a rotation. The store's own module implements it, because only that module
 * knows how a value is stored around the envelope and which encryption context (AAD) belongs to a row; the server
 * collects every implementation.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #page} returns the tenant's rows in ascending, stable {@link Row#key} order, strictly after
 *       {@code afterKey} ({@code null}: from the start), at most {@code limit} rows. The key order is the resume cursor,
 *       so it must be total and never reuse a key for another row;</li>
 *   <li>{@link Row#envelope} is the {@code sgmenc:v1:} envelope inside the stored value, or {@code null} when the row
 *       holds no encrypted value (a legacy clear value, an empty column); such rows are skipped;</li>
 *   <li>{@link #replace} is a compare-and-set: it writes {@code newStored} only while the stored value still equals
 *       {@code expectedStored}, so a value the application rewrote meanwhile (already under the active key) is never
 *       overwritten with an older plaintext. It runs in the job's transaction and never commits.</li>
 * </ul>
 */
public interface ReencryptionSource {

    /** One row of the store: its key (cursor), its encryption context, the stored value and the envelope inside it. */
    record Row(String key, String context, String stored, String envelope) {
    }

    /** Stable name of the store, at most 64 characters (part of the progress key). */
    String name();

    List<Row> page(Connection c, String tenantId, String afterKey, int limit);

    /** The stored value with {@code envelope} replaced by {@code newEnvelope}. */
    String withEnvelope(String stored, String newEnvelope);

    /** Compare-and-set write; true when the row was updated. */
    boolean replace(Connection c, String tenantId, String key, String expectedStored, String newStored);
}
