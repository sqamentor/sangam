// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Product tables used by the Flowable ports, as portable DDL with {@link Dialect} type tokens.
 *
 * <p><b>Ownership.</b> The Liquibase changelogs for all product tables belong to WS-ENGINE (sangam-persistence,
 * ARCHITECTURE §3). This class is the source of truth for the ports' table shapes; WS-ENGINE copies them into the
 * per-dialect changelogs (README "Integration notes"). {@link #create} exists for development and unit tests only.
 * Every table carries {@code tenant_id}; reads go through the VI.4 predicate owned by WS-SECURITY.
 *
 * <p>Not here by integrator decision: unique start uses WS-INTEGRATION's {@code UNIQUE_START}
 * ({@link org.sangam.core.ports.UniqueStartPort}); privileges and tokens use WS-SECURITY's {@code SEC_PRIVILEGE},
 * {@code SEC_PRIVILEGE_MAPPING}, {@code SEC_API_TOKEN} ({@link org.sangam.core.ports.IdentityExtrasPort}).
 */
public final class PortsSchema {

    private PortsSchema() {
    }

    private static final List<String> TEMPLATES = List.of(
            // ------------------------------------------------ event registry (FLOWABLE-INTO-OPERATON FC-02, FLOWABLE-INTO-OPERATON FC-03, FLOWABLE-INTO-OPERATON FC-43); FLOWABLE-INTO-OPERATON FC-04 uses UNIQUE_START (WS-INTEGRATION)
            """
            CREATE TABLE EVT_CHANNEL_DEF (
              tenant_id   {str64} NOT NULL,
              channel_key {str} NOT NULL,
              version     {int} NOT NULL,
              definition  {text} NOT NULL,
              sha256      {str64} NOT NULL,
              app_deployment_id {str64},
              state       {str64} NOT NULL,
              retired_by  {str},
              retired_at  {ts},
              created_at  {ts} NOT NULL,
              PRIMARY KEY (tenant_id, channel_key, version)
            )""",
            """
            CREATE TABLE EVT_EVENT_DEF (
              tenant_id   {str64} NOT NULL,
              event_key   {str} NOT NULL,
              version     {int} NOT NULL,
              definition  {text} NOT NULL,
              sha256      {str64} NOT NULL,
              app_deployment_id {str64},
              state       {str64} NOT NULL,
              retired_by  {str},
              retired_at  {ts},
              created_at  {ts} NOT NULL,
              PRIMARY KEY (tenant_id, event_key, version)
            )""",
            // version high-water mark per (tenant, kind, key): numbers are never reused, also after delete-all
            """
            CREATE TABLE EVT_DEFINITION_SEQ (
              tenant_id    {str64} NOT NULL,
              def_kind     {str64} NOT NULL,
              def_key      {str} NOT NULL,
              last_version {int} NOT NULL,
              PRIMARY KEY (tenant_id, def_kind, def_key)
            )""",
            """
            CREATE TABLE EVT_LOG (
              id              {identity} PRIMARY KEY,
              tenant_id       {str64} NOT NULL,
              direction       {str64} NOT NULL,
              channel_key     {str},
              event_key       {str},
              message_id      {str},
              correlation_key {str},
              outcome         {str64} NOT NULL,
              detail          {text},
              payload         {text},
              created_at      {ts} NOT NULL
            )""",
            "CREATE INDEX EVT_LOG_KEY ON EVT_LOG (tenant_id, event_key, created_at)",
            // ------------------------------------------------ links (FLOWABLE-INTO-OPERATON FC-31, FLOWABLE-INTO-OPERATON FC-32, FLOWABLE-INTO-OPERATON FC-59)
            """
            CREATE TABLE INSTANCE_LINK (
              id             {identity} PRIMARY KEY,
              tenant_id      {str64} NOT NULL,
              link_type      {str64} NOT NULL,
              hierarchy_type {str64},
              source_type    {str64} NOT NULL,
              source_id      {str64} NOT NULL,
              target_type    {str64} NOT NULL,
              target_id      {str64} NOT NULL,
              root_type      {str64},
              root_id        {str64},
              created_by     {str},
              created_at     {ts} NOT NULL
            )""",
            "CREATE UNIQUE INDEX INSTANCE_LINK_UQ ON INSTANCE_LINK (tenant_id, link_type, source_type, source_id, target_type, target_id)",
            "CREATE INDEX INSTANCE_LINK_TGT ON INSTANCE_LINK (tenant_id, target_type, target_id)",
            "CREATE INDEX INSTANCE_LINK_ROOT ON INSTANCE_LINK (tenant_id, root_type, root_id)",
            // ------------------------------------------------ content (FLOWABLE-INTO-OPERATON FC-40, EC-18)
            """
            CREATE TABLE CONTENT_ITEM (
              id              {str64} NOT NULL PRIMARY KEY,
              tenant_id       {str64} NOT NULL,
              name            {str} NOT NULL,
              mime_type       {str},
              scope_type      {str64},
              scope_id        {str64},
              task_id         {str64},
              current_version {int} NOT NULL,
              deleted_flag    {small} NOT NULL,
              created_by      {str},
              created_at      {ts} NOT NULL
            )""",
            "CREATE INDEX CONTENT_ITEM_SCOPE ON CONTENT_ITEM (tenant_id, scope_type, scope_id)",
            """
            CREATE TABLE CONTENT_VERSION (
              item_id     {str64} NOT NULL,
              version     {int} NOT NULL,
              store_key   {str} NOT NULL,
              size_bytes  {long} NOT NULL,
              sha256      {str64} NOT NULL,
              scan_state  {str64} NOT NULL,
              created_by  {str},
              created_at  {ts} NOT NULL,
              PRIMARY KEY (item_id, version)
            )""",
            // ------------------------------------------------ forms (FLOWABLE-INTO-OPERATON FC-39, EC-02 runtime)
            """
            CREATE TABLE FORM_DEF (
              tenant_id   {str64} NOT NULL,
              form_key    {str} NOT NULL,
              version     {int} NOT NULL,
              definition  {text} NOT NULL,
              sha256      {str64} NOT NULL,
              app_deployment_id {str64},
              created_at  {ts} NOT NULL,
              PRIMARY KEY (tenant_id, form_key, version)
            )""",
            // ------------------------------------------------ app bundles (FLOWABLE-INTO-OPERATON FC-41)
            """
            CREATE TABLE APP_DEPLOYMENT (
              id                   {str64} NOT NULL PRIMARY KEY,
              tenant_id            {str64} NOT NULL,
              app_key              {str} NOT NULL,
              version              {int} NOT NULL,
              bundle_sha256        {str64} NOT NULL,
              engine_deployment_id {str64},
              manifest             {text} NOT NULL,
              state                {str64} NOT NULL,
              approved_by          {str},
              created_at           {ts} NOT NULL
            )""",
            "CREATE UNIQUE INDEX APP_DEPLOYMENT_UQ ON APP_DEPLOYMENT (tenant_id, app_key, version)",
            // ------------------------------------------------ product batch service (FLOWABLE-INTO-OPERATON D-34, FLOWABLE-INTO-OPERATON FC-35, FLOWABLE-INTO-OPERATON FC-18)
            """
            CREATE TABLE PORT_BATCH (
              id          {str64} NOT NULL PRIMARY KEY,
              tenant_id   {str64} NOT NULL,
              batch_type  {str64} NOT NULL,
              state       {str64} NOT NULL,
              total_items {int} NOT NULL,
              done_items  {int} NOT NULL,
              failed_items {int} NOT NULL,
              config      {text},
              reason      {str},
              four_eyes_flag {small} NOT NULL,
              approved_by {str},
              approved_at {ts},
              created_by  {str},
              created_at  {ts} NOT NULL,
              updated_at  {ts} NOT NULL,
              completed_at {ts}
            )""",
            "CREATE INDEX PORT_BATCH_STATE ON PORT_BATCH (tenant_id, state, created_at)",
            """
            CREATE TABLE PORT_BATCH_ITEM (
              batch_id  {str64} NOT NULL,
              item_seq  {int} NOT NULL,
              item_ref  {str} NOT NULL,
              state     {str64} NOT NULL,
              attempts  {int} NOT NULL,
              result    {text},
              updated_at {ts} NOT NULL,
              PRIMARY KEY (batch_id, item_seq)
            )""",
            // ------------------------------------------------ multi-instance aggregation (FLOWABLE-INTO-OPERATON FC-21)
            """
            CREATE TABLE MI_AGGREGATE (
              tenant_id               {str64} NOT NULL,
              process_instance_id     {str64} NOT NULL,
              mi_body_instance_id     {str64} NOT NULL,
              loop_counter            {int} NOT NULL,
              payload                 {text} NOT NULL,
              created_at              {ts} NOT NULL,
              PRIMARY KEY (process_instance_id, mi_body_instance_id, loop_counter)
            )""",
            // ------------------------------------------------ task lifecycle + business status (FLOWABLE-INTO-OPERATON FC-52)
            """
            CREATE TABLE TASK_LIFECYCLE (
              task_id           {str64} NOT NULL PRIMARY KEY,
              tenant_id         {str64},
              claim_time        {ts},
              claimed_by        {str},
              in_progress_start {ts},
              in_progress_by    {str},
              suspended_time    {ts},
              last_event        {str64},
              updated_at        {ts} NOT NULL
            )""",
            """
            CREATE TABLE INSTANCE_BUSINESS_STATUS (
              instance_id     {str64} NOT NULL PRIMARY KEY,
              tenant_id       {str64},
              business_status {str} NOT NULL,
              updated_by      {str},
              updated_at      {ts} NOT NULL
            )""",
            // ------------------------------------------------ history read model (FLOWABLE-INTO-OPERATON FC-25, FLOWABLE-INTO-OPERATON FC-20, FLOWABLE-INTO-OPERATON D-41)
            """
            CREATE TABLE HRM_PROCESS_INSTANCE (
              id               {str64} NOT NULL PRIMARY KEY,
              tenant_id        {str64},
              scope_type       {str64} NOT NULL,
              definition_key   {str},
              definition_id    {str64},
              business_key     {str},
              state            {str64},
              super_id         {str64},
              root_id          {str64},
              start_time       {ts},
              end_time         {ts},
              last_seq         {long} NOT NULL
            )""",
            """
            CREATE TABLE HRM_ACTIVITY_INSTANCE (
              id                  {str64} NOT NULL PRIMARY KEY,
              tenant_id           {str64},
              process_instance_id {str64},
              activity_id         {str},
              activity_type       {str64},
              parent_id           {str64},
              state               {str64} NOT NULL,
              start_time          {ts},
              end_time            {ts}
            )""",
            "CREATE INDEX HRM_ACT_OPEN ON HRM_ACTIVITY_INSTANCE (process_instance_id, state)",
            "CREATE INDEX HRM_PI_QUERY ON HRM_PROCESS_INSTANCE (tenant_id, scope_type, definition_key, start_time)",
            """
            CREATE TABLE HRM_TASK (
              id                  {str64} NOT NULL PRIMARY KEY,
              tenant_id           {str64},
              process_instance_id {str64},
              case_instance_id    {str64},
              task_def_key        {str},
              name                {str},
              assignee            {str},
              owner_id            {str},
              state               {str64},
              delete_reason       {str},
              create_time         {ts},
              end_time            {ts}
            )""",
            """
            CREATE INDEX HRM_TASK_QUERY ON HRM_TASK (tenant_id, process_instance_id, task_def_key)""",
            """
            CREATE TABLE HRM_CHECKPOINT (
              projector  {str64} NOT NULL,
              tenant_id  {str64} NOT NULL,
              root_id    {str} NOT NULL,
              last_seq   {long} NOT NULL,
              PRIMARY KEY (projector, tenant_id, root_id)
            )""",
            // ------------------------------------------------ dynamic injection audit (FLOWABLE-INTO-OPERATON FC-15, FLOWABLE-INTO-OPERATON FC-17)
            """
            CREATE TABLE DYN_INJECTION (
              id                    {str64} NOT NULL PRIMARY KEY,
              tenant_id             {str64},
              process_instance_id   {str64} NOT NULL,
              source_definition_id  {str64} NOT NULL,
              derived_definition_id {str64},
              kind                  {str64} NOT NULL,
              spec                  {text} NOT NULL,
              approved_by           {str},
              created_at            {ts} NOT NULL
            )""",
            // ------------------------------------------------ SLA (EC-08, FLOWABLE-INTO-OPERATON D-32)
            """
            CREATE TABLE SLA_INSTANCE (
              id          {str64} NOT NULL PRIMARY KEY,
              tenant_id   {str64} NOT NULL,
              sla_key     {str} NOT NULL,
              definition_key {str},
              scope_type  {str64} NOT NULL,
              scope_id    {str64} NOT NULL,
              state       {str64} NOT NULL,
              calendar    {str},
              target_ms   {long} NOT NULL,
              warn_ms     {long},
              started_at  {ts} NOT NULL,
              due_at      {ts} NOT NULL,
              warn_at     {ts},
              paused_at   {ts},
              paused_ms   {long} NOT NULL,
              warned_flag {small} NOT NULL,
              breached_at {ts},
              stopped_at  {ts}
            )""",
            "CREATE INDEX SLA_INSTANCE_DUE ON SLA_INSTANCE (state, due_at)",
            "CREATE INDEX SLA_INSTANCE_STATS ON SLA_INSTANCE (tenant_id, definition_key, sla_key, started_at)",
            // ------------------------------------------------ job executor global acquire lock (FLOWABLE-INTO-OPERATON FC-24)
            """
            CREATE TABLE JOB_ACQUIRE_LOCK (
              lock_name   {str64} NOT NULL PRIMARY KEY,
              owner_id    {str},
              acquired_at {ts}
            )""",
            // ------------------------------------------------ debugger breakpoints, sandbox tenants only (FLOWABLE-INTO-OPERATON FC-44)
            """
            CREATE TABLE DEBUG_BREAKPOINT (
              tenant_id      {str64} NOT NULL,
              definition_key {str} NOT NULL,
              activity_id    {str} NOT NULL,
              PRIMARY KEY (tenant_id, definition_key, activity_id)
            )""");

    public static List<String> ddl(Dialect dialect) {
        return TEMPLATES.stream()
                .map(dialect::expand)
                .map(sql -> sql.startsWith("CREATE TABLE") ? sql + dialect.createTableSuffix() : sql)
                .toList();
    }

    public static List<String> tables() {
        return TEMPLATES.stream().filter(s -> s.startsWith("CREATE TABLE"))
                .map(s -> s.substring("CREATE TABLE ".length(), s.indexOf(' ', "CREATE TABLE ".length())).trim())
                .toList();
    }

    /** Development/test only: production schema comes from Liquibase (WS-ENGINE). */
    public static void create(Connection c, Dialect dialect) {
        try (Statement st = c.createStatement()) {
            for (String sql : ddl(dialect)) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException("ports schema", e);
        }
    }
}
