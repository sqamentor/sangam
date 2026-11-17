// SPDX-License-Identifier: Apache-2.0
package org.sangam.spike.dmn;

import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.compiler.RuntimeTypeCheckOption;
import org.kie.dmn.core.impl.DMNRuntimeImpl;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.dmn.feel.lang.types.impl.ComparablePeriod;
import org.kie.internal.io.ResourceFactory;
import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnDecisionRequirementsGraph;
import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.dmn.engine.DmnDecisionResultEntries;
import org.operaton.bpm.dmn.engine.DmnEngine;
import org.operaton.bpm.dmn.engine.DmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionTableImpl;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.model.dmn.HitPolicy;
import org.sangam.feel.SangamFeel;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The two candidate engines, each configured as Sangam would run it, evaluated on the same DMN XML.
 * OPERATON: Operaton 2.2.0 standalone DMN engine + SangamFeel.install (SG-33/34/35, DMN 1.6 reader) - the production
 * configuration at 24c10a65. DROOLS: Drools 10.1.0 DMNRuntime, runtime type check on, any ERROR message = error.
 */
final class Engines {

    /** Outcome of one evaluation: a value, or an error (deploy or evaluation). */
    record Outcome(boolean error, Object value, String message) {
        static Outcome ok(Object v) {
            return new Outcome(false, v, null);
        }

        static Outcome err(String m) {
            return new Outcome(true, null, m == null ? "" : m.replace('\n', ' '));
        }

        @Override
        public String toString() {
            return error ? "ERROR(" + (message.length() > 140 ? message.substring(0, 140) + "..." : message) + ")" : String.valueOf(value)
                    + (value == null ? "" : " (" + value.getClass().getSimpleName() + ")");
        }
    }

    enum Kind { OPERATON, DROOLS }

    private Engines() {
    }

    static DmnEngine operatonEngine() {
        DefaultDmnEngineConfiguration c = (DefaultDmnEngineConfiguration) DmnEngineConfiguration.createDefaultDmnEngineConfiguration();
        SangamFeel.install(c);
        // SangamPortsPlugin installs this when PortsSettings.dmnPriorityHitPolicies is on (FC-54)
        org.sangam.ports.dmn.PriorityHitPolicies.install(c);
        return c.buildEngine();
    }

    private static final DmnEngine OPERATON = operatonEngine();

    static Outcome evaluate(Kind kind, byte[] xml, String decisionName, Map<String, Object> inputs) {
        return kind == Kind.OPERATON ? operaton(xml, decisionName, inputs) : drools(xml, decisionName, inputs);
    }

    // ------------------------------------------------------------------ Operaton

    static DmnDecisionRequirementsGraph operatonParse(byte[] xml) {
        return OPERATON.parseDecisionRequirementsGraph(new ByteArrayInputStream(xml));
    }

    static Outcome operaton(byte[] xml, String decisionName, Map<String, Object> inputs) {
        DmnDecisionRequirementsGraph drg;
        try {
            drg = operatonParse(xml);
        } catch (Throwable e) {
            return Outcome.err("deploy: " + root(e));
        }
        DmnDecision d = drg.getDecisions().stream().filter(x -> decisionName.equals(x.getName())).findFirst()
                .orElse(drg.getDecision(decisionName));
        if (d == null) {
            return Outcome.err("deploy: decision " + decisionName + " not executable");
        }
        return operatonEval(d, inputs);
    }

    static Outcome operatonEval(DmnDecision d, Map<String, Object> inputs) {
        try {
            DmnDecisionResult r = OPERATON.evaluateDecision(d, Variables.fromMap(new LinkedHashMap<>(inputs)));
            return Outcome.ok(operatonValue(d, r));
        } catch (Throwable e) {
            return Outcome.err(root(e));
        }
    }

    static Object operatonValue(DmnDecision d, DmnDecisionResult r) {
        if (d.getDecisionLogic() instanceof DmnDecisionTableImpl t) {
            HitPolicy hp = t.getHitPolicyHandler().getHitPolicyEntry().getHitPolicy();
            boolean aggregated = t.getHitPolicyHandler().getHitPolicyEntry().getAggregator() != null;
            boolean multi = !aggregated && (hp == HitPolicy.COLLECT || hp == HitPolicy.RULE_ORDER || hp == HitPolicy.OUTPUT_ORDER);
            List<Object> values = new ArrayList<>();
            for (DmnDecisionResultEntries e : r) {
                values.add(e.size() == 1 && t.getOutputs().size() == 1 ? e.getSingleEntry() : new LinkedHashMap<>(e.getEntryMap()));
            }
            return multi ? values : values.isEmpty() ? null : values.get(0);
        }
        if (r.isEmpty()) {
            return null;
        }
        DmnDecisionResultEntries e = r.getFirstResult();
        return e.size() == 1 ? e.getSingleEntry() : new LinkedHashMap<>(e.getEntryMap());
    }

    // ------------------------------------------------------------------ Drools

    record DroolsModel(DMNRuntime runtime, DMNModel model) {
    }

    static DroolsModel droolsCompile(byte[] xml) {
        Resource r = ResourceFactory.newByteArrayResource(xml);
        r.setSourcePath("model.dmn");
        r.setResourceType(ResourceType.DMN);
        DMNRuntime rt = DMNRuntimeBuilder.fromDefaults().buildConfiguration().fromResources(List.of(r))
                .getOrElseThrow(e -> new IllegalStateException("compile: " + e, e));
        ((DMNRuntimeImpl) rt).setOption(new RuntimeTypeCheckOption(true));
        DMNModel m = rt.getModels().get(0);
        if (m.hasErrors()) {
            throw new IllegalStateException("model errors: " + m.getMessages(DMNMessage.Severity.ERROR).stream()
                    .map(DMNMessage::getText).collect(Collectors.joining("; ")));
        }
        return new DroolsModel(rt, m);
    }

    static Outcome drools(byte[] xml, String decisionName, Map<String, Object> inputs) {
        DroolsModel dm;
        try {
            dm = droolsCompile(xml);
        } catch (Throwable e) {
            return Outcome.err("deploy: " + root(e));
        }
        return droolsEval(dm, decisionName, inputs);
    }

    static Outcome droolsEval(DroolsModel dm, String decisionName, Map<String, Object> inputs) {
        try {
            DMNContext ctx = dm.runtime().newContext();
            inputs.forEach(ctx::set);
            DMNResult res = dm.runtime().evaluateByName(dm.model(), ctx, decisionName);
            if (res.hasErrors()) {
                return Outcome.err(res.getMessages(DMNMessage.Severity.ERROR).stream().map(DMNMessage::getText)
                        .collect(Collectors.joining("; ")));
            }
            DMNDecisionResult dr = res.getDecisionResultByName(decisionName);
            if (dr == null || dr.getEvaluationStatus() != DMNDecisionResult.DecisionEvaluationStatus.SUCCEEDED) {
                return Outcome.err("status " + (dr == null ? "missing" : dr.getEvaluationStatus()));
            }
            return Outcome.ok(plain(dr.getResult()));
        } catch (Throwable e) {
            return Outcome.err(root(e));
        }
    }

    static Object plain(Object v) {
        if (v instanceof ComparablePeriod p) {
            return p.asPeriod();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> o = new LinkedHashMap<>();
            m.forEach((k, x) -> o.put(String.valueOf(k), plain(x)));
            return o;
        }
        if (v instanceof List<?> l) {
            List<Object> o = new ArrayList<>();
            l.forEach(x -> o.add(plain(x)));
            return o;
        }
        return v;
    }

    // ------------------------------------------------------------------ comparison (no engine involved)

    /** null when equal. Numbers: exact compareTo; a binary Double/Float never equals a decimal (SG-33). */
    static String same(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual ? null : "expected " + expected + ", got " + actual;
        }
        if (actual instanceof Double || actual instanceof Float) {
            return "binary floating point result " + actual + " (SG-33 forbids Double)";
        }
        if (expected instanceof Number en) {
            if (!(actual instanceof Number an)) {
                return "expected number " + expected + ", got " + actual.getClass().getSimpleName() + " " + actual;
            }
            return new BigDecimal(en.toString()).compareTo(new BigDecimal(an.toString())) == 0 ? null
                    : "expected " + expected + ", got " + actual;
        }
        if (expected instanceof List<?> el) {
            if (!(actual instanceof List<?> al) || al.size() != el.size()) {
                return "expected list " + expected + ", got " + actual;
            }
            for (int i = 0; i < el.size(); i++) {
                String w = same(el.get(i), al.get(i));
                if (w != null) {
                    return "[" + i + "] " + w;
                }
            }
            return null;
        }
        if (expected instanceof Map<?, ?> em) {
            if (!(actual instanceof Map<?, ?> am) || !am.keySet().equals(em.keySet())) {
                return "expected context " + expected + ", got " + actual;
            }
            for (var e : em.entrySet()) {
                String w = same(e.getValue(), am.get(e.getKey()));
                if (w != null) {
                    return e.getKey() + ": " + w;
                }
            }
            return null;
        }
        if (expected instanceof Period p && actual instanceof java.time.chrono.ChronoPeriod cp) {
            return p.toTotalMonths() == Period.from(cp).toTotalMonths() ? null : "expected " + p + ", got " + cp;
        }
        return expected.equals(actual) ? null : "expected " + expected + ", got " + actual + " (" + actual.getClass().getSimpleName() + ")";
    }

    static String root(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
