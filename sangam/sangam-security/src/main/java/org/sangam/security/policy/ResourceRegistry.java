// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import org.sangam.core.security.SangamSecurityException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protected resource types and their table mappings (VI.4 rules 1, 3 and 8). Engine tables are Operaton 2.2.0's
 * ({@code ACT_RU_*}, {@code ACT_HI_*}); product tables are Sangam's. Packs may register further types
 * (e.g. data objects, R78) at start-up.
 */
public final class ResourceRegistry {

    // ---- engine runtime and history (VI.4 rule 8)
    public static final String TASK = "TASK";
    public static final String PROCESS_INSTANCE = "PROCESS_INSTANCE";
    public static final String PROCESS_DEFINITION = "PROCESS_DEFINITION";
    public static final String CASE_INSTANCE = "CASE_INSTANCE";
    public static final String DECISION_DEFINITION = "DECISION_DEFINITION";
    public static final String DEPLOYMENT = "DEPLOYMENT";
    public static final String EXTERNAL_TASK = "EXTERNAL_TASK";
    public static final String INCIDENT = "INCIDENT";
    public static final String JOB = "JOB";
    public static final String BATCH = "BATCH";
    public static final String HISTORIC_PROCESS_INSTANCE = "HISTORIC_PROCESS_INSTANCE";
    public static final String HISTORIC_TASK = "HISTORIC_TASK";
    public static final String HISTORIC_DECISION = "HISTORIC_DECISION";
    // ---- product
    public static final String AUDIT = "AUDIT";
    public static final String INSTANCE_LINK = "INSTANCE_LINK";
    public static final String RECONCILIATION_ITEM = "RECONCILIATION_ITEM";
    public static final String OUTBOX = "OUTBOX";
    // ---- virtual admin resources (object checks only, B-11: fine-grained model for admin APIs)
    public static final String TENANT = "TENANT";
    public static final String POLICY = "POLICY";
    public static final String ROLE = "ROLE";
    public static final String GROUP = "GROUP";
    public static final String USER = "USER";
    public static final String ORG_UNIT = "ORG_UNIT";
    public static final String SEAT = "SEAT";
    public static final String POSTING = "POSTING";
    public static final String POWER_TABLE = "POWER_TABLE";
    public static final String SOD_EXCEPTION = "SOD_EXCEPTION";
    public static final String PRIVILEGE = "PRIVILEGE";
    public static final String API_TOKEN = "API_TOKEN";
    public static final String WORKER_TOPIC = "WORKER_TOPIC";
    public static final String OPERATIONS = "OPERATIONS";

    /** Resource-type wildcard: a grant or deny on {@code '*'} type covers every type (A-13). */
    public static final String ANY_TYPE = "*";
    /** Resource-id wildcard. */
    public static final String ANY_ID = "*";

    private final Map<String, ResourceDescriptor> descriptors = new ConcurrentHashMap<>();
    private final Map<String, Set<Permission>> permissionSets = new ConcurrentHashMap<>();

    public ResourceRegistry() {
        registerDefaults();
        ModuleResourceTypes.registerAll(this);
    }

    public void register(ResourceDescriptor d) {
        descriptors.put(d.resourceType(), d);
    }

    /**
     * Registers a type with the permissions that are meaningful on it. Policies naming other permissions on this type
     * are rejected at deployment ({@link PolicyCompiler#validate}).
     */
    public void register(ResourceDescriptor d, Set<Permission> permissions) {
        descriptors.put(d.resourceType(), d);
        permissionSets.put(d.resourceType(), Set.copyOf(permissions));
    }

    /** The permission set of a type; empty means "not restricted" (engine and core types). */
    public Set<Permission> permissionsOf(String resourceType) {
        return permissionSets.getOrDefault(resourceType, Set.of());
    }

    /** All registered type names (admin UI pick lists). */
    public Set<String> types() {
        return Set.copyOf(descriptors.keySet());
    }

    public ResourceDescriptor get(String resourceType) {
        ResourceDescriptor d = descriptors.get(resourceType);
        if (d == null) {
            throw new SangamSecurityException.PolicyRejected("unknown resource type " + resourceType);
        }
        return d;
    }

    public boolean known(String resourceType) {
        return ANY_TYPE.equals(resourceType) || descriptors.containsKey(resourceType);
    }

    private void registerDefaults() {
        register(new ResourceDescriptor(TASK, "ACT_RU_TASK", "TENANT_ID_", "ID_", null,
                List.of(new ResourceDescriptor.Scope("procinst:", "PROC_INST_ID_"),
                        new ResourceDescriptor.Scope("taskdef:", "TASK_DEF_KEY_")),
                Map.of("assignee", "ASSIGNEE_", "owner", "OWNER_", "priority", "PRIORITY_",
                        "taskDefinitionKey", "TASK_DEF_KEY_", "processInstanceId", "PROC_INST_ID_",
                        "processDefinitionId", "PROC_DEF_ID_", "name", "NAME_", "caseInstanceId", "CASE_INST_ID_")));
        register(new ResourceDescriptor(PROCESS_INSTANCE, "ACT_RU_EXECUTION", "TENANT_ID_", "ID_", "r.ID_ = r.PROC_INST_ID_",
                List.of(new ResourceDescriptor.Scope("bk:", "BUSINESS_KEY_")),
                Map.of("businessKey", "BUSINESS_KEY_", "processDefinitionId", "PROC_DEF_ID_",
                        "suspensionState", "SUSPENSION_STATE_")));
        register(new ResourceDescriptor(PROCESS_DEFINITION, "ACT_RE_PROCDEF", "TENANT_ID_", "ID_", null,
                List.of(new ResourceDescriptor.Scope("def:", "KEY_")),
                Map.of("key", "KEY_", "version", "VERSION_", "versionTag", "VERSION_TAG_", "category", "CATEGORY_")));
        register(new ResourceDescriptor(DECISION_DEFINITION, "ACT_RE_DECISION_DEF", "TENANT_ID_", "ID_", null,
                List.of(new ResourceDescriptor.Scope("dec:", "KEY_")),
                Map.of("key", "KEY_", "version", "VERSION_", "versionTag", "VERSION_TAG_")));
        register(new ResourceDescriptor(DEPLOYMENT, "ACT_RE_DEPLOYMENT", "TENANT_ID_", "ID_", null, List.of(),
                Map.of("name", "NAME_", "source", "SOURCE_")));
        register(new ResourceDescriptor(CASE_INSTANCE, "ACT_RU_CASE_EXECUTION", "TENANT_ID_", "ID_", "r.ID_ = r.CASE_INST_ID_",
                List.of(new ResourceDescriptor.Scope("bk:", "BUSINESS_KEY_")),
                Map.of("businessKey", "BUSINESS_KEY_", "caseDefinitionId", "CASE_DEF_ID_")));
        register(new ResourceDescriptor(EXTERNAL_TASK, "ACT_RU_EXT_TASK", "TENANT_ID_", "ID_", null,
                List.of(new ResourceDescriptor.Scope("procinst:", "PROC_INST_ID_"),
                        new ResourceDescriptor.Scope("topic:", "TOPIC_NAME_")),
                Map.of("topic", "TOPIC_NAME_", "workerId", "WORKER_ID_", "processInstanceId", "PROC_INST_ID_",
                        "processDefinitionKey", "PROC_DEF_KEY_")));
        register(new ResourceDescriptor(INCIDENT, "ACT_RU_INCIDENT", "TENANT_ID_", "ID_", null,
                List.of(new ResourceDescriptor.Scope("procinst:", "PROC_INST_ID_")),
                Map.of("incidentType", "INCIDENT_TYPE_", "processInstanceId", "PROC_INST_ID_")));
        register(new ResourceDescriptor(JOB, "ACT_RU_JOB", "TENANT_ID_", "ID_", null,
                List.of(new ResourceDescriptor.Scope("procinst:", "PROCESS_INSTANCE_ID_"),
                        new ResourceDescriptor.Scope("def:", "PROCESS_DEF_KEY_")),
                Map.of("processInstanceId", "PROCESS_INSTANCE_ID_", "processDefinitionKey", "PROCESS_DEF_KEY_",
                        "retries", "RETRIES_")));
        register(new ResourceDescriptor(BATCH, "ACT_RU_BATCH", "TENANT_ID_", "ID_", null, List.of(),
                Map.of("type", "TYPE_", "createUserId", "CREATE_USER_ID_")));
        register(new ResourceDescriptor(HISTORIC_PROCESS_INSTANCE, "ACT_HI_PROCINST", "TENANT_ID_", "ID_", null,
                List.of(new ResourceDescriptor.Scope("def:", "PROC_DEF_KEY_"),
                        new ResourceDescriptor.Scope("bk:", "BUSINESS_KEY_"),
                        new ResourceDescriptor.Scope("procinst:", "PROC_INST_ID_")),
                Map.of("businessKey", "BUSINESS_KEY_", "processDefinitionKey", "PROC_DEF_KEY_",
                        "startUserId", "START_USER_ID_", "state", "STATE_")));
        register(new ResourceDescriptor(HISTORIC_TASK, "ACT_HI_TASKINST", "TENANT_ID_", "ID_", null,
                List.of(new ResourceDescriptor.Scope("def:", "PROC_DEF_KEY_"),
                        new ResourceDescriptor.Scope("procinst:", "PROC_INST_ID_"),
                        new ResourceDescriptor.Scope("taskdef:", "TASK_DEF_KEY_")),
                Map.of("assignee", "ASSIGNEE_", "owner", "OWNER_", "processDefinitionKey", "PROC_DEF_KEY_",
                        "taskDefinitionKey", "TASK_DEF_KEY_", "processInstanceId", "PROC_INST_ID_")));
        register(new ResourceDescriptor(HISTORIC_DECISION, "ACT_HI_DECINST", "TENANT_ID_", "ID_", null,
                List.of(new ResourceDescriptor.Scope("dec:", "DEC_DEF_KEY_"),
                        new ResourceDescriptor.Scope("procinst:", "PROC_INST_ID_")),
                Map.of("decisionDefinitionKey", "DEC_DEF_KEY_", "processInstanceId", "PROC_INST_ID_")));
        // product tables (names from the core Schema / integration notes)
        register(new ResourceDescriptor(AUDIT, "AUDIT_EVENT", "tenant_id", "event_id", null,
                List.of(new ResourceDescriptor.Scope("chain:", "chain_id")),
                Map.of("chainId", "chain_id", "eventType", "event_type")));
        register(new ResourceDescriptor(INSTANCE_LINK, "INSTANCE_LINK", "tenant_id", "link_id", null,
                List.of(new ResourceDescriptor.Scope("procinst:", "source_id")),
                Map.of("linkType", "link_type", "sourceId", "source_id", "targetId", "target_id")));
        register(new ResourceDescriptor(RECONCILIATION_ITEM, "RECONCILIATION_ITEM", "tenant_id", "item_id", null,
                List.of(), Map.of("state", "state", "kind", "kind")));
        for (String v : List.of(TENANT, POLICY, ROLE, GROUP, USER, ORG_UNIT, SEAT, POSTING, POWER_TABLE, SOD_EXCEPTION,
                PRIVILEGE, API_TOKEN, WORKER_TOPIC, OPERATIONS, OUTBOX)) {
            register(ResourceDescriptor.virtual(v));
        }
    }
}
