# SPDX-License-Identifier: Apache-2.0
# SL-42: promotion of a tenant's configuration between environments (sangam-onboarding PromotionService; sangam-server
# PromotionController). Platform administrators, as onboarding. Executed inside gen_openapi.py (after onboarding.py).

PROMO = ["SL-42", "ADR-D-20", "X.0e"]

schema("PromotionPlanRequest", "A signed bundle exported from another environment and the target tenant here.",
       "bundle:TenantBundle targetTenant", ["bundle", "targetTenant"])
schema("PromotionApplyRequest", "The reviewed plan: the same bundle and target, and the plan hash shown.",
       "bundle:TenantBundle targetTenant planHash", ["bundle", "targetTenant", "planHash"])
schema("PromotionPlan", "What the apply step will do and the hash it must present. Nothing was changed.",
       "sourceTenant sourceEnvironment targetTenant targetEnvironment changes:list:BundleDiffEntry "
       "request:OnboardingRequest dryRun:OnboardingDryRun planHash")

op("get", "/onboarding/promotions/{tenant}/bundle", "exportPromotionBundle", "Onboarding", "Export for promotion",
   "The tenant's signed bundle labelled with this environment (sangam.onboarding.environment). 409 "
   "PROMOTION_ENVIRONMENT_NOT_SET without a label.", PROMO,
   params=[pp("tenant", "tenant id")], resp=(200, "TenantBundle"), perm="tenant.admin", errors=GET_E)
op("post", "/onboarding/promotions/plan", "planPromotion", "Onboarding", "Plan a promotion",
   "Verifies the bundle signature, diffs it against the target tenant here and dry-runs it; returns the plan hash. "
   "422 PROMOTION_ENVIRONMENT_MISSING, PROMOTION_SAME_ENVIRONMENT, PROMOTION_TARGET_MISMATCH; 409 "
   "PROMOTION_ENVIRONMENT_NOT_SET.", PROMO,
   body="PromotionPlanRequest", resp=(200, "PromotionPlan"), perm="tenant.admin",
   errors=("BadRequest", "Unauthorized", "Forbidden", "Conflict", "Unprocessable"))
op("post", "/onboarding/promotions", "applyPromotion", "Onboarding", "Apply a promotion",
   "Starts the onboarding run of the reviewed plan (maker-checker approval follows). 409 PROMOTION_PLAN_CHANGED, "
   "PROMOTION_NO_CHANGE; 422 PROMOTION_DRY_RUN_FAILED.", PROMO,
   body="PromotionApplyRequest", resp=(200, "OnboardingRun"), cmd="onboarding.promote", perm="tenant.admin")
