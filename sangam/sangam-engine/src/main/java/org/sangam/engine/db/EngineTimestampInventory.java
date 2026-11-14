// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Decisions 150 and 203, engine part: the Operaton 2.2.0 {@code ACT_*} timestamp columns, classified, in the CSV form
 * of the product inventory ({@code table,column,token,class,changeset}), for the operator-run UTC conversion tool
 * ({@code org.sangam.ops.utc.UtcInventory.productAndEngine()}, which reads {@link #RESOURCE} by name at run time,
 * because sangam-ops does not compile against sangam-engine).
 *
 * <p>Evidence: the list is every {@code timestamp} column of Operaton's own PostgreSQL create scripts
 * ({@code org/operaton/bpm/engine/db/create/activiti.postgres.create.*.sql} in operaton-engine 2.2.0; the same 66
 * columns are {@code datetime2} on SQL Server, {@code TIMESTAMP(6)} on Oracle, {@code timestamp} on H2), kept equal by
 * {@code EngineTimestampInventoryTest}. Every one is written by the engine from {@code ClockUtil} through
 * {@code java.util.Date} (an instant in the JVM zone at write time), so each is {@code EVENT_INSTANT}. Not listed,
 * because they are zone-free: date and date-time process variables (stored as epoch milliseconds in {@code LONG_}),
 * {@code ACT_RU_METER_LOG.MILLISECONDS_}. MySQL and MariaDB declare five of the columns as {@code timestamp}, which
 * the server itself converts by the session time zone (ACT_HI_IDENTITYLINK.TIMESTAMP_, ACT_HI_OP_LOG.TIMESTAMP_,
 * ACT_HI_INCIDENT.CREATE_TIME_ and END_TIME_, ACT_HI_EXT_TASK_LOG.TIMESTAMP_): the tool must treat those per dialect.
 *
 * <p>Before an apply the operator stops every job executor and external-task worker of the engine (no timer may fire
 * on partially converted rows; decision 150).
 */
public final class EngineTimestampInventory {

    /** Classpath resource in sangam-engine. */
    public static final String RESOURCE = "db/engine-timestamp-inventory.csv";

    /** The MySQL/MariaDB {@code timestamp}-typed columns among them (server-side session-zone conversion). */
    public static final java.util.Set<String> MYSQL_SESSION_ZONED = java.util.Set.of("ACT_HI_IDENTITYLINK.TIMESTAMP_",
            "ACT_HI_OP_LOG.TIMESTAMP_", "ACT_HI_INCIDENT.CREATE_TIME_", "ACT_HI_INCIDENT.END_TIME_",
            "ACT_HI_EXT_TASK_LOG.TIMESTAMP_");

    private EngineTimestampInventory() {
    }

    /** The CSV text; fails when the resource is missing (no ACT_* column is convertible without it). */
    public static String csv() {
        try (InputStream in = EngineTimestampInventory.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }
}
