// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.sql.Connection;
import java.util.List;

import org.sangam.core.layer.Jdbc;

/**
 * The DDL of {@code sangam-handoff/W2-JOB-SIDE-TABLES-CONTRACT.md} §1–§3, verbatim (H2 / PostgreSQL types), for the H2
 * tests until W2-E's changelogs create the tables. Keep it equal to the contract.
 */
final class JobSideTablesDdl {

    static final List<String> DDL = List.of(
            "CREATE TABLE EXEC_JOB_SLOT (job_id VARCHAR(64) NOT NULL, tenant_id VARCHAR(64) NOT NULL, "
                    + "root_id VARCHAR(64) NOT NULL, slot INTEGER NOT NULL, owner_partition INTEGER, owner_epoch BIGINT, "
                    + "recoveries INTEGER NOT NULL, created_at_ms BIGINT NOT NULL, "
                    + "CONSTRAINT PK_EXEC_JOB_SLOT PRIMARY KEY (job_id))",
            "CREATE INDEX EXEC_JOB_SLOT_SLOT ON EXEC_JOB_SLOT (slot)",
            "CREATE INDEX EXEC_JOB_SLOT_OWNER ON EXEC_JOB_SLOT (owner_partition, owner_epoch)",
            "CREATE TABLE JOB_TRACE_CONTEXT (job_id VARCHAR(64) NOT NULL, kind VARCHAR(16) NOT NULL, "
                    + "traceparent VARCHAR(55) NOT NULL, tracestate VARCHAR(512), created_at_ms BIGINT NOT NULL, "
                    + "CONSTRAINT PK_JOB_TRACE_CONTEXT PRIMARY KEY (job_id))",
            "CREATE TABLE EXEC_PARTITION (partition_id INTEGER NOT NULL, owner_node VARCHAR(255), "
                    + "owner_incarnation VARCHAR(36), owner_epoch BIGINT NOT NULL, lease_expires_at TIMESTAMP, "
                    + "state VARCHAR(16) NOT NULL, drain_deadline TIMESTAMP, map_epoch BIGINT NOT NULL, "
                    + "CONSTRAINT PK_EXEC_PARTITION PRIMARY KEY (partition_id))",
            "CREATE TABLE EXEC_SLOT (slot INTEGER NOT NULL, partition_id INTEGER NOT NULL, state VARCHAR(16) NOT NULL, "
                    + "from_partition INTEGER, move_started_at TIMESTAMP, map_epoch BIGINT NOT NULL, "
                    + "CONSTRAINT PK_EXEC_SLOT PRIMARY KEY (slot))",
            "CREATE INDEX EXEC_SLOT_PARTITION ON EXEC_SLOT (partition_id, state)");

    private JobSideTablesDdl() {
    }

    static void create(Connection c) {
        DDL.forEach(sql -> Jdbc.update(c, sql));
    }
}
