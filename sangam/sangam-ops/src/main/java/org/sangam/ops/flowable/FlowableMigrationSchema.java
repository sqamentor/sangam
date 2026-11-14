// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.sangam.core.layer.Dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Portable DDL of the M-135 migration tables, for module tests. The product changelog gets the same shapes through
 * {@code sangam-persistence/tools/generate_changelogs.py} (generator owner W4-G); this class must stay equal to it.
 *
 * <ul>
 *   <li>{@code OPS_FMIG_RUN}: one row per migration run, its state machine position ({@code version} guards every
 *       transition), mapping and counts.</li>
 *   <li>{@code OPS_FMIG_ITEM}: one row per source instance of a run: classification, plan, fingerprint, the target
 *       instance once applied, reconciliation result.</li>
 *   <li>{@code OPS_FMIG_ACK} (changeset 0045-07, decision 231): one operator acknowledgement of a source process
 *       definition's model listeners per tenant, source system and definition id.</li>
 * </ul>
 */
public final class FlowableMigrationSchema {

    private FlowableMigrationSchema() {
    }

    public static final List<String> TEMPLATES = List.of(
            """
            CREATE TABLE OPS_FMIG_RUN (
              run_id         {str64} NOT NULL,
              tenant_id      {str64} NOT NULL,
              source_system  {str64} NOT NULL,
              source_tenant  {str64},
              source_zone    {str64} NOT NULL,
              state          {str64} NOT NULL,
              mapping_json   {text} NOT NULL,
              report_json    {text},
              planned        {long} DEFAULT 0 NOT NULL,
              rejected       {long} DEFAULT 0 NOT NULL,
              applied        {long} DEFAULT 0 NOT NULL,
              failed         {long} DEFAULT 0 NOT NULL,
              matched        {long} DEFAULT 0 NOT NULL,
              mismatched     {long} DEFAULT 0 NOT NULL,
              version        {long} NOT NULL,
              created_by     {str} NOT NULL,
              created_at     {ts} NOT NULL,
              updated_at     {ts} NOT NULL,
              PRIMARY KEY (run_id)
            )""",
            "CREATE INDEX OPS_FMIG_RUN_TENANT ON OPS_FMIG_RUN (tenant_id, state)",
            """
            CREATE TABLE OPS_FMIG_ITEM (
              run_id              {str64} NOT NULL,
              source_instance_id  {str64} NOT NULL,
              tenant_id           {str64} NOT NULL,
              definition_key      {str},
              status              {str64} NOT NULL,
              fingerprint         {str64} NOT NULL,
              plan_json           {text} NOT NULL,
              target_instance_id  {str64},
              attempts            {int} DEFAULT 0 NOT NULL,
              error               {text},
              updated_at          {ts} NOT NULL,
              PRIMARY KEY (run_id, source_instance_id)
            )""",
            "CREATE INDEX OPS_FMIG_ITEM_STATUS ON OPS_FMIG_ITEM (run_id, status)",
            "CREATE INDEX OPS_FMIG_ITEM_SOURCE ON OPS_FMIG_ITEM (source_instance_id, status)",
            "CREATE INDEX OPS_FMIG_ITEM_TARGET ON OPS_FMIG_ITEM (target_instance_id)",
            """
            CREATE TABLE OPS_FMIG_ACK (
              tenant_id           {str64} NOT NULL,
              source_system       {str64} NOT NULL,
              definition_id       {str64} NOT NULL,
              definition_key      {str} NOT NULL,
              definition_version  {int} NOT NULL,
              listeners_sha256    {str64} NOT NULL,
              listeners_json      {text} NOT NULL,
              reason              {text} NOT NULL,
              acknowledged_by     {str} NOT NULL,
              acknowledged_at     {ts} NOT NULL,
              PRIMARY KEY (tenant_id, source_system, definition_id)
            )""");

    public static void create(Connection c, Dialect d) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String t : TEMPLATES) {
                String sql = d.expand(t);
                s.execute(sql.startsWith("CREATE TABLE") ? sql + d.createTableSuffix() : sql);
            }
        }
    }
}
