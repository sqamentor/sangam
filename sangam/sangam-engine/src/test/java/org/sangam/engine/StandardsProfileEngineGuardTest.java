// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * R10-bpmn: the BPMN rows of the published standards profile whose rule SangamBpmnParseListener enforces are checked
 * against this engine (the other BPMN rows: sangam-flowable-ports StandardsProfileGuardTest). For each row the probe
 * that follows the rule must deploy when the profile says it deploys, and must be refused when it says Rejected; a
 * "Supported with rules" row also has a probe that breaks the rule, which must be refused.
 */
class StandardsProfileEngineGuardTest {

    private static final String SOURCE = "sangam-engine SangamBpmnParseListener";
    private static final Set<String> DEPLOYS = Set.of("SUPPORTED", "SUPPORTED_WITH_RULES", "PORTED", "NOT_EXECUTABLE");

    private static String flow(String node) {
        return "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" "
                + "targetNamespace=\"t\"><process id=\"p\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                + "<sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"x\"/>" + node
                + "<sequenceFlow id=\"f2\" sourceRef=\"x\" targetRef=\"e\"/><endEvent id=\"e\"/></process></definitions>";
    }

    /** construct -> {probe that follows the rule, probe that breaks it (null when the row has no rule)}. */
    static Map<String, String[]> probes() {
        Map<String, String[]> p = new LinkedHashMap<>();
        p.put("BusinessRuleTask", new String[] {
                flow("<businessRuleTask id=\"x\" operaton:decisionRef=\"d\" operaton:decisionRefBinding=\"deployment\" operaton:mapDecisionResult=\"singleEntry\" operaton:resultVariable=\"r\"/>"),
                flow("<businessRuleTask id=\"x\" operaton:decisionRef=\"d\" operaton:mapDecisionResult=\"singleEntry\" operaton:resultVariable=\"r\"/>")});
        p.put("CallActivity", new String[] {flow("<callActivity id=\"x\" calledElement=\"other\"/>"), null});
        p.put("MultiInstanceLoopCharacteristics", new String[] {
                flow("<userTask id=\"x\"><multiInstanceLoopCharacteristics isSequential=\"true\"><loopCardinality>3</loopCardinality></multiInstanceLoopCharacteristics></userTask>"),
                flow("<userTask id=\"x\"><multiInstanceLoopCharacteristics isSequential=\"true\"><loopCardinality>100000</loopCardinality></multiInstanceLoopCharacteristics></userTask>")});
        p.put("TimerEventDefinition", new String[] {
                flow("<intermediateCatchEvent id=\"x\"><timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition></intermediateCatchEvent>"), null});
        p.put("ScriptTask", new String[] {flow("<scriptTask id=\"x\" scriptFormat=\"javascript\"><script>1</script></scriptTask>"), null});
        return p;
    }

    @Test
    void engineEnforcedBpmnRowsBehaveAsThePublishedProfileSays() throws Exception {
        String json = Files.readString(Path.of("..", "deploy", "evidence", "standards-profile.json"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"notation\": \"BPMN\",\\s*\"construct\": \"([^\"]+)\",\\s*\"status\": \"([A-Z_]+)\""
                + "(?:(?!\"notation\").)*?\"source\": \"([^\"]*)\"", Pattern.DOTALL).matcher(json);
        Map<String, String[]> probes = probes();
        List<String> wrong = new ArrayList<>();
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:profileguard" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setSystemAuditScope("system:platform");
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true");
        int checked = 0;
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, settings, "h2")) {
            int n = 0;
            while (m.find()) {
                String construct = m.group(1);
                String status = m.group(2);
                if (!m.group(3).contains(SOURCE)) {
                    continue;
                }
                String[] pr = probes.get(construct);
                if (pr == null) {
                    wrong.add(construct + ": enforced by sangam-engine but has no probe here");
                    continue;
                }
                checked++;
                boolean deployed = deploys(a, "g" + (n++), pr[0]);
                if (deployed != DEPLOYS.contains(status)) {
                    wrong.add(construct + ": profile says " + status + " but the probe " + (deployed ? "deployed" : "was refused"));
                }
                if ("SUPPORTED_WITH_RULES".equals(status) && pr[1] != null && deploys(a, "b" + (n++), pr[1])) {
                    wrong.add(construct + ": the probe that breaks the rule deployed");
                }
            }
        }
        assertTrue(checked == probes.size(), "expected " + probes.size() + " engine rows in the profile, found " + checked);
        assertTrue(wrong.isEmpty(), "standards-profile.json disagrees with sangam-engine:\n  " + String.join("\n  ", wrong));
    }

    private static boolean deploys(OperatonEngineAdapter a, String name, String xml) {
        try {
            a.deploy("t1", name, Map.of(name + ".bpmn", xml.getBytes(StandardCharsets.UTF_8)));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
