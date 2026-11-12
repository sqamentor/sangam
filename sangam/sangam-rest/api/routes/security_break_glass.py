# SPDX-License-Identifier: Apache-2.0
# M-084 break-glass tokens (W3-D REQUEST 2026-11-12T11:29:26Z, contract-first; service SangamSecurity.breakGlass()).
# A grant is requested with a reason, approved by the configured number of approvers (the last approval issues the
# token, shown once), used under audit (BREAK_GLASS_USED raises an alarm) and reviewed afterwards. Object check:
# ADMIN on API_TOKEN (sangam-security); coarse permissions admin.read / admin.write. 404 BREAK_GLASS_NOT_CONFIGURED when
# the tenant configuration has no break-glass token class. Executed inside gen_openapi.py.
_BG = "/admin/security/break-glass/grants"
_BG_REQ = ["X.5.2", "VI.4", "R19"]
schema("BreakGlassGrant", "A break-glass grant (M-084).",
       "grantId tokenClass holderPersonId requestedBy reason status requiredApprovals:int approvals:list:obj "
       "validMinutes:int issuedAt:dt expiresAt:dt firstUsedAt:dt lastUsedAt:dt useCount:int reviewStatus reviewedBy "
       "reviewOutcome reviewedAt:dt",
       required=("grantId", "tokenClass", "holderPersonId", "status"),
       enums={"status": ["REQUESTED", "ISSUED", "REJECTED"], "reviewStatus": ["NONE", "PENDING", "DONE"],
              "reviewOutcome": ["ACCEPTED", "ESCALATED"]})
schema("BreakGlassRequest", "Request a break-glass grant.", "tokenClass holderPersonId reason",
       required=("tokenClass", "holderPersonId", "reason"))
schema("BreakGlassIssued", "The last required approval issued the token: it is shown here once and never again.",
       "grant:BreakGlassGrant token expiresAt:dt", required=("grant", "token", "expiresAt"))
schema("BreakGlassReview", "Post-use review of a grant.", "outcome notes", required=("outcome",),
       enums={"outcome": ["ACCEPTED", "ESCALATED"]})
_BG_ID = pp("grantId", "break-glass grant id")
_BG_404 = " 404 BREAK_GLASS_NOT_CONFIGURED when the tenant configuration has no break-glass token class."
op("post", _BG, "requestBreakGlassGrant", "Admin", "Request a break-glass grant",
   "Records a grant in REQUESTED with its reason (audited BREAK_GLASS_REQUESTED). ADMIN on API_TOKEN." + _BG_404,
   _BG_REQ + ["R41", "R42"], body="BreakGlassRequest", resp=(201, "BreakGlassGrant"), cmd="admin.security.break-glass.request",
   perm="admin.write", created=True)
op("get", _BG, "listBreakGlassGrants", "Admin", "Break-glass grants",
   "Grants of the caller's tenant, filtered by status and review status. ADMIN on API_TOKEN." + _BG_404, _BG_REQ,
   list_=True, params=[q("status"), q("reviewStatus")], resp=(200, "array:BreakGlassGrant"), perm="admin.read",
   errors=GET_E)
op("post", _BG + "/{grantId}/approve", "approveBreakGlassGrant", "Admin", "Approve a break-glass grant",
   "Adds the caller's approval (audited BREAK_GLASS_APPROVED); 200 with the grant while more approvals are required, "
   "201 with the token (shown once) when this approval issues it (BREAK_GLASS_ISSUED). ADMIN on API_TOKEN." + _BG_404,
   _BG_REQ + ["R41", "R42"], params=[_BG_ID], resp=(200, "BreakGlassGrant"), cmd="admin.security.break-glass.approve",
   perm="admin.write", body_required=False, body=None)
PATHS[_BG + "/{grantId}/approve"]["post"]["responses"]["201"] = {
    "description": "The token was issued by this approval; it is shown once.",
    "content": {"application/json": {"schema": R("BreakGlassIssued")}},
    "headers": {"Idempotent-Replayed": H("Idempotent-Replayed"), "Idempotency-Key-Expires": H("Idempotency-Key-Expires")}}
op("post", _BG + "/{grantId}/reject", "rejectBreakGlassGrant", "Admin", "Reject a break-glass grant",
   "Rejects a requested grant with a reason (audited BREAK_GLASS_REJECTED). ADMIN on API_TOKEN." + _BG_404,
   _BG_REQ + ["R41", "R42"], params=[_BG_ID], body="ReasonRequest", resp=(200, "BreakGlassGrant"),
   cmd="admin.security.break-glass.reject", perm="admin.write")
op("post", _BG + "/{grantId}/review", "reviewBreakGlassGrant", "Admin", "Review a used break-glass grant",
   "Records the post-use review (audited BREAK_GLASS_REVIEWED). ADMIN on API_TOKEN." + _BG_404,
   _BG_REQ + ["R41", "R42"], params=[_BG_ID], body="BreakGlassReview", resp=(200, "BreakGlassGrant"),
   cmd="admin.security.break-glass.review", perm="admin.write")
