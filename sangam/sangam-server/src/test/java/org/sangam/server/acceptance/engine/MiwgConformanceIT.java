// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * R10-bpmn MIWG harness on the real server and database (chunk B-engine: PostgreSQL, Oracle, SQL Server, MySQL). Every
 * BPMN MIWG reference model is deployed unchanged through the server's deploy path (all engine plugins, the Sangam parse
 * listener and the ports validator); the deploy outcome (PASS or FAIL) must equal the DEPLOY column of
 * {@code sangam-flowable-ports/src/test/resources/miwg/expected.tsv}, which MiwgConformanceH2Test keeps in step with
 * deploy/evidence/MIWG-REPORT.md. C.9.1 is also run to its end on the database.
 */
class MiwgConformanceIT extends EngineScenarioSupport {

    private static final Path MIWG = Path.of("..", "sangam-flowable-ports", "src", "test", "resources", "miwg");

    private static Map<String, String> expectedDeploy() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(MIWG.resolve("expected.tsv"), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] c = line.split("\t");
                out.put(c[0], c[3].startsWith("PASS") ? "PASS" : "FAIL");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static byte[] model(String name) {
        try {
            return Files.readAllBytes(MIWG.resolve("reference").resolve(name + ".bpmn"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void everyReferenceModelDeploysOrIsRefusedAsOnH2() {
        scenario("MIWG-deploy", ctx -> {
            Map<String, String> expected = expectedDeploy();
            ctx.require("22 reference models expected", expected.size() == 22);
            List<String> results = new ArrayList<>();
            for (Map.Entry<String, String> e : expected.entrySet()) {
                String outcome;
                try {
                    adapter.deploy(SCENARIO_TENANT, "miwg-" + e.getKey() + "-" + RUN, Map.of(e.getKey() + ".bpmn", model(e.getKey())));
                    outcome = "PASS";
                } catch (RuntimeException ex) {
                    outcome = "FAIL";
                    ctx.detail(e.getKey(), String.valueOf(ex.getMessage()).replaceAll("\\s+", " "));
                }
                results.add(e.getKey() + "=" + outcome);
                ctx.check(e.getKey() + " deploy " + outcome + " (expected " + e.getValue() + ")", outcome.equals(e.getValue()));
            }
            ctx.detail("results", results);
        });
    }

    @Test
    void c91DocumentRequestRunsToItsEnd() {
        scenario("MIWG-C.9.1-run", ctx -> {
            adapter.deploy(SCENARIO_TENANT, "miwg-run-C.9.1-" + RUN, Map.of("C.9.1.bpmn", model("C.9.1")));
            String pid = adapter.startProcess(SCENARIO_TENANT, "requestDocument_en", key("miwg-c91"), Map.of());
            adapter.executeDueJobs();   // camunda:async="true" on the e-mail send task
            completeExternal(ctx, pid, "emailService", Map.of(), false);
            ctx.require("waits for the document", active(pid).contains("ReceiveTask_WaitForDocument"));
            engine.getRuntimeService().createMessageCorrelation("MESSAGE_documentReceived").tenantId(SCENARIO_TENANT)
                    .processInstanceId(pid).correlate();
            adapter.executeDueJobs();   // asyncAfter on the end event
            ctx.check("ended at 'Document received'; history " + historicActivityIds(pid),
                    ended(pid) && historicActivityIds(pid).contains("EndEvent_GotDocument"));
        });
    }
}
