# SPDX-License-Identifier: Apache-2.0
# Owner decision 381(3) (decisions 145 and 199): the migration inventory of running instances the removed tenantId
# variable fallback carried, and the audited repair of one of them (sangam-rest TenantMigrationController over the
# server's TenantMigrationBridge: TenantFallbackInventory and ChangeTenantService.moveInstance). Platform operation:
# it spans tenants. Executed inside gen_openapi.py.

TAGS.append(("TenantMigration", "Decision 145 tenant migration: inventory of instances without an authoritative engine "
                                "tenant and their audited repair; platform administrators only"))
_TM_REQ = ["R42", "R41"]
_TM_PERM = "platform.tenant-migration.admin"
_TM_PERM_TEXT = (" Platform-only permission platform.tenant-migration.admin (platform administrator on a platform-issuer "
                 "identity); a tenant administrator never holds it.")

schema("TenantClaim", "A tenant variable of an instance as stored (tenantId or sangamTenantId): untrusted business data, "
                      "never a tenant source (decision 145).",
       "variableName value", ["variableName"])
schema("TenantFallbackInstance", "A running root instance the engine-tenant-only rule refuses (decision 145).",
       "processInstanceId definitionKey engineTenant definitionTenant finding reliedOnTheFallback:bool "
       "claims:list:TenantClaim",
       ["processInstanceId", "finding", "reliedOnTheFallback", "claims"],
       enums={"finding": ["TENANT_CONTEXT_MISSING", "TENANT_CONTEXT_CONFLICT"]},
       descs={"engineTenant": "The instance's engine tenant; absent when it has none",
              "definitionTenant": "The deployment tenant of its definition (absent for a shared deployment); one "
                                  "piece of ownership evidence, never the answer",
              "finding": "TENANT_CONTEXT_MISSING: no engine tenant; TENANT_CONTEXT_CONFLICT: a tenant variable "
                         "differs from the engine tenant",
              "reliedOnTheFallback": "True when the instance had no engine tenant and a tenant variable: the case "
                                     "the removed fallback served",
              "claims": "Its tenant variables at any scope, as stored"})
schema("TenantRepairRequest", "Repair of one instance on authoritative ownership evidence (decisions 145 and 199).",
       "targetTenant evidenceRef reason", ["targetTenant", "evidenceRef", "reason"],
       descs={"targetTenant": "The tenant that owns the instance; not the shared scope",
              "evidenceRef": "Reference of the ownership evidence the operator checked (record id, file, ticket)",
              "reason": "Reason for the audit record (R42)"})
SCHEMAS["TenantRepairRequest"]["properties"]["targetTenant"]["minLength"] = 1
SCHEMAS["TenantRepairRequest"]["properties"]["targetTenant"]["maxLength"] = 64
SCHEMAS["TenantRepairRequest"]["properties"]["evidenceRef"]["minLength"] = 1
SCHEMAS["TenantRepairRequest"]["properties"]["evidenceRef"]["maxLength"] = 500
SCHEMAS["TenantRepairRequest"]["properties"]["reason"]["minLength"] = 1
SCHEMAS["TenantRepairRequest"]["properties"]["reason"]["maxLength"] = 1000
schema("TenantRepairResult", "The cut-over of a repaired instance: the old instance is cancelled and a new one runs in "
                             "the target tenant at the same active activities, with its variables and business key; a "
                             "change-tenant link joins the two ids.",
       "processInstanceId newProcessInstanceId fromTenant targetTenant finding evidenceRef",
       ["processInstanceId", "newProcessInstanceId", "targetTenant", "finding", "evidenceRef"],
       descs={"fromTenant": "The old engine tenant; absent when it had none"})

op("get", "/admin/tenant-migration/instances", "listTenantFallbackInstances", "TenantMigration",
   "Tenant migration inventory",
   "Read-only scan of the engine tables: running root instances without an engine tenant, with or without a tenant "
   "variable, and instances whose tenant variable contradicts the engine tenant; consistent instances are not listed. "
   "Changes nothing." + _TM_PERM_TEXT, _TM_REQ[:1],
   resp=(200, "array:TenantFallbackInstance"), perm=_TM_PERM, errors=STD_R)
op("post", "/admin/tenant-migration/instances/{id}/repair", "repairTenantFallbackInstance", "TenantMigration",
   "Repair an instance's tenant",
   "Moves one listed instance to the tenant that owns it through the change-tenant cut-over "
   "(ChangeTenantService.moveInstance). 409 NOT_IN_INVENTORY when the instance is not listed now; 409 "
   "INVALID_TRANSITION when it already runs in targetTenant; 422 REPAIR_REFUSED when the engine refuses the cut-over "
   "(for example a called instance: move its root); 400 VALIDATION_FAILED for a missing or too long targetTenant, "
   "evidenceRef or reason. Audited TENANT_INSTANCE_REPAIRED (old and new instance, tenants, finding, evidenceRef, "
   "reason, actor) on the chains of both instances. A retry with the same Idempotency-Key returns the same result."
   + _TM_PERM_TEXT, _TM_REQ, params=[ID], body="TenantRepairRequest", resp=(200, "TenantRepairResult"),
   cmd="tenant.migration.repair", perm=_TM_PERM)

_TM_OFF = {"description": "CAPABILITY_NOT_INSTALLED: this server has no process engine port for the tenant migration. "
                          "Not retryable; checked after the permission", "content": PROBLEM}
PATHS["/admin/tenant-migration/instances"]["get"]["responses"]["501"] = _TM_OFF
PATHS["/admin/tenant-migration/instances/{id}/repair"]["post"]["responses"]["501"] = _TM_OFF
PATHS["/admin/tenant-migration/instances/{id}/repair"]["post"]["responses"]["409"] = {
    "description": "NOT_IN_INVENTORY or INVALID_TRANSITION", "content": PROBLEM}
PATHS["/admin/tenant-migration/instances/{id}/repair"]["post"]["responses"]["422"] = {
    "description": "REPAIR_REFUSED: the engine refused the cut-over", "content": PROBLEM}
