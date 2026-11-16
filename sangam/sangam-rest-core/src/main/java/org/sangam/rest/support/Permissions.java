// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.util.Map;
import java.util.Set;

/**
 * Endpoint permission names and the default group → permission mapping used by the default {@code PermissionPort}
 * (sangam-security replaces it with the VI.4 policy). Group names follow the X.4.1 personas.
 */
public final class Permissions {

    public static final String WORK_EXECUTE = "work.execute";
    public static final String TASK_READ = "task.read";
    public static final String TASK_WRITE = "task.write";
    public static final String PROCESS_READ = "process.read";
    public static final String PROCESS_START = "process.start";
    public static final String PROCESS_WRITE = "process.write";
    public static final String PROCESS_CANCEL = "process.cancel";
    public static final String PROCESS_DELETE = "process.delete";
    public static final String PROCESS_MODIFY = "process.modify";
    /**
     * Decision 146 (X.2.1.7 ADMIN_EXECUTOR): executor partition resize request, get and list. Platform-only: in no
     * persona group; with sangam-security the server decides it from the platform-issuer identity alone.
     */
    public static final String ADMIN_EXECUTOR = "executor.admin";
    /**
     * Owner decision 381(3): read the decision 145 tenant migration inventory and repair an instance
     * ({@code /admin/tenant-migration/**}). Platform-only, platform administrator: it spans tenants and moves an
     * instance between them; in no persona group (the server decides it from the platform-issuer identity alone).
     */
    public static final String TENANT_MIGRATION_ADMIN = "platform.tenant-migration.admin";
    /**
     * Decision 144: read the audit of a system audit scope ({@code scope} on GET /audit/events and /audit/verify).
     * Platform-only (platform administrator and platform auditor); the server maps it in SecurityPermissionBridge
     * (SYSTEM_AUDIT_READ, the same string). Tenant audit.read never reaches a system scope.
     */
    public static final String AUDIT_SYSTEM_READ = "platform.audit.system.read";
    /**
     * Decision 227: approve, reject or revoke another user's DSC certificate enrolment. Tenant-scoped, tenant
     * administrators only; the controller refuses an approver acting on their own enrolment as a second check.
     */
    public static final String DSC_CERTIFICATE_APPROVE = "dsc.certificate.approve";
    /**
     * Decision 258 (FLOWABLE-INTO-OPERATON FC-18, W4-B 04:13:28Z): move the history of an ended process instance to a later version of its
     * definition (POST /admin/history/process-instances/{id}/migrate). Tenant-scoped, tenant administrators only.
     */
    public static final String HISTORY_MIGRATE = "history.migrate";
    /**
     * Decision 305: read the tenant's stored DLT registration (GET /admin/dlt-registration). Tenant-scoped, tenant
     * administrators only.
     */
    public static final String DLT_REGISTRATION_READ = "dlt.registration.read";
    /**
     * Decision 305: change the tenant's stored DLT registration (entity set, header, template and URL prefix add).
     * Tenant-scoped, tenant administrators only.
     */
    public static final String DLT_REGISTRATION_WRITE = "dlt.registration.write";
    public static final String CASE_READ = "case.read";
    public static final String CASE_WRITE = "case.write";
    public static final String DECISION_EVALUATE = "decision.evaluate";
    public static final String MESSAGE_SEND = "message.send";
    public static final String DEPLOYMENT_READ = "deployment.read";
    public static final String DEPLOYMENT_WRITE = "deployment.write";
    public static final String PACKAGE_READ = "package.read";
    public static final String PACKAGE_AUTHOR = "package.author";
    public static final String PACKAGE_REVIEW = "package.review";
    public static final String PACKAGE_APPROVE = "package.approve";
    public static final String PACKAGE_DEPLOY = "package.deploy";
    public static final String PACKAGE_OPERATE = "package.operate";
    public static final String DOCUMENT_READ = "document.read";
    public static final String DOCUMENT_WRITE = "document.write";
    public static final String HISTORY_READ = "history.read";
    public static final String AUDIT_READ = "audit.read";
    public static final String AUDIT_EXPORT = "audit.export";
    public static final String ADMIN_READ = "admin.read";
    public static final String ADMIN_WRITE = "admin.write";
    public static final String POLICY_ADMIN = "policy.admin";
    public static final String TENANT_ADMIN = "tenant.admin";
    public static final String OPS_READ = "ops.read";
    public static final String OPS_WRITE = "ops.write";
    public static final String RECON_RESOLVE = "recon.resolve";
    public static final String BATCH_ADMIN = "batch.admin";
    public static final String EVENT_ADMIN = "event.admin";
    public static final String EVENT_PUBLISH = "event.publish";
    public static final String FORM_READ = "form.read";
    public static final String FORM_DRAFT = "form.draft";
    public static final String ANALYTICS_READ = "analytics.read";
    public static final String ENGINE_REST = "engine.rest";
    public static final String PORTAL_USE = "portal.use";
    public static final String DEBUG_USE = "debug.use";
    public static final String ANALYTICS_ADMIN = "analytics.admin";
    public static final String DOCUMENT_LEGAL_HOLD = "document.legal-hold";
    public static final String POWER_TABLE_APPROVE = "power-table.approve";
    public static final String LOWCODE_READ = "lowcode.read";
    public static final String LOWCODE_ADMIN = "lowcode.admin";
    /** Decision 284: create a data-object record (POST /data-objects/{key}/records); lowcode.read only reads. */
    public static final String LOWCODE_RECORD_WRITE = "lowcode.record.write";
    /** sangam-compliance (resource type COMPLIANCE): queries, case work, configuration. */
    public static final String COMPLIANCE_READ = "compliance.read";
    public static final String COMPLIANCE_MODIFY = "compliance.modify";
    public static final String COMPLIANCE_ADMIN = "compliance.admin";

    /** Data protection officer / grievance officer: all compliance permissions plus audit read. */
    public static final String GROUP_DPO = "sangam-dpo";

    /** Generic modules (final integration wave): read / write / admin per module, plus the module-specific duties. */
    public static final Set<String> MODULE_READ = Set.of("config.read", "ledger.read", "billing.read", "registry.read",
            "resources.read", "geo.read", "case.read", "programme.read", "channels.read");
    public static final Set<String> MODULE_WRITE = Set.of("ledger.write", "billing.write", "registry.write",
            "resources.write", "geo.write", "case.write", "programme.write", "field.review", "dispatch.operate", "ai.read", "ops.tenant.read", "channels.write", "modeller.action.invoke");
    public static final Set<String> MODULE_ADMIN = Set.of("config.write", "config.admin", "ledger.admin", "billing.admin",
            "registry.admin", "resources.admin", "case.admin", "programme.approve", "programme.admin", "field.admin", "ai.review", "ai.admin", "ops.tenant.admin", "channels.admin", "modeller.action.publish", "modeller.lease.takeover");

    /** External applicants (PER-8): portal only, never staff APIs. */
    public static final String GROUP_CITIZEN = "sangam-citizen";

    /**
     * Platform administrator (every tenant). SL-70: only an identity from a configured platform issuer holds it; in a
     * tenant token {@code sangam-admin} is replaced by {@link #GROUP_TENANT_ADMIN} when the identity is built.
     */
    public static final String GROUP_ADMIN = "sangam-admin";
    /**
     * SL-70 marker of every identity from a platform issuer ({@code sangam.security.platform-issuer}), set by the server
     * when the identity is built and removed from every other token. Engine-wide operations require it.
     */
    public static final String GROUP_PLATFORM = "sangam-platform";
    public static final String GROUP_TENANT_ADMIN = "sangam-tenant-admin";
    public static final String GROUP_OPERATOR = "sangam-operator";
    public static final String GROUP_AUDITOR = "sangam-auditor";
    public static final String GROUP_WORKER = "sangam-worker";
    public static final String GROUP_USER = "sangam-user";
    public static final String GROUP_MODEL_AUTHOR = "sangam-model-author";
    public static final String GROUP_MODEL_REVIEWER = "sangam-model-reviewer";
    public static final String GROUP_MODEL_APPROVER = "sangam-model-approver";
    public static final String GROUP_MODEL_DEPLOYER = "sangam-model-deployer";
    public static final String GROUP_INTEGRATION = "sangam-integration";
    public static final String GROUP_SYSTEM = "sangam-system";
    /** Engine administrators: the unchanged Operaton REST API ({@code /engine-rest/**}) and nothing else extra. */
    public static final String GROUP_ENGINE_ADMIN = "sangam-engine-admin";

    /**
     * The only groups admitted to {@code /engine-rest/**} (server gate, {@code SecurityConfig}). The workspace
     * {@code /me} roles derive the UI platform role from the same set, so a UI engine screen is shown exactly to the
     * callers the server lets through (SL-37). SL-70: {@code sangam-admin} here is the platform administrator (a
     * tenant token's {@code sangam-admin} arrives as {@code sangam-tenant-admin}, which is not an engine group).
     */
    public static final Set<String> ENGINE_REST_GROUPS = Set.of(GROUP_ADMIN, GROUP_ENGINE_ADMIN);

    private static final Set<String> USER_BASE = Set.of(TASK_READ, TASK_WRITE, PROCESS_READ, PROCESS_START, PROCESS_WRITE,
            PROCESS_CANCEL, CASE_READ, CASE_WRITE, DECISION_EVALUATE, MESSAGE_SEND, DOCUMENT_READ, DOCUMENT_WRITE,
            FORM_READ, FORM_DRAFT, PACKAGE_READ, DEPLOYMENT_READ, LOWCODE_READ,
            // decision 284: record entry on low-code pages is user data entry, like DOCUMENT_WRITE and CASE_WRITE
            LOWCODE_RECORD_WRITE);

    private static final Set<String> USER = union(union(USER_BASE, MODULE_READ), Set.of("field.capture", "ai.use", "search.use"));

    public static final Map<String, Set<String>> DEFAULT_GROUP_PERMISSIONS = Map.ofEntries(
            Map.entry(GROUP_USER, USER),
            Map.entry(GROUP_CITIZEN, Set.of(PORTAL_USE)),
            Map.entry(GROUP_WORKER, Set.of(WORK_EXECUTE, MESSAGE_SEND, DOCUMENT_READ, DOCUMENT_WRITE, EVENT_PUBLISH)),
            Map.entry(GROUP_INTEGRATION, Set.of(WORK_EXECUTE, MESSAGE_SEND, EVENT_PUBLISH, PROCESS_START,
                    PROCESS_READ, DEPLOYMENT_READ)),
            Map.entry(GROUP_OPERATOR, union(union(USER, MODULE_WRITE), Set.of(OPS_READ, OPS_WRITE, RECON_RESOLVE, BATCH_ADMIN,
                    PROCESS_DELETE, PROCESS_MODIFY, HISTORY_READ, ANALYTICS_READ, ANALYTICS_ADMIN, DEPLOYMENT_READ,
                    DEBUG_USE, COMPLIANCE_READ, COMPLIANCE_MODIFY))),
            Map.entry(GROUP_AUDITOR, union(MODULE_READ, Set.of(AUDIT_READ, AUDIT_EXPORT, HISTORY_READ, ANALYTICS_READ, PROCESS_READ,
                    TASK_READ, CASE_READ, DOCUMENT_READ, PACKAGE_READ, DOCUMENT_LEGAL_HOLD, COMPLIANCE_READ))),
            Map.entry(GROUP_TENANT_ADMIN, union(union(union(MODULE_READ, MODULE_WRITE), MODULE_ADMIN), Set.of(ADMIN_READ, ADMIN_WRITE, POLICY_ADMIN, TENANT_ADMIN, EVENT_ADMIN,
                    PACKAGE_READ, PACKAGE_AUTHOR, DEPLOYMENT_READ, ANALYTICS_READ, ANALYTICS_ADMIN, HISTORY_READ,
                    // SL-166: a tenant admin defines and retires custom fields (tenant.admin), so it must list them
                    FORM_READ,
                    POWER_TABLE_APPROVE, DOCUMENT_LEGAL_HOLD, LOWCODE_READ, LOWCODE_ADMIN, LOWCODE_RECORD_WRITE, COMPLIANCE_READ,
                    COMPLIANCE_MODIFY, COMPLIANCE_ADMIN,
                    // decision 227: DSC certificate enrolment approval
                    DSC_CERTIFICATE_APPROVE,
                    // decision 258: FLOWABLE-INTO-OPERATON FC-18 historic instance migration
                    HISTORY_MIGRATE,
                    // decision 305: the tenant's DLT registration (entity, headers, templates, URL prefixes)
                    DLT_REGISTRATION_READ, DLT_REGISTRATION_WRITE))),
            Map.entry(GROUP_DPO, Set.of(COMPLIANCE_READ, COMPLIANCE_MODIFY, COMPLIANCE_ADMIN, AUDIT_READ, AUDIT_EXPORT,
                    HISTORY_READ, DOCUMENT_READ, DOCUMENT_LEGAL_HOLD)),
            Map.entry(GROUP_MODEL_AUTHOR, Set.of(PACKAGE_READ, PACKAGE_AUTHOR, DEPLOYMENT_READ, FORM_READ, DEBUG_USE,
                    LOWCODE_READ, LOWCODE_ADMIN, LOWCODE_RECORD_WRITE)),
            Map.entry(GROUP_MODEL_REVIEWER, Set.of(PACKAGE_READ, PACKAGE_REVIEW, DEPLOYMENT_READ)),
            Map.entry(GROUP_MODEL_APPROVER, Set.of(PACKAGE_READ, PACKAGE_REVIEW, PACKAGE_APPROVE, DEPLOYMENT_READ)),
            Map.entry(GROUP_MODEL_DEPLOYER, Set.of(PACKAGE_READ, PACKAGE_DEPLOY, PACKAGE_OPERATE, DEPLOYMENT_READ,
                    DEPLOYMENT_WRITE)),
            Map.entry(GROUP_SYSTEM, Set.of(WORK_EXECUTE, MESSAGE_SEND, EVENT_PUBLISH, PROCESS_START, PROCESS_READ,
                    PROCESS_WRITE, OPS_READ, OPS_WRITE)));

    private Permissions() {
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.Set<String> s = new java.util.HashSet<>(a);
        s.addAll(b);
        return Set.copyOf(s);
    }
}
