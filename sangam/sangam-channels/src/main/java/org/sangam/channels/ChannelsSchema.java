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
              body            {text},
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
            // the channel inbox; the media_* columns hold a webhook message's pending media (messenger.InboundMediaQueue)
            """
            CREATE TABLE CH_INBOUND_SEEN (
              tenant_id     {str64} NOT NULL,
              channel       {str} NOT NULL,
              message_id    {str} NOT NULL,
              seen_at       {ts} NOT NULL,
              external_id   {str},
              principal_id  {str},
              media_ref     {text},
              media_state   {str64},
              media_attempt {int},
              media_next_at {ts},
              media_owner   {str},
              media_epoch   {long},
              media_doc     {text},
              media_error   {text},
              PRIMARY KEY (tenant_id, channel, message_id)
            )""",
            "CREATE INDEX CH_INBOUND_SEEN_MEDIA ON CH_INBOUND_SEEN (media_state, media_next_at)",
            """
            CREATE TABLE CH_POLL_CURSOR (
              tenant_id  {str64} NOT NULL,
              channel    {str} NOT NULL,
              cursor_val {str} NOT NULL,
              updated_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, channel)
            )""",
            // NG-12 search index: the distinct lower-cased trigrams of each message body as UTF-8 hex (ConversationService.search)
            """
            CREATE TABLE CH_MESSAGE_GRAM (
              message_id {str64} NOT NULL,
              tenant_id  {str64} NOT NULL,
              gram       {str64} NOT NULL,
              PRIMARY KEY (message_id, gram)
            )""",
            "CREATE INDEX CH_MESSAGE_GRAM_LOOKUP ON CH_MESSAGE_GRAM (tenant_id, gram, message_id)");

    public static void create(Connection c, Dialect d) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String t : TEMPLATES) {
                String sql = d.expand(t);
                s.execute(sql.startsWith("CREATE TABLE") ? sql + d.createTableSuffix() : sql);
            }
        }
    }
}
