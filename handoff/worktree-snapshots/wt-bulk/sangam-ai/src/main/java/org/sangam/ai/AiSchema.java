// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai;

import org.sangam.core.layer.Dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Portable DDL of the AI tables. The integrator copies it into the sangam-persistence changelogs, per dialect. */
public final class AiSchema {

    private AiSchema() {
    }

    public static final List<String> TEMPLATES = List.of(
            """
            CREATE TABLE AI_AGENT_RUN (
              run_id           {str64} NOT NULL PRIMARY KEY,
              tenant_id        {str64} NOT NULL,
              agent_key        {str} NOT NULL,
              agent_version    {int} NOT NULL,
              state            {str64} NOT NULL,
              lock_version     {long} NOT NULL,
              lease_owner      {str},
              lease_expires_at {ts},
              parent_run_id    {str64},
              depth            {int} NOT NULL,
              steps_used       {int} NOT NULL,
              tokens_used      {long} NOT NULL,
              root_id          {str64} NOT NULL,
              business_key     {str},
              principal_id     {str} NOT NULL,
              definition_json  {text} NOT NULL,
              input_text       {text} NOT NULL,
              result_text      {text},
              created_at       {ts} NOT NULL,
              updated_at       {ts} NOT NULL
            )""",
            "CREATE INDEX AI_AGENT_RUN_STATE ON AI_AGENT_RUN (state, lease_expires_at)",
            """
            CREATE TABLE AI_AGENT_STEP (
              run_id       {str64} NOT NULL,
              step_no      {int} NOT NULL,
              kind         {str64} NOT NULL,
              payload_json {text} NOT NULL,
              created_at   {ts} NOT NULL,
              PRIMARY KEY (run_id, step_no)
            )""",
            """
            CREATE TABLE AI_AGENT_APPROVAL (
              approval_id  {str64} NOT NULL PRIMARY KEY,
              tenant_id    {str64} NOT NULL,
              run_id       {str64} NOT NULL,
              tool_call_id {str} NOT NULL,
              tool_name    {str} NOT NULL,
              args_json    {text} NOT NULL,
              args_hash    {str64} NOT NULL,
              state        {str64} NOT NULL,
              requested_by {str} NOT NULL,
              decided_by   {str},
              reason       {text},
              created_at   {ts} NOT NULL,
              decided_at   {ts}
            )""",
            "CREATE UNIQUE INDEX AI_AGENT_APPROVAL_CALL ON AI_AGENT_APPROVAL (run_id, tool_call_id)",
            """
            CREATE TABLE AI_KB_VERSION (
              tenant_id {str64} NOT NULL,
              kb_key    {str} NOT NULL,
              version   {int} NOT NULL,
              state     {str64} NOT NULL,
              model     {str} NOT NULL,
              created_at {ts} NOT NULL,
              metadata_json {text},
              retire_reason {text},
              retired_by {str},
              retired_at {ts},
              PRIMARY KEY (tenant_id, kb_key, version)
            )""",
            """
            CREATE TABLE AI_KB_CHUNK (
              tenant_id   {str64} NOT NULL,
              kb_key      {str} NOT NULL,
              version     {int} NOT NULL,
              id          {str} NOT NULL,
              document_id {str} NOT NULL,
              doc_version {str64},
              seq         {int} NOT NULL,
              text_value  {text} NOT NULL,
              embedding   {blob} NOT NULL,
              PRIMARY KEY (tenant_id, kb_key, version, id)
            )""",
            """
            CREATE TABLE AI_DRAFT (
              draft_id     {str64} NOT NULL PRIMARY KEY,
              tenant_id    {str64} NOT NULL,
              kind         {str64} NOT NULL,
              state        {str64} NOT NULL,
              content      {text} NOT NULL,
              validation   {text} NOT NULL,
              prompt       {text} NOT NULL,
              prompt_hash  {str64} NOT NULL,
              content_hash {str64} NOT NULL,
              model        {str} NOT NULL,
              created_by   {str} NOT NULL,
              reviewed_by  {str},
              review_note  {text},
              created_at   {ts} NOT NULL,
              reviewed_at  {ts},
              package_id   {str}
            )""",
            "CREATE INDEX AI_DRAFT_STATE ON AI_DRAFT (tenant_id, state)",
            """
            CREATE TABLE AI_CLASSIFICATION (
              id               {str64} NOT NULL PRIMARY KEY,
              tenant_id        {str64} NOT NULL,
              template_key     {str} NOT NULL,
              template_version {int} NOT NULL,
              source_ref       {str},
              label            {str},
              confidence       {str64},
              fields_json      {text} NOT NULL,
              route            {str64} NOT NULL,
              model            {str} NOT NULL,
              corrected_label  {str},
              corrected_by     {str},
              created_at       {ts} NOT NULL,
              status           {str64} DEFAULT 'PENDING' NOT NULL,
              reviewed_by      {str},
              reviewed_at      {ts}
            )""",
            "CREATE INDEX AI_CLASSIFICATION_TPL ON AI_CLASSIFICATION (tenant_id, template_key, template_version)");

    public static void create(Connection c, Dialect d) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String t : TEMPLATES) {
                String sql = d.expand(t);
                s.execute(sql.startsWith("CREATE TABLE") ? sql + d.createTableSuffix() : sql);
            }
        }
    }
}
