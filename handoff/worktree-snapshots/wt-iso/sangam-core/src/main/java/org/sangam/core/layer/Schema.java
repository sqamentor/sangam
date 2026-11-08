package org.sangam.core.layer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * DDL for the product tables, written once as portable SQL with type tokens that {@link Dialect#expand(String)}
 * specialises.
 *
 * <p>Deviations from the specification text (all deliberate, all spike-only):
 * <ul>
 *   <li>{@code ROOT_SEQ} stands in for the counters that SANGAM.md keeps on {@code RT_ROOT}
 *       ({@code next_outbox_seq}, VI.2.1, and {@code audit_seq}, X.2.3.3). Locking its row is the L2 surrogate:
 *       the engines under test do not have an {@code RT_ROOT} table the product layer may extend.</li>
 *   <li>{@code OUTBOX} is not range-partitioned by {@code created_at} (VI.2.1). On PostgreSQL that requires the
 *       partition key in every unique constraint ({@code PRIMARY KEY(id, created_at)}, {@code UNIQUE(event_id, created_at)}),
 *       so global {@code event_id} uniqueness would move to the consumers' dedup. Oracle uses interval partitioning,
 *       SQL Server a partition function/scheme; both have the same unique-key restriction.</li>
 *   <li>Payloads are {@code {text}} (JSON text). PostgreSQL production would use {@code JSONB}, Oracle 21c+ {@code JSON},
 *       SQL Server {@code NVARCHAR(MAX)} with {@code ISJSON} checks.</li>
 *   <li>Booleans ({@code deny_flag}, {@code ordered_flag}) are {@code SMALLINT} 0/1 on every dialect.</li>
 *   <li>MySQL: every table is InnoDB, utf8mb4, {@code utf8mb4_bin} (ids and keys compare case-sensitively, as on
 *       the other dialects); the widest key stays under InnoDB's 3072-byte index limit (see {@link Dialect#MYSQL}).</li>
 *   <li>{@code TOMBSTONE} adds {@code lease_expires_at} (needed for VI.5 rule 4) next to {@code expires_at}.</li>
 *   <li>{@code IDEMPOTENCY_RECORD} keeps the X.2.2.3 columns except {@code response_headers}; {@code response_body}
 *       is stored unencrypted (production: tenant-key encryption, ≤ 64 KiB, else a reference).</li>
 * </ul>
 */
public final class Schema {

    private Schema() {
    }

    private static final List<String> TEMPLATES = List.of(
            // ---------------------------------------------------------------- VI.4 + A-3
            """
            CREATE TABLE AUTH_EFFECTIVE (
              tenant_id      {str64} NOT NULL,
              principal_id   {str} NOT NULL,
              resource_type  {str64} NOT NULL,
              resource_id    {str} NOT NULL,
              deny_flag      {small} NOT NULL,
              perms          {long} NOT NULL,
              condition_id   {str64},
              cond_key       {str64} NOT NULL,
              policy_version {long} NOT NULL,
              PRIMARY KEY (tenant_id, principal_id, resource_type, resource_id, deny_flag, cond_key)
            )""",
            "CREATE INDEX AUTH_EFFECTIVE_RES ON AUTH_EFFECTIVE (tenant_id, resource_type, resource_id, principal_id)",
            """
            CREATE TABLE AUTH_POLICY_VERSION (
              tenant_id      {str64} NOT NULL PRIMARY KEY,
              policy_version {long} NOT NULL
            )""",
            // ---------------------------------------------------------------- VI.3 + A-1/A-2/A-8
            """
            CREATE TABLE OPERATION_LEDGER (
              operation_id         {str64} NOT NULL PRIMARY KEY,
              tenant_id            {str64} NOT NULL,
              root_id              {str64} NOT NULL,
              activity_instance_id {str} NOT NULL,
              loop_counter         {int} NOT NULL,
              capability_class     {str64} NOT NULL,
              request_fingerprint  {bytes32},
              state                {str64} NOT NULL,
              attempt_id           {str64},
              lock_version         {long} NOT NULL,
              attempts             {int} NOT NULL,
              outcome              {text},
              created_at           {ts} NOT NULL,
              updated_at           {ts} NOT NULL
            )""",
            "CREATE INDEX OPERATION_LEDGER_ROOT ON OPERATION_LEDGER (tenant_id, root_id)",
            """
            CREATE TABLE RECONCILIATION_ITEM (
              id            {identity} PRIMARY KEY,
              tenant_id     {str64} NOT NULL,
              kind          {str64} NOT NULL,
              operation_id  {str64},
              root_id       {str64},
              task_id       {str64},
              state         {str64} NOT NULL,
              details       {text},
              action        {str64},
              resolved_by   {str},
              resolution    {text},
              created_at    {ts} NOT NULL,
              resolved_at   {ts}
            )""",
            "CREATE INDEX RECONCILIATION_OPEN ON RECONCILIATION_ITEM (tenant_id, state, kind)",
            // ---------------------------------------------------------------- X.2.2
            """
            CREATE TABLE IDEMPOTENCY_RECORD (
              tenant_id           {str64} NOT NULL,
              command_type        {str64} NOT NULL,
              idem_key            {str} NOT NULL,
              fp_version          {small} NOT NULL,
              fingerprint         {bytes32} NOT NULL,
              principal_id        {str} NOT NULL,
              state               {str64} NOT NULL,
              owner_node          {str64},
              claim_epoch         {long} NOT NULL,
              claim_expires_at    {ts} NOT NULL,
              http_status         {small},
              response_body       {blob},
              resource_ref        {str},
              created_at          {ts} NOT NULL,
              completed_at        {ts},
              response_expires_at {ts},
              key_expires_at      {ts},
              PRIMARY KEY (tenant_id, command_type, idem_key)
            )""",
            "CREATE INDEX IDEMPOTENCY_EXPIRY ON IDEMPOTENCY_RECORD (key_expires_at)",
            // ---------------------------------------------------------------- VI.2
            """
            CREATE TABLE ROOT_SEQ (
              tenant_id       {str64} NOT NULL,
              root_id         {str64} NOT NULL,
              next_outbox_seq {long} NOT NULL,
              next_audit_seq  {long} NOT NULL,
              audit_head_hash {bytes32},
              PRIMARY KEY (tenant_id, root_id)
            )""",
            """
            CREATE TABLE OUTBOX (
              id          {identity} PRIMARY KEY,
              tenant_id   {str64} NOT NULL,
              root_id     {str64} NOT NULL,
              root_seq    {long} NOT NULL,
              event_type  {str} NOT NULL,
              event_id    {str64} NOT NULL UNIQUE,
              payload     {text},
              created_at  {ts} NOT NULL
            )""",
            "CREATE UNIQUE INDEX OUTBOX_ROOT_SEQ ON OUTBOX (tenant_id, root_id, root_seq)",
            """
            CREATE TABLE OUTBOX_DELIVERY (
              outbox_id        {long} NOT NULL,
              destination      {str64} NOT NULL,
              tenant_id        {str64} NOT NULL,
              root_id          {str64} NOT NULL,
              root_seq         {long} NOT NULL,
              ordered_flag     {small} NOT NULL,
              state            {str64} NOT NULL,
              attempts         {int} NOT NULL,
              next_attempt_at  {ts} NOT NULL,
              claim_owner      {str64},
              claim_epoch      {long} NOT NULL,
              claim_expires_at {ts},
              last_error       {text},
              PRIMARY KEY (outbox_id, destination)
            )""",
            "CREATE INDEX OUTBOX_DELIVERY_DUE ON OUTBOX_DELIVERY (state, next_attempt_at)",
            "CREATE INDEX OUTBOX_DELIVERY_LINE ON OUTBOX_DELIVERY (tenant_id, root_id, destination, root_seq)",
            """
            CREATE TABLE INBOX (
              tenant_id        {str64} NOT NULL,
              source           {str64} NOT NULL,
              message_id       {str} NOT NULL,
              received_at      {ts} NOT NULL,
              state            {str64} NOT NULL,
              correlation_key  {str},
              payload          {text},
              claim_owner      {str64},
              claim_epoch      {long} NOT NULL,
              claim_expires_at {ts},
              next_attempt_at  {ts} NOT NULL,
              expires_at       {ts} NOT NULL,
              PRIMARY KEY (tenant_id, source, message_id)
            )""",
            "CREATE INDEX INBOX_DUE ON INBOX (state, next_attempt_at)",
            // ---------------------------------------------------------------- VI.5
            """
            CREATE TABLE TOMBSTONE (
              tenant_id        {str64} NOT NULL,
              task_id          {str64} NOT NULL,
              operation_id     {str64},
              root_id          {str64} NOT NULL,
              lock_version     {long} NOT NULL,
              state            {str64} NOT NULL,
              created_at       {ts} NOT NULL,
              lease_expires_at {ts} NOT NULL,
              expires_at       {ts} NOT NULL,
              PRIMARY KEY (tenant_id, task_id)
            )""",
            // ---------------------------------------------------------------- X.2.3
            """
            CREATE TABLE AUDIT_EVENT (
              tenant_id      {str64} NOT NULL,
              chain_id       {str64} NOT NULL,
              seq            {long} NOT NULL,
              event_id       {str64} NOT NULL UNIQUE,
              event_type     {str64} NOT NULL,
              schema_version {int} NOT NULL,
              occurred_at    {ts} NOT NULL,
              envelope       {text} NOT NULL,
              payload_digest {bytes32} NOT NULL,
              prev_hash      {bytes32} NOT NULL,
              hash           {bytes32} NOT NULL,
              PRIMARY KEY (tenant_id, chain_id, seq)
            )""",
            """
            CREATE TABLE AUDIT_PAYLOAD (
              event_id     {str64} NOT NULL PRIMARY KEY,
              salt         {bytes32},
              payload      {text},
              erased_at    {ts},
              erase_reason {str}
            )""");

    private static final List<String> TABLES = List.of(
            "AUDIT_PAYLOAD", "AUDIT_EVENT", "TOMBSTONE", "INBOX", "OUTBOX_DELIVERY", "OUTBOX", "ROOT_SEQ",
            "IDEMPOTENCY_RECORD", "RECONCILIATION_ITEM", "OPERATION_LEDGER", "AUTH_POLICY_VERSION", "AUTH_EFFECTIVE");

    /** The DDL statements for one dialect, in creation order. */
    public static List<String> ddl(Dialect dialect) {
        return TEMPLATES.stream().map(dialect::expand)
                .map(sql -> sql.startsWith("CREATE TABLE") ? sql + dialect.createTableSuffix() : sql)
                .toList();
    }

    /** Product table names, in drop order. */
    public static List<String> tables() {
        return TABLES;
    }

    /** Creates every product table on {@code c}. The caller commits. */
    public static void create(Connection c, Dialect dialect) {
        try (Statement st = c.createStatement()) {
            for (String sql : ddl(dialect)) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException("schema", e);
        }
    }
}
