// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a new tenant needs, as data. The same request shape is produced by a bundle import, so a tenant can be
 * copied from another one. Nothing in it is specific to a country, government or sector: the jurisdiction, regime
 * preset, parent profile, packs and connectors are ids of data that ships with the platform or with a plugin.
 *
 * @param tenant       the tenant (its id is also its configuration scope and its engine tenant id)
 * @param profile      the configuration profile: jurisdiction, regime preset, parent, and the tenant document
 * @param packs        the packs to enable (dependencies are added automatically)
 * @param connectors   connector bindings for the pack connector ids, each with an optional test call
 * @param security     module role templates, tenant roles, tenant administrators
 * @param seeds        classifications, party identifier types and calendars
 * @param compliance   compliance jurisdiction packs
 * @param dmnOverrides tenant-specific DMN tables (decision key to DMN XML), deployed for this tenant only (GP-37)
 * @param goLive       go-live evidence and language requirements
 */
public record OnboardingRequest(TenantSpec tenant, ProfileSpec profile, PackSpec packs,
                                List<ConnectorBindingSpec> connectors, SecuritySpec security, SeedSpec seeds,
                                ComplianceSpec compliance, Map<String, String> dmnOverrides, GoLiveSpec goLive) {

    public OnboardingRequest {
        if (tenant == null || tenant.id() == null || tenant.id().isBlank()) {
            throw new IllegalArgumentException("tenant.id is required");
        }
        profile = profile == null ? new ProfileSpec(null, null, null, null, null, null, null, null, null, null) : profile;
        packs = packs == null ? new PackSpec(null, null, null) : packs;
        connectors = connectors == null ? List.of() : List.copyOf(connectors);
        security = security == null ? new SecuritySpec(null, null, null, null, null) : security;
        seeds = seeds == null ? new SeedSpec(null, null, null) : seeds;
        compliance = compliance == null ? new ComplianceSpec(null, null) : compliance;
        dmnOverrides = dmnOverrides == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(dmnOverrides));
        goLive = goLive == null ? new GoLiveSpec(null, null) : goLive;
    }

    public String tenantId() {
        return tenant.id();
    }

    public OnboardingRequest withTenant(TenantSpec t) {
        return new OnboardingRequest(t, profile, packs, connectors, security, seeds, compliance, dmnOverrides, goLive);
    }

    public OnboardingRequest withProfile(ProfileSpec p) {
        return new OnboardingRequest(tenant, p, packs, connectors, security, seeds, compliance, dmnOverrides, goLive);
    }

    public OnboardingRequest withGoLive(GoLiveSpec g) {
        return new OnboardingRequest(tenant, profile, packs, connectors, security, seeds, compliance, dmnOverrides, g);
    }

    /**
     * The tenant.
     *
     * @param id                   tenant id = configuration scope ({@code A}, {@code A.B}, ...), 1-60 safe characters
     * @param name                 display name
     * @param names                display names by language tag
     * @param parentId             parent tenant in the control-plane hierarchy (null for a root)
     * @param levelKind            hierarchy level name used by the control plane (data: country, state, department,
     *                             organisation, branch ...)
     * @param sector               sector tag (data: government, banking, health, retail ...)
     * @param residency            data-residency rule the placement must satisfy (data: a region or jurisdiction code)
     * @param expectedStartsPerSec expected load, used by placement headroom
     * @param hostingProfile       hosting profile recorded with the tenant
     * @param quotas               per-tenant quotas
     * @param preferredCell        a cell to place on if it qualifies (otherwise the placement service decides)
     */
    public record TenantSpec(String id, String name, Map<String, String> names, String parentId, String levelKind,
                             String sector, String residency, Double expectedStartsPerSec, String hostingProfile,
                             Map<String, Object> quotas, String preferredCell) {
        public TenantSpec {
            names = names == null ? Map.of() : Map.copyOf(names);
            quotas = quotas == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(quotas));
        }

        public TenantSpec withId(String newId, String newParent) {
            return new TenantSpec(newId, name, names, newParent, levelKind, sector, residency, expectedStartsPerSec,
                    hostingProfile, quotas, preferredCell);
        }

        public TenantSpec withName(String newName, Map<String, String> newNames) {
            return new TenantSpec(id, newName, newNames, parentId, levelKind, sector, residency, expectedStartsPerSec,
                    hostingProfile, quotas, preferredCell);
        }
    }

    /** How the tenant's own profile document is produced. */
    public enum ProfileMode {
        /** Generate a document for the tenant scope that extends the chosen jurisdiction / preset and carries overrides. */
        INHERIT,
        /** Use the supplied documents as they are (for example a new country's jurisdiction profile plus its tenant). */
        IMPORT,
        /** Adopt sections from a donor profile (another tenant), plus overrides. */
        COPY
    }

    /**
     * The configuration profile.
     *
     * @param jurisdiction  id of the jurisdiction-layer profile ({@code layer: jurisdiction}); may be supplied in
     *                      {@code documents}
     * @param regimePreset  id of a regime- or category-layer preset to build on (optional)
     * @param parent        id of the parent profile the tenant inherits from, when it is not the scope prefix
     *                      ({@code A} for {@code A.B} is found automatically)
     * @param mode          INHERIT (default), IMPORT or COPY
     * @param layer         layer of the tenant document (default: government for a root scope, department below)
     * @param version       version of the tenant document (default {@code onb.<run>.<revision>})
     * @param effectiveFrom ISO date the document is effective from (default today)
     * @param overrides     data sections of the tenant document (merged over everything it extends)
     * @param documents     whole profile documents (trees in the sangam-config file shape) to store as drafts
     * @param adoptFrom     COPY: donor profile id and the sections to adopt ({@code [{profile, sections}]})
     */
    public record ProfileSpec(String jurisdiction, String regimePreset, String parent, ProfileMode mode, String layer,
                              String version, String effectiveFrom, Map<String, Object> overrides,
                              List<Map<String, Object>> documents, List<Map<String, Object>> adoptFrom) {
        public ProfileSpec {
            mode = mode == null ? ProfileMode.INHERIT : mode;
            overrides = overrides == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
            documents = documents == null ? List.of() : List.copyOf(documents);
            adoptFrom = adoptFrom == null ? List.of() : List.copyOf(adoptFrom);
        }

        public ProfileSpec withDocuments(List<Map<String, Object>> docs, Map<String, Object> newOverrides) {
            return new ProfileSpec(jurisdiction, regimePreset, parent, mode, layer, version, effectiveFrom, newOverrides,
                    docs, adoptFrom);
        }

        public ProfileSpec withVersion(String v) {
            return new ProfileSpec(jurisdiction, regimePreset, parent, mode, layer, v, effectiveFrom, overrides,
                    documents, adoptFrom);
        }
    }

    /**
     * Pack selection.
     *
     * @param ids          pack ids to enable
     * @param versions     optional version range per pack id ({@code ^1.0}); default any
     * @param allowUpgrade allow replacing an installed version of a pack (subject to its upgrade path)
     */
    public record PackSpec(List<String> ids, Map<String, String> versions, Boolean allowUpgrade) {
        public PackSpec {
            ids = ids == null ? List.of() : List.copyOf(ids);
            versions = versions == null ? Map.of() : Map.copyOf(versions);
        }

        public boolean upgrades() {
            return Boolean.TRUE.equals(allowUpgrade);
        }
    }

    /**
     * One connector binding.
     *
     * @param packConnector the connector id a pack uses ({@code notify.sms})
     * @param connectorId   the registry connector that serves it for this tenant (a simulator or a live connector)
     * @param operation     operation to call on the connector (null: the pack's own operation name)
     * @param test          optional test call
     */
    public record ConnectorBindingSpec(String packConnector, String connectorId, String operation, TestCall test) {
    }

    /**
     * A connector test call.
     *
     * @param operation operation to invoke (default: the binding's operation)
     * @param input     input of the call
     * @param invoke    true: really invoke (allowed on a simulator connector, or a live connector of capability class
     *                  a, which is idempotent by operationId); false: health probe only
     */
    public record TestCall(String operation, Map<String, Object> input, Boolean invoke) {
        public TestCall {
            input = input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
        }

        public boolean wantsInvoke() {
            return Boolean.TRUE.equals(invoke);
        }
    }

    /**
     * Security set-up.
     *
     * @param moduleDefaults install the module role templates (default true)
     * @param selfService    also install the person-conditioned self-service grants (default true)
     * @param roles          tenant roles to define
     * @param tenantAdmins   tenant administrators to bootstrap
     * @param orgLevels      the tenant's org level types, top first (SEC_ORG_LEVEL); empty means the default
     *                       hierarchy of the resolved profile ({@code security.orgLevels}). Levels are fixed once the
     *                       first org unit exists (A02), so they are defined before pack seats are seeded.
     */
    public record SecuritySpec(Boolean moduleDefaults, Boolean selfService, List<RoleSpec> roles,
                               List<AdminSpec> tenantAdmins, List<String> orgLevels) {
        public SecuritySpec {
            roles = roles == null ? List.of() : List.copyOf(roles);
            tenantAdmins = tenantAdmins == null ? List.of() : List.copyOf(tenantAdmins);
            // blanks and nulls are kept so that the dry run reports them (ORG_LEVELS_INVALID) instead of failing to parse
            orgLevels = orgLevels == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(orgLevels));
        }

        public boolean defaults() {
            return moduleDefaults == null || moduleDefaults;
        }

        public boolean selfServiceGrants() {
            return selfService == null || selfService;
        }
    }

    /** A role: id, name and rules ({@code resourceType}, {@code resourceId} default {@code *}, permissions, deny). */
    public record RoleSpec(String id, String name, List<RoleRuleSpec> rules) {
        public RoleSpec {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public record RoleRuleSpec(String resourceType, String resourceId, List<String> permissions, Boolean deny) {
        public RoleRuleSpec {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }

    public record AdminSpec(String personId, String externalSubject, String displayName) {
    }

    /**
     * Seeds.
     *
     * @param classifications      master-code seed files: {@code {scope?, format: csv|yaml, resource?, content?}}
     *                             ({@code resource} is a class-path path, {@code content} the text itself)
     * @param partyIdentifierTypes party-registry identifier-type definitions (registry seed format)
     * @param calendars            availability calendars for the resources calendar service: {@code {id, name, zone, hours,
     *                             businessCalendarId, closedOnHolidays}}
     */
    public record SeedSpec(List<Map<String, Object>> classifications, List<Map<String, Object>> partyIdentifierTypes,
                           List<Map<String, Object>> calendars) {
        public SeedSpec {
            classifications = classifications == null ? List.of() : List.copyOf(classifications);
            partyIdentifierTypes = partyIdentifierTypes == null ? List.of() : List.copyOf(partyIdentifierTypes);
            calendars = calendars == null ? List.of() : List.copyOf(calendars);
        }
    }

    /**
     * Compliance.
     *
     * @param jurisdictions compliance jurisdiction pack codes; the first is the tenant's
     * @param settings      per-tenant compliance settings ({@code ComplianceConfig} keys)
     */
    public record ComplianceSpec(List<String> jurisdictions, Map<String, String> settings) {
        public ComplianceSpec {
            jurisdictions = jurisdictions == null ? List.of() : List.copyOf(jurisdictions);
            settings = settings == null ? Map.of() : Map.copyOf(settings);
        }
    }

    /**
     * Go-live.
     *
     * @param evidence  go-live evidence by checklist item (references to documents held elsewhere)
     * @param languages languages the tenant must have available (in addition to the profile's statutory languages)
     */
    public record GoLiveSpec(Map<String, String> evidence, List<String> languages) {
        public GoLiveSpec {
            evidence = evidence == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
            languages = languages == null ? List.of() : List.copyOf(languages);
        }

        public GoLiveSpec withEvidence(Map<String, String> more) {
            Map<String, String> m = new LinkedHashMap<>(evidence);
            if (more != null) {
                m.putAll(more);
            }
            return new GoLiveSpec(m, languages);
        }
    }

    /** Helper for callers building lists. */
    public static <T> List<T> listOf(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a == null ? List.of() : a);
        if (b != null) {
            out.addAll(b);
        }
        return out;
    }
}
