// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.api.OnboardingRun;
import org.sangam.onboarding.api.StepId;
import org.sangam.onboarding.deps.PackDependencyResolver;
import org.sangam.onboarding.deps.PackRequirementsCatalog;
import org.sangam.onboarding.deps.PlatformInfo;
import org.sangam.onboarding.port.OnboardingPorts;
import org.sangam.onboarding.support.OnboardingJson;
import org.sangam.packs.spi.PackRegistry;

/**
 * Joint decision EV-M-0986, IFMS condition (2): the PACKS step deploys into the new tenant as that tenant (option A),
 * and its audit row names both the maker who started the run and the target tenant. Before the fix the step wrote only
 * {@code STEP_DONE} with the actor {@code system}, so no row tied the deployment to the maker.
 */
class OnboardingPacksAuditTest {

    record Row(String tenant, String runId, String action, String actor, Map<String, Object> details) {
    }

    @Test
    void thePacksDeploymentIsAuditedWithTheMakerAndTheTargetTenant() {
        List<Row> rows = new ArrayList<>();
        List<String> deployedFor = new ArrayList<>();
        OnboardingPorts.PackInstallPort packs = new OnboardingPorts.PackInstallPort() {
            @Override
            public Map<String, String> installed(String tenantId) {
                return Map.of();
            }

            @Override
            public Map<String, String> deploy(String tenantId, List<String> ids) {
                deployedFor.add(tenantId);
                Map<String, String> out = new LinkedHashMap<>();
                ids.forEach(id -> out.put(id + "@1.0.0", "dep-" + id));
                return out;
            }

            @Override
            public String deployDecisionOverrides(String tenantId, Map<String, String> dmn) {
                return "dep-overrides";
            }

            @Override
            public void select(String tenantId, Set<String> ids) {
            }
        };
        OnboardingService.Ports ports = new OnboardingService.Ports(null, null, packs, null, null, null, null,
                (tenant, runId, action, actor, details) -> rows.add(new Row(tenant, runId, action, actor, details)));
        PackDependencyResolver resolver = new PackDependencyResolver(PackRegistry.discover(),
                PackRequirementsCatalog.scan(getClass().getClassLoader()),
                new PlatformInfo("0.1.0-SNAPSHOT", "2.2.0",
                        Map.of("sangam-config", "0.1.0-SNAPSHOT", "sangam-ledger", "0.1.0-SNAPSHOT")));
        OnboardingStore store = new OnboardingStore.InMemory();
        OnboardingService service = new OnboardingService(store, null, ports, resolver, null, null, null, null, null);

        OnboardingRequest req = OnboardingJson.read("{\"tenant\":{\"id\":\"ATS-AUDIT\"},\"packs\":{\"ids\":[\"sangam.core\"]}}",
                OnboardingRequest.class);
        Instant now = Instant.now();
        store.save(new OnboardingRun("run-1", req.tenantId(), OnboardingRun.Status.RUNNING, "acc-admin", null, 1, req,
                OnboardingJson.hash(OnboardingJson.tree(req)), null, null, null, now, now));

        assertTrue(service.executeStep("run-1", StepId.PACKS));

        assertEquals(List.of("ATS-AUDIT"), deployedFor);
        List<Row> deployed = rows.stream().filter(r -> r.action().equals("PACKS_DEPLOYED")).toList();
        assertEquals(1, deployed.size(), "one deploy row: " + rows);
        Row r = deployed.get(0);
        assertEquals("ATS-AUDIT", r.tenant(), "on the target tenant's onboarding chain");
        assertEquals("run-1", r.runId());
        assertEquals("acc-admin", r.actor(), "the maker, not 'system'");
        assertEquals("acc-admin", r.details().get("maker"));
        assertEquals("ATS-AUDIT", r.details().get("targetTenant"));
        assertEquals(List.of("sangam.core@1.0.0"), r.details().get("packs"));
    }
}
