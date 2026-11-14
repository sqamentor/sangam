// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import org.sangam.core.layer.Hashes;

/**
 * One dataset of decision 150: the rows of one table (optionally one tenant's) whose timestamp columns were written as
 * source-zone wall clocks before a cutover. The operator supplies the source zone and the cutover per dataset (decision
 * 203); mixed data is split into several datasets, never covered by one assumed zone.
 *
 * @param table            the table
 * @param columns          the {@link UtcInventory.ColumnClass#EVENT_INSTANT} columns to convert
 * @param provenanceColumn the column that tells when a row was written (often {@code created_at}); compared with the
 *                         cutover to tell pre-cutover rows from already-UTC rows
 * @param sourceZone       the named zone the writers used before the cutover (never a fixed offset)
 * @param cutover          the instant from which the writers stored UTC
 * @param tenantId         restricts the dataset to rows with this {@code tenant_id}, or null for every row
 * @param sessionZone      owner decision 240: the historical MySQL/MariaDB session time zone under which the
 *                         session-zoned engine columns of the table were written (named zone or whole-minute offset), or
 *                         null; those columns are refused on MySQL without it
 */
public record UtcDataset(String table, List<String> columns, String provenanceColumn, ZoneId sourceZone,
                         Instant cutover, String tenantId, ZoneId sessionZone) {

    /** A dataset without a declared session zone. */
    public UtcDataset(String table, List<String> columns, String provenanceColumn, ZoneId sourceZone, Instant cutover,
                      String tenantId) {
        this(table, columns, provenanceColumn, sourceZone, cutover, tenantId, null);
    }

    public UtcDataset {
        table = UtcInventory.identifier(table, "table");
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("at least one column to convert is required");
        }
        List<String> cols = new ArrayList<>();
        for (String c : new LinkedHashSet<>(columns)) {
            cols.add(UtcInventory.identifier(c, "column"));
        }
        if (cols.size() != columns.size()) {
            throw new IllegalArgumentException("a column is listed twice: " + columns);
        }
        columns = List.copyOf(cols);
        provenanceColumn = UtcInventory.identifier(provenanceColumn, "provenance column");
        WallClock.requireNamedZone(sourceZone);
        Objects.requireNonNull(cutover, "cutover");
        if (tenantId != null && tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is null (every row) or a tenant id, never blank");
        }
        SessionZones.requireDeclarable(sessionZone);
    }

    /** SHA-256 over every field: a run's apply is refused when its dataset no longer matches its dry run. */
    public String fingerprint() {
        String canonical = String.join("\u0000", table, String.join(",", columns), provenanceColumn, sourceZone.getId(),
                cutover.toString(), tenantId == null ? "" : tenantId, rulesVersion(sourceZone))
                // decision 240: only a declared session zone enters, so earlier runs keep their fingerprint
                + (sessionZone == null ? "" : "\u0000session=" + SessionZones.text(sessionZone) + "\u0000"
                + rulesVersion(sessionZone));
        return Hashes.hex(Hashes.sha256(canonical));
    }

    /** The time-zone database version that supplies the zone's rules (a changed tzdata changes the fingerprint). */
    public static String rulesVersion(ZoneId zone) {
        try {
            return ZoneRulesProvider.getVersions(zone.getId()).lastKey();
        } catch (RuntimeException e) {
            return "fixed";
        }
    }
}
