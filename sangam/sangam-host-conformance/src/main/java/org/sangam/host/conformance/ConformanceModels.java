// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import java.nio.charset.StandardCharsets;

/**
 * The fixed models the equivalence scenarios deploy. Their content never changes within a kit version, so the rule
 * version a scenario evaluates is the same on every host; a change here is a new kit version with new golden results.
 *
 * <ul>
 *   <li>{@value #FEE_DECISION}: a DMN 1.3 decision table (hit policy UNIQUE) from an amount in minor units to a
 *       charge in minor units: below 100000 gives 1500, from 100000 below 1000000 gives 2500, from 1000000 on gives
 *       5000;</li>
 *   <li>{@value #FEE_PROCESS}: start, a business-rule task evaluating {@value #FEE_DECISION} from the same deployment
 *       into {@code feeMinor}, then the user task {@value #REVIEW_TASK}.</li>
 * </ul>
 */
public final class ConformanceModels {

    public static final String FEE_DECISION = "conformance_fee";
    public static final String FEE_PROCESS = "conformance_fee_process";
    public static final String REVIEW_TASK = "review";
    public static final String DMN_RESOURCE = "conformance-fee.dmn";
    public static final String BPMN_RESOURCE = "conformance-fee-process.bpmn";

    private ConformanceModels() {
    }

    public static byte[] feeDecision() {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"def_" + FEE_DECISION + "\""
                + " name=\"" + FEE_DECISION + "\" namespace=\"http://sangam.org/host-conformance\">\n"
                + "  <decision id=\"" + FEE_DECISION + "\" name=\"" + FEE_DECISION + "\">\n"
                + "    <decisionTable id=\"dt_" + FEE_DECISION + "\" hitPolicy=\"UNIQUE\">\n"
                + "      <input id=\"in_amount\" label=\"amountMinor\"><inputExpression id=\"ie_amount\" typeRef=\"long\">"
                + "<text>amountMinor</text></inputExpression></input>\n"
                + "      <output id=\"out_fee\" name=\"feeMinor\" typeRef=\"long\"/>\n"
                + rule(1, "&lt; 100000", "1500")
                + rule(2, "[100000..1000000[", "2500")
                + rule(3, "&gt;= 1000000", "5000")
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String rule(int n, String input, String output) {
        return "      <rule id=\"r" + n + "\"><inputEntry id=\"ri" + n + "\"><text>" + input + "</text></inputEntry>"
                + "<outputEntry id=\"ro" + n + "\"><text>" + output + "</text></outputEntry></rule>\n";
    }

    public static byte[] feeProcess() {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n"
                + "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "  xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\"\n"
                + "  id=\"defs_" + FEE_PROCESS + "\" targetNamespace=\"http://sangam.org/host-conformance\">\n"
                + "  <process id=\"" + FEE_PROCESS + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"decide\"/>\n"
                + "    <businessRuleTask id=\"decide\" operaton:decisionRef=\"" + FEE_DECISION + "\""
                + " operaton:decisionRefBinding=\"deployment\" operaton:resultVariable=\"feeMinor\""
                + " operaton:mapDecisionResult=\"singleEntry\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"decide\" targetRef=\"" + REVIEW_TASK + "\"/>\n"
                + "    <userTask id=\"" + REVIEW_TASK + "\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"" + REVIEW_TASK + "\" targetRef=\"e\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n").getBytes(StandardCharsets.UTF_8);
    }
}
