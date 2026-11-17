// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.spi.PackRegistry;

/** TEMPORARY probe (not committed). */
class IfmsProbeTmpTest {
    static ProcessEngine engine;
    static PackDriver driver;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-ifms-probe;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine).withWorkers();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @Test
    void probe() {
        List<String> keys = List.of("sangam-ifms-audit-para", "sangam-ifms-gia-release", "sangam-ifms-uc",
                "sangam-ifms-advance", "sangam-ifms-monthly-accounts", "sangam-ifms-receipt", "sangam-ifms-scheme-appraisal",
                "sangam-ifms-sna-sparsh-release", "sangam-ifms-supplementary", "sangam-ifms-surrender",
                "sangam-ifms-year-end-lapse", "sangam-ifms-year-end-closing", "sangam-ifms-bill", "sangam-ifms-gpf",
                "sangam-ifms-budget-allocation", "sangam-leave-credit", "sangam-leave-yearend", "sangam-pension-monthly",
                "sangam-pension-payment", "sangam-ifms-bill-establishment-pay", "sangam-ifms-bill-vendor");
        StringBuilder out = new StringBuilder();
        for (String k : keys) {
            PackRuntime.install(PackRuntime.ports().withGateway(new org.sangam.packs.sim.SimulatorConnectorGateway())
                    .withAudit(new LoggingAuditSink()).withLedgers(new org.sangam.packs.sim.InMemoryLedgerStore())
                    .withBatches(new org.sangam.packs.sim.InMemoryBatchLedger()).withClock(PackDriver.clock()));
            String bk = "PROBE/" + k;
            String line;
            try {
                Map<String, Object> v = AllPacksSmokeTest.bag();
                engine.getRuntimeService().createProcessInstanceByKey(k).businessKey(bk).setVariables(v)
                        .processDefinitionTenantId(PackTestTenant.ID).execute();
                FormAnswers fa = new FormAnswers(engine);
                List<String> done = driver.run(bk, fa::answer, 80);
                Set<String> types = new LinkedHashSet<>();
                ((LoggingAuditSink) PackRuntime.audit()).timeline(bk).forEach(e -> types.add(e.type()));
                List<String> open = new ArrayList<>();
                engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%").list()
                        .forEach(t -> open.add(t.getTaskDefinitionKey()));
                List<String> subs = new ArrayList<>();
                engine.getRuntimeService().createEventSubscriptionQuery().list().stream()
                        .filter(s -> engine.getRuntimeService().createProcessInstanceQuery()
                                .processInstanceId(s.getProcessInstanceId()).processInstanceBusinessKey(bk).count() > 0)
                        .forEach(s -> subs.add(s.getEventName() + "@" + s.getActivityId()));
                List<String> conns = new ArrayList<>();
                ((org.sangam.packs.sim.SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                        .filter(r -> bk.equals(r.businessKey())).forEach(r -> conns.add(r.connector() + "." + r.operation()));
                List<String> inc = new ArrayList<>();
                engine.getRuntimeService().createIncidentQuery().list()
                        .forEach(i -> inc.add(i.getActivityId() + ":" + i.getIncidentMessage()));
                line = k + " | ended=" + driver.ended(bk) + " state=" + driver.var(bk, "caseState") + " done=" + done
                        + " open=" + open + " subs=" + subs + " audit=" + types + " conns=" + conns + " inc=" + inc;
            } catch (RuntimeException e) {
                line = k + " | EXC " + e;
            }
            out.append("[PROBE] ").append(line).append('\n');
        }
        System.out.println(out);
    }
}
