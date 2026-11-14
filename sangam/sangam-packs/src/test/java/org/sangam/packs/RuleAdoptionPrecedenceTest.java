// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.repository.DeploymentBuilder;

import org.sangam.packs.core.Decisions;
import org.sangam.packs.spi.PackDeployer;

/**
 * Owner decision 202 (GP-19 with GP-37): a running case keeps the rule version it started with; a tenant's own table
 * overrides it only when the tenant deployed that table separately from the pack. A newer pack version, or another
 * tenant's table, never reaches the case.
 */
class RuleAdoptionPrecedenceTest {

    static final String KEY = "rule-adoption-limit";
    static final String TENANT = PackTestTenant.ID;
    static ProcessEngine engine;
    static String packV1;
    static String packV2;

    @BeforeAll
    static void setUp() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-rule-adoption;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        packV1 = deploy(TENANT, "pack:test.rules@1.0.0", PackDeployer.SOURCE, "v1");
        packV2 = deploy(TENANT, "pack:test.rules@1.1.0", PackDeployer.SOURCE, "v2");
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    static String deploy(String tenant, String name, String source, String value) {
        DeploymentBuilder b = engine.getRepositoryService().createDeployment().name(name).source(source)
                .addString(KEY + ".dmn", dmn(value));
        if (tenant != null) {
            b = b.tenantId(tenant);
        }
        return b.deploy().getId();
    }

    static String dmn(String value) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d_%1$s" name="Rule adoption"
                             namespace="http://sangam.org/test">
                  <decision id="%1$s" name="Rule adoption limit">
                    <decisionTable id="t_%1$s" hitPolicy="UNIQUE">
                      <input id="i1" label="Kind"><inputExpression id="ie1" typeRef="string"><text>kind</text></inputExpression></input>
                      <output id="o1" name="rule" typeRef="string"/>
                      <rule id="r1"><inputEntry id="e1"><text>-</text></inputEntry><outputEntry id="x1"><text>"%2$s"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """.formatted(KEY, value);
    }

    static String rule(String tenant, String deploymentId) {
        return (String) Decisions.evaluate(engine, tenant, KEY, Map.of("kind", "any"), deploymentId).single().get("rule");
    }

    @Test
    void aRunningCaseKeepsTheRuleItStartedWithAndANewCaseTakesTheNewPackVersion() {
        assertEquals("v1", rule(TENANT, packV1), "the case started under pack 1.0.0 keeps its rule");
        assertEquals("v2", rule(TENANT, packV2), "a case started under pack 1.1.0 has the new rule");
        assertEquals("v2", rule(TENANT, null), "without a binding: the tenant's latest pack table");
    }

    @Test
    void onlyATableTheTenantDeployedSeparatelyOverridesTheStartRule() {
        String other = deploy("t-other", "rules:t-other", "tenant-rules", "other-tenant");
        String own = deploy(TENANT, "rules:" + TENANT, "tenant-rules", "tenant-own");
        try {
            assertEquals("tenant-own", rule(TENANT, packV1), "the tenant's own table wins over the start rule");
            assertEquals("tenant-own", rule(TENANT, packV2));
            assertEquals("tenant-own", rule(TENANT, null));
            assertEquals("other-tenant", rule("t-other", null), "each tenant sees only its own table");
        } finally {
            engine.getRepositoryService().deleteDeployment(own, true);
            engine.getRepositoryService().deleteDeployment(other, true);
        }
        assertEquals("v1", rule(TENANT, packV1), "after the tenant table is removed the start rule applies again");
    }

    @Test
    void aTenantTableWithoutASourceIsTheTenantsOwn() {
        String own = deploy(TENANT, "rules:no-source", null, "no-source");
        try {
            assertEquals("no-source", rule(TENANT, packV1));
        } finally {
            engine.getRepositoryService().deleteDeployment(own, true);
        }
    }

    @Test
    void aTenantWithNoTableAndNoSharedTableFailsClosed() {
        assertThrows(ProcessEngineException.class, () -> rule("t-without-tables", null));
    }
}
