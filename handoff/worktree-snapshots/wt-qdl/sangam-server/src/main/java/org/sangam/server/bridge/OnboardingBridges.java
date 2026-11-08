// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.repository.Deployment;
import org.sangam.config.master.SeedLoader;
import org.sangam.config.store.JdbcConfigStore;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.onboarding.api.OnboardingRequest.AdminSpec;
import org.sangam.onboarding.api.OnboardingRequest.ConnectorBindingSpec;
import org.sangam.onboarding.api.OnboardingRequest.RoleRuleSpec;
import org.sangam.onboarding.api.OnboardingRequest.RoleSpec;
import org.sangam.onboarding.api.OnboardingRequest.TenantSpec;
import org.sangam.onboarding.deps.SemVer;
import org.sangam.onboarding.flow.OnboardingException;
import org.sangam.onboarding.port.OnboardingPorts;
import org.sangam.onboarding.readiness.OrgLevels;
import org.sangam.onboarding.support.OnboardingJson;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.resources.SangamResources;
import org.sangam.resources.calendar.AvailabilityCalendar;
import org.sangam.registry.party.PartyService;
import org.sangam.rest.port.RequestContext;
import org.sangam.security.SangamSecurity;
import org.sangam.security.admin.RoleAdminService;
import org.sangam.security.admin.TenantAdminService;
import org.sangam.security.policy.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bindings of the onboarding ports to the platform's modules. */
public final class OnboardingBridges {

    private static final Logger LOG = LoggerFactory.getLogger(OnboardingBridges.class);

    private OnboardingBridges() {
    }

    private static SecurityIdentity platformAdmin(String tenant) {
        return SecurityIdentity.system(tenant, "tenant-onboarding")
                .withGroupsAndRoles(Set.of(), Set.of("system", TenantAdminService.PLATFORM_ADMIN_ROLE));
    }

    /** sangam-security tenant directory. Activation is direct, so the pack selection is not widened. */
    public static final class Tenants implements OnboardingPorts.TenantPort {
        private final SangamSecurity security;
        private final Transactions tx;

        public Tenants(SangamSecurity security, Transactions tx) {
            this.security = security;
            this.tx = tx;
        }

        @Override
        public Optional<TenantInfo> find(String id) {
            return tx.inTransaction(c -> security.tenants().find(c, id).map(t -> new TenantInfo(t.tenantId(), t.status())));
        }

        @Override
        public TenantInfo create(TenantSpec s, String actor) {
            return tx.inTransaction(c -> {
                Optional<TenantAdminService.Tenant> t = security.tenants().find(c, s.id());
                if (t.isEmpty()) {
                    t = Optional.of(security.tenants().createTenant(c, platformAdmin(s.id()), s.id(),
                            s.name() == null ? s.id() : s.name(), s.names().get("hi"), null, s.hostingProfile(), s.quotas()));
                }
                return new TenantInfo(t.get().tenantId(), t.get().status());
            });
        }

        @Override
        public void configure(String id, Map<String, Object> config, Map<String, Object> quotas, String actor) {
            tx.inTransaction(c -> {
                security.tenants().configure(c, platformAdmin(id), config, quotas, null);
                return null;
            });
            // SL-43: the tenant's quota overrides changed; drop this node's cached copy once committed
            org.sangam.server.config.QuotaWiring.invalidateAfterCommit(id);
        }

        @Override
        public List<String> missingGoLiveEvidence(String id, List<String> planned) {
            return tx.inTransaction(c -> security.tenants().missingEvidence(c, id, planned));
        }

        @Override
        public void recordEvidence(String id, String item, String ref, String actor) {
            tx.inTransaction(c -> {
                security.tenants().recordChecklistItem(c, platformAdmin(id), item, ref);
                return null;
            });
        }

        @Override
        public void activate(String id, String actor) {
            tx.inTransaction(c -> {
                security.tenants().activate(c, platformAdmin(id));
                return null;
            });
        }
    }

    /** Control-plane admin API; without a URL the deployment is one cell and the tenant lives on it. */
    public static final class Placement implements OnboardingPorts.PlacementPort {
        private final String url;
        private final String token;
        private final String localCell;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        public Placement(String url, String token, String localCell) {
            this.url = url == null || url.isBlank() ? null : url.replaceAll("/+$", "");
            this.token = token;
            this.localCell = localCell == null || localCell.isBlank() ? "local" : localCell;
        }

        @Override
        public Optional<OnboardingPorts.PlacementPort.Placement> placement(String id) {
            if (url == null) {
                return Optional.empty();
            }
            JsonNode t = call("GET", "/api/v1/admin/tenants/" + id, null, true);
            String cell = t == null ? null : t.path("cellId").asText(null);
            return cell == null || cell.isBlank() ? Optional.empty()
                    : Optional.of(new OnboardingPorts.PlacementPort.Placement(id, cell, "CONTROL_PLANE", t.path("path").asText("")));
        }

        @Override
        public List<String> candidates(TenantSpec s) {
            if (url == null) {
                return List.of(localCell);
            }
            List<String> out = new ArrayList<>();
            JsonNode cells = call("GET", "/api/v1/cells", null, false);
            if (cells != null) {
                for (JsonNode c : cells) {
                    boolean up = !"DOWN".equals(c.path("status").asText()) && !"RETIRED".equals(c.path("status").asText());
                    boolean residency = s.residency() == null || s.residency().equals(c.path("residency").asText(s.residency()));
                    if (up && residency) {
                        out.add(c.path("cellId").asText(c.path("id").asText()));
                    }
                }
            }
            return out;
        }

        @Override
        public OnboardingPorts.PlacementPort.Placement register(TenantSpec s, String actor) {
            if (url == null) {
                return new OnboardingPorts.PlacementPort.Placement(s.id(), localCell, "LOCAL", "single-cell deployment");
            }
            if (call("GET", "/api/v1/admin/tenants/" + s.id(), null, true) == null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("tenantCode", s.id());
                body.put("parentCode", s.parentId());
                body.put("levelKind", s.levelKind());
                body.put("name", s.name() == null ? s.id() : s.name());
                body.put("jurisdiction", s.residency());
                body.put("residency", s.residency());
                body.put("sector", s.sector());
                body.put("expectedStartsPerSec", s.expectedStartsPerSec() == null ? 0d : s.expectedStartsPerSec());
                call("POST", "/api/v1/admin/tenants", OnboardingJson.write(body), false);
            }
            JsonNode t = call("POST", "/api/v1/admin/tenants/" + s.id() + "/place", null, false);
            return new OnboardingPorts.PlacementPort.Placement(s.id(), t.path("cellId").asText(), "CONTROL_PLANE", t.path("path").asText(""));
        }

        private JsonNode call(String method, String path, String json, boolean notFoundIsNull) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(10));
            if (token != null && !token.isBlank()) {
                b.header("Authorization", "Bearer " + token);
            }
            b.header("Content-Type", "application/json");
            b.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            try {
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 404 && notFoundIsNull) {
                    return null;
                }
                if (r.statusCode() / 100 != 2) {
                    throw new IllegalStateException(method + " " + path + " on the control plane: HTTP " + r.statusCode());
                }
                return OnboardingJson.MAPPER.readTree(r.body());
            } catch (IOException e) {
                throw new IllegalStateException("control plane unreachable: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** Per-tenant deployment on the cell engine. */
    public static final class Packs implements OnboardingPorts.PackInstallPort {
        private final ProcessEngine engine;
        private final PackDeployer deployer;
        private final PacksTenantDeployer tenantDeployer;

        public Packs(ProcessEngine engine, PackDeployer deployer, PacksTenantDeployer tenantDeployer) {
            this.engine = engine;
            this.deployer = deployer;
            this.tenantDeployer = tenantDeployer;
        }

        @Override
        public Map<String, String> installed(String tenant) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Deployment d : engine.getRepositoryService().createDeploymentQuery().tenantIdIn(tenant).list()) {
                String n = d.getName();
                if (n == null || !n.startsWith("pack:") || n.indexOf('@') < 0) {
                    continue;
                }
                String id = n.substring(5, n.lastIndexOf('@'));
                String v = n.substring(n.lastIndexOf('@') + 1);
                out.merge(id, v, (a, b) -> {
                    SemVer x = SemVer.tryParse(a);
                    SemVer y = SemVer.tryParse(b);
                    return x != null && y != null && y.compareTo(x) > 0 ? b : a;
                });
            }
            return out;
        }

        @Override
        public Map<String, String> deploy(String tenant, List<String> ids) {
            Map<String, String> out = new LinkedHashMap<>();
            for (PackDeployer.Result r : deployer.deploy(ids, tenant)) {
                out.put(r.packId() + "@" + r.version(), r.deploymentId());
            }
            return out;
        }

        @Override
        public String deployDecisionOverrides(String tenant, Map<String, String> dmn) {
            var b = engine.getRepositoryService().createDeployment().name("onboarding-overrides:" + tenant)
                    .tenantId(tenant).enableDuplicateFiltering(false);
            dmn.forEach((key, xml) -> b.addString(key + ".dmn", xml));
            return b.deploy().getId();
        }

        @Override
        public void select(String tenant, Set<String> ids) {
            tenantDeployer.select(tenant, ids);
        }
    }

    /** Per-tenant bindings; other tenants keep the platform binding or the simulator fallback. */
    public static final class Connectors implements OnboardingPorts.ConnectorPort {
        private final ConnectorRegistry registry;
        private final PackConnectorBindings bindings;
        private final BooleanSupplier fallback;

        public Connectors(ConnectorRegistry registry, PackConnectorBindings bindings, BooleanSupplier fallback) {
            this.registry = registry;
            this.bindings = bindings;
            this.fallback = fallback;
        }

        @Override
        public List<ConnectorInfo> available(String tenant) {
            List<ConnectorInfo> out = new ArrayList<>();
            for (ConnectorDescriptor d : registry.descriptors()) {
                if (registry.find(tenant, d.id()).isPresent()) {
                    out.add(new ConnectorInfo(d.id(), d.type(), d.profile().name(), d.operations(), d.capabilityClass().name()));
                }
            }
            return out;
        }

        @Override
        public Optional<String> boundTo(String tenant, String packConnector) {
            return bindings.binding(tenant, packConnector).map(PackConnectorBindings.Binding::connectorId)
                    .filter(id -> registry.find(tenant, id).isPresent());
        }

        @Override
        public boolean simulatorFallback() {
            return fallback.getAsBoolean();
        }

        @Override
        public void bind(String tenant, ConnectorBindingSpec b, String actor) {
            registry.require(tenant, b.connectorId());
            bindings.bindForTenant(tenant, b.packConnector(), b.connectorId(), b.operation());
        }

        @Override
        public TestResult test(String tenant, ConnectorBindingSpec b, boolean invoke) {
            SangamConnector c = registry.find(tenant, b.connectorId()).orElse(null);
            if (c == null) {
                return new TestResult(false, "HEALTH", "NOT_FOUND", "no connector " + b.connectorId());
            }
            boolean safe = c.descriptor().profile() == ConnectorProfile.SIMULATOR
                    || c.capabilityClass() == CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP;
            if (!invoke || !safe) {
                boolean up = c.healthy();
                return new TestResult(up, "HEALTH", up ? "SUCCESS" : "UNHEALTHY",
                        invoke ? "a live connector without idempotency is only probed" : "probe");
            }
            String op = b.test() != null && b.test().operation() != null ? b.test().operation()
                    : b.operation() != null ? b.operation() : "invoke";
            ConnectorResult r = c.invoke(new ConnectorRequest(tenant, UUID.randomUUID(), "onboarding-test", 1, "onboarding-test",
                    "onboarding-test", op, b.test() == null ? Map.of() : b.test().input(), null));
            if (r instanceof ConnectorResult.Success s) {
                return new TestResult(true, "INVOKE", "SUCCESS", Objects.toString(s.providerRef(), ""));
            }
            if (r instanceof ConnectorResult.BusinessFailure f) {
                // the system answered: the connection works, the sample input was refused
                return new TestResult(true, "INVOKE", "BUSINESS_FAILURE", f.code() + ": " + f.message());
            }
            if (r instanceof ConnectorResult.TransientFailure t) {
                return new TestResult(false, "INVOKE", "TRANSIENT_FAILURE", t.message());
            }
            return new TestResult(false, "INVOKE", "OUTCOME_UNKNOWN", ((ConnectorResult.OutcomeUnknown) r).reason());
        }
    }

    /** Module role templates, roles, administrators, key status. */
    public static final class Security implements OnboardingPorts.SecurityPort {
        private final SangamSecurity security;
        private final Transactions tx;
        private final BooleanSupplier masterKey;
        private final BooleanSupplier signer;

        public Security(SangamSecurity security, Transactions tx, BooleanSupplier masterKey, BooleanSupplier signer) {
            this.security = security;
            this.tx = tx;
            this.masterKey = masterKey;
            this.signer = signer;
        }

        @Override
        public void installModuleDefaults(String tenant, boolean selfService, String actor) {
            tx.inTransaction(c -> {
                security.defaults().install(c, platformAdmin(tenant), selfService);
                return null;
            });
        }

        @Override
        public int defineRoles(String tenant, List<RoleSpec> roles, String actor) {
            tx.inTransaction(c -> {
                for (RoleSpec r : roles) {
                    List<RoleAdminService.RoleRule> rules = new ArrayList<>();
                    for (RoleRuleSpec s : r.rules()) {
                        Set<Permission> perms = new LinkedHashSet<>();
                        s.permissions().forEach(p -> perms.add(Permission.parse(p)));
                        rules.add(new RoleAdminService.RoleRule(s.resourceType(), s.resourceId() == null ? "*" : s.resourceId(),
                                perms, Boolean.TRUE.equals(s.deny())));
                    }
                    security.roles().defineRole(c, platformAdmin(tenant), r.id(), r.name() == null ? r.id() : r.name(), rules);
                }
                return null;
            });
            return roles.size();
        }

        @Override
        public void bootstrapAdmins(String tenant, List<AdminSpec> admins, String actor) {
            List<RoleAdminService.AdminSeed> seeds = admins.stream()
                    .map(a -> new RoleAdminService.AdminSeed(a.personId(), a.externalSubject(), a.displayName())).toList();
            tx.inTransaction(c -> {
                security.roles().bootstrapTenantAdmins(c, platformAdmin(tenant), tenant, seeds);
                return null;
            });
        }

        @Override
        public List<String> orgLevels(String tenant) {
            return tx.inTransaction(c -> security.orgUnits().levels(c, tenant));
        }

        @Override
        public boolean orgUnitsExist(String tenant) {
            return tx.inTransaction(c -> security.orgUnits().hasUnits(c, tenant));
        }

        @Override
        public void defineOrgLevels(String tenant, List<String> levelTypes, String actor) {
            tx.inTransaction(c -> {
                // idempotent for a re-run step; different levels after units exist are refused by defineLevels
                if (!security.orgUnits().levels(c, tenant).equals(levelTypes)) {
                    security.orgUnits().defineLevels(c, platformAdmin(tenant), levelTypes);
                }
                return null;
            });
        }

        @Override
        public int installPackSeeds(String tenant, List<PackDescriptor> packs, String actor) {
            SecurityIdentity admin = platformAdmin(tenant);
            Instant now = Instant.now();
            return tx.inTransaction(c -> {
                Map<String, PackDescriptor.SeedSeat> pending = new LinkedHashMap<>();
                for (PackDescriptor p : packs) {
                    for (PackDescriptor.SeedRole r : p.roles()) {
                        security.roles().defineRole(c, admin, r.code(), r.name().getOrDefault("en", r.code()), List.of());
                    }
                    p.seats().forEach(s -> pending.putIfAbsent(s.id(), s));
                }
                int created = 0;
                Set<String> known = new LinkedHashSet<>();
                for (String id : pending.keySet()) {
                    if (security.seats().seatAt(c, tenant, id, now).isPresent()) {
                        known.add(id);
                    }
                }
                pending.keySet().removeAll(known);
                // a seat is created after the seat it reports to; a dangling or circular reference is dropped
                while (!pending.isEmpty()) {
                    PackDescriptor.SeedSeat next = pending.values().stream()
                            .filter(s -> s.reportsTo() == null || s.reportsTo().isBlank() || known.contains(s.reportsTo())
                                    || !pending.containsKey(s.reportsTo()))
                            .findFirst().orElse(pending.values().iterator().next());
                    String office = OrgLevels.office(next);
                    if (security.orgUnits().unitAt(c, tenant, office, now).isEmpty()) {
                        // the unit's level is the one its seats name (SL-14); the onboarding SECURITY step defined the
                        // tenant's levels first and the dry run refused seats whose level is missing or unknown
                        security.orgUnits().createUnit(c, admin, office, next.level(), null, office, null, now);
                    }
                    String escalation = known.contains(next.reportsTo()) ? next.reportsTo() : null;
                    security.seats().createSeat(c, admin, next.id(), next.role(), office, 1, false, escalation, now);
                    known.add(next.id());
                    pending.remove(next.id());
                    created++;
                }
                return created;
            });
        }

        @Override
        public boolean masterKeyConfigured() {
            return masterKey.getAsBoolean();
        }

        @Override
        public boolean signerConfigured() {
            return signer.getAsBoolean();
        }
    }

    /** Master codes and party identifier types. */
    public static final class Seeds implements OnboardingPorts.SeedPort {
        private final JdbcConfigStore store;
        private final PartyService parties;
        private final List<ClassLoader> loaders;
        private final SangamResources resources;
        private final Transactions tx;

        public Seeds(JdbcConfigStore store, PartyService parties, List<ClassLoader> loaders, SangamResources resources, Transactions tx) {
            this.resources = resources;
            this.tx = tx;
            this.store = store;
            this.parties = parties;
            this.loaders = loaders;
        }

        @Override
        public int loadClassifications(String scope, String format, String text) {
            return SeedLoader.load(store, "yaml".equalsIgnoreCase(format) ? SeedLoader.yaml(scope, text) : SeedLoader.csv(scope, text));
        }

        @Override
        public int defineIdentifierTypes(String tenant, List<Map<String, Object>> defs) {
            if (parties == null) {
                throw new OnboardingException(422, "CAPABILITY_NOT_INSTALLED", "sangam-registry is off");
            }
            return parties.defineIdentifierTypes(tenant, defs).size();
        }

        @Override
        public int saveCalendars(String tenant, List<Map<String, Object>> definitions) {
            if (resources == null) {
                throw new OnboardingException(422, "CAPABILITY_NOT_INSTALLED", "sangam-resources is off");
            }
            return tx.inTransaction(c -> {
                for (Map<String, Object> d : definitions) {
                    resources.calendars().saveCalendar(c, platformAdmin(tenant),
                            AvailabilityCalendar.fromMap(String.valueOf(d.get("id")), d));
                }
                return definitions.size();
            });
        }

        @Override
        public String readResource(String path) {
            for (ClassLoader cl : loaders) {
                try (var in = cl.getResourceAsStream(path)) {
                    if (in != null) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                } catch (IOException e) {
                    LOG.warn("seed {}: {}", path, e.toString());
                }
            }
            throw new IllegalArgumentException("seed resource not found: " + path);
        }
    }

    /** Compliance jurisdictions per tenant. */
    public static final class Compliance implements OnboardingPorts.CompliancePort {
        private final ComplianceConfigProvider provider;

        public Compliance(ComplianceConfigProvider provider) {
            this.provider = provider;
        }

        @Override
        public Set<String> installed() {
            Set<String> s = new LinkedHashSet<>();
            provider.registry().all().forEach(p -> s.add(p.code()));
            return s;
        }

        @Override
        public void enable(String tenant, List<String> jurisdictions, Map<String, String> settings, String actor) {
            provider.assign(tenant, jurisdictions.get(0), settings);
        }

        @Override
        public Optional<String> jurisdictionOf(String tenant) {
            return Optional.ofNullable(provider.effectiveJurisdiction(tenant));
        }
    }

    /** Class-B audit on the chain {@code onb:<tenant>}. */
    public static final class Audit implements OnboardingPorts.AuditPort {
        private final org.sangam.rest.port.AuditPort audit;

        public Audit(org.sangam.rest.port.AuditPort audit) {
            this.audit = audit;
        }

        @Override
        public void record(String tenant, String runId, String action, String actor, Map<String, Object> details) {
            Map<String, Object> p = new LinkedHashMap<>(details);
            p.put("runId", runId);
            p.put("actor", actor);
            audit.record(RequestContext.system(tenant, "onboarding"), "onb:" + tenant, "ONBOARDING_" + action, p);
        }
    }
}
