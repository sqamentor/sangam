// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.sql.DataSource;

import liquibase.Scope;
import org.sangam.core.licence.LiquibaseLicenceGuard;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the Sangam product schema (R12, ADR-D-7, FLOWABLE-INTO-OPERATON D-54): the Liquibase 4.33.0 master changelog
 * {@value #MASTER_CHANGELOG} with one formatted-SQL changelog per dialect (PostgreSQL, Oracle, SQL Server,
 * MySQL 8.4, H2), and, separately, the engine schema patches {@value #ENGINE_PATCHES_CHANGELOG} (P-1, P-2).
 *
 * <p>Concurrency: Liquibase takes its session lock (DATABASECHANGELOGLOCK), so two nodes starting together apply the
 * changelog once (R12: "one controlled migration job with a session lock"). In production the same changelog runs
 * from the migration Job / CLI ({@code liquibase update --changelog-file=db/changelog/sangam-master.xml}); the
 * application may also call {@link #apply(DataSource)} at start-up (profile switch in sangam-server).
 *
 * <p>Order at start-up: {@link #apply} (product tables) → engine build (ACT_* tables) →
 * {@link #applyEnginePatches} (P-1/P-2 on MySQL; no-op elsewhere).
 */
public final class SangamSchema {

    private static final Logger LOG = LoggerFactory.getLogger(SangamSchema.class);

    public static final String MASTER_CHANGELOG = "db/changelog/sangam-master.xml";
    public static final String ENGINE_PATCHES_CHANGELOG = "db/changelog/sangam-engine-patches.xml";
    /** IR-8: optional ACT_HI_* partitioning; never part of the master (see the changelog's header). */
    public static final String HISTORY_PARTITIONING_CHANGELOG = "db/changelog/sangam-history-partitioning.xml";
    public static final String HISTORY_PARTITIONING_CONTEXT = "partitioned-history";

    /** Product tables created by the master changelog (documented in sangam-persistence/README.md). */
    public static final List<String> PRODUCT_TABLES = List.of(
            "SANGAM_LEASE", "SANGAM_FETCH_LOCK", "EXCLUSIVE_LEASE",
            "OPERATION_LEDGER", "RECONCILIATION_ITEM", "SANGAM_ENGINE_INCIDENT",
            "IDEMPOTENCY_RECORD",
            "ROOT_SEQ", "OUTBOX", "OUTBOX_DELIVERY", "INBOX",
            "TOMBSTONE",
            "AUDIT_EVENT", "AUDIT_PAYLOAD", "AUDIT_CHECKPOINT",
            // VI.4 and IX.5: exact shapes of sangam-security's reference DDL (integrator decision)
            "AUTH_EFFECTIVE", "AUTH_POLICY_VERSION", "AUTH_GRANT_SOURCE", "AUTH_CONDITION", "AUTH_MEMBERSHIP",
            "SEC_POLICY", "SEC_POLICY_CHANGE", "SEC_ROLE", "SEC_ROLE_BINDING", "SEC_GROUP", "SEC_GROUP_MEMBER",
            "SEC_PRIVILEGE", "SEC_PRIVILEGE_MAPPING", "SEC_API_TOKEN", "SEC_WORKER_BINDING", "SEC_BULK_PLAN",
            "SEC_FIELD_RULE", "SEC_TENANT", "SEC_TENANT_CHECKLIST", "SEC_TENANT_QUOTA_WINDOW", "SEC_PERSON", "SEC_ORG_LEVEL", "SEC_ORG_UNIT",
            "SEC_ORG_UNIT_PARENT", "SEC_ORG_UNIT_NAME", "SEC_TENANT_KEY", "SEC_SEAT", "SEC_SEAT_PLACEMENT", "SEC_POSTING", "SEC_LEAVE", "SEC_POWER_TABLE",
            "SEC_CASE_ACTOR", "SEC_SOD_EXCEPTION", "SEC_APPROVAL",
            // WS-INTEGRATION reference DDL (IntegrationSchema)
            "CONNECTOR_CALL", "INTEGRATION_DEAD_LETTER", "INTEGRATION_LEADER", "INTEGRATION_WEBHOOK_STATE", "UNIQUE_START",
            "RECONCILIATION_ATTEMPT", "LEGAL_HOLD",
            "FORM_SUBMISSION", "FORM_DRAFT", "ATTACHMENT",
            "PACK_DEFINITION", "PACK_TENANT_CONFIG", "PACKAGE_VERSION",
            // WS-PORTS reference DDL (PortsSchema)
            "EVT_CHANNEL_DEF", "EVT_EVENT_DEF", "EVT_DEFINITION_SEQ", "EVT_LOG", "INSTANCE_LINK", "CONTENT_ITEM",
            "CONTENT_VERSION", "FORM_DEF", "APP_DEPLOYMENT", "PORT_BATCH", "PORT_BATCH_ITEM", "MI_AGGREGATE",
            "TASK_LIFECYCLE", "INSTANCE_BUSINESS_STATUS", "HRM_PROCESS_INSTANCE", "HRM_ACTIVITY_INSTANCE", "HRM_TASK",
            "HRM_CHECKPOINT", "DYN_INJECTION", "SLA_INSTANCE",
            "JOB_ACQUIRE_LOCK", "DEBUG_BREAKPOINT",
            // packs: WS-INTEGRATION PACK_OPERATION + WS-PACKS ledger ports (JDBC implementations in sangam-engine)
            "PACK_OPERATION", "PACK_LEDGER_ACCOUNT", "PACK_LEDGER_POSTING", "PACK_BATCH_ITEM",
            // sangam-config ConfigSchema
            "CFG_PROFILE", "CFG_CHANGE", "CFG_MASTER_CODE", "CFG_SERIES", "CFG_CASE_BINDING",
            // sangam-onboarding
            "ONB_RUN",
            // sangam-ledger LedgerSchema (also the billing tables)
            "LDG_BALANCE", "LDG_ENTRY", "LDG_HOLD", "LDG_FREEZE", "LDG_ACCOUNTING",
            // sangam-registry RegistrySchema
            "REG_OBJECT_TYPE", "REG_OBJECT", "REG_OBJECT_VERSION", "REG_NUMBER_SERIES",
            "REG_NUMBER_COUNTER", "REG_NUMBER_ALLOCATION", "REG_REGISTER_DEF", "REG_REGISTER_BOOK",
            "REG_REGISTER_ENTRY", "REG_CERTIFIED_EXTRACT", "REG_VERIFICATION", "REG_ENDORSEMENT",
            "REG_INSTRUMENT_DOC", "REG_PARTY", "REG_PARTY_IDENTIFIER", "REG_PARTY_RELATIONSHIP",
            "REG_PARTY_ADDRESS", "REG_PARTY_BLOCK", "REG_MATCH_CANDIDATE", "REG_MERGE_EVENT",
            "REG_PARTY_SNAPSHOT", "REG_PARTY_CONFIG", "REG_ASSET_CLASS", "REG_ASSET", "REG_ASSET_MOVEMENT",
            "REG_METER", "REG_METER_READING", "REG_MAINT_PLAN", "REG_WORK_ORDER", "REG_CUSTODY_ITEM",
            "REG_CUSTODY_EVENT", "REG_CUSTODY_TRANSFER", "REG_DISPOSAL_ORDER",
            // sangam-resources ResourcesSchema
            "RES_TYPE", "RES_RESOURCE", "RES_RESOURCE_ATTR", "RES_CALENDAR", "RES_BLACKOUT",
            "RES_SLOT_TEMPLATE", "RES_BOOKING", "RES_CLAIM", "RES_ALLOTMENT", "RES_WAITLIST", "RES_DRAW",
            "RES_BOOKING_SEQ", "RES_EVENT", "RES_SERVICE_POINT", "RES_COUNTER", "RES_COUNTER_SKILL",
            "RES_TOKEN_SEQ", "RES_TOKEN", "RES_CALL_EVENT", "RES_SHIFT_TEMPLATE", "RES_ROTATION",
            "RES_ROSTER_PERIOD", "RES_ROSTER_MEMBER", "RES_COVERAGE_DEMAND", "RES_ROSTER_ASSIGNMENT",
            "RES_PERSON_CLAIM", "RES_SWAP_REQUEST", "RES_SWEEP_LEASE",
            // sangam-geo GeoSchema
            "GEO_LAYER", "GEO_FEATURE", "GEO_FEATURE_CELL", "GEO_FENCE", "GEO_OBJECT_GEOMETRY",
            "GEO_EXTERNAL_LAYER",
            // sangam-field FieldSchema
            "FLD_TEMPLATE", "FLD_ASSIGNMENT", "FLD_SCHEDULE", "FLD_SELECTION", "FLD_DEVICE", "FLD_SYNC_OP",
            "FLD_SEQ", "FLD_CHANGE", "FLD_CONFLICT", "FLD_PACKET", "FLD_EVIDENCE", "FLD_FINDING",
            "FLD_UNIT", "FLD_JOB", "FLD_OFFER", "FLD_TRIP", "FLD_POSITION",
            // sangam-case CaseFileSchema
            "CF_CONFIG", "CF_NUMBER_SEQ", "CF_CASE", "CF_PARTY", "CF_PARTY_KEY", "CF_CASE_PARTY",
            "CF_CASE_RECORD", "CF_FOLDER", "CF_DOCUMENT", "CF_NOTE", "CF_EVENT", "CF_HEARING",
            "CF_CASE_LINK", "CF_DEADLINE", "CF_COMMITTEE", "CF_COMMITTEE_MEMBER", "CF_MEETING",
            "CF_AGENDA_ITEM", "CF_ATTENDANCE", "CF_NOTICE", "CF_MINUTES", "CF_DECISION", "CF_ACTION_ITEM",
            "CF_REFERENCE", "CF_REFERENCE_MOVE",
            // sangam-programme ProgrammeSchema
            "PG_CONFIG", "PG_PROGRAMME", "PG_INDICATOR", "PG_PERIOD", "PG_TARGET", "PG_MILESTONE",
            "PG_RETURN_DEF", "PG_RETURN", "PG_RETURN_VALUE", "PG_RETURN_FINDING", "PG_RETURN_EVENT",
            "PG_ACHIEVEMENT", "PG_FINANCIAL", "PG_ALERT", "PG_DASHBOARD_SNAPSHOT",
            "SANGAM_FEEL_BINDING", // R10
            "MOD_SERVICE_MOCK", "OPS_RETENTION_RUN",
            // ChannelsSchema, ModellerSchema, OpsSchema
            "CH_CONVERSATION", "CH_PARTICIPANT", "CH_MESSAGE", "CH_OPT_IN", "CH_INBOUND_SEEN", "CH_POLL_CURSOR", "MOD_EDIT_LEASE", "MOD_TAKEOVER_DRAFT", "MOD_TEMPLATE", "MOD_ACTION", "MOD_ACTION_RUN", "MOD_TEST_RUN", "OPS_MIG_CHECKPOINT", "OPS_MIG_ROW", "OPS_USAGE_DAILY", "OPS_SEARCH_DOC", "OPS_SEARCH_ALIAS", "OPS_INDEX_JOB", "OPS_VARIABLE", "OPS_SECRET",
            // AiSchema, ComplianceSchema, PipelineStore, TelemetrySchema
            "AI_AGENT_RUN", "AI_AGENT_STEP", "AI_AGENT_APPROVAL", "AI_KB_VERSION", "AI_KB_CHUNK", "AI_DRAFT",
            "AI_CLASSIFICATION", "CMP_RESTRICTION", "CMP_BREACH_PLAN", "PIPELINE_RUN", "PIPELINE_STEP_RUN",
            "PIPELINE_SCHEDULE", "TELEMETRY_DEVICE", "ALARM_EPISODE",
            "SANGAM_TIMER_GUARD", "SANGAM_PAUSE",
            // WS-REST default adapters: typed JSON documents and binary contents (nothing kept in memory)
            "REST_DOCUMENT", "REST_BLOB",
            // sangam-compliance (ComplianceSchema): DPDP Act 2023 / Rules 2025, CERT-In (GOV-4, R53)
            "CMP_PURPOSE", "CMP_OBJECTION", "CMP_PROCESSING_ACTIVITY", "CMP_DISCLOSURE", "CMP_LAWFUL_REQUEST",
            "CMP_CONTACT", "CMP_TIER_STATUS", "CMP_IMPACT_ASSESSMENT", "CMP_AUDIT_EVIDENCE", "CMP_NOTICE",
            "CMP_NOTICE_DELIVERY", "CMP_CONSENT", "CMP_CONSENT_PURPOSE", "CMP_GUARDIAN_VERIFICATION",
            "CMP_RETENTION_ITEM", "CMP_RIGHTS_REQUEST", "CMP_NOMINATION", "CMP_INCIDENT", "CMP_BREACH",
            "CMP_BREACH_NOTICE", "CMP_BREACH_ROOT", "CMP_LOG_SEGMENT", "CMP_LOG_SOURCE", "CMP_NTP_CHECK",
            "CMP_TRANSFER_DECISION", "CMP_REPORT");

    /**
     * Changesets that alter a table created earlier (generator {@code ALTERS}): FORM_DRAFT.form_version_hash,
     * SEC_POWER_TABLE name/test_cases/deployment_id/row_revision, PACKAGE_VERSION.package_id,
     * DEBUG_BREAKPOINT.process_instance_id (0016-03..06); EVT_CHANNEL_DEF / EVT_EVENT_DEF state, retired_by,
     * retired_at; PORT_BATCH reason, four_eyes_flag, approved_by/_at, completed_at + PORT_BATCH_STATE;
     * SLA_INSTANCE.definition_key + SLA_INSTANCE_STATS; HRM_PI_QUERY; HRM_TASK_QUERY (0017-01..06).
     * The changelog holds {@code PRODUCT_TABLES.size() + ADDITIONAL_CHANGESETS} changesets.
     */
    public static final int ADDITIONAL_CHANGESETS = 10;

    /** Liquibase dbms names used in the changelogs. */
    public enum Dbms {
        POSTGRESQL("postgresql"), ORACLE("oracle"), MSSQL("mssql"), MYSQL("mysql"), H2("h2");

        private final String liquibaseName;

        Dbms(String liquibaseName) {
            this.liquibaseName = liquibaseName;
        }

        public String liquibaseName() {
            return liquibaseName;
        }

        /** From {@link java.sql.DatabaseMetaData#getDatabaseProductName()}; MariaDB servers count as MySQL. */
        public static Dbms fromProductName(String productName) {
            String p = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
            if (p.contains("postgres")) {
                return POSTGRESQL;
            }
            if (p.contains("oracle")) {
                return ORACLE;
            }
            if (p.contains("sql server")) {
                return MSSQL;
            }
            if (p.contains("mysql") || p.contains("mariadb")) {
                return MYSQL;
            }
            if (p.startsWith("h2")) {
                return H2;
            }
            throw new IllegalArgumentException("unsupported database for the Sangam schema: " + productName);
        }
    }

    private SangamSchema() {
    }

    /** Applies the product changelog on a dedicated connection taken from {@code dataSource}. */
    public static void apply(DataSource dataSource) {
        run(dataSource, MASTER_CHANGELOG, null);
    }

    /** Applies the product changelog with Liquibase contexts (for example "seed-dev"). */
    public static void apply(DataSource dataSource, String contexts) {
        run(dataSource, MASTER_CHANGELOG, contexts);
    }

    /**
     * IR-8: partitions the high-volume {@code ACT_HI_*} tables by {@code REMOVAL_TIME_} (PostgreSQL, Oracle, SQL Server;
     * no-op on MySQL and H2). Call after the engine created its tables; idempotent.
     */
    public static void applyHistoryPartitioning(DataSource dataSource) {
        run(dataSource, HISTORY_PARTITIONING_CHANGELOG, HISTORY_PARTITIONING_CONTEXT);
    }

    /** Applies the engine schema patches (P-1, P-2). Call after the engine created/upgraded its tables. */
    public static void applyEnginePatches(DataSource dataSource) {
        run(dataSource, ENGINE_PATCHES_CHANGELOG, null);
    }

    /**
     * Applies {@code changelog} on {@code connection}. Liquibase commits its own changesets; the caller keeps
     * ownership of the connection (it is not closed) and its auto-commit flag is restored.
     */
    public static void apply(Connection connection, String changelog, String contexts) {
        Objects.requireNonNull(connection, "connection");
        LiquibaseLicenceGuard.check(); // SG-26: never run a non-4.x Liquibase
        boolean autoCommit = true;
        try {
            autoCommit = connection.getAutoCommit();
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            ClassLoader cl = SangamSchema.class.getClassLoader();
            Scope.child(Scope.Attr.resourceAccessor.name(), new ClassLoaderResourceAccessor(cl), () -> {
                CommandScope update = new CommandScope(UpdateCommandStep.COMMAND_NAME)
                        .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
                        .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, changelog);
                if (contexts != null && !contexts.isBlank()) {
                    update.addArgumentValue(UpdateCommandStep.CONTEXTS_ARG, contexts);
                }
                update.execute();
            });
            LOG.info("Sangam schema: {} applied on {}", changelog, database.getShortName());
        } catch (Exception e) {
            throw new SchemaMigrationException("Liquibase update of " + changelog + " failed", e);
        } finally {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // the connection is broken; the caller's pool discards it
            }
        }
    }

    /** The dialect the changelog will apply to this connection's database. */
    public static Dbms detect(Connection connection) {
        try {
            return Dbms.fromProductName(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new SchemaMigrationException("cannot read database metadata", e);
        }
    }

    private static void run(DataSource dataSource, String changelog, String contexts) {
        try (Connection c = dataSource.getConnection()) {
            detect(c); // fail fast on an unsupported database
            apply(c, changelog, contexts);
        } catch (SQLException e) {
            throw new SchemaMigrationException("cannot open a connection for " + changelog, e);
        }
    }

    /** A failed migration (R12: the documented recovery procedure is in sangam-persistence/README.md). */
    public static final class SchemaMigrationException extends RuntimeException {
        public SchemaMigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
