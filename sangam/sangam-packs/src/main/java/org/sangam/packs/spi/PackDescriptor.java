// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.util.List;
import java.util.Map;

/**
 * What a pack is (the SangamPack descriptor): identity and version, the BPMN / DMN / CMMN models, the forms (JSON,
 * Hindi + English labels), seed roles and seats, the connectors it uses with their capability class, the requirement
 * and pattern ids it covers, and its acceptance scenarios. Versioned as one package (R43, K10): a case is bound to the
 * pack version it started under.
 *
 * @param id             stable id, e.g. {@code gov.p1.bill-payment}
 * @param version        semantic version of the package
 * @param name           display name by language ({@code en}, {@code hi})
 * @param domain         domain from the catalogue (IFMS, e-Office, DBT, Works/WAMS, RTI, universal ...)
 * @param description    one-paragraph description (English)
 * @param mainProcesses  process / case definition keys a user starts
 * @param resources      every model, form and seed file of the pack
 * @param roles          seed roles (designations) with bilingual names
 * @param seats          seed seats (posts) with role, office and reporting seat
 * @param connectors     external systems used, with capability class (A-2) and government tag (X.4.0.2)
 * @param covers         requirement and pattern ids covered: GP-xx, UP-xx, AC-xx, R-xx, J-xx
 * @param acceptance     acceptance scenarios (machine-readable given / when / then)
 * @param dependsOn      ids of packs that must be deployed first (all government packs depend on {@code sangam.core})
 * @param tenantOverrides tenant id to DMN paths deployed only for that tenant (GP-37 department-specific rules and
 *                        pack configuration, for example {@code works-config})
 * @param placeholders   values shipped in the pack's DMN that are not from a verified source; the readiness check
 *                        blocks go-live of a tenant that enables the pack until it overrides the named decision
 */
public record PackDescriptor(String id, String version, Map<String, String> name, String domain, String description,
                             List<String> mainProcesses, List<PackResource> resources, List<SeedRole> roles,
                             List<SeedSeat> seats, List<ConnectorUse> connectors, List<String> covers,
                             List<AcceptanceScenario> acceptance, List<String> dependsOn,
                             Map<String, List<String>> tenantOverrides, List<Placeholder> placeholders) {

    /** Descriptor without placeholders. */
    public PackDescriptor(String id, String version, Map<String, String> name, String domain, String description,
                          List<String> mainProcesses, List<PackResource> resources, List<SeedRole> roles,
                          List<SeedSeat> seats, List<ConnectorUse> connectors, List<String> covers,
                          List<AcceptanceScenario> acceptance, List<String> dependsOn,
                          Map<String, List<String>> tenantOverrides) {
        this(id, version, name, domain, description, mainProcesses, resources, roles, seats, connectors, covers,
                acceptance, dependsOn, tenantOverrides, List.of());
    }

    public PackDescriptor {
        name = name == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(name));
        mainProcesses = mainProcesses == null ? List.of() : List.copyOf(mainProcesses);
        resources = resources == null ? List.of() : List.copyOf(resources);
        roles = roles == null ? List.of() : List.copyOf(roles);
        seats = seats == null ? List.of() : List.copyOf(seats);
        connectors = connectors == null ? List.of() : List.copyOf(connectors);
        covers = covers == null ? List.of() : List.copyOf(covers);
        acceptance = acceptance == null ? List.of() : List.copyOf(acceptance);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        tenantOverrides = tenantOverrides == null ? Map.of() : Map.copyOf(tenantOverrides);
        placeholders = placeholders == null ? List.of() : List.copyOf(placeholders);
    }

    /**
     * A pack DMN value that needs owner confirmation (never an invented amount presented as verified).
     *
     * @param decision decision key a tenant overrides with the confirmed value
     * @param rule     which rule / value is unconfirmed
     * @param reason   why, and from which source the value must be confirmed
     */
    public record Placeholder(String decision, String rule, String reason) {
    }

    /** {@code id@version}, the value bound to every case as {@code packVersion}. */
    /** DMN override paths for {@code tenantId}, empty when the tenant has none (or for shared deployments). */
    public List<String> overridesFor(String tenantId) {
        return tenantId == null ? List.of() : tenantOverrides.getOrDefault(tenantId, List.of());
    }

    public String coordinates() {
        return id + "@" + version;
    }

    public List<PackResource> resources(PackResource.Kind kind) {
        return resources.stream().filter(r -> r.kind() == kind).toList();
    }

    /** One file of the pack. */
    public record PackResource(Kind kind, String path) {
        public enum Kind { BPMN, DMN, CMMN, FORM, SEED, OTHER }

        /** True for files the engine deploys as definitions. */
        public boolean model() {
            return kind == Kind.BPMN || kind == Kind.DMN || kind == Kind.CMMN;
        }
    }

    /** A designation; the code is what the delegation-of-powers DMN tables use. */
    public record SeedRole(String code, Map<String, String> name) {
    }

    /**
     * A post in the synthetic pilot hierarchy (IX.5).
     *
     * @param level the org level type of the seat's office; one of the tenant's org levels (onboarding defines the
     *              levels from the request or the profile default {@code security.orgLevels} before seeding seats)
     */
    public record SeedSeat(String id, String role, String office, String level, String reportsTo, Map<String, String> name) {
    }

    /**
     * A connector the pack uses.
     *
     * @param id              connector id
     * @param capabilityClass a, b or c (A-2)
     * @param govTag          X.4.0.2 tag (G-PFMS, G-PG, G-SIGN, G-DL, G-DLT, G-MAIL ...) or empty
     * @param purpose         what for
     */
    public record ConnectorUse(String id, String capabilityClass, String govTag, String purpose) {
    }

    /**
     * An acceptance scenario.
     *
     * @param id             AC-xx, J-Pn-Exx or a pack-local id
     * @param title          short title
     * @param refs           related ids (tests T.., journeys J.., patterns)
     * @param given          preconditions
     * @param when           action / injected condition (simulator directives)
     * @param then           expected outcome
     * @param startVariables variables to start the main process with
     * @param expectState    expected final {@code caseState}, if any
     */
    public record AcceptanceScenario(String id, String title, List<String> refs, String given, String when,
                                     String then, Map<String, Object> startVariables, String expectState) {
        public AcceptanceScenario {
            refs = refs == null ? List.of() : List.copyOf(refs);
            startVariables = startVariables == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(startVariables));
        }
    }
}
