// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** FWT-057 deploy part (FW-44, D-46): a script task deployed directly, without the importer, is rejected. */
class FwtScriptTaskDeployH2Test {

    private static final String SCRIPT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="scripted" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="sc"/>
                <scriptTask id="sc" scriptFormat="javascript"><script>java.lang.System.exit(1)</script></scriptTask>
                <sequenceFlow id="f2" sourceRef="sc" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    // FWT-057
    @Test
    void fwt057_scriptTaskDeployedDirectlyIsRejected() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:fwt057;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true");
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, settings, "h2")) {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> a.deploy("t1", "scripted", Map.of("scripted.bpmn", SCRIPT.getBytes(StandardCharsets.UTF_8))));
            String all = String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : e.getCause().getMessage());
            assertTrue(all.contains("D-46") || all.toLowerCase().contains("script"), all);
            assertEquals(0, a.processEngine().getRepositoryService().createProcessDefinitionQuery()
                    .processDefinitionKey("scripted").count(), "nothing deployed");
        }
    }
}
