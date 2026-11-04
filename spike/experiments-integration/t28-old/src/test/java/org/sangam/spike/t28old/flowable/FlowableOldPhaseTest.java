package org.sangam.spike.t28old.flowable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flowable.cmmn.engine.configurator.CmmnEngineConfigurator;
import org.flowable.dmn.engine.configurator.DmnEngineConfigurator;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.flowable.engine.repository.DeploymentBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.sangam.spike.corpus.Corpus;
import org.sangam.spike.t28old.OldPhase;

/**
 * T28 old phase on Flowable {@code -Dt28.flowable.version} (7.2.0): the old engine creates its schema (process + DMN +
 * CMMN, like adapter-flowable), deploys WF-1 and leaves three instances in flight. Public engine API only.
 */
@EnabledIfSystemProperty(named = "t28.jdbcUrl", matches = ".+")
class FlowableOldPhaseTest {

    @Test
    void startInstancesOnTheOldVersion() throws Exception {
        OldPhase.canonicaliseDefaultTimeZone();
        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setJdbcUrl(OldPhase.jdbcUrl());
        cfg.setJdbcUsername(OldPhase.user());
        cfg.setJdbcPassword(OldPhase.password());
        cfg.setJdbcDriver("org.postgresql.Driver");
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        cfg.setAsyncExecutorActivate(false);
        cfg.addConfigurator(new DmnEngineConfigurator());
        cfg.addConfigurator(new CmmnEngineConfigurator());
        ProcessEngine pe = cfg.buildProcessEngine();
        try {
            DeploymentBuilder d = pe.getRepositoryService().createDeployment().name("t28-old").tenantId(OldPhase.TENANT);
            for (Map.Entry<String, byte[]> r : Corpus.resources(Corpus.FLOWABLE, OldPhase.WF1_FILES).entrySet()) {
                d.addInputStream(r.getKey(), new ByteArrayInputStream(r.getValue()));
            }
            String deploymentId = d.deploy().getId();

            Map<String, String> instances = new LinkedHashMap<>();
            instances.put("atChecker", start(pe, "t28-checker"));
            String atApprove = start(pe, "t28-approve");
            complete(pe, atApprove, "wf1_checkerReview", Map.of("checkerDecision", "forward"));
            instances.put("atApprove", atApprove);
            String atPayment = start(pe, "t28-payment");
            complete(pe, atPayment, "wf1_checkerReview", Map.of("checkerDecision", "forward"));
            complete(pe, atPayment, "wf1_approve", Map.of("approverDecision", "approve"));
            assertEquals(1, pe.getManagementService().createExternalWorkerJobQuery().processInstanceId(atPayment).count(),
                    "payment external worker job waiting");
            instances.put("atPayment", atPayment);

            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("deploymentId", deploymentId);
            extra.put("engineVersionClassPath", ProcessEngine.VERSION);
            OldPhase.writeState("flowable", System.getProperty("t28.flowable.version", "7.2.0"), instances, extra);
        } finally {
            pe.close();
        }
    }

    private static String start(ProcessEngine pe, String bk) {
        return pe.getRuntimeService().createProcessInstanceBuilder().processDefinitionKey("wf1-bill").tenantId(OldPhase.TENANT)
                .businessKey(bk).variables(OldPhase.wf1Vars(bk)).start().getId();
    }

    private static void complete(ProcessEngine pe, String pid, String key, Map<String, Object> vars) {
        String id = pe.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey(key).singleResult().getId();
        pe.getTaskService().complete(id, vars);
    }
}
