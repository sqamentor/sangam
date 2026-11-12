# SPDX-License-Identifier: Apache-2.0
# SL-41: pack lifecycle for one tenant (sangam-packs PackDeployer installed / uninstall / rollback; sangam-server
# PackLifecycleController). Platform administrators, as onboarding. Executed inside gen_openapi.py.

TAGS.append(("Packs", "Installed packs of a tenant, uninstall (refused while dependants or live instances exist) and "
                      "rollback of the previous content as a new version (roll-forward, sangam-packs)"))
PACKS = ["SL-41", "ADR-D-20", "X.0e"]
PACK_ID = pp("id", "pack id, e.g. sec.health.admission-discharge")

schema("InstalledPack", "One engine deployment of a pack version for the tenant, oldest first.",
       "packId version tenantId deploymentId deployedAt:dt")
schema("PackLifecycleRequest", "The tenant whose packs change, and the audited reason.", "tenant reason",
       ["tenant", "reason"])
schema("PackUninstalled", "The deployments removed; definitions retired, history kept.",
       "packId tenantId deploymentIds:list:s")
schema("PackDeployResult", "The deployment made by a rollback: the previous content deployed as a new version "
                           "(roll-forward, SL-54).",
       "packId version tenantId deploymentId resources:int newDeployment:bool",
       descs={"version": "The new version: the next patch above the highest installed version of the pack "
                         "(MAJOR.MINOR.PATCH); never a version that was installed before.",
              "resources": "Number of resources read back from the previous deployment and deployed again.",
              "newDeployment": "True when the call made a new engine deployment."})

op("get", "/packs/installed", "listInstalledPacks", "Packs", "Installed packs",
   "Pack deployments of the tenant, oldest first. 400 VALIDATION_FAILED without tenant.", PACKS,
   params=[q("tenant", required=True)], resp=(200, "array:InstalledPack"), perm="tenant.admin", errors=STD_R)
op("post", "/packs/{id}/uninstall", "uninstallPack", "Packs", "Uninstall a pack",
   "Removes every version of the pack from the tenant, audited (PACK_UNINSTALLED). 404 PACK_NOT_INSTALLED; 409 "
   "PACK_HAS_DEPENDANTS or PACK_HAS_LIVE_INSTANCES with `blockers`; 400 REASON_REQUIRED.", PACKS,
   params=[PACK_ID], body="PackLifecycleRequest", resp=(200, "PackUninstalled"), cmd="pack.uninstall",
   perm="tenant.admin")
op("post", "/packs/{id}/rollback", "rollbackPack", "Packs", "Roll a pack back",
   "Roll-forward (SL-54): the resources of the most recent deployment of a different version are read back from the "
   "engine and deployed as a NEW version, the next patch above the highest installed version of the pack, with every "
   "version-bearing part restated for it; no existing `id@version` is reused or changed. New instances started by key "
   "then start on the previous models; running instances keep the definitions they started on. Each further rollback "
   "rolls forward again to a new version. Audited (PACK_ROLLED_BACK, with the new version). 404 PACK_NOT_INSTALLED; "
   "409 PACK_NO_PREVIOUS_VERSION; 409 PACK_VERSION_NOT_SEMVER with `blockers` (the installed versions that are not "
   "MAJOR.MINOR.PATCH, so the next version cannot be determined); 422 PACK_REJECTED with `violations`; "
   "400 REASON_REQUIRED.", PACKS,
   params=[PACK_ID], body="PackLifecycleRequest", resp=(200, "PackDeployResult"), cmd="pack.rollback",
   perm="tenant.admin")
