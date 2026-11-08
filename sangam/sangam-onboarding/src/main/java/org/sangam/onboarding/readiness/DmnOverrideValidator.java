// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.readiness;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.dmn.engine.DmnEngine;
import org.operaton.bpm.model.dmn.Dmn;
import org.operaton.bpm.model.dmn.DmnModelInstance;
import org.operaton.bpm.model.dmn.instance.Decision;
import org.operaton.bpm.model.dmn.instance.DecisionTable;
import org.operaton.bpm.model.dmn.instance.Output;
import org.sangam.onboarding.api.Problem;
import org.sangam.onboarding.api.StepId;
import org.sangam.packs.spi.PackDescriptor.PackResource;
import org.sangam.packs.spi.SangamPack;

/**
 * Checks tenant DMN overrides: the table parses and compiles on the engine's DMN implementation, it replaces a
 * decision that an enabled pack really has, and it still produces every output the pack's table produces (a process
 * reads those variables).
 */
public final class DmnOverrideValidator {

    private final DmnEngine engine = org.sangam.engine.feel.SangamFeel.dmnEngineConfiguration().buildEngine();

    public List<Problem> validate(Map<String, String> overrides, List<SangamPack> enabled) {
        List<Problem> problems = new ArrayList<>();
        if (overrides.isEmpty()) {
            return problems;
        }
        Map<String, Set<String>> base = new HashMap<>();
        for (SangamPack p : enabled) {
            for (PackResource r : p.descriptor().resources(PackResource.Kind.DMN)) {
                try (InputStream in = p.open(r.path())) {
                    base.putAll(outputs(Dmn.readModelFromStream(in)));
                } catch (IOException | RuntimeException e) {
                    problems.add(Problem.warn(StepId.READINESS, "DMN_BASE_UNREADABLE", r.path(), String.valueOf(e.getMessage())));
                }
            }
        }
        overrides.forEach((key, xml) -> {
            byte[] bytes = xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8);
            try {
                engine.parseDecisions(new ByteArrayInputStream(bytes));
                Map<String, Set<String>> mine = outputs(Dmn.readModelFromStream(new ByteArrayInputStream(bytes)));
                if (!mine.containsKey(key)) {
                    problems.add(Problem.error(StepId.READINESS, "DMN_OVERRIDE_INVALID", key,
                            "the file does not define decision '" + key + "'; it defines " + mine.keySet()));
                } else if (!base.containsKey(key)) {
                    problems.add(Problem.error(StepId.READINESS, "DMN_OVERRIDE_UNKNOWN", key,
                            "no enabled pack has a decision with this key"));
                } else {
                    Set<String> lost = new LinkedHashSet<>(base.get(key));
                    lost.removeAll(mine.get(key));
                    if (!lost.isEmpty()) {
                        problems.add(Problem.error(StepId.READINESS, "DMN_OVERRIDE_INCOMPATIBLE", key,
                                "the override drops outputs the pack reads: " + lost));
                    }
                }
            } catch (RuntimeException e) {
                problems.add(Problem.error(StepId.READINESS, "DMN_OVERRIDE_INVALID", key, String.valueOf(e.getMessage())));
            }
        });
        return problems;
    }

    private static Map<String, Set<String>> outputs(DmnModelInstance model) {
        Map<String, Set<String>> m = new HashMap<>();
        for (Decision d : model.getModelElementsByType(Decision.class)) {
            Set<String> names = new LinkedHashSet<>();
            if (d.getExpression() instanceof DecisionTable t) {
                for (Output o : t.getOutputs()) {
                    if (o.getName() != null) {
                        names.add(o.getName());
                    }
                }
            }
            m.put(d.getId(), names);
        }
        return m;
    }
}
