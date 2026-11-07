// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.sangam.onboarding.api.OnboardingRequest.AdminSpec;
import org.sangam.onboarding.api.OnboardingRequest.ConnectorBindingSpec;
import org.sangam.onboarding.api.OnboardingRequest.RoleSpec;
import org.sangam.onboarding.api.OnboardingRequest.TenantSpec;

/**
 * The ports through which onboarding reaches the rest of the platform. sangam-server binds each one to the owning
 * module (security, control plane, packs, integration, registry, compliance); a port left unbound makes its step
 * report {@code CAPABILITY_NOT_INSTALLED} instead of failing silently. Every mutating method is idempotent: a resumed
 * step calls it again.
 */
public final class OnboardingPorts {

    private OnboardingPorts() {
    }

    /** The tenant directory (sangam-security). */
    public interface TenantPort {
        record TenantInfo(String id, String status) {
        }

        Optional<TenantInfo> find(String tenantId);

        /** Creates the tenant if it does not exist (status CREATED); returns the current record either way. */
        TenantInfo create(TenantSpec spec, String actor);

        /** Tenant settings (languages, default language, profile scope, quotas); moves CREATED to CONFIGURED. */
        void configure(String tenantId, Map<String, Object> config, Map<String, Object> quotas, String actor);

        /** The go-live items required (stored and planned jurisdictions counted) that have no evidence yet. */
        List<String> missingGoLiveEvidence(String tenantId, List<String> plannedJurisdictions);

        void recordEvidence(String tenantId, String item, String evidenceRef, String actor);

        /** Activates the tenant (fails if evidence is missing). */
        void activate(String tenantId, String actor);
    }

    /** The control-plane tenant registry and placement (SCALE 5). */
    public interface PlacementPort {
        /**
         * @param mode CONTROL_PLANE when the control plane placed it, LOCAL in a single-cell deployment
         */
        record Placement(String tenantId, String cellId, String mode, String detail) {
        }

        Optional<Placement> placement(String tenantId);

        /** Cells that would accept the tenant (residency, capacity, headroom); empty = no cell qualifies. */
        List<String> candidates(TenantSpec spec);

        /** Registers the tenant (hierarchy, residency, sector) and places it; idempotent. */
        Placement register(TenantSpec spec, String actor);
    }

    /** Pack deployment per tenant (sangam-packs on the cell engine). */
    public interface PackInstallPort {
        /** Installed pack id to version for the tenant ({@code pack:<id>@<version>} deployments). */
        Map<String, String> installed(String tenantId);

        /** Deploys the packs (dependencies first, already ordered) for the tenant; returns coordinates to deployment id. */
        Map<String, String> deploy(String tenantId, List<String> orderedPackIds);

        /** Deploys tenant-specific DMN tables (decision key to XML) as one tenant deployment; returns its id. */
        String deployDecisionOverrides(String tenantId, Map<String, String> dmnByDecisionKey);

        /** Remembers the tenant's pack selection, so later automatic provisioning deploys only these packs. */
        void select(String tenantId, Set<String> packIds);
    }

    /** Connectors (sangam-integration registry and pack bindings). */
    public interface ConnectorPort {
        /**
         * @param profile LIVE or SIMULATOR
         */
        record ConnectorInfo(String id, String type, String profile, List<String> operations, String capabilityClass) {
        }

        /**
         * @param ok      the call (or probe) succeeded
         * @param mode    HEALTH (probe only) or INVOKE
         * @param outcome SUCCESS, BUSINESS_FAILURE, TRANSIENT_FAILURE, OUTCOME_UNKNOWN, UNHEALTHY, NOT_FOUND
         */
        record TestResult(boolean ok, String mode, String outcome, String detail) {
        }

        /** Connectors registered for the tenant (tenant-specific and platform-wide). */
        List<ConnectorInfo> available(String tenantId);

        /** The connector a pack connector id resolves to for the tenant, if any (tenant binding, else platform). */
        Optional<String> boundTo(String tenantId, String packConnector);

        /** True when an unbound pack connector falls back to the packs' simulator (development only). */
        boolean simulatorFallback();

        /** Binds a pack connector id for one tenant. */
        void bind(String tenantId, ConnectorBindingSpec binding, String actor);

        /** Test call: health probe, or an invocation when {@code invoke} is allowed for the connector. */
        TestResult test(String tenantId, ConnectorBindingSpec binding, boolean invoke);
    }

    /** Security set-up (sangam-security) and key status. */
    public interface SecurityPort {
        void installModuleDefaults(String tenantId, boolean selfService, String actor);

        /** Defines (or redefines) the roles; returns how many. */
        int defineRoles(String tenantId, List<RoleSpec> roles, String actor);

        void bootstrapAdmins(String tenantId, List<AdminSpec> admins, String actor);

        /** Installs the seed roles, offices and seats of the packs; existing seats stay. Returns the seats created. */
        int installPackSeeds(String tenantId, List<org.sangam.packs.spi.PackDescriptor> packs, String actor);

        /** A key-encryption (master) key is configured for field encryption (not the development key). */
        boolean masterKeyConfigured();

        /** A signing key is configured (registry instruments / certificates, bundle signing). */
        boolean signerConfigured();
    }

    /** Seeds (sangam-config master codes, sangam-registry identifier types). */
    public interface SeedPort {
        /** Loads master codes (CSV or YAML text) into the scope; returns codes loaded. */
        int loadClassifications(String scope, String format, String text);

        /** Defines party identifier types for the tenant; returns how many. */
        int defineIdentifierTypes(String tenantId, List<Map<String, Object>> definitions);

        /** Saves availability calendars in the resources calendar store ({id, name, zone, hours, ...}); returns how many. */
        int saveCalendars(String tenantId, List<Map<String, Object>> definitions);

        /** Reads a class-path seed resource (packs, plugins). */
        String readResource(String path);
    }

    /** Compliance jurisdiction packs (sangam-compliance). */
    public interface CompliancePort {
        /** Installed jurisdiction pack codes. */
        Set<String> installed();

        /** Assigns the jurisdiction (and settings) to the tenant. */
        void enable(String tenantId, List<String> jurisdictions, Map<String, String> settings, String actor);

        /** The tenant's current jurisdiction, if one was assigned. */
        Optional<String> jurisdictionOf(String tenantId);
    }

    /** Languages the platform can serve (UI catalogues, form labels, notices). */
    public interface LanguagePort {
        Set<String> available();
    }

    /** Append-only audit of onboarding actions (class-B audit on {@code onb:<tenant>}). */
    public interface AuditPort {
        void record(String tenantId, String runId, String action, String actor, Map<String, Object> details);
    }
}
