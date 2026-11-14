// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.model.bpmn.Bpmn;

/**
 * Coordinator order D-5 (3): the Quarkus module's class path has no Spring (spring-beans and spring-core are excluded
 * from operaton-bpm-quarkus-engine and from sangam-engine, D-38), and the Operaton engine still builds, deploys and runs
 * a process. Spring is only reached through the engine's XML (beans) configuration ({@code BeansConfigurationHelper},
 * {@code SpringBeanFactoryProxyMap}, used by {@code ProcessEngineConfiguration.createProcessEngineConfigurationFrom*}),
 * which neither this host nor the Operaton Quarkus extension calls. Written for the test phase (not run).
 */
class EngineWithoutSpringTest {

    @Test
    void springIsNotOnTheClassPath() {
        for (String c : new String[] {"org.springframework.beans.factory.BeanFactory",
                "org.springframework.core.io.Resource"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(c, false, getClass().getClassLoader()), c);
        }
    }

    @Test
    void theEngineBuildsDeploysAndRunsWithoutSpring() {
        StandaloneInMemProcessEngineConfiguration config = new StandaloneInMemProcessEngineConfiguration();
        config.setProcessEngineName("no-spring-" + UUID.randomUUID());
        config.setJdbcUrl("jdbc:h2:mem:no-spring-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP);
        config.setJobExecutorActivate(false);
        ProcessEngine engine = config.buildProcessEngine();
        try {
            engine.getRepositoryService().createDeployment()
                    .addModelInstance("p.bpmn", Bpmn.createExecutableProcess("p").startEvent().userTask("t")
                            .endEvent().done())
                    .deploy();
            engine.getRuntimeService().startProcessInstanceByKey("p");
            assertEquals(1, engine.getTaskService().createTaskQuery().taskDefinitionKey("t").count());
        } finally {
            engine.close();
        }
    }
}
