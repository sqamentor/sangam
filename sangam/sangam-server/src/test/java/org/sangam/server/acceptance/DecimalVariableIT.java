// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.sangam.packs.engine.DecimalText;
import org.sangam.packs.engine.DecimalValueSerializer;
import org.sangam.packs.engine.DecimalValueType;
import org.sangam.server.acceptance.engine.EngineScenarioSupport;

/**
 * Decision 138 on the lane's real database (PostgreSQL, Oracle, SQL Server, MySQL; Oracle and SQL Server lanes are
 * NOT_RUN until their matrix runs): the server's engine stores BigDecimal variables as {@code sangam-decimal-v1} text
 * in {@code TEXT_} (Oracle {@code NVARCHAR2(2000)}, SQL Server {@code nvarchar(4000)}) and reads back the same value
 * and scale at runtime and from history; the widest accepted value fits every dialect's column; equality queries
 * match value and scale.
 */
class DecimalVariableIT extends EngineScenarioSupport {

    private static final String MODEL = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " targetNamespace=\"d138\"><process id=\"d138\" isExecutable=\"true\"><startEvent id=\"s\"/>"
            + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"work\"/><userTask id=\"work\"/>"
            + "<sequenceFlow id=\"f2\" sourceRef=\"work\" targetRef=\"e\"/><endEvent id=\"e\"/></process></definitions>";

    @Test
    void decimalsRoundTripOnThisDatabase() {
        scenario("D138-decimal-" + AcceptanceDatabase.name(), ctx -> {
            adapter.deploy(null, "d138-" + RUN, Map.of("d138.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("rate", new BigDecimal("0.0725"));
            values.put("amount", new BigDecimal("98765432109876543210.12"));
            values.put("adjustment", new BigDecimal("-1250.50"));
            values.put("scaled", new BigDecimal("1E+3"));
            values.put("widest", new BigDecimal("9".repeat(DecimalText.MAX_CHARS)));
            String pid = adapter.startProcess(null, "d138", key("d138"), values);

            String prefix = ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
                    .getDatabaseTablePrefix();
            try (Connection c = DriverManager.getConnection(AcceptanceDatabase.jdbcUrl(), AcceptanceDatabase.username(),
                    AcceptanceDatabase.password())) {
                for (Map.Entry<String, Object> e : values.entrySet()) {
                    BigDecimal want = (BigDecimal) e.getValue();
                    Object back = engine.getRuntimeService().getVariable(pid, e.getKey());
                    ctx.check(e.getKey() + ": runtime value and scale", back instanceof BigDecimal b
                            && b.unscaledValue().equals(want.unscaledValue()) && b.scale() == want.scale());
                    TypedValue typed = engine.getRuntimeService().getVariableTyped(pid, e.getKey());
                    ctx.check(e.getKey() + ": type " + DecimalValueType.NAME,
                            DecimalValueType.NAME.equals(typed.getType().getName()));
                    Object hist = engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(pid)
                            .variableName(e.getKey()).singleResult().getValue();
                    ctx.check(e.getKey() + ": history value and scale", hist instanceof BigDecimal h
                            && h.unscaledValue().equals(want.unscaledValue()) && h.scale() == want.scale());
                    try (PreparedStatement ps = c.prepareStatement("SELECT TYPE_, TEXT_, DOUBLE_ FROM " + prefix
                            + "ACT_RU_VARIABLE WHERE PROC_INST_ID_ = ? AND NAME_ = ?")) {
                        ps.setString(1, pid);
                        ps.setString(2, e.getKey());
                        try (ResultSet rs = ps.executeQuery()) {
                            ctx.require(e.getKey() + ": row present", rs.next());
                            ctx.check(e.getKey() + ": TYPE_ " + DecimalValueSerializer.NAME,
                                    DecimalValueSerializer.NAME.equals(rs.getString(1)));
                            ctx.check(e.getKey() + ": TEXT_ is format v1", want.toString().equals(rs.getString(2)));
                            rs.getDouble(3);
                            ctx.check(e.getKey() + ": DOUBLE_ is NULL", rs.wasNull());
                        }
                    }
                }
            }
            ctx.check("equality matches value and scale", engine.getRuntimeService().createProcessInstanceQuery()
                    .processInstanceId(pid).variableValueEquals("adjustment", new BigDecimal("-1250.50")).count() == 1);
            ctx.check("a different scale does not match", engine.getRuntimeService().createProcessInstanceQuery()
                    .processInstanceId(pid).variableValueEquals("adjustment", new BigDecimal("-1250.5")).count() == 0);
        });
    }
}
