# SPDX-License-Identifier: Apache-2.0
# Round 6 lane w2 item 4: the state changes the console's incident, tenant, user and group screens need, routed only where
# the core already does them: incident skip (instance modification, R49) and cancel (ProcessPort business cancel, VI.5)
# next to retry and resolve; the tenant lifecycle of TenantAdminService (validate = the activation dry run, activate or
# resume, suspend); users (PersonService create, update, disable with token revocation) and groups (GroupAdminService
# create and rename). Executed inside gen_openapi.py.

_INC_REQ = ["R63", "R41", "R42", "R49"]
op("post", "/ops/incidents/{id}/skip", "skipIncident", "Ops", "Skip an incident",
   "Moves the token past the incident's activity (instance modification, R49): starts after the activity under the same "
   "parent, then cancels the incident's own activity or transition instance; the activity's effect does not happen and "
   "other instances of the activity are untouched (INCIDENT_SKIPPED; the reason is the modification's annotation). 400 "
   "REASON_REQUIRED without a reason; 422 INCIDENT_NOT_SKIPPABLE when the incident has no active token to move.",
   _INC_REQ, params=[ID], body="IncidentAction", resp=(200, "Incident"), cmd="ops.incident.skip", perm="ops.write")
op("post", "/ops/incidents/{id}/cancel", "cancelIncident", "Ops", "Cancel an incident's instance",
   "Cancels the incident's process instance through the business cancel (VI.5: the model's cancel paths, open external "
   "work becomes CANCEL_REQUESTED, INSTANCE_CANCELLED), then audits INCIDENT_CANCELLED with the open operation ids. 400 "
   "REASON_REQUIRED without a reason.", ["R63", "R41", "R42", "VI.5"], params=[ID], body="IncidentAction",
   resp=(200, "Incident"), cmd="ops.incident.cancel", perm="ops.write")
PATHS["/ops/incidents/{id}/skip"]["post"]["responses"]["422"] = {
    "description": "INCIDENT_NOT_SKIPPABLE, or the engine refused the modification (ENGINE_REJECTED)", "content": PROBLEM}

schema("TenantReadiness", "Go-live readiness of a tenant (AJ-01 step 10): the activation check as a dry run.",
       "tenantId status ready:bool required:list:s missing:list:s", ["tenantId", "ready", "required", "missing"],
       descs={"required": "Checklist items the activation requires: core items, the tenant's jurisdiction packs, its "
                          "profile",
              "missing": "Required items without recorded evidence; empty when ready"})
op("post", "/admin/tenants/{id}/validate", "validateTenant", "Admin", "Check a tenant's go-live readiness",
   "Lists the required go-live checklist items and those without evidence; changes nothing. 404 for a tenant the caller "
   "does not administer; 409 ACTIVATION_BLOCKED when a required jurisdiction pack is not installed.", ["AJ-01"],
   params=[ID], body=None, resp=(200, "TenantReadiness"), perm="tenant.admin")
op("post", "/admin/tenants/{id}/activate", "activateTenant", "Admin", "Activate or resume a tenant",
   "A suspended tenant is resumed (platform administrator). Any other tenant is activated by its own administrator once "
   "every required item has evidence (409 ACTIVATION_BLOCKED names the missing items); naming another tenant is 409 "
   "TENANT_ACTIVATION_BY_OWN_ADMIN. TENANT_ACTIVATED, with the reason when one is given.",
   ["AJ-01", "R41"], params=[ID], body="ReasonRequest", body_required=False, resp=(200, "Tenant"),
   cmd="admin.tenant.activate", perm="tenant.admin")
op("post", "/admin/tenants/{id}/suspend", "suspendTenant", "Admin", "Suspend a tenant",
   "Platform administrator only; requests for the tenant are then refused. TENANT_SUSPENDED with the reason.",
   ["AJ-01", "R41", "R42"], params=[ID], body="ReasonRequest", resp=(200, "Tenant"), cmd="admin.tenant.suspend",
   perm="tenant.admin")
for _p in ("/admin/tenants/{id}/validate", "/admin/tenants/{id}/activate"):
    PATHS[_p]["post"]["responses"]["409"] = {"description": "ACTIVATION_BLOCKED or TENANT_ACTIVATION_BY_OWN_ADMIN",
                                             "content": PROBLEM}

schema("UserRequest", "A LOCAL user an administrator creates or updates (R67).",
       "id username displayName email active:bool source", [],
       enums={"source": ["LOCAL"]},
       descs={"id": "Person id of a new user (1-64 characters); username when omitted",
              "active": "Only true: a user is created active and disabled afterwards with a reason",
              "source": "Only LOCAL: SCIM and directory sync provision their own users"})
for _f, _n in (("id", 64), ("username", 64), ("displayName", 255), ("email", 255)):
    SCHEMAS["UserRequest"]["properties"][_f]["maxLength"] = _n
# decision 393: user and group ids are IdP principal ids and group paths (may carry '/' or ':'): query parameters
UIDQ_ = q("id", desc="User id (an IdP principal id); may carry '/' or ':'", required=True)
GIDQ_ = q("id", desc="Group id (an IdP group path such as /staff/clerks); may carry '/' or ':'", required=True)
op("post", "/admin/users", "createUser", "Admin", "Create a user",
   "A LOCAL user of the caller's tenant (USER_PROVISIONED); an IdP subject is linked separately. 409 USER_EXISTS.",
   ["R67", "R41"], body="UserRequest", resp=(201, "User"), cmd="admin.user.create", perm="admin.write", created=True)
op("put", "/admin/users/by-id", "updateUser", "Admin", "Update a user", "Display name and e-mail (USER_UPDATED).",
   ["R67", "R41"], params=[UIDQ_], body="UserRequest", resp=(200, "User"), cmd="admin.user.update", perm="admin.write")
op("post", "/admin/users/by-id/disable", "disableUser", "Admin", "Disable a user",
   "USER_DISABLED with the reason; every API token of the user is revoked in the same transaction. 409 SELF_DISABLE "
   "for the caller's own user.", ["R67", "R41", "R42"], params=[UIDQ_], body="ReasonRequest", resp=(200, "User"),
   cmd="admin.user.disable", perm="admin.write")
op("post", "/admin/groups", "createGroup", "Admin", "Create a group",
   "A LOCAL group of the caller's tenant (MEMBERSHIP_CHANGED, created). 409 GROUP_EXISTS.", ["R67", "R41"],
   body="Group", resp=(201, "Group"), cmd="admin.group.create", perm="policy.admin", created=True)
op("put", "/admin/groups/by-id", "updateGroup", "Admin", "Rename a group",
   "The name only; memberships and the group's IdP or SCIM reference are kept (MEMBERSHIP_CHANGED, updated).",
   ["R67", "R41"], params=[GIDQ_], body="Group", resp=(200, "Group"), cmd="admin.group.update", perm="policy.admin")
for _p, _m in (("/admin/users", "post"), ("/admin/users/by-id/disable", "post"), ("/admin/groups", "post")):
    PATHS[_p][_m]["responses"]["409"] = {"description": "USER_EXISTS, SELF_DISABLE or GROUP_EXISTS", "content": PROBLEM}
