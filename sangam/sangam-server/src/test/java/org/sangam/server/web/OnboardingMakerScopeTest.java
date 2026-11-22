// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.api.OnboardingRun;
import org.sangam.onboarding.flow.OnboardingFlowEngine;
import org.sangam.onboarding.flow.OnboardingService;
import org.sangam.onboarding.flow.OnboardingStore;
import org.sangam.onboarding.support.OnboardingJson;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.server.security.PlatformIssuers;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * IFMS [AMEND] to joint decision EV-M-0986, the maker rule: a tenant-identity maker may start onboarding only for its
 * own tenant; any other tenant needs a platform operator. Fail closed, with an audited refusal (CONTRACT-REGISTER row
 * of 2026-11-22 on POST /onboarding/runs and POST /onboarding/bundles/import). Before the fix a tenant identity was
 * refused only by the platform-administrator gate (403 ACTION_NOT_PERMITTED, not audited, no rule of its own).
 */
class OnboardingMakerScopeTest {

    static final List<String> ADMIN_GROUPS = List.of("sangam-admin", "sangam-user", "sangam-operator", "sangam-worker");

    record Row(String tenant, String principal, String chain, String type, Map<String, Object> payload) {
    }

    static final class RecordingAudit implements AuditPort {
        final List<Row> rows = new ArrayList<>();

        @Override
        public String record(RequestContext ctx, String chainId, String eventType, Map<String, ?> payload) {
            rows.add(new Row(ctx.tenantId(), ctx.principalId(), chainId, eventType, new LinkedHashMap<>(payload)));
            return "e" + rows.size();
        }

        @Override
        public Page<AuditEntry> timeline(RequestContext ctx, String businessKey, boolean includeHistory, Page.PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<AuditEntry> search(RequestContext ctx, AuditFilter filter, Page.PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VerifyResult verify(RequestContext ctx, String chainId, String businessKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Download export(RequestContext ctx, String businessKey, String format) {
            throw new UnsupportedOperationException();
        }
    }

    static final PermissionPort ALL = new PermissionPort() {
        @Override
        public boolean has(RequestContext ctx, String permission) {
            return true;
        }

        @Override
        public Set<String> permissions(RequestContext ctx) {
            return Set.of(Permissions.TENANT_ADMIN);
        }

        @Override
        public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
            return true;
        }
    };

    /** SL-70: a token of a tenant realm (sangam-admin becomes the tenant administrator, no platform group). */
    static RequestContext tenantIdentity(String tenant, String person) {
        return new RequestContext(tenant, person, PlatformIssuers.tenant(ADMIN_GROUPS), null, null, null, null, null, "OIDC");
    }

    /** SL-70: a token of a platform issuer, home tenant {@code home}. */
    static RequestContext platformOperator(String home, String person) {
        Set<String> groups = new java.util.LinkedHashSet<>(ADMIN_GROUPS);
        groups.add(Permissions.GROUP_PLATFORM);
        return new RequestContext(home, person, groups, null, null, null, null, null, "OIDC");
    }

    static OnboardingRequest request(String tenant) {
        return OnboardingJson.read("{\"tenant\":{\"id\":\"" + tenant + "\"}}", OnboardingRequest.class);
    }

    record Fixture(OperatonEngineAdapter engine, OnboardingController controller, OnboardingStore store,
                   RecordingAudit audit, List<String> onboardingRows) {
    }

    private static Fixture fixture() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:onbm" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2");
        OnboardingFlowEngine flow = new OnboardingFlowEngine(a.processEngine());
        flow.deploy();
        OnboardingStore store = new OnboardingStore.InMemory();
        List<String> onboardingRows = new ArrayList<>();
        OnboardingService.Ports ports = new OnboardingService.Ports(null, null, null, null, null, null, null,
                (tenant, runId, action, actor, details) -> onboardingRows.add(tenant + "|" + action + "|" + actor));
        OnboardingService service = new OnboardingService(store, flow, ports, null, null, null, null, null, null);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("onboardingService", service);
        beans.addBean("onboardingPorts", ports);
        RecordingAudit audit = new RecordingAudit();
        OnboardingController c = new OnboardingController(beans.getBeanProvider(OnboardingService.class),
                beans.getBeanProvider(org.sangam.onboarding.bundle.BundleService.class),
                beans.getBeanProvider(org.sangam.onboarding.deps.PackDependencyResolver.class),
                beans.getBeanProvider(OnboardingService.Ports.class), ALL, audit);
        return new Fixture(a, c, store, audit, onboardingRows);
    }

    @Test
    void aTenantMakerStartingOnboardingForAnotherTenantIsRefusedAndAudited() {
        Fixture f = fixture();
        try (OperatonEngineAdapter ignored = f.engine()) {
            RequestContext maker = tenantIdentity("acc", "acc-admin");
            ApiProblem p = assertThrows(ApiProblem.class, () -> f.controller().start(maker, request("ATS-OTHER")));
            assertEquals(403, p.status());
            assertEquals("ONBOARDING_OTHER_TENANT", p.code(), p.getMessage());
            assertTrue(f.store().list(null).isEmpty(), "no run was created");
            assertTrue(f.onboardingRows().isEmpty(), "nothing was started: " + f.onboardingRows());
            assertEquals(1, f.audit().rows.size(), "one audited refusal: " + f.audit().rows);
            Row r = f.audit().rows.get(0);
            assertEquals("ONBOARDING_START_REFUSED", r.type());
            assertEquals("acc", r.tenant(), "recorded in the caller's own tenant");
            assertEquals("onb:acc", r.chain());
            assertEquals("acc-admin", r.payload().get("maker"));
            assertEquals("acc", r.payload().get("makerTenant"));
            assertEquals("ATS-OTHER", r.payload().get("targetTenant"));
            assertEquals("POST /onboarding/runs", r.payload().get("route"));

            // the bundle import starts a run as well: same rule, before the bundle is even read
            ApiProblem q = assertThrows(ApiProblem.class, () -> f.controller().importBundle(maker,
                    new OnboardingController.BundleImportRequest(Map.of(), "ATS-OTHER", null, null, null, null, null, false)));
            assertEquals("ONBOARDING_OTHER_TENANT", q.code(), q.getMessage());
            assertEquals(2, f.audit().rows.size());
            assertEquals("POST /onboarding/bundles/import", f.audit().rows.get(1).payload().get("route"));
        }
    }

    @Test
    void aPlatformOperatorStartsOnboardingForAnotherTenant() {
        Fixture f = fixture();
        try (OperatonEngineAdapter ignored = f.engine()) {
            OnboardingRun run = f.controller().start(platformOperator("acc", "acc-admin"), request("ATS-OTHER"));
            assertEquals("ATS-OTHER", run.tenantId());
            assertEquals("acc-admin", run.maker());
            assertEquals(OnboardingRun.Status.RUNNING, run.status());
            assertEquals(List.of("ATS-OTHER|ONBOARDING_STARTED|acc-admin"), f.onboardingRows());
            assertTrue(f.audit().rows.isEmpty(), "no refusal: " + f.audit().rows);
        }
    }

    /** Nothing is widened: for its own tenant a tenant identity still meets the platform-administrator gate. */
    @Test
    void aTenantMakerForItsOwnTenantStillNeedsThePlatformAdministrator() {
        Fixture f = fixture();
        try (OperatonEngineAdapter ignored = f.engine()) {
            ApiProblem p = assertThrows(ApiProblem.class,
                    () -> f.controller().start(tenantIdentity("acc", "acc-admin"), request("acc")));
            assertEquals(403, p.status());
            assertEquals("ACTION_NOT_PERMITTED", p.code());
            assertTrue(f.store().list(null).isEmpty());
            assertTrue(f.audit().rows.isEmpty(), "the existing gate, not the new rule: " + f.audit().rows);
        }
    }
}
