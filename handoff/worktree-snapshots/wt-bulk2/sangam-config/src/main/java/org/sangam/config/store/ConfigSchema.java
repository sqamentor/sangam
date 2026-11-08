// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.store;

import org.sangam.core.layer.Dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * DDL of the configuration tables, portable with {@link Dialect} type tokens (the integrator copies it into the
 * Liquibase changelogs of sangam-persistence, one changeSet per statement, per dialect).
 *
 * <ul>
 *   <li>{@code CFG_PROFILE}: profile documents by (id, version) with status, layer, dates and the canonical JSON;</li>
 *   <li>{@code CFG_CHANGE}: append-only change register;</li>
 *   <li>{@code CFG_MASTER_CODE}: master codes by (scope, scheme, segment, parent, code, valid_from);</li>
 *   <li>{@code CFG_SERIES}: number-series counters by (scope, series, unit, period), allocated under a row lock;</li>
 *   <li>{@code CFG_CASE_BINDING}: the profile binding stored per case (root id → binding) for audit.</li>
 * </ul>
 */
public final class ConfigSchema {

    private ConfigSchema() {
    }

    public static final List<String> TEMPLATES = List.of(
            """
            CREATE TABLE CFG_PROFILE (
              profile_id     {str} NOT NULL,
              version        {str64} NOT NULL,
              layer_id       {str64} NOT NULL,
              status         {str64} NOT NULL,
              effective_from {str64},
              effective_to   {str64},
              content_hash   {str64} NOT NULL,
              document_json  {text} NOT NULL,
              updated_at     {ts} NOT NULL,
              PRIMARY KEY (profile_id, version)
            )""",
            "CREATE INDEX CFG_PROFILE_STATUS ON CFG_PROFILE (profile_id, status)",
            """
            CREATE TABLE CFG_CHANGE (
              change_id      {identity} PRIMARY KEY,
              profile_id     {str} NOT NULL,
              version        {str64} NOT NULL,
              action         {str64} NOT NULL,
              actor          {str},
              at_time        {ts} NOT NULL,
              content_hash   {str64},
              reason         {text}
            )""",
            "CREATE INDEX CFG_CHANGE_PROFILE ON CFG_CHANGE (profile_id, change_id)",
            """
            CREATE TABLE CFG_MASTER_CODE (
              scope_id       {str64} NOT NULL,
              scheme_id      {str64} NOT NULL,
              segment_id     {str64} NOT NULL,
              parent_code    {str64} NOT NULL,
              code           {str64} NOT NULL,
              valid_from     {str64} NOT NULL,
              valid_to       {str64},
              name_json      {text},
              flags_json     {text},
              source_ref     {str},
              PRIMARY KEY (scope_id, scheme_id, segment_id, parent_code, code, valid_from)
            )""",
            "CREATE INDEX CFG_MASTER_LIST ON CFG_MASTER_CODE (scope_id, scheme_id, segment_id)",
            """
            CREATE TABLE CFG_SERIES (
              scope_id       {str64} NOT NULL,
              series_id      {str64} NOT NULL,
              unit_id        {str64} NOT NULL,
              period         {str64} NOT NULL,
              last_no     {long} NOT NULL,
              PRIMARY KEY (scope_id, series_id, unit_id, period)
            )""",
            """
            CREATE TABLE CFG_CASE_BINDING (
              tenant_id      {str64} NOT NULL,
              root_id        {str64} NOT NULL,
              binding        {text} NOT NULL,
              bound_at       {ts} NOT NULL,
              PRIMARY KEY (tenant_id, root_id)
            )""");

    public static List<String> ddl(Dialect d) {
        return TEMPLATES.stream().map(t -> d.expand(t) + (t.startsWith("CREATE TABLE") ? d.createTableSuffix() : "")).toList();
    }

    public static void create(Connection c, Dialect d) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String sql : ddl(d)) {
                s.execute(sql);
            }
        }
    }
}
