// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels;

import org.sangam.core.layer.Dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Portable DDL of the channel tables. The integrator copies it into the sangam-persistence changelogs, per dialect. */
public final class ChannelsSchema {

    private ChannelsSchema() {
    }

    public static final List<String> TEMPLATES = List.of(
            """
            CREATE TABLE CH_CONVERSATION (
              id              {str64} NOT NULL PRIMARY KEY,
              tenant_id       {str64} NOT NULL,
              title           {str},
              case_ref        {str},
              retention_class {str64} NOT NULL,
              created_by      {str} NOT NULL,
              created_at      {ts} NOT NULL
            )""",
            "CREATE INDEX CH_CONVERSATION_CASE ON CH_CONVERSATION (tenant_id, case_ref)",
            """
            CREATE TABLE CH_PARTICIPANT (
              conversation_id {str64} NOT NULL,
              principal_id    {str} NOT NULL,
              added_by        {str} NOT NULL,
              added_at        {ts} NOT NULL,
              PRIMARY KEY (conversation_id, principal_id)
            )""",
            """
            CREATE TABLE CH_MESSAGE (
              id              {str64} NOT NULL PRIMARY KEY,
              tenant_id       {str64} NOT NULL,
              conversation_id {str64} NOT NULL,
              sender          {str} NOT NULL,
              body            {text} NOT NULL,
              attachments     {text} NOT NULL,
              client_key      {str},
              created_at      {ts} NOT NULL
            )""",
            "CREATE INDEX CH_MESSAGE_CONV ON CH_MESSAGE (conversation_id, created_at)",
            "CREATE UNIQUE INDEX CH_MESSAGE_CLIENT ON CH_MESSAGE (conversation_id, sender, client_key)",
            """
            CREATE TABLE CH_OPT_IN (
              tenant_id    {str64} NOT NULL,
              channel      {str64} NOT NULL,
              external_id  {str} NOT NULL,
              principal_id {str} NOT NULL,
              state        {str64} NOT NULL,
              evidence     {text},
              updated_at   {ts} NOT NULL,
              PRIMARY KEY (tenant_id, channel, external_id)
            )""",
            """
            CREATE TABLE CH_INBOUND_SEEN (
              tenant_id  {str64} NOT NULL,
              channel    {str} NOT NULL,
              message_id {str} NOT NULL,
              seen_at    {ts} NOT NULL,
              PRIMARY KEY (tenant_id, channel, message_id)
            )""",
            """
            CREATE TABLE CH_POLL_CURSOR (
              tenant_id  {str64} NOT NULL,
              channel    {str} NOT NULL,
              cursor_val {str} NOT NULL,
              updated_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, channel)
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
