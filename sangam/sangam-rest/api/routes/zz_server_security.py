# SPDX-License-Identifier: Apache-2.0
# Server-side security administration (sangam-server web/SecurityDefaultsController; WS-SECURITY ModuleDefaults).
schema("ModuleDefaultsRequest", "Install the module role templates; selfService also installs the person-conditioned "
       "self-service grants (default true).", "selfService:bool")
schema("ModuleDefaultsResult", "Installed role templates for the caller's tenant.",
       "tenantId roleTemplates:list:s selfService:bool")
op("post", "/admin/security/module-defaults", "installModuleDefaults", "Admin",
   "Install module role templates and self-service grants",
   "Installs sangam-security's role templates for every registered module resource type and, with selfService, the "
   "person-conditioned self-service grants, for the caller's tenant. Idempotent; also run at tenant onboarding.",
   ["VI.4", "R19", "EC-17"], body="ModuleDefaultsRequest", resp=(200, "ModuleDefaultsResult"),
   cmd="admin.security.module-defaults", perm="tenant.admin")
