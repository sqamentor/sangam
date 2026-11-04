// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources;

import org.sangam.resources.dialect.ResourcesDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * DDL of the resources tables, portable with type tokens that {@link ResourcesDialect#expand(String)} specialises for
 * PostgreSQL, Oracle, SQL Server, MySQL 8.4 and H2. The integrator copies it into the Liquibase changelogs of
 * sangam-persistence (one changeSet per statement, per dialect).
 *
 * <p>Identifiers avoid the reserved words of all five databases ({@code MODE}, {@code START}, {@code RANK},
 * {@code RULE}, {@code USER}, {@code LEVEL}, {@code DATE}, {@code NUMBER}, {@code KEY}). Booleans are SMALLINT 0/1.
 * Amounts are decimal strings ({@code {str64}}) so no dialect rounds them. Times are UTC ({@code {ts}}).
 *
 * <h2>Exclusion (no double booking)</h2>
 * <ul>
 *   <li>{@code RES_CLAIM} primary key (tenant_id, resource_id, slot_key, unit_no): one row per minute-granular slot
 *       start and capacity unit; see {@link ResourcesDialect} for the per-dialect insert;</li>
 *   <li>{@code RES_ALLOTMENT}: long occupancy, serialised by a row lock on {@code RES_RESOURCE} plus an overlap check;</li>
 *   <li>{@code RES_ROSTER_ASSIGNMENT} unique (tenant_id, period_id, person_id, duty_date, shift_id) and
 *       {@code RES_PERSON_CLAIM} (person-hour claims) so no person is rostered twice for overlapping duty.</li>
 * </ul>
 */
public final class ResourcesSchema {

    private ResourcesSchema() {
    }

    // ------------------------------------------------------------------ catalogue and calendars (N01)
    public static final List<String> CATALOGUE = List.of(
            """
            CREATE TABLE RES_TYPE (
              tenant_id       {str64} NOT NULL,
              type_id         {str64} NOT NULL,
              kind            {str64} NOT NULL,
              definition_json {text} NOT NULL,
              def_version     {long} NOT NULL,
              status          {str64} NOT NULL,
              updated_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, type_id)
            )""",
            """
            CREATE TABLE RES_RESOURCE (
              tenant_id       {str64} NOT NULL,
              resource_id     {str64} NOT NULL,
              type_id         {str64} NOT NULL,
              code            {str64} NOT NULL,
              name            {str} NOT NULL,
              parent_id       {str64},
              tree_path       {str} NOT NULL,
              org_unit_id     {str64},
              location_id     {str64},
              geo_lat         {str64},
              geo_lon         {str64},
              capacity        {int} NOT NULL,
              unit_label      {str64},
              calendar_id     {str64},
              status          {str64} NOT NULL,
              attributes_json {text},
              row_version     {long} NOT NULL,
              created_at      {ts} NOT NULL,
              updated_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, resource_id)
            )""",
            "CREATE UNIQUE INDEX RES_RESOURCE_CODE ON RES_RESOURCE (tenant_id, type_id, code)",
            "CREATE INDEX RES_RESOURCE_PARENT ON RES_RESOURCE (tenant_id, parent_id)",
            "CREATE INDEX RES_RESOURCE_PATH ON RES_RESOURCE (tenant_id, tree_path)",
            "CREATE INDEX RES_RESOURCE_UNIT ON RES_RESOURCE (tenant_id, org_unit_id, type_id)",
            "CREATE INDEX RES_RESOURCE_LOC ON RES_RESOURCE (tenant_id, location_id, type_id)",
            """
            CREATE TABLE RES_RESOURCE_ATTR (
              tenant_id       {str64} NOT NULL,
              resource_id     {str64} NOT NULL,
              attr_name       {str64} NOT NULL,
              attr_value      {str} NOT NULL,
              PRIMARY KEY (tenant_id, resource_id, attr_name, attr_value)
            )""",
            "CREATE INDEX RES_RESOURCE_ATTR_V ON RES_RESOURCE_ATTR (tenant_id, attr_name, attr_value)",
            """
            CREATE TABLE RES_CALENDAR (
              tenant_id       {str64} NOT NULL,
              calendar_id     {str64} NOT NULL,
              name            {str} NOT NULL,
              zone_id         {str64} NOT NULL,
              business_cal_id {str64},
              hours_json      {text} NOT NULL,
              closed_on_holidays {small} NOT NULL,
              updated_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, calendar_id)
            )""",
            """
            CREATE TABLE RES_BLACKOUT (
              tenant_id       {str64} NOT NULL,
              blackout_id     {str64} NOT NULL,
              calendar_id     {str64},
              resource_id     {str64},
              kind            {str64} NOT NULL,
              starts_at       {ts} NOT NULL,
              ends_at         {ts} NOT NULL,
              reason          {str},
              created_by      {str64},
              created_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, blackout_id)
            )""",
            "CREATE INDEX RES_BLACKOUT_RES ON RES_BLACKOUT (tenant_id, resource_id, starts_at)",
            "CREATE INDEX RES_BLACKOUT_CAL ON RES_BLACKOUT (tenant_id, calendar_id, starts_at)",
            """
            CREATE TABLE RES_SLOT_TEMPLATE (
              tenant_id       {str64} NOT NULL,
              template_id     {str64} NOT NULL,
              resource_id     {str64} NOT NULL,
              weekdays        {str64} NOT NULL,
              from_time       {str64} NOT NULL,
              to_time         {str64} NOT NULL,
              slot_minutes    {int} NOT NULL,
              units_per_slot  {int} NOT NULL,
              valid_from      {str64},
              valid_to        {str64},
              attributes_json {text},
              PRIMARY KEY (tenant_id, template_id)
            )""",
            "CREATE INDEX RES_SLOT_TEMPLATE_RES ON RES_SLOT_TEMPLATE (tenant_id, resource_id)");

    // ------------------------------------------------------------------ booking, allotment, waitlist (N01)
    public static final List<String> BOOKING = List.of(
            """
            CREATE TABLE RES_BOOKING (
              tenant_id       {str64} NOT NULL,
              booking_id      {str64} NOT NULL,
              booking_ref     {str64} NOT NULL,
              resource_id     {str64} NOT NULL,
              type_id         {str64} NOT NULL,
              state           {str64} NOT NULL,
              requester_id    {str64} NOT NULL,
              beneficiary_id  {str64},
              party_ref       {str},
              purpose         {str},
              entitlement_class {str64},
              priority_level  {int} NOT NULL,
              quantity        {int} NOT NULL,
              starts_at       {ts} NOT NULL,
              ends_at         {ts} NOT NULL,
              hold_expires_at {ts},
              tariff_code     {str64},
              charge_amount   {str64},
              refund_amount   {str64},
              currency        {str64},
              charge_ref      {str64},
              business_key    {str},
              process_ref     {str64},
              recurrence_id   {str64},
              waitlist_id     {str64},
              bumped_by       {str64},
              state_reason    {str},
              attributes_json {text},
              row_version     {long} NOT NULL,
              created_at      {ts} NOT NULL,
              updated_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, booking_id)
            )""",
            "CREATE UNIQUE INDEX RES_BOOKING_REF ON RES_BOOKING (tenant_id, booking_ref)",
            "CREATE INDEX RES_BOOKING_RES ON RES_BOOKING (tenant_id, resource_id, starts_at)",
            "CREATE INDEX RES_BOOKING_HOLD ON RES_BOOKING (tenant_id, state, hold_expires_at)",
            "CREATE INDEX RES_BOOKING_REQ ON RES_BOOKING (tenant_id, requester_id, starts_at)",
            "CREATE INDEX RES_BOOKING_REC ON RES_BOOKING (tenant_id, recurrence_id)",
            """
            CREATE TABLE RES_CLAIM (
              tenant_id       {str64} NOT NULL,
              resource_id     {str64} NOT NULL,
              slot_key        {long} NOT NULL,
              unit_no         {int} NOT NULL,
              booking_id      {str64} NOT NULL,
              PRIMARY KEY (tenant_id, resource_id, slot_key, unit_no)
            )""",
            "CREATE INDEX RES_CLAIM_BOOKING ON RES_CLAIM (tenant_id, booking_id)",
            """
            CREATE TABLE RES_ALLOTMENT (
              tenant_id       {str64} NOT NULL,
              allotment_id    {str64} NOT NULL,
              allotment_ref   {str64} NOT NULL,
              resource_id     {str64} NOT NULL,
              unit_no         {int} NOT NULL,
              holder_id       {str64} NOT NULL,
              beneficiary_id  {str64},
              waitlist_id     {str64},
              state           {str64} NOT NULL,
              valid_from      {ts} NOT NULL,
              valid_to        {ts},
              vacate_due_at   {ts},
              vacated_at      {ts},
              order_ref       {str},
              tariff_code     {str64},
              charge_amount   {str64},
              currency        {str64},
              charge_ref      {str64},
              state_reason    {str},
              attributes_json {text},
              row_version     {long} NOT NULL,
              created_at      {ts} NOT NULL,
              updated_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, allotment_id)
            )""",
            "CREATE UNIQUE INDEX RES_ALLOTMENT_REF ON RES_ALLOTMENT (tenant_id, allotment_ref)",
            "CREATE INDEX RES_ALLOTMENT_RES ON RES_ALLOTMENT (tenant_id, resource_id, state, valid_from)",
            "CREATE INDEX RES_ALLOTMENT_HOLDER ON RES_ALLOTMENT (tenant_id, holder_id, state)",
            """
            CREATE TABLE RES_WAITLIST (
              tenant_id       {str64} NOT NULL,
              entry_id        {str64} NOT NULL,
              scope_key       {str} NOT NULL,
              type_id         {str64} NOT NULL,
              resource_id     {str64},
              requester_id    {str64} NOT NULL,
              beneficiary_id  {str64},
              category        {str64},
              priority_level  {int} NOT NULL,
              priority_score  {long} NOT NULL,
              seniority_at    {ts},
              wanted_from     {ts},
              wanted_to       {ts},
              quantity        {int} NOT NULL,
              state           {str64} NOT NULL,
              offer_expires_at {ts},
              booking_id      {str64},
              allotment_id    {str64},
              draw_id         {str64},
              draw_position   {int},
              attributes_json {text},
              row_version     {long} NOT NULL,
              created_at      {ts} NOT NULL,
              updated_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, entry_id)
            )""",
            "CREATE INDEX RES_WAITLIST_Q ON RES_WAITLIST (tenant_id, scope_key, state, priority_score, created_at)",
            "CREATE INDEX RES_WAITLIST_REQ ON RES_WAITLIST (tenant_id, requester_id, state)",
            """
            CREATE TABLE RES_DRAW (
              tenant_id       {str64} NOT NULL,
              draw_id         {str64} NOT NULL,
              scope_key       {str} NOT NULL,
              seed            {str} NOT NULL,
              algorithm       {str64} NOT NULL,
              entrants_hash   {str64} NOT NULL,
              entrant_count   {int} NOT NULL,
              result_json     {text} NOT NULL,
              drawn_by        {str64} NOT NULL,
              drawn_at        {ts} NOT NULL,
              PRIMARY KEY (tenant_id, draw_id)
            )""",
            """
            CREATE TABLE RES_BOOKING_SEQ (
              tenant_id       {str64} NOT NULL,
              series          {str64} NOT NULL,
              period          {str64} NOT NULL,
              last_no         {long} NOT NULL,
              PRIMARY KEY (tenant_id, series, period)
            )""",
            """
            CREATE TABLE RES_EVENT (
              event_id        {identity} PRIMARY KEY,
              tenant_id       {str64} NOT NULL,
              entity_type     {str64} NOT NULL,
              entity_id       {str64} NOT NULL,
              event_type      {str64} NOT NULL,
              actor_id        {str64},
              reason          {str},
              authority_ref   {str},
              payload_json    {text},
              at_time         {ts} NOT NULL
            )""",
            "CREATE INDEX RES_EVENT_ENTITY ON RES_EVENT (tenant_id, entity_type, entity_id, event_id)");

    // ------------------------------------------------------------------ queue / token (N07)
    public static final List<String> QUEUE = List.of(
            """
            CREATE TABLE RES_SERVICE_POINT (
              tenant_id       {str64} NOT NULL,
              service_point_id {str64} NOT NULL,
              name            {str} NOT NULL,
              resource_id     {str64},
              calendar_id     {str64},
              token_prefix    {str64},
              sla_minutes     {int} NOT NULL,
              priority_decision {str64},
              routing_decision {str64},
              config_json     {text},
              status          {str64} NOT NULL,
              updated_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, service_point_id)
            )""",
            """
            CREATE TABLE RES_COUNTER (
              tenant_id       {str64} NOT NULL,
              counter_id      {str64} NOT NULL,
              service_point_id {str64} NOT NULL,
              name            {str} NOT NULL,
              status          {str64} NOT NULL,
              operator_id     {str64},
              current_token_id {str64},
              row_version     {long} NOT NULL,
              updated_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, counter_id)
            )""",
            "CREATE INDEX RES_COUNTER_SP ON RES_COUNTER (tenant_id, service_point_id)",
            """
            CREATE TABLE RES_COUNTER_SKILL (
              tenant_id       {str64} NOT NULL,
              counter_id      {str64} NOT NULL,
              skill           {str64} NOT NULL,
              PRIMARY KEY (tenant_id, counter_id, skill)
            )""",
            """
            CREATE TABLE RES_TOKEN_SEQ (
              tenant_id       {str64} NOT NULL,
              service_point_id {str64} NOT NULL,
              day_key         {str64} NOT NULL,
              series          {str64} NOT NULL,
              last_no         {long} NOT NULL,
              PRIMARY KEY (tenant_id, service_point_id, day_key, series)
            )""",
            """
            CREATE TABLE RES_TOKEN (
              tenant_id       {str64} NOT NULL,
              token_id        {str64} NOT NULL,
              service_point_id {str64} NOT NULL,
              day_key         {str64} NOT NULL,
              token_no        {int} NOT NULL,
              display_no      {str64} NOT NULL,
              channel         {str64} NOT NULL,
              booking_id      {str64},
              party_id        {str64},
              priority_class  {str64},
              priority_level  {int} NOT NULL,
              skill           {str64} NOT NULL,
              state           {str64} NOT NULL,
              counter_id      {str64},
              transferred_from {str64},
              recall_count    {int} NOT NULL,
              issued_at       {ts} NOT NULL,
              queued_at       {ts} NOT NULL,
              called_at       {ts},
              serve_started_at {ts},
              served_at       {ts},
              sla_due_at      {ts},
              outcome         {str64},
              attributes_json {text},
              row_version     {long} NOT NULL,
              PRIMARY KEY (tenant_id, token_id)
            )""",
            "CREATE UNIQUE INDEX RES_TOKEN_NO ON RES_TOKEN (tenant_id, service_point_id, day_key, display_no)",
            "CREATE INDEX RES_TOKEN_WAIT ON RES_TOKEN (tenant_id, service_point_id, state, priority_level, queued_at)",
            "CREATE INDEX RES_TOKEN_COUNTER ON RES_TOKEN (tenant_id, counter_id, state)",
            """
            CREATE TABLE RES_CALL_EVENT (
              event_id        {identity} PRIMARY KEY,
              tenant_id       {str64} NOT NULL,
              service_point_id {str64} NOT NULL,
              token_id        {str64} NOT NULL,
              counter_id      {str64},
              kind            {str64} NOT NULL,
              actor_id        {str64},
              detail          {str},
              at_time         {ts} NOT NULL
            )""",
            "CREATE INDEX RES_CALL_EVENT_SP ON RES_CALL_EVENT (tenant_id, service_point_id, event_id)");

    // ------------------------------------------------------------------ rostering (N08)
    public static final List<String> ROSTER = List.of(
            """
            CREATE TABLE RES_SHIFT_TEMPLATE (
              tenant_id       {str64} NOT NULL,
              shift_id        {str64} NOT NULL,
              code            {str64} NOT NULL,
              name            {str} NOT NULL,
              from_time       {str64} NOT NULL,
              duration_minutes {int} NOT NULL,
              night_flag      {small} NOT NULL,
              skills          {str},
              zone_id         {str64} NOT NULL,
              attributes_json {text},
              PRIMARY KEY (tenant_id, shift_id)
            )""",
            "CREATE UNIQUE INDEX RES_SHIFT_CODE ON RES_SHIFT_TEMPLATE (tenant_id, code)",
            """
            CREATE TABLE RES_ROTATION (
              tenant_id       {str64} NOT NULL,
              rotation_id     {str64} NOT NULL,
              name            {str} NOT NULL,
              cycle_json      {text} NOT NULL,
              PRIMARY KEY (tenant_id, rotation_id)
            )""",
            """
            CREATE TABLE RES_ROSTER_PERIOD (
              tenant_id       {str64} NOT NULL,
              period_id       {str64} NOT NULL,
              unit_id         {str64} NOT NULL,
              name            {str} NOT NULL,
              first_day       {str64} NOT NULL,
              last_day        {str64} NOT NULL,
              state           {str64} NOT NULL,
              seed            {str},
              constraints_json {text},
              process_ref     {str64},
              generated_at    {ts},
              published_at    {ts},
              published_by    {str64},
              row_version     {long} NOT NULL,
              created_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, period_id)
            )""",
            """
            CREATE TABLE RES_ROSTER_MEMBER (
              tenant_id       {str64} NOT NULL,
              period_id       {str64} NOT NULL,
              person_id       {str64} NOT NULL,
              seat_id         {str64},
              skills          {str},
              rotation_id     {str64},
              rotation_offset {int} NOT NULL,
              attributes_json {text},
              PRIMARY KEY (tenant_id, period_id, person_id)
            )""",
            """
            CREATE TABLE RES_COVERAGE_DEMAND (
              tenant_id       {str64} NOT NULL,
              demand_id       {str64} NOT NULL,
              period_id       {str64} NOT NULL,
              shift_id        {str64} NOT NULL,
              skill           {str64},
              weekdays        {str64},
              duty_day        {str64},
              required_count  {int} NOT NULL,
              duty_seat_id    {str64},
              location_id     {str64},
              PRIMARY KEY (tenant_id, demand_id)
            )""",
            "CREATE INDEX RES_COVERAGE_PERIOD ON RES_COVERAGE_DEMAND (tenant_id, period_id)",
            """
            CREATE TABLE RES_ROSTER_ASSIGNMENT (
              tenant_id       {str64} NOT NULL,
              assignment_id   {str64} NOT NULL,
              period_id       {str64} NOT NULL,
              person_id       {str64} NOT NULL,
              seat_id         {str64},
              shift_id        {str64} NOT NULL,
              duty_day        {str64} NOT NULL,
              starts_at       {ts} NOT NULL,
              ends_at         {ts} NOT NULL,
              kind            {str64} NOT NULL,
              demand_id       {str64},
              duty_seat_id    {str64},
              location_id     {str64},
              state           {str64} NOT NULL,
              posting_ref     {str64},
              attendance      {str64},
              swapped_from    {str64},
              row_version     {long} NOT NULL,
              updated_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, assignment_id)
            )""",
            "CREATE UNIQUE INDEX RES_ROSTER_ASSIGN_U ON RES_ROSTER_ASSIGNMENT (tenant_id, period_id, person_id, duty_day, shift_id)",
            "CREATE INDEX RES_ROSTER_ASSIGN_P ON RES_ROSTER_ASSIGNMENT (tenant_id, person_id, starts_at)",
            "CREATE INDEX RES_ROSTER_ASSIGN_D ON RES_ROSTER_ASSIGNMENT (tenant_id, period_id, duty_day, shift_id)",
            """
            CREATE TABLE RES_PERSON_CLAIM (
              tenant_id       {str64} NOT NULL,
              person_id       {str64} NOT NULL,
              hour_key        {long} NOT NULL,
              assignment_id   {str64} NOT NULL,
              PRIMARY KEY (tenant_id, person_id, hour_key)
            )""",
            "CREATE INDEX RES_PERSON_CLAIM_A ON RES_PERSON_CLAIM (tenant_id, assignment_id)",
            """
            CREATE TABLE RES_SWAP_REQUEST (
              tenant_id       {str64} NOT NULL,
              swap_id         {str64} NOT NULL,
              kind            {str64} NOT NULL,
              assignment_id   {str64} NOT NULL,
              requester_id    {str64} NOT NULL,
              counterpart_id  {str64} NOT NULL,
              counterpart_assignment_id {str64},
              state           {str64} NOT NULL,
              reason          {str},
              decided_by      {str64},
              process_ref     {str64},
              row_version     {long} NOT NULL,
              created_at      {ts} NOT NULL,
              decided_at      {ts},
              PRIMARY KEY (tenant_id, swap_id)
            )""",
            "CREATE INDEX RES_SWAP_ASSIGN ON RES_SWAP_REQUEST (tenant_id, assignment_id, state)");

    // ------------------------------------------------------------------ operations (sweeper leases)
    /**
     * One lease row per (tenant, sweeper): a node runs a sweeper only while it holds an unexpired lease; the fencing
     * counter increments on every acquisition so a node whose lease was taken over cannot act or release.
     */
    public static final List<String> OPS = List.of(
            """
            CREATE TABLE RES_SWEEP_LEASE (
              tenant_id       {str64} NOT NULL,
              sweeper         {str64} NOT NULL,
              owner_node      {str} NOT NULL,
              lease_until     {ts} NOT NULL,
              fencing         {long} NOT NULL,
              last_started_at {ts},
              last_finished_at {ts},
              last_count      {int},
              PRIMARY KEY (tenant_id, sweeper)
            )""");

    /** Every template in creation order. */
    public static List<String> templates() {
        List<String> all = new ArrayList<>();
        all.addAll(CATALOGUE);
        all.addAll(BOOKING);
        all.addAll(QUEUE);
        all.addAll(ROSTER);
        all.addAll(OPS);
        return all;
    }

    public static List<String> ddl(ResourcesDialect d) {
        return templates().stream()
                .map(t -> d.expand(t) + (t.startsWith("CREATE TABLE") ? d.createTableSuffix() : ""))
                .toList();
    }

    /** Creates the tables (development and tests; production uses the Liquibase changelogs). */
    public static void create(Connection c, ResourcesDialect d) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String sql : ddl(d)) {
                s.execute(sql);
            }
        }
    }
}
