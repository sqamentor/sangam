# SPDX-License-Identifier: Apache-2.0
# sangam-onboarding: guided tenant onboarding, dry run, pack resolution, signed bundles; signed plugin admin
# (SANGAM.md X.0e, ADR-D-20..24). Executed inside gen_openapi.py.

TAGS.append(("Onboarding", "Plug-and-play tenant onboarding as a BPMN process with maker-checker at go-live, dry run, "
                           "pack dependency resolution and signed tenant bundles (sangam-onboarding)"))
TAGS.append(("Plugins", "Signed plugin drop-in: list, enable or disable (effective at restart), audit trail"))
ONB = ["ADR-D-20", "ADR-D-24", "X.0e"]
RUN = pp("id", "onboarding run id")

schema("OnboardingProblem", "One finding.", "severity code step subject message",
       enums={"severity": ["ERROR", "WARNING", "INFO"]})
schema("OnboardingRequest", "Everything a new tenant needs, as data. See org.sangam.onboarding.api.OnboardingRequest.",
       "tenant:obj profile:obj packs:obj connectors:list:obj security:obj seeds:obj compliance:obj dmnOverrides:smap "
       "goLive:obj", ["tenant"])
schema("OnboardingReadiness", "The go-live checklist; the checker approves `hash`.",
       "ready:bool problems:list:OnboardingProblem checks:smap hash checkedAt:dt revision:int")
schema("OnboardingDryRun", "Every problem and the plan; nothing was changed.",
       "tenantId ok:bool problems:list:OnboardingProblem plan:obj readiness:OnboardingReadiness requestHash")
schema("OnboardingRun", "State of a run, per step.",
       "id tenantId status maker checker revision:int request:OnboardingRequest requestHash processInstanceId "
       "steps:obj readiness:OnboardingReadiness createdAt:dt updatedAt:dt",
       enums={"status": ["RUNNING", "WAITING_FOR_FIX", "AWAITING_APPROVAL", "APPROVED", "LIVE", "REJECTED", "FAILED",
                         "CANCELLED"]})
schema("OnboardingApproveRequest", "Checker approval, bound to the readiness hash reviewed.", "presentedHash",
       ["presentedHash"])
schema("OnboardingReasonRequest", "A reason.", "reason")
schema("PackResolveRequest", "Packs to resolve, optionally against what a tenant has.",
       "ids:list:s versions:smap tenant allowUpgrade:bool", ["ids"])
schema("PackResolution", "Dependency closure, order, requirements, upgrades and problems.",
       "order:list:s versions:smap addedDependencies:list:s connectors:obj profileKeys:obj upgrades:list:obj "
       "problems:list:OnboardingProblem")
schema("TenantBundle", "A signed tenant configuration bundle.", "format:int body:obj signature:obj", ["format", "body"])
schema("BundleImportRequest", "Import a bundle as another tenant, or dry-run it.",
       "bundle:TenantBundle targetTenant name names:smap parentId profileOverrides:obj goLive:obj dryRun:bool",
       ["bundle", "targetTenant"])
schema("BundleImportResult", "The dry-run report or the started run.", "dryRun:OnboardingDryRun run:OnboardingRun")
schema("BundleDiffRequest", "Two bundles.", "left:TenantBundle right:TenantBundle", ["left", "right"])
schema("BundleDiffEntry", "One difference.", "path kind left:any right:any",
       enums={"kind": ["ADDED", "REMOVED", "CHANGED"]})
schema("PluginInfo", "A plugin found in the plugin folder.",
       "id version name vendor file status reason signedBy enabledAfterRestart:bool provides:obj",
       enums={"status": ["LOADED", "DISABLED", "REFUSED", "FAILED"]})

op("post", "/onboarding/dry-run", "dryRunOnboarding", "Onboarding", "Dry run",
   "Evaluates every step and the readiness checklist on the planned state; changes nothing.", ONB,
   body="OnboardingRequest", resp=(200, "OnboardingDryRun"), perm="tenant.admin")
op("post", "/onboarding/runs", "startOnboarding", "Onboarding", "Start a run",
   "Starts the BPMN process (409 ONBOARDING_IN_PROGRESS when the tenant has an open run).", ONB,
   body="OnboardingRequest", resp=(200, "OnboardingRun"), cmd="onboarding.start", perm="tenant.admin")
op("get", "/onboarding/runs", "listOnboardingRuns", "Onboarding", "List runs", "Newest first.", ONB,
   params=[q("tenant")], resp=(200, "array:OnboardingRun"), perm="tenant.admin")
op("get", "/onboarding/runs/{id}", "getOnboardingRun", "Onboarding", "Get a run", "State of every step.", ONB,
   params=[RUN], resp=(200, "OnboardingRun"), perm="tenant.admin")
op("put", "/onboarding/runs/{id}/request", "amendOnboardingRun", "Onboarding", "Amend the request",
   "Maker fix: a new revision; the steps run again.", ONB, params=[RUN], body="OnboardingRequest",
   resp=(200, "OnboardingRun"), cmd="onboarding.amend", perm="tenant.admin")
op("post", "/onboarding/runs/{id}/recheck", "recheckOnboardingRun", "Onboarding", "Re-run the checks",
   "After an external fix, without changing the request.", ONB, params=[RUN], resp=(200, "OnboardingRun"),
   cmd="onboarding.recheck", perm="tenant.admin")
op("post", "/onboarding/runs/{id}/approve", "approveOnboardingRun", "Onboarding", "Approve go-live",
   "Checker must differ from the maker (422 SEGREGATION_OF_DUTIES); bound to the hash (409 APPROVAL_INVALIDATED).",
   ONB, params=[RUN], body="OnboardingApproveRequest", resp=(200, "OnboardingRun"), cmd="onboarding.approve",
   perm="tenant.admin")
op("post", "/onboarding/runs/{id}/reject", "rejectOnboardingRun", "Onboarding", "Reject go-live",
   "Ends the run.", ONB, params=[RUN], body="OnboardingReasonRequest", resp=(200, "OnboardingRun"),
   cmd="onboarding.reject", perm="tenant.admin")
op("post", "/onboarding/runs/{id}/resume", "resumeOnboardingRun", "Onboarding", "Resume a failed run",
   "Retries the failed step.", ONB, params=[RUN], resp=(200, "OnboardingRun"), cmd="onboarding.resume",
   perm="tenant.admin")
op("post", "/onboarding/runs/{id}/cancel", "cancelOnboardingRun", "Onboarding", "Cancel a run",
   "Ends the process; what was configured stays.", ONB, params=[RUN], body="OnboardingReasonRequest",
   body_required=False, resp=(200, "OnboardingRun"), cmd="onboarding.cancel", perm="tenant.admin")
op("post", "/onboarding/packs/resolve", "resolveOnboardingPacks", "Onboarding", "Resolve packs",
   "Dependencies, semantic versions, compatibility, conflicts and upgrade paths.", ONB, body="PackResolveRequest",
   resp=(200, "PackResolution"), perm="tenant.admin")
op("get", "/onboarding/tenants/{tenant}/bundle", "exportTenantBundle", "Onboarding", "Export a bundle",
   "The tenant's configuration, signed. No secrets, no go-live evidence.", ONB, params=[pp("tenant")],
   resp=(200, "TenantBundle"), perm="tenant.admin")
op("post", "/onboarding/bundles/import", "importTenantBundle", "Onboarding", "Import a bundle",
   "Verifies the signature (422 BUNDLE_SIGNATURE_INVALID, BUNDLE_UNSIGNED), renames the scope, then dry-runs or starts.",
   ONB, body="BundleImportRequest", resp=(200, "BundleImportResult"), cmd="onboarding.bundle.import",
   perm="tenant.admin")
op("post", "/onboarding/bundles/diff", "diffTenantBundles", "Onboarding", "Compare two bundles",
   "Tenant ids are normalised, so only real differences show.", ONB, body="BundleDiffRequest",
   resp=(200, "array:BundleDiffEntry"), perm="tenant.admin")

op("get", "/admin/plugins", "listPlugins", "Plugins", "List plugins", "Every jar found, with its status and reason.",
   ONB, resp=(200, "array:PluginInfo"), perm="tenant.admin")
op("get", "/admin/plugins/audit", "getPluginAudit", "Plugins", "Plugin audit trail",
   "Hash-chained JSON lines, oldest first.", ONB, resp=(200, {"application/json": {"schema": {
       "type": "array", "items": {"type": "string"}}}}), perm="tenant.admin")
op("post", "/admin/plugins/{id}/enable", "enablePlugin", "Plugins", "Enable a plugin", "Effective at the next restart.",
   ONB, params=[pp("id")], resp=(200, "PluginInfo"), cmd="plugin.enable", perm="tenant.admin")
op("post", "/admin/plugins/{id}/disable", "disablePlugin", "Plugins", "Disable a plugin", "Effective at the next restart.",
   ONB, params=[pp("id")], resp=(200, "PluginInfo"), cmd="plugin.disable", perm="tenant.admin")

schema("UiPluginManifest", "A UI plugin: its entry module, styles and shell contributions.",
       "id version name vendor entry styles:list:s contributes:list:obj requires:obj", ["id", "version", "entry"])
op("get", "/plugins/ui", "listUiPlugins", "Plugins", "Enabled UI plugins",
   "Manifests of the enabled, signature-verified UI bundles, for the shell to load. Any signed-in user.", ONB,
   resp=(200, "array:UiPluginManifest"), errors=("Unauthorized",))
op("get", "/plugins/ui/{id}/assets/{path}", "getUiPluginAsset", "Plugins", "A UI plugin asset",
   "No token needed (same-origin module import); plugin code holds no secrets. `path` may contain slashes. Allowed file "
   "types only, exact name, nosniff. Disabled or refused plugins: 404.", ONB, params=[pp("id"), pp("path")],
   resp=(200, {"application/octet-stream": {"schema": {"type": "string", "format": "binary"}}}), errors=("NotFound",))
public("/plugins/ui/{id}/assets/{path}", "get")
