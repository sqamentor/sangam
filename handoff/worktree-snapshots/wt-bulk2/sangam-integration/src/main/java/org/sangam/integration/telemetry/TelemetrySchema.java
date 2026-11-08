// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.List;

/**
 * Reference DDL of the N12 telemetry tables (portable type tokens as in {@code org.sangam.core.layer.Schema} and
 * {@code org.sangam.integration.schema.IntegrationSchema}). WS-ENGINE ports these statements into the per-dialect
 * Liquibase changelogs; {@link #create} exists for tests and development databases only.
 *
 * <ul>
 *   <li>{@code TELEMETRY_DEVICE}: the Device/Sensor registry, linked to N03 assets ({@code asset_ref}) and N13
 *       locations ({@code location_ref}). Its row is also the per-device lock that serialises rule evaluation and
 *       keeps at most one non-closed episode per (tenant, device, sensor, rule).</li>
 *   <li>{@code ALARM_EPISODE}: one alarm episode. Measured values are stored as plain decimal strings
 *       ({@link java.math.BigDecimal#toPlainString()}) so no dialect rounds them; booleans as {@code {small}} 0/1.</li>
 * </ul>
 */
public final class TelemetrySchema {

    private TelemetrySchema() {
    }

    public static final List<String> DDL = List.of(
            """
            CREATE TABLE TELEMETRY_DEVICE (
              tenant_id     {str64} NOT NULL,
              device_id     {str64} NOT NULL,
              asset_ref     {str},
              location_ref  {str},
              channel_key   {str64},
              enabled       {small} NOT NULL,
              last_seen_at  {ts},
              created_at    {ts} NOT NULL,
              updated_at    {ts} NOT NULL,
              PRIMARY KEY (tenant_id, device_id)
            )""",
            """
            CREATE TABLE ALARM_EPISODE (
              episode_id           {str64} NOT NULL PRIMARY KEY,
              tenant_id            {str64} NOT NULL,
              device_id            {str64} NOT NULL,
              sensor               {str64} NOT NULL,
              rule_id              {str64} NOT NULL,
              state                {str64} NOT NULL,
              severity             {str64},
              breach_count         {int} NOT NULL,
              first_breach_at      {ts},
              opened_at            {ts},
              last_no           {str64},
              peak_value           {str64},
              last_reading_at      {ts},
              cleared_at           {ts},
              acked_by             {str},
              acked_at             {ts},
              ack_note             {text},
              ack_due_at           {ts},
              overdue_notified_at  {ts},
              closed_at            {ts},
              close_reason         {str64},
              reopen_count         {int} NOT NULL,
              last_event           {str64},
              last_event_outcome   {str64},
              last_event_error     {text},
              created_at           {ts} NOT NULL,
              updated_at           {ts} NOT NULL,
              version              {long} NOT NULL
            )""",
            "CREATE INDEX ALARM_EPISODE_ACTIVE ON ALARM_EPISODE (tenant_id, device_id, sensor, rule_id, state)",
            "CREATE INDEX ALARM_EPISODE_TENANT_STATE ON ALARM_EPISODE (tenant_id, state)",
            "CREATE INDEX ALARM_EPISODE_ACK_DUE ON ALARM_EPISODE (state, ack_due_at)"
    );

    public static void create(Connection c, Dialect dialect) {
        for (String sql : DDL) {
            String s = dialect.expand(sql);
            Jdbc.update(c, s.startsWith("CREATE TABLE") ? s + dialect.createTableSuffix() : s);
        }
    }
}
