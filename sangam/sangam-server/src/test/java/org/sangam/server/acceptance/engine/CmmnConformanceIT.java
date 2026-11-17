// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.engine;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.TaskView;

/**
 * CMMN 1.1 behaviour of the sangam-flowable-ports extensions on the real server and its database (R10-cmmn). Runs in
 * the B-engine IT chunk on postgres, oracle, sqlserver and mysql.
 */
class CmmnConformanceIT extends EngineScenarioSupport {

    private static final String MAX_COUNT_CASE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/cmmn"
                         xmlns:sangam="http://sangam.org/schema/1.0/cmmn" targetNamespace="t" id="d">
              <case id="r10-max-count">
                <casePlanModel id="plan" autoComplete="false">
                  <planItem id="PI_review" definitionRef="review">
                    <itemControl id="ic">
                      <repetitionRule id="rr" operaton:repeatOnStandardEvent="complete" sangam:maxInstanceCount="3"/>
                    </itemControl>
                  </planItem>
                  <humanTask id="review" name="Review"/>
                </casePlanModel>
              </case>
            </definitions>
            """;

    private void deployCmmn(String name, String xml) {
        adapter.deploy(SCENARIO_TENANT, name + "-" + RUN, Map.of(name + ".cmmn", xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** P-4 / FC-57: {@code sangam:maxInstanceCount} stops repetition after the third instance. */
    @Test
    void repetitionMaxInstanceCount() {
        scenario("CMMN-P4-MAX-COUNT", ctx -> {
            deployCmmn("r10-max-count", MAX_COUNT_CASE);
            String cid = adapter.startCase(SCENARIO_TENANT, "r10-max-count", key("MAX"), vars());
            for (int i = 1; i <= 3; i++) {
                List<TaskView> open = tasks(cid, null);
                ctx.require("instance " + i + " open (found " + open.size() + ")", open.size() == 1);
                adapter.completeTask(open.get(0).id(), vars());
            }
            ctx.check("no fourth instance after max count 3 (found " + tasks(cid, null).size() + ")",
                    tasks(cid, null).isEmpty());
        });
    }
}
