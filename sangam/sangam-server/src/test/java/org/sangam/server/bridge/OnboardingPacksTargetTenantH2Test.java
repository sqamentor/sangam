// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.identity.Authentication;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.deploy.TenantIsolationException;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * PlugAndPlayIT at1-at5 (CI 44963286110, 44971503167): the onboarding PACKS step is an async job of the run's tenant,
 * the maker's tenant ({@code acc}); Operaton's {@code ExecuteJobsCmd} runs it with the engine authentication
 * {@code [acc]} (javap of operaton-engine 2.2.1: {@code IdentityService.setAuthentication(null, null,
 * singletonList(job.getTenantId()))}), and SL-13 ({@code TenantScopedCreation}) refused the deployment into the new
 * tenant: "deployment 'pack:...' of tenant acc may not be created in tenant ...".
 *
 * <p>Joint decision EV-M-0986, option A with the IFMS conditions: {@link OnboardingBridges.Packs} runs its engine calls
 * for the target tenant as exactly that tenant and restores the previous identity in {@code finally}; SL-13 is
 * unchanged. Before the fix: (i) fails with TENANT_ISOLATION_VIOLATION, (iii) never reaches the second pack.
 */
class OnboardingPacksTargetTenantH2Test {

    static final String MAKER_TENANT = "acc";
    static final String TARGET = "ATS-NEW";

    static final String PACK_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="%s" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    /** The onboarding job's shape: an async service task of a definition of the maker's tenant. */
    static final String JOB_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="onbjob" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="packs"/>
                <serviceTask id="packs" operaton:asyncBefore="true"
                             operaton:class="org.sangam.server.bridge.OnboardingPacksTargetTenantH2Test$PacksStep"/>
                <sequenceFlow id="f2" sourceRef="packs" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                         xmlns:operaton="http://operaton.org/schema/1.0/dmn" id="d" name="d" namespace="t">
              <decision id="onb_override" name="override" operaton:historyTimeToLive="30">
                <decisionTable id="t1"><input id="i1"><inputExpression id="ie1" typeRef="string"><text>x</text></inputExpression></input>
                  <output id="o1" name="y" typeRef="string"/>
                  <rule id="r1"><inputEntry id="e1"><text>-</text></inputEntry><outputEntry id="oe1"><text>"ok"</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    /** Steps the job runs, by the run key in the process variable {@code step}; removed by each test. */
    static final Map<String, Consumer<DelegateExecution>> STEPS = new ConcurrentHashMap<>();

    public static final class PacksStep implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            STEPS.get((String) execution.getVariable("step")).accept(execution);
        }
    }

    /** A code-built pack with one BPMN; {@code open} may be told to fail, which is a throw in the middle of a deploy. */
    static SangamPack pack(String id, List<String> dependsOn, Runnable onOpen) {
        String path = "packs/" + id + "/" + id + ".bpmn";
        PackDescriptor d = new PackDescriptor(id, "1.0.0", Map.of("en", id), "test", "", List.of(),
                List.of(new PackDescriptor.PackResource(PackDescriptor.PackResource.Kind.BPMN, path)), List.of(),
                List.of(), List.of(), List.of(), List.of(), dependsOn, Map.of());
        byte[] bytes = PACK_BPMN.formatted(id.replace('.', '_')).getBytes(StandardCharsets.UTF_8);
        return new SangamPack() {
            @Override
            public PackDescriptor descriptor() {
                return d;
            }

            @Override
            public InputStream open(String p) {
                onOpen.run();
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    record Fixture(OperatonEngineAdapter adapter, ProcessEngine engine, OnboardingBridges.Packs packs) {
        IdentityService ids() {
            return engine.getIdentityService();
        }

        /** The engine's tenant check applies: under another tenant's identity this counts 0, whatever is deployed. */
        long packDeployments(String tenant, String name) {
            return engine.getRepositoryService().createDeploymentQuery().tenantIdIn(tenant).deploymentName(name).count();
        }
    }

    private static Fixture fixture(SangamPack... packs) {
        return fixture(false, packs);
    }

    private static Fixture fixture(boolean jobExecutor, SangamPack... packs) {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setJobExecutorActivate(jobExecutor);
        s.setJobWaitTimeInMillis(50);
        s.setJobMaxWait(250);
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setDeployAllowedClasses(Map.of("*", Set.of(PacksStep.class.getName())));
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:onbt" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2");
        // an empty registry (no index, no service entries on the bootstrap loader), then the test's packs
        PackRegistry registry = PackRegistry.discover(new ClassLoader(null) {
        });
        for (SangamPack p : packs) {
            registry.add(p);
        }
        PackDeployer deployer = new PackDeployer(a.processEngine(), registry);
        return new Fixture(a, a.processEngine(),
                new OnboardingBridges.Packs(a.processEngine(), deployer, new PacksTenantDeployer(deployer, registry, null)));
    }

    /**
     * Runs {@code step} inside a real async job of the maker's tenant, executed by the job executor as the onboarding
     * PACKS step is. Only that path authenticates the job with its tenant: {@code ExecuteJobsCmd} sets
     * {@code [job tenant]} in its job-executor branch (javap 2.2.1, offsets 168-194); {@code ManagementService#executeJob}
     * skips it (offset 165). The step's throwable, if any, is rethrown here.
     */
    private static void inJobOfMakerTenant(Fixture f, Consumer<DelegateExecution> step) {
        String key = UUID.randomUUID().toString();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        STEPS.put(key, ex -> {
            try {
                step.accept(ex);
            } catch (RuntimeException | Error e) {
                failure.set(e);
                throw e;
            } finally {
                ran.set(true);
            }
        });
        try {
            f.adapter().deploy(MAKER_TENANT, "onboarding-like", Map.of("onbjob.bpmn", JOB_BPMN.getBytes(StandardCharsets.UTF_8)));
            String pid = f.adapter().startProcess(MAKER_TENANT, "onbjob", "BK-" + key, Map.of("step", key));
            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end && !ran.get()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            assertTrue(ran.get(), "the job executor ran the step");
            if (failure.get() instanceof RuntimeException r) {
                throw r;
            }
            if (failure.get() instanceof Error e) {
                throw e;
            }
            long until = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < until && f.engine().getRuntimeService().createProcessInstanceQuery()
                    .processInstanceId(pid).count() > 0) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        } finally {
            STEPS.remove(key);
        }
    }

    /** (i) the bridge path deploys into the target tenant, from a job of the maker's tenant. */
    @Test
    void aJobOfTheMakersTenantDeploysThePacksIntoTheTargetTenant() {
        Fixture f = fixture(true, pack("onb.core", List.of(), () -> { }), pack("onb.app", List.of("onb.core"), () -> { }));
        try (OperatonEngineAdapter ignored = f.adapter()) {
            List<Object> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            inJobOfMakerTenant(f, ex -> {
                seen.add(f.ids().getCurrentAuthentication().getTenantIds());
                Map<String, String> out = f.packs().deploy(TARGET, List.of("onb.core", "onb.app"));
                seen.add(out.keySet());
                seen.add(f.packs().deployDecisionOverrides(TARGET, Map.of("onb_override", DMN)));
                // the job continues as the maker's tenant after the bridge call
                seen.add(f.ids().getCurrentAuthentication().getTenantIds());
            });
            // a later step, as in onboarding: a deployment is not visible to a query of the command that creates it
            inJobOfMakerTenant(f, ex -> {
                seen.add(f.packs().installed(TARGET));
                seen.add(f.ids().getCurrentAuthentication().getTenantIds());
            });
            assertEquals(List.of(MAKER_TENANT), seen.get(0), "the job runs as the maker's tenant (Operaton ExecuteJobsCmd)");
            assertEquals(Set.of("onb.core@1.0.0", "onb.app@1.0.0"), seen.get(1));
            assertNotNull(seen.get(2));
            assertEquals(List.of(MAKER_TENANT), seen.get(3), "the maker tenant's identity is back after the bridge call");
            assertEquals(Map.of("onb.core", "1.0.0", "onb.app", "1.0.0"), seen.get(4),
                    "installed() sees the target tenant's packs");
            assertEquals(List.of(MAKER_TENANT), seen.get(5), "the maker tenant's identity is back after installed()");
            assertEquals(1, f.packDeployments(TARGET, "pack:onb.core@1.0.0"));
            assertEquals(1, f.packDeployments(TARGET, "pack:onb.app@1.0.0"));
            assertEquals(1, f.packDeployments(TARGET, "onboarding-overrides:" + TARGET));
            assertEquals(0, f.packDeployments(MAKER_TENANT, "pack:onb.core@1.0.0"), "nothing lands in the maker's tenant");
        }
    }

    /** (ii) SL-13 unchanged: an identity of another tenant is refused outside the bridge, before and after it ran. */
    @Test
    void anIdentityOfAnotherTenantIsStillRefusedOutsideTheBridge() {
        Fixture f = fixture(pack("onb.core", List.of(), () -> { }));
        try (OperatonEngineAdapter ignored = f.adapter()) {
            f.ids().setAuthentication("acc-job", List.of(), List.of(MAKER_TENANT));
            try {
                Runnable direct = () -> f.engine().getRepositoryService().createDeployment().name("direct").tenantId(TARGET)
                        .addString("d.bpmn", PACK_BPMN.formatted("direct")).deploy();
                assertThrows(TenantIsolationException.class, direct::run, "refused before the bridge ran");
                f.packs().deploy(TARGET, List.of("onb.core"));
                TenantIsolationException e = assertThrows(TenantIsolationException.class, direct::run,
                        "refused after the bridge ran: the bridge grants nothing to its caller");
                assertTrue(e.getMessage().contains("of tenant " + MAKER_TENANT + " may not be created in tenant " + TARGET),
                        e.getMessage());
            } finally {
                f.ids().clearAuthentication();
            }
            // counted with no identity: as the maker's tenant both counts would be 0
            assertEquals(0, f.packDeployments(TARGET, "direct"));
            assertEquals(1, f.packDeployments(TARGET, "pack:onb.core@1.0.0"));
        }
    }

    /** (iii) a throw in the middle of the deploy restores the previous identity, a set one and none. */
    @Test
    void aThrowInTheMiddleOfTheDeployRestoresThePreviousIdentity() {
        List<List<String>> duringSecond = new ArrayList<>();
        Fixture[] holder = new Fixture[1];
        Fixture f = fixture(pack("onb.core", List.of(), () -> { }), pack("onb.app", List.of("onb.core"), () -> {
            // PackDeployer's money-path check opens every pack's models while it checks the first one: the resource
            // fails only once the first pack is deployed, so the throw is in the middle of the deploy
            if (holder[0].packDeployments(TARGET, "pack:onb.core@1.0.0") == 0) {
                return;
            }
            Authentication now = holder[0].ids().getCurrentAuthentication();
            duringSecond.add(now == null ? null : now.getTenantIds());
            throw new IllegalStateException("pack resource unreadable mid-deploy");
        }));
        holder[0] = f;
        try (OperatonEngineAdapter ignored = f.adapter()) {
            f.ids().setAuthentication("acc-job", List.of("g1"), List.of(MAKER_TENANT));
            try {
                IllegalStateException e = assertThrows(IllegalStateException.class,
                        () -> f.packs().deploy(TARGET, List.of("onb.core", "onb.app")));
                assertEquals("pack resource unreadable mid-deploy", e.getMessage());
                assertEquals(List.of(List.of(TARGET)), duringSecond, "the second pack ran as exactly the target tenant");
                Authentication after = f.ids().getCurrentAuthentication();
                assertEquals("acc-job", after.getUserId());
                assertEquals(List.of("g1"), after.getGroupIds());
                assertEquals(List.of(MAKER_TENANT), after.getTenantIds());
                // a malformed table is a throw inside the engine's own deploy command
                assertThrows(RuntimeException.class,
                        () -> f.packs().deployDecisionOverrides(TARGET, Map.of("broken", "<definitions")));
                assertEquals(List.of(MAKER_TENANT), f.ids().getCurrentAuthentication().getTenantIds());
            } finally {
                f.ids().clearAuthentication();
            }
            assertEquals(1, f.packDeployments(TARGET, "pack:onb.core@1.0.0"), "the first pack was deployed before the throw");
            // platform code (no engine authentication): none again afterwards
            assertThrows(IllegalStateException.class, () -> f.packs().deploy(TARGET, List.of("onb.app")));
            assertNull(f.ids().getCurrentAuthentication(), "no identity before, none after");
        }
    }
}
