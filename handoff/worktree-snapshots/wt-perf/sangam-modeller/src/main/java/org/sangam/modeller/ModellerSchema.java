// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller;

import org.sangam.core.layer.Dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Portable DDL of the modeller tables. The integrator copies it into the sangam-persistence changelogs, per dialect. */
public final class ModellerSchema {

    private ModellerSchema() {
    }

    public static final List<String> TEMPLATES = List.of(
            """
            CREATE TABLE MOD_EDIT_LEASE (
              tenant_id    {str64} NOT NULL,
              file_id      {str} NOT NULL,
              holder       {str} NOT NULL,
              fence        {long} NOT NULL,
              expires_at   {ts} NOT NULL,
              requested_by {str},
              PRIMARY KEY (tenant_id, file_id)
            )""",
            """
            CREATE TABLE MOD_TAKEOVER_DRAFT (
              id          {identity} PRIMARY KEY,
              tenant_id   {str64} NOT NULL,
              file_id     {str} NOT NULL,
              former      {str} NOT NULL,
              taken_by    {str} NOT NULL,
              content     {text},
              created_at  {ts} NOT NULL
            )""",
            """
            CREATE TABLE MOD_TEMPLATE (
              tenant_id    {str64} NOT NULL,
              template_id  {str} NOT NULL,
              version      {int} NOT NULL,
              kind         {str64} NOT NULL,
              document     {text} NOT NULL,
              created_by   {str} NOT NULL,
              created_at   {ts} NOT NULL,
              PRIMARY KEY (tenant_id, template_id, version)
            )""",
            """
            CREATE TABLE MOD_ACTION (
              tenant_id  {str64} NOT NULL,
              action_key {str} NOT NULL,
              version    {int} NOT NULL,
              document   {text} NOT NULL,
              active     {small} NOT NULL,
              created_by {str} NOT NULL,
              created_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, action_key, version)
            )""",
            """
            CREATE TABLE MOD_ACTION_RUN (
              operation_id {str64} NOT NULL PRIMARY KEY,
              tenant_id    {str64} NOT NULL,
              action_key   {str} NOT NULL,
              version      {int} NOT NULL,
              target_ref   {str} NOT NULL,
              actor        {str} NOT NULL,
              result_json  {text},
              created_at   {ts} NOT NULL
            )""",
            """
            CREATE TABLE MOD_TEST_RUN (
              run_id     {str64} NOT NULL PRIMARY KEY,
              tenant_id  {str64} NOT NULL,
              package_id {str} NOT NULL,
              report     {text} NOT NULL,
              passed     {int} NOT NULL,
              failed     {int} NOT NULL,
              not_judged {int} NOT NULL,
              created_by {str} NOT NULL,
              created_at {ts} NOT NULL
            )""",
            """
            CREATE TABLE MOD_SERVICE_MOCK (
              tenant_id       {str64} NOT NULL,
              service         {str} NOT NULL,
              operation       {str} NOT NULL,
              when_input_json {text},
              output_json     {text},
              error_code      {str64},
              delay_ms        {long},
              enabled         {small} NOT NULL,
              created_by      {str} NOT NULL,
              created_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, service, operation)
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
