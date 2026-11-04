package org.sangam.spike.t28old.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.operaton.bpm.engine.repository.DeploymentBuilder;
import org.sangam.spike.corpus.Corpus;
import org.sangam.spike.t28old.OldPhase;

/**
 * T28 old phase on Operaton {@code -Dt28.operaton.version} (2.1.6): the old engine creates its schema, deploys WF-1 and
 * leaves three instances in flight. Public engine API only, configured like adapter-operaton (history full, DMN + CMMN,
 * job executor off, no history TTL enforcement).
 */
@EnabledIfSystemProperty(named = "t28.jdbcUrl", matches = ".+")
class OperatonOldPhaseTest {

    @Test
    void startInstancesOnTheOldVersion() throws Exception {
        OldPhase.canonicaliseDefaultTimeZone();
        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setJdbcUrl(OldPhase.jdbcUrl());
        cfg.setJdbcUsername(OldPhase.user());
        cfg.setJdbcPassword(OldPhase.password());
        cfg.setJdbcDriver("org.postgresql.Driver");
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        cfg.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
        cfg.setJobExecutorActivate(false);
        cfg.setDmnEnabled(true);
        cfg.setCmmnEnabled(true);
        cfg.setEnforceHistoryTimeToLive(false);
        ProcessEngine pe = cfg.buildProcessEngine();
        try {
            DeploymentBuilder d = pe.getRepositoryService().createDeployment().name("t28-old").tenantId(OldPhase.TENANT);
            for (Map.Entry<String, byte[]> r : Corpus.resources(Corpus.OPERATON, OldPhase.WF1_FILES).entrySet()) {
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
            assertEquals(1, pe.getExternalTaskService().createExternalTaskQuery().processInstanceId(atPayment).count(),
                    "payment external task waiting");
            instances.put("atPayment", atPayment);

            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("deploymentId", deploymentId);
            extra.put("engineVersionClassPath", String.valueOf(ProcessEngine.class.getPackage().getImplementationVersion()));
            OldPhase.writeState("operaton", System.getProperty("t28.operaton.version", "2.1.6"), instances, extra);
        } finally {
            pe.close();
        }
    }

    private static String start(ProcessEngine pe, String bk) {
        return pe.getRuntimeService().createProcessInstanceByKey("wf1-bill").processDefinitionTenantId(OldPhase.TENANT)
                .businessKey(bk).setVariables(OldPhase.wf1Vars(bk)).execute().getId();
    }

    private static void complete(ProcessEngine pe, String pid, String key, Map<String, Object> vars) {
        String id = pe.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey(key).singleResult().getId();
        pe.getTaskService().complete(id, vars);
    }
}
