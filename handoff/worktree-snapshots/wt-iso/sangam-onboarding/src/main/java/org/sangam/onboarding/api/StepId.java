// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.api;

/**
 * The steps of the onboarding flow, in execution order. Each step is one asynchronous service task of the
 * {@code sangam-onboarding} BPMN process ({@code step_<NAME>}), so a failure stops at that step and a retry resumes it.
 */
public enum StepId {
    /** Tenant record in the security directory (status CREATED / CONFIGURED). */
    TENANT,
    /** Control-plane tenant registry entry, residency-filtered placement on a cell. */
    PLACEMENT,
    /** Jurisdiction, regime preset and parent profile; the tenant profile is imported or generated as a draft. */
    PROFILE,
    /** Pack selection, dependency resolution, compatibility check and per-tenant deployment. */
    PACKS,
    /** Connector bindings with a test call. */
    CONNECTORS,
    /** Module role templates, self-service grants, tenant roles and tenant administrators. */
    SECURITY,
    /** Classifications, party identifier types, calendars. */
    SEEDS,
    /** Compliance jurisdiction packs for the tenant. */
    COMPLIANCE,
    /** The go-live readiness checklist. */
    READINESS,
    /** Checker approval (maker-checker), profile approval + activation, tenant activation. */
    GO_LIVE;

    /** BPMN activity id of the step's service task. */
    public String activityId() {
        return "step_" + name();
    }

    public static StepId ofActivity(String activityId) {
        if (activityId == null || !activityId.startsWith("step_")) {
            throw new IllegalArgumentException("not an onboarding step activity: " + activityId);
        }
        return valueOf(activityId.substring(5));
    }
}
