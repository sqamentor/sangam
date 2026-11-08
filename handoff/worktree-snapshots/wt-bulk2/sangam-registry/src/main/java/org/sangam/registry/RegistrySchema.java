// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * DDL of the registry module (M4), portable with {@link Dialect} type tokens; the integrator copies it into the
 * Liquibase changelogs of sangam-persistence. Every table is tenant-scoped. Booleans are {@code SMALLINT} 0/1, amounts
 * and meter values {@code NUMERIC(24,6)}, calendar dates ISO-8601 strings ({@code {str64}}), instants {@code {ts}}.
 *
 * <p>Areas:
 * <ul>
 *   <li><b>Kernel</b>: {@code REG_OBJECT_TYPE} (configured type definitions, versioned), {@code REG_OBJECT} (the current
 *       state of every registered object, any kind), {@code REG_OBJECT_VERSION} (append-only version history).</li>
 *   <li><b>Numbering</b>: {@code REG_NUMBER_SERIES} (series definitions), {@code REG_NUMBER_COUNTER} (one counter row per
 *       series, scope and period: the gap-free lock row, or the block high-water mark for unique-only series),
 *       {@code REG_NUMBER_ALLOCATION} (every gap-free number with its use or void reason).</li>
 *   <li><b>Register books</b>: {@code REG_REGISTER_DEF}, {@code REG_REGISTER_BOOK} (one book per register, scope and
 *       period), {@code REG_REGISTER_ENTRY} (append-only, hash-chained), {@code REG_CERTIFIED_EXTRACT}.</li>
 *   <li><b>Instruments</b>: {@code REG_VERIFICATION} (hashed verification codes), {@code REG_ENDORSEMENT},
 *       {@code REG_INSTRUMENT_DOC} (generated and published documents).</li>
 *   <li><b>Parties</b>: {@code REG_PARTY}, {@code REG_PARTY_IDENTIFIER}, {@code REG_PARTY_RELATIONSHIP},
 *       {@code REG_PARTY_ADDRESS}, {@code REG_PARTY_BLOCK} (matching blocking keys), {@code REG_MATCH_CANDIDATE},
 *       {@code REG_MERGE_EVENT} (merge with the before images that unmerge restores), {@code REG_PARTY_SNAPSHOT},
 *       {@code REG_PARTY_CONFIG} (identifier types and match configuration saved by administrators).</li>
 *   <li><b>Assets</b>: {@code REG_ASSET_CLASS}, {@code REG_ASSET}, {@code REG_ASSET_MOVEMENT}, {@code REG_METER},
 *       {@code REG_METER_READING}, {@code REG_MAINT_PLAN}, {@code REG_WORK_ORDER}.</li>
 *   <li><b>Custody</b>: {@code REG_CUSTODY_ITEM}, {@code REG_CUSTODY_EVENT} (hash-chained per item),
 *       {@code REG_CUSTODY_TRANSFER}, {@code REG_DISPOSAL_ORDER}.</li>
 * </ul>
 * Unique keys never include a nullable column (SQL Server allows one NULL per unique index). The audit chain itself
 * ({@code AUDIT_EVENT}, {@code AUDIT_PAYLOAD}, {@code ROOT_SEQ}) is the core schema.
 */
public final class RegistrySchema {

    private RegistrySchema() {
    }

    public static final List<String> TEMPLATES = List.of(
            // ------------------------------------------------------------------------------------------- kernel
            """
            CREATE TABLE REG_OBJECT_TYPE (
              tenant_id       {str64} NOT NULL,
              type_key        {str64} NOT NULL,
              version         {int} NOT NULL,
              kind            {str64} NOT NULL,
              status          {str64} NOT NULL,
              definition_json {text} NOT NULL,
              effective_from  {ts} NOT NULL,
              created_at      {ts} NOT NULL,
              created_by      {str} NOT NULL,
              PRIMARY KEY (tenant_id, type_key, version)
            )""",
            """
            CREATE TABLE REG_OBJECT (
              id               {str64} NOT NULL PRIMARY KEY,
              tenant_id        {str64} NOT NULL,
              type_key         {str64} NOT NULL,
              type_version     {int} NOT NULL,
              kind             {str64} NOT NULL,
              object_number    {str},
              display_name     {str},
              state            {str64} NOT NULL,
              state_reason     {str},
              state_until      {ts},
              version          {long} NOT NULL,
              valid_from       {ts},
              valid_to         {ts},
              renewal_due_at   {ts},
              renewal_count    {int} NOT NULL,
              holder_party_id  {str64},
              subject_ref      {str},
              parent_id        {str64},
              external_ref     {str},
              attrs_json       {text},
              created_at       {ts} NOT NULL,
              created_by       {str} NOT NULL,
              updated_at       {ts} NOT NULL,
              updated_by       {str} NOT NULL
            )""",
            "CREATE INDEX REG_OBJECT_TYPE_STATE ON REG_OBJECT (tenant_id, type_key, state)",
            "CREATE INDEX REG_OBJECT_NUMBER ON REG_OBJECT (tenant_id, object_number)",
            "CREATE INDEX REG_OBJECT_VALID_TO ON REG_OBJECT (tenant_id, valid_to)",
            "CREATE INDEX REG_OBJECT_RENEWAL ON REG_OBJECT (tenant_id, renewal_due_at)",
            "CREATE INDEX REG_OBJECT_HOLDER ON REG_OBJECT (tenant_id, holder_party_id)",
            "CREATE INDEX REG_OBJECT_PARENT ON REG_OBJECT (tenant_id, parent_id)",
            """
            CREATE TABLE REG_OBJECT_VERSION (
              object_id       {str64} NOT NULL,
              version         {long} NOT NULL,
              tenant_id       {str64} NOT NULL,
              change_type     {str64} NOT NULL,
              state           {str64} NOT NULL,
              reason          {str},
              valid_from      {ts},
              valid_to        {ts},
              holder_party_id {str64},
              attrs_json      {text},
              audit_event_id  {str64},
              changed_at      {ts} NOT NULL,
              changed_by      {str} NOT NULL,
              PRIMARY KEY (object_id, version)
            )""",
            // ---------------------------------------------------------------------------------------- numbering
            """
            CREATE TABLE REG_NUMBER_SERIES (
              tenant_id       {str64} NOT NULL,
              series_key      {str64} NOT NULL,
              version         {int} NOT NULL,
              definition_json {text} NOT NULL,
              updated_at      {ts} NOT NULL,
              updated_by      {str} NOT NULL,
              PRIMARY KEY (tenant_id, series_key)
            )""",
            """
            CREATE TABLE REG_NUMBER_COUNTER (
              tenant_id   {str64} NOT NULL,
              series_key  {str64} NOT NULL,
              scope_key   {str} NOT NULL,
              period_key  {str64} NOT NULL,
              next_value  {long} NOT NULL,
              closed_flag {small} NOT NULL,
              updated_at  {ts} NOT NULL,
              PRIMARY KEY (tenant_id, series_key, scope_key, period_key)
            )""",
            """
            CREATE TABLE REG_NUMBER_ALLOCATION (
              tenant_id    {str64} NOT NULL,
              series_key   {str64} NOT NULL,
              scope_key    {str} NOT NULL,
              period_key   {str64} NOT NULL,
              seq_value    {long} NOT NULL,
              formatted    {str} NOT NULL,
              status       {str64} NOT NULL,
              ref_type     {str64},
              ref_id       {str},
              allocated_at {ts} NOT NULL,
              allocated_by {str} NOT NULL,
              void_reason  {str},
              voided_by    {str},
              PRIMARY KEY (tenant_id, series_key, scope_key, period_key, seq_value)
            )""",
            "CREATE UNIQUE INDEX REG_NUMBER_ALLOC_FMT ON REG_NUMBER_ALLOCATION (tenant_id, series_key, formatted)",
            // ----------------------------------------------------------------------------------- register books
            """
            CREATE TABLE REG_REGISTER_DEF (
              tenant_id       {str64} NOT NULL,
              register_key    {str64} NOT NULL,
              version         {int} NOT NULL,
              definition_json {text} NOT NULL,
              updated_at      {ts} NOT NULL,
              updated_by      {str} NOT NULL,
              PRIMARY KEY (tenant_id, register_key)
            )""",
            """
            CREATE TABLE REG_REGISTER_BOOK (
              id            {str64} NOT NULL PRIMARY KEY,
              tenant_id     {str64} NOT NULL,
              register_key  {str64} NOT NULL,
              scope_key     {str} NOT NULL,
              period_key    {str64} NOT NULL,
              status        {str64} NOT NULL,
              entry_count   {long} NOT NULL,
              head_hash     {bytes32},
              opened_at     {ts} NOT NULL,
              closed_at     {ts},
              closed_by     {str},
              close_digest  {bytes32}
            )""",
            "CREATE UNIQUE INDEX REG_REGISTER_BOOK_KEY ON REG_REGISTER_BOOK (tenant_id, register_key, scope_key, period_key)",
            """
            CREATE TABLE REG_REGISTER_ENTRY (
              book_id           {str64} NOT NULL,
              entry_no          {long} NOT NULL,
              tenant_id         {str64} NOT NULL,
              entry_number      {str} NOT NULL,
              entry_type        {str64} NOT NULL,
              corrects_entry_no {long},
              columns_json      {text} NOT NULL,
              ref_type          {str64},
              ref_id            {str},
              made_by           {str} NOT NULL,
              countersigned_by  {str},
              recorded_at       {ts} NOT NULL,
              prev_hash         {bytes32} NOT NULL,
              entry_hash        {bytes32} NOT NULL,
              audit_event_id    {str64} NOT NULL,
              PRIMARY KEY (book_id, entry_no)
            )""",
            "CREATE INDEX REG_REGISTER_ENTRY_REF ON REG_REGISTER_ENTRY (tenant_id, ref_type, ref_id)",
            """
            CREATE TABLE REG_CERTIFIED_EXTRACT (
              id           {str64} NOT NULL PRIMARY KEY,
              tenant_id    {str64} NOT NULL,
              book_id      {str64} NOT NULL,
              from_entry   {long} NOT NULL,
              to_entry     {long} NOT NULL,
              purpose      {str},
              payload_json {text} NOT NULL,
              digest       {bytes32} NOT NULL,
              sig_alg      {str64} NOT NULL,
              key_id       {str} NOT NULL,
              signature    {blob} NOT NULL,
              document_ref {str},
              issued_at    {ts} NOT NULL,
              issued_by    {str} NOT NULL
            )""",
            // -------------------------------------------------------------------------------------- instruments
            """
            CREATE TABLE REG_VERIFICATION (
              code_hash     {str64} NOT NULL PRIMARY KEY,
              tenant_id     {str64} NOT NULL,
              target_kind   {str64} NOT NULL,
              target_id     {str64} NOT NULL,
              status        {str64} NOT NULL,
              issued_at     {ts} NOT NULL,
              superseded_at {ts}
            )""",
            "CREATE INDEX REG_VERIFICATION_TARGET ON REG_VERIFICATION (tenant_id, target_id)",
            """
            CREATE TABLE REG_ENDORSEMENT (
              object_id        {str64} NOT NULL,
              seq              {int} NOT NULL,
              tenant_id        {str64} NOT NULL,
              endorsement_type {str64} NOT NULL,
              content_json     {text} NOT NULL,
              made_at          {ts} NOT NULL,
              made_by          {str} NOT NULL,
              PRIMARY KEY (object_id, seq)
            )""",
            """
            CREATE TABLE REG_INSTRUMENT_DOC (
              object_id     {str64} NOT NULL,
              doc_seq       {int} NOT NULL,
              tenant_id     {str64} NOT NULL,
              purpose       {str64} NOT NULL,
              template_key  {str},
              document_ref  {str} NOT NULL,
              digest_hex    {str64},
              published_ref {str},
              created_at    {ts} NOT NULL,
              PRIMARY KEY (object_id, doc_seq)
            )""",
            // ------------------------------------------------------------------------------------------ parties
            """
            CREATE TABLE REG_PARTY (
              id              {str64} NOT NULL PRIMARY KEY,
              tenant_id       {str64} NOT NULL,
              party_kind      {str64} NOT NULL,
              status          {str64} NOT NULL,
              merged_into     {str64},
              display_name    {str} NOT NULL,
              name_json       {text},
              birth_date      {str64},
              gender          {str64},
              attrs_json      {text},
              source          {str64},
              source_priority {int} NOT NULL,
              version         {long} NOT NULL,
              created_at      {ts} NOT NULL,
              created_by      {str} NOT NULL,
              updated_at      {ts} NOT NULL,
              updated_by      {str} NOT NULL
            )""",
            "CREATE INDEX REG_PARTY_STATUS ON REG_PARTY (tenant_id, status)",
            """
            CREATE TABLE REG_PARTY_IDENTIFIER (
              id               {str64} NOT NULL PRIMARY KEY,
              tenant_id        {str64} NOT NULL,
              party_id         {str64} NOT NULL,
              id_type          {str64} NOT NULL,
              value_normalized {str},
              value_hash       {str64} NOT NULL,
              value_masked     {str64},
              vault_ref        {str},
              issuer           {str},
              valid_from       {ts},
              valid_to         {ts},
              verified_flag    {small} NOT NULL,
              status           {str64} NOT NULL,
              moved_from_party {str64},
              created_at       {ts} NOT NULL
            )""",
            "CREATE INDEX REG_PARTY_ID_VALUE ON REG_PARTY_IDENTIFIER (tenant_id, id_type, value_hash)",
            "CREATE INDEX REG_PARTY_ID_PARTY ON REG_PARTY_IDENTIFIER (tenant_id, party_id)",
            """
            CREATE TABLE REG_PARTY_RELATIONSHIP (
              id          {str64} NOT NULL PRIMARY KEY,
              tenant_id   {str64} NOT NULL,
              from_party  {str64} NOT NULL,
              to_party    {str64} NOT NULL,
              rel_type    {str64} NOT NULL,
              status      {str64} NOT NULL,
              valid_from  {ts},
              valid_to    {ts},
              attrs_json  {text},
              created_at  {ts} NOT NULL
            )""",
            "CREATE INDEX REG_PARTY_REL_FROM ON REG_PARTY_RELATIONSHIP (tenant_id, from_party)",
            "CREATE INDEX REG_PARTY_REL_TO ON REG_PARTY_RELATIONSHIP (tenant_id, to_party)",
            """
            CREATE TABLE REG_PARTY_ADDRESS (
              id            {str64} NOT NULL PRIMARY KEY,
              tenant_id     {str64} NOT NULL,
              party_id      {str64} NOT NULL,
              address_type  {str64} NOT NULL,
              address_json  {text} NOT NULL,
              geometry_ref  {str},
              primary_flag  {small} NOT NULL,
              valid_from    {ts},
              valid_to      {ts},
              status        {str64} NOT NULL,
              created_at    {ts} NOT NULL
            )""",
            "CREATE INDEX REG_PARTY_ADDR_PARTY ON REG_PARTY_ADDRESS (tenant_id, party_id)",
            """
            CREATE TABLE REG_PARTY_BLOCK (
              tenant_id {str64} NOT NULL,
              block_key {str} NOT NULL,
              party_id  {str64} NOT NULL,
              PRIMARY KEY (tenant_id, block_key, party_id)
            )""",
            "CREATE INDEX REG_PARTY_BLOCK_PARTY ON REG_PARTY_BLOCK (tenant_id, party_id)",
            """
            CREATE TABLE REG_MATCH_CANDIDATE (
              id           {str64} NOT NULL PRIMARY KEY,
              tenant_id    {str64} NOT NULL,
              party_a      {str64} NOT NULL,
              party_b      {str64} NOT NULL,
              score        NUMERIC(12,6) NOT NULL,
              decision     {str64} NOT NULL,
              rule_key     {str64} NOT NULL,
              details_json {text},
              status       {str64} NOT NULL,
              created_at   {ts} NOT NULL,
              resolved_at  {ts},
              resolved_by  {str}
            )""",
            "CREATE UNIQUE INDEX REG_MATCH_PAIR ON REG_MATCH_CANDIDATE (tenant_id, party_a, party_b)",
            "CREATE INDEX REG_MATCH_STATUS ON REG_MATCH_CANDIDATE (tenant_id, status)",
            """
            CREATE TABLE REG_MERGE_EVENT (
              id                   {str64} NOT NULL PRIMARY KEY,
              tenant_id            {str64} NOT NULL,
              survivor_id          {str64} NOT NULL,
              merged_id            {str64} NOT NULL,
              status               {str64} NOT NULL,
              survivor_before_json {text} NOT NULL,
              merged_before_json   {text} NOT NULL,
              survivor_after_json  {text} NOT NULL,
              moved_json           {text} NOT NULL,
              reason               {str},
              candidate_id         {str64},
              merged_at            {ts} NOT NULL,
              merged_by            {str} NOT NULL,
              unmerged_at          {ts},
              unmerged_by          {str},
              unmerge_reason       {str}
            )""",
            "CREATE INDEX REG_MERGE_SURVIVOR ON REG_MERGE_EVENT (tenant_id, survivor_id)",
            "CREATE INDEX REG_MERGE_MERGED ON REG_MERGE_EVENT (tenant_id, merged_id)",
            """
            CREATE TABLE REG_PARTY_SNAPSHOT (
              id            {str64} NOT NULL PRIMARY KEY,
              tenant_id     {str64} NOT NULL,
              party_id      {str64} NOT NULL,
              reference     {str} NOT NULL,
              purpose       {str64},
              snapshot_json {text} NOT NULL,
              digest        {bytes32} NOT NULL,
              taken_at      {ts} NOT NULL,
              taken_by      {str} NOT NULL
            )""",
            "CREATE INDEX REG_PARTY_SNAPSHOT_REF ON REG_PARTY_SNAPSHOT (tenant_id, reference)",
            // party configuration saved by tenant administrators: identifier types (config_kind IDENTIFIER_TYPE,
            // config_key = type key) and the match configuration (config_kind MATCH, config_key '-'). Rows override
            // the pack seed; status RETIRED hides a pack-seeded type for the tenant.
            """
            CREATE TABLE REG_PARTY_CONFIG (
              tenant_id       {str64} NOT NULL,
              config_kind     {str64} NOT NULL,
              config_key      {str64} NOT NULL,
              version         {long} NOT NULL,
              status          {str64} NOT NULL,
              definition_json {text} NOT NULL,
              updated_at      {ts} NOT NULL,
              updated_by      {str} NOT NULL,
              PRIMARY KEY (tenant_id, config_kind, config_key)
            )""",
            // ------------------------------------------------------------------------------------------- assets
            """
            CREATE TABLE REG_ASSET_CLASS (
              tenant_id       {str64} NOT NULL,
              class_key       {str64} NOT NULL,
              parent_class    {str64},
              version         {int} NOT NULL,
              definition_json {text} NOT NULL,
              updated_at      {ts} NOT NULL,
              updated_by      {str} NOT NULL,
              PRIMARY KEY (tenant_id, class_key)
            )""",
            """
            CREATE TABLE REG_ASSET (
              object_id           {str64} NOT NULL PRIMARY KEY,
              tenant_id           {str64} NOT NULL,
              class_key           {str64} NOT NULL,
              asset_tag           {str} NOT NULL,
              parent_asset_id     {str64},
              location_ref        {str},
              geometry_ref        {str},
              custodian_ref       {str},
              acquisition_date    {str64},
              in_service_date     {str64},
              acquisition_value   {money},
              residual_value      {money},
              currency            {str64},
              useful_life_months  {int},
              depreciation_method {str64},
              depreciation_rate   NUMERIC(12,6),
              warranty_ref        {str},
              warranty_until      {str64},
              disposed_date       {str64},
              status              {str64} NOT NULL,
              version             {long} NOT NULL
            )""",
            "CREATE UNIQUE INDEX REG_ASSET_TAG ON REG_ASSET (tenant_id, asset_tag)",
            "CREATE INDEX REG_ASSET_PARENT ON REG_ASSET (tenant_id, parent_asset_id)",
            "CREATE INDEX REG_ASSET_CLASS_IDX ON REG_ASSET (tenant_id, class_key)",
            """
            CREATE TABLE REG_ASSET_MOVEMENT (
              id             {str64} NOT NULL PRIMARY KEY,
              tenant_id      {str64} NOT NULL,
              asset_id       {str64} NOT NULL,
              from_location  {str},
              to_location    {str},
              from_custodian {str},
              to_custodian   {str},
              reason         {str},
              moved_at       {ts} NOT NULL,
              moved_by       {str} NOT NULL
            )""",
            "CREATE INDEX REG_ASSET_MOVE_ASSET ON REG_ASSET_MOVEMENT (tenant_id, asset_id)",
            """
            CREATE TABLE REG_METER (
              id            {str64} NOT NULL PRIMARY KEY,
              tenant_id     {str64} NOT NULL,
              asset_id      {str64} NOT NULL,
              meter_type    {str64} NOT NULL,
              unit          {str64},
              cumulative    {small} NOT NULL,
              rollover_at   NUMERIC(24,6),
              total_value   NUMERIC(24,6) NOT NULL,
              last_no       NUMERIC(24,6),
              last_read_at  {ts},
              version       {long} NOT NULL
            )""",
            "CREATE UNIQUE INDEX REG_METER_KEY ON REG_METER (tenant_id, asset_id, meter_type)",
            """
            CREATE TABLE REG_METER_READING (
              id          {str64} NOT NULL PRIMARY KEY,
              tenant_id   {str64} NOT NULL,
              meter_id    {str64} NOT NULL,
              reading     NUMERIC(24,6) NOT NULL,
              delta       NUMERIC(24,6) NOT NULL,
              total_after NUMERIC(24,6) NOT NULL,
              read_at     {ts} NOT NULL,
              source      {str64},
              status      {str64} NOT NULL,
              note        {str},
              recorded_at {ts} NOT NULL,
              recorded_by {str} NOT NULL
            )""",
            "CREATE INDEX REG_METER_READING_METER ON REG_METER_READING (tenant_id, meter_id, read_at)",
            """
            CREATE TABLE REG_MAINT_PLAN (
              id              {str64} NOT NULL PRIMARY KEY,
              tenant_id       {str64} NOT NULL,
              asset_id        {str64} NOT NULL,
              plan_key        {str64} NOT NULL,
              trigger_type    {str64} NOT NULL,
              interval_days   {int},
              meter_type      {str64},
              meter_interval  NUMERIC(24,6),
              lead_days       {int} NOT NULL,
              work_type       {str64} NOT NULL,
              task_json       {text},
              last_done_at    {ts},
              last_done_meter NUMERIC(24,6),
              next_due_at     {ts},
              next_due_meter  NUMERIC(24,6),
              status          {str64} NOT NULL,
              version         {long} NOT NULL
            )""",
            "CREATE UNIQUE INDEX REG_MAINT_PLAN_KEY ON REG_MAINT_PLAN (tenant_id, asset_id, plan_key)",
            "CREATE INDEX REG_MAINT_PLAN_DUE ON REG_MAINT_PLAN (tenant_id, status, next_due_at)",
            """
            CREATE TABLE REG_WORK_ORDER (
              id             {str64} NOT NULL PRIMARY KEY,
              tenant_id      {str64} NOT NULL,
              wo_number      {str} NOT NULL,
              gen_key        {str} NOT NULL,
              asset_id       {str64} NOT NULL,
              plan_id        {str64},
              work_type      {str64} NOT NULL,
              priority       {str64} NOT NULL,
              state          {str64} NOT NULL,
              description    {str},
              reported_by    {str},
              reported_at    {ts} NOT NULL,
              due_at         {ts},
              assigned_to    {str},
              started_at     {ts},
              completed_at   {ts},
              closed_at      {ts},
              downtime_start {ts},
              downtime_end   {ts},
              meter_at_done  NUMERIC(24,6),
              cost           {money},
              currency       {str64},
              findings_json  {text},
              parts_json     {text},
              evidence_json  {text},
              version        {long} NOT NULL,
              updated_at     {ts} NOT NULL,
              updated_by     {str} NOT NULL
            )""",
            "CREATE UNIQUE INDEX REG_WORK_ORDER_GEN ON REG_WORK_ORDER (tenant_id, gen_key)",
            "CREATE INDEX REG_WORK_ORDER_ASSET ON REG_WORK_ORDER (tenant_id, asset_id, state)",
            // ------------------------------------------------------------------------------------------ custody
            """
            CREATE TABLE REG_CUSTODY_ITEM (
              object_id           {str64} NOT NULL PRIMARY KEY,
              tenant_id           {str64} NOT NULL,
              label               {str} NOT NULL,
              label_kind          {str64} NOT NULL,
              description         {str},
              case_ref            {str},
              container_id        {str64},
              container_flag      {small} NOT NULL,
              seal_no             {str},
              seal_status         {str64} NOT NULL,
              custodian_ref       {str},
              location_ref        {str},
              custody_state       {str64} NOT NULL,
              pending_transfer_id {str64},
              event_count         {long} NOT NULL,
              head_hash           {bytes32},
              last_event_at       {ts} NOT NULL,
              version             {long} NOT NULL
            )""",
            "CREATE UNIQUE INDEX REG_CUSTODY_LABEL ON REG_CUSTODY_ITEM (tenant_id, label)",
            "CREATE INDEX REG_CUSTODY_CONTAINER ON REG_CUSTODY_ITEM (tenant_id, container_id)",
            "CREATE INDEX REG_CUSTODY_CUSTODIAN ON REG_CUSTODY_ITEM (tenant_id, custodian_ref)",
            """
            CREATE TABLE REG_CUSTODY_EVENT (
              id                 {str64} NOT NULL PRIMARY KEY,
              tenant_id          {str64} NOT NULL,
              item_id            {str64} NOT NULL,
              seq                {long} NOT NULL,
              event_type         {str64} NOT NULL,
              from_custodian     {str},
              to_custodian       {str},
              location_ref       {str},
              condition_text     {str},
              seal_no            {str},
              seal_intact        {small},
              from_signature_ref {str},
              to_signature_ref   {str},
              witness_ref        {str},
              receipt_number     {str},
              transfer_id        {str64},
              details_json       {text},
              occurred_at        {ts} NOT NULL,
              recorded_by        {str} NOT NULL,
              prev_hash          {bytes32} NOT NULL,
              event_hash         {bytes32} NOT NULL,
              audit_event_id     {str64} NOT NULL
            )""",
            "CREATE UNIQUE INDEX REG_CUSTODY_EVENT_SEQ ON REG_CUSTODY_EVENT (tenant_id, item_id, seq)",
            """
            CREATE TABLE REG_CUSTODY_TRANSFER (
              id                 {str64} NOT NULL PRIMARY KEY,
              tenant_id          {str64} NOT NULL,
              item_id            {str64} NOT NULL,
              from_custodian     {str},
              to_custodian       {str} NOT NULL,
              to_location        {str},
              reason             {str},
              status             {str64} NOT NULL,
              initiated_at       {ts} NOT NULL,
              initiated_by       {str} NOT NULL,
              from_signature_ref {str},
              resolved_at        {ts},
              resolved_by        {str},
              to_signature_ref   {str},
              condition_text     {str},
              seal_intact        {small},
              receipt_number     {str}
            )""",
            "CREATE INDEX REG_CUSTODY_TRANSFER_ITEM ON REG_CUSTODY_TRANSFER (tenant_id, item_id, status)",
            """
            CREATE TABLE REG_DISPOSAL_ORDER (
              id            {str64} NOT NULL PRIMARY KEY,
              tenant_id     {str64} NOT NULL,
              item_id       {str64} NOT NULL,
              method        {str64} NOT NULL,
              authority_ref {str} NOT NULL,
              status        {str64} NOT NULL,
              ordered_at    {ts} NOT NULL,
              ordered_by    {str} NOT NULL,
              executed_at   {ts},
              executed_by   {str},
              details_json  {text}
            )""",
            "CREATE INDEX REG_DISPOSAL_ITEM ON REG_DISPOSAL_ORDER (tenant_id, item_id)");

    private static final List<String> TABLES = List.of(
            "REG_DISPOSAL_ORDER", "REG_CUSTODY_TRANSFER", "REG_CUSTODY_EVENT", "REG_CUSTODY_ITEM",
            "REG_WORK_ORDER", "REG_MAINT_PLAN", "REG_METER_READING", "REG_METER", "REG_ASSET_MOVEMENT", "REG_ASSET",
            "REG_ASSET_CLASS", "REG_PARTY_CONFIG", "REG_PARTY_SNAPSHOT", "REG_MERGE_EVENT", "REG_MATCH_CANDIDATE", "REG_PARTY_BLOCK",
            "REG_PARTY_ADDRESS", "REG_PARTY_RELATIONSHIP", "REG_PARTY_IDENTIFIER", "REG_PARTY", "REG_INSTRUMENT_DOC",
            "REG_ENDORSEMENT", "REG_VERIFICATION", "REG_CERTIFIED_EXTRACT", "REG_REGISTER_ENTRY", "REG_REGISTER_BOOK",
            "REG_REGISTER_DEF", "REG_NUMBER_ALLOCATION", "REG_NUMBER_COUNTER", "REG_NUMBER_SERIES",
            "REG_OBJECT_VERSION", "REG_OBJECT", "REG_OBJECT_TYPE");

    /** The DDL statements for one dialect, in creation order. */
    public static List<String> ddl(Dialect dialect) {
        return TEMPLATES.stream().map(dialect::expand)
                .map(sql -> sql.startsWith("CREATE TABLE") ? sql + dialect.createTableSuffix() : sql)
                .toList();
    }

    /** Registry table names, in drop order. */
    public static List<String> tables() {
        return TABLES;
    }

    /** Creates every registry table on {@code c} (development and tests; production uses Liquibase). */
    public static void create(Connection c, Dialect dialect) {
        try (Statement st = c.createStatement()) {
            for (String sql : ddl(dialect)) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException("registry schema", e);
        }
    }
}
