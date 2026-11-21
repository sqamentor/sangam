// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.history.HistoricDecisionInstance;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ADR-DMN-ENGINE D1 through Sangam's process engine (SangamEngineFactory, H2): HIT-8 exact COLLECT SUM and GAP-2
 * default output entry, including Operaton's decision history. Operaton's history keeps an aggregated COLLECT result
 * in a double column ({@code ACT_HI_DECINST.COLLECT_VALUE_}); the exact value stays in the decision result and in each
 * matched rule's output row.
 */
class DecisionTableRulesH2Test {

    private static OperatonEngineAdapter engine;

    private static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="gates" name="gates" namespace="https://sangam.org/gates">
              <decision id="feeSum" name="feeSum">
                <decisionTable id="t1" hitPolicy="COLLECT" aggregation="SUM">
                  <input id="i1"><inputExpression id="ie1" typeRef="number"><text>x</text></inputExpression></input>
                  <output id="o1" name="fee" typeRef="number"/>
                  <rule id="a"><inputEntry id="ai"><text>&gt; 0</text></inputEntry><outputEntry id="ao"><text>0.1</text></outputEntry></rule>
                  <rule id="b"><inputEntry id="bi"><text>&gt; 5</text></inputEntry><outputEntry id="bo"><text>0.2</text></outputEntry></rule>
                </decisionTable>
              </decision>
              <decision id="band" name="band">
                <decisionTable id="t2" hitPolicy="UNIQUE">
                  <input id="i2"><inputExpression id="ie2" typeRef="number"><text>x</text></inputExpression></input>
                  <output id="o2" name="band" typeRef="string"><defaultOutputEntry id="d2"><text>"none"</text></defaultOutputEntry></output>
                  <rule id="c"><inputEntry id="ci"><text>&gt; 0</text></inputEntry><outputEntry id="co"><text>"positive"</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    @BeforeAll
    static void start() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dmngates;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setSystemAuditScope("system:platform");
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true");
        engine = SangamEngineFactory.build(ds, settings, "h2", List.of());
        engine.deploy("t1", "dmn-gates", Map.of("gates.dmn", DMN.getBytes(StandardCharsets.UTF_8)));
    }

    @AfterAll
    static void stop() {
        if (engine != null) {
            engine.close();
        }
        engine = null;
    }

    private static Object evaluate(String key, Object x) {
        var r = engine.processEngine().getDecisionService().evaluateDecisionByKey(key).decisionDefinitionTenantId("t1")
                .variables(Map.of("x", x)).evaluate();
        return r.isEmpty() ? null : r.getSingleResult().getSingleEntry();
    }

    @Test
    void collectSumIsExactAndRecordedInHistory() {
        Object v = evaluate("feeSum", 10);
        assertInstanceOf(BigDecimal.class, v, "HIT-8: never a binary double (SG-33)");
        assertEquals(0, new BigDecimal("0.3").compareTo((BigDecimal) v));
        HistoricDecisionInstance h = engine.processEngine().getHistoryService().createHistoricDecisionInstanceQuery()
                .decisionDefinitionKey("feeSum").includeOutputs().list().get(0);
        assertNotNull(h.getCollectResultValue(), "Operaton history keeps the aggregate (double column)");
        assertEquals(2, h.getOutputs().size(), "each matched rule's exact output is in history");
        BigDecimal fromRows = h.getOutputs().stream().map(o -> new BigDecimal(o.getValue().toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("0.3").compareTo(fromRows), "the exact sum is reproducible from history");
    }

    @Test
    void defaultOutputEntryIsTheResultAndIsInHistory() {
        assertEquals("positive", evaluate("band", 1));
        assertEquals("none", evaluate("band", -1), "GAP-2: no rule matches, the default output entry applies");
        HistoricDecisionInstance h = engine.processEngine().getHistoryService().createHistoricDecisionInstanceQuery()
                .decisionDefinitionKey("band").includeOutputs().list().stream()
                .filter(x -> x.getOutputs().stream().anyMatch(o -> "none".equals(o.getValue()))).findFirst().orElseThrow();
        assertEquals(org.sangam.feel.DecisionTableRules.DEFAULT_RULE_ID, h.getOutputs().get(0).getRuleId());
    }
}
