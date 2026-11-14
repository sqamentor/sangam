# SPDX-License-Identifier: Apache-2.0
# Owner decisions 144 and 197 (RM-49): the shared-deployment registry, the proof that untenanted engine work belongs to
# the platform (system audit scope) and not to a customer tenant. Shapes agreed on the board: W4-M INFO
# 2026-11-13T01:30:52Z, W4-E ACK 01:46:14Z (codes, no re-registration of a revoked id, the 422 rule), permissions W4-B
# CHANGE 01:27:21Z (SecurityPermissionBridge.SHARED_DEPLOYMENT_READ and SHARED_DEPLOYMENT_MANAGE, platform-issuer
# identities only). The engine writes the SHARED_DEPLOYMENT row and the audit event SHARED_DEPLOYMENT_REGISTERED or
# SHARED_DEPLOYMENT_REVOKED in one transaction. Executed inside gen_openapi.py.

TAGS.append(("SharedDeployments", "Shared-deployment registry (decisions 144 and 197): which engine deployments are "
                                  "platform-owned; platform deployers only"))
_SD_TAG = "SharedDeployments"
_SD_REQ = ["RM-49", "R42"]
_SD_READ = "platform.shared-deployment.read"
_SD_MANAGE = "platform.shared-deployment.manage"
_SD_PLATFORM = (" Platform-only: held only by a platform-issuer identity (marker group sangam-platform), never through a "
                "tenant role, a tenant grant or the administrator wildcard. The registry is platform-wide: no tenant "
                "parameter and no tenant filter.")

SCHEMAS["SharedDeployment"] = {
    "type": "object",
    "description": "One registry row (SHARED_DEPLOYMENT). One row per deployment id; revocation is final for that id.",
    "required": ["deploymentId", "registeredBy", "reason", "registeredAt"],
    "properties": {
        "deploymentId": {"type": "string", "minLength": 1, "maxLength": 64,
                         "description": "Engine deployment id (ACT_RE_DEPLOYMENT) of an untenanted deployment"},
        "registeredBy": {"type": "string",
                         "description": "Platform deployer that registered it (server-side identity)"},
        "reason": {"type": "string", "description": "Reason given at registration"},
        "registeredAt": {"type": "string", "format": "date-time"},
        "revokedBy": {"type": ["string", "null"], "description": "Platform deployer that revoked it; absent while "
                                                                 "active"},
        "revokedAt": {"type": ["string", "null"], "format": "date-time"},
        "revokeReason": {"type": ["string", "null"], "description": "Reason given at revocation"},
    },
}
SCHEMAS["SharedDeploymentRegistration"] = {
    "type": "object", "description": "Register an untenanted engine deployment as platform-owned.",
    "required": ["deploymentId", "reason"],
    "properties": {
        "deploymentId": {"type": "string", "minLength": 1, "maxLength": 64},
        "reason": {"type": "string", "minLength": 1, "maxLength": 1000,
                   "description": "Mandatory reason for the audit record (R42)"},
    },
}
SCHEMAS["SharedDeploymentRevocation"] = {
    "type": "object", "description": "Revoke a registered shared deployment (final for that deployment id).",
    "required": ["reason"],
    "properties": {
        "reason": {"type": "string", "minLength": 1, "maxLength": 1000,
                   "description": "Mandatory reason for the audit record (R42)"},
    },
}

op("get", "/admin/shared-deployments", "listSharedDeployments", _SD_TAG, "Shared deployments",
   "The registry: active rows by default; includeRevoked=true lists revoked rows too. Needs " + _SD_READ + "."
   + _SD_PLATFORM, _SD_REQ, list_=True,
   params=[q("includeRevoked", "bool", "Also list revoked rows (default false)")],
   resp=(200, "array:SharedDeployment"), perm=_SD_READ, errors=("Unauthorized", "Forbidden"))
op("post", "/admin/shared-deployments", "registerSharedDeployment", _SD_TAG, "Register a shared deployment",
   "Marks an untenanted engine deployment as platform-owned, so its work runs under the system audit scope. The row "
   "and the audit event SHARED_DEPLOYMENT_REGISTERED commit in one transaction. Needs " + _SD_MANAGE + "; a replay "
   "of the stored response re-checks it." + _SD_PLATFORM,
   _SD_REQ + ["R41"], body="SharedDeploymentRegistration", resp=(201, "SharedDeployment"), created=True,
   cmd="shared-deployment.register", perm=_SD_MANAGE, errors=("BadRequest", "Unauthorized", "Forbidden"))
op("delete", "/admin/shared-deployments/{deploymentId}", "revokeSharedDeployment", _SD_TAG,
   "Revoke a shared deployment",
   "Ends the registration; work of the deployment then has no system owner and fails closed (UNRESOLVED_TENANT). The "
   "row and the audit event SHARED_DEPLOYMENT_REVOKED commit in one transaction. Revocation is final: the id is never "
   "registered again (deploy again and register the new id). Needs " + _SD_MANAGE + "." + _SD_PLATFORM,
   _SD_REQ + ["R41"], params=[pp("deploymentId", "Engine deployment id")], body="SharedDeploymentRevocation",
   resp=(200, "SharedDeployment"), cmd="shared-deployment.revoke", perm=_SD_MANAGE)

_SD_400 = {"description": "VALIDATION_FAILED: deploymentId must be 1..64 characters and reason 1..1000 characters; or "
                          "an Idempotency-Key problem as for every command", "content": PROBLEM}
_SD_403 = {"description": "ACTION_NOT_PERMITTED: the caller is not a platform deployer (" + _SD_MANAGE + "), or "
                          "POLICY_DENIED as for every command", "content": PROBLEM}
_r = PATHS["/admin/shared-deployments"]["post"]["responses"]
_r["400"] = _SD_400
_r["403"] = _SD_403
_r["409"] = {"description": "SHARED_DEPLOYMENT_REGISTERED: the id is registered and not revoked; "
                            "SHARED_DEPLOYMENT_REVOKED: the id was revoked and cannot be registered again; or an "
                            "Idempotency-Key conflict as for every command", "content": PROBLEM}
_r["422"] = {"description": "SHARED_DEPLOYMENT_INVALID: the id is not an engine deployment, or the deployment has a "
                            "tenant (a tenant-owned deployment never needs the registry); the detail names which",
             "content": PROBLEM}
_r = PATHS["/admin/shared-deployments/{deploymentId}"]["delete"]["responses"]
_r["400"] = _SD_400
_r["403"] = _SD_403
_r["404"] = {"description": "NOT_FOUND: the id was never registered", "content": PROBLEM}
_r["409"] = {"description": "SHARED_DEPLOYMENT_REVOKED: the id is already revoked; or an Idempotency-Key conflict as "
                            "for every command", "content": PROBLEM}
