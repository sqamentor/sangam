// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.ConditionEvaluationBuilderImpl;
import org.operaton.bpm.engine.impl.ExternalTaskServiceImpl;
import org.operaton.bpm.engine.impl.FormServiceImpl;
import org.operaton.bpm.engine.impl.MessageCorrelationAsyncBuilderImpl;
import org.operaton.bpm.engine.impl.MessageCorrelationBuilderImpl;
import org.operaton.bpm.engine.impl.ProcessInstanceModificationBuilderImpl;
import org.operaton.bpm.engine.impl.ProcessInstantiationBuilderImpl;
import org.operaton.bpm.engine.impl.RuntimeServiceImpl;
import org.operaton.bpm.engine.impl.SignalEventReceivedBuilderImpl;
import org.operaton.bpm.engine.impl.TaskServiceImpl;
import org.operaton.bpm.engine.impl.migration.MigrationPlanBuilderImpl;

/**
 * Upgrade guard for PATCHES I-30 / B-49 (SEQ-PARALLEL I-1). Every public method of the replaced Operaton services and
 * builders that writes or removes variables (name {@code set|remove|updateVariable...}, or any {@code Map} parameter
 * on a non-read method) must be overridden in {@link EngineOwnedServices}; an Operaton version that adds such a method
 * fails here until the override exists. Checked by hand against operaton-engine 2.2.0 and 2.2.1 (identical write
 * surface, javap 2026-11-18); the pom pin must be one of those until this test is re-run on a newer version.
 */
class EngineOwnedServicesPinTest {

    private static final Set<String> SUPPORTED = Set.of("2.2.0", "2.2.1");

    private static final Pattern WRITE_NAME = Pattern.compile("(set|remove|update)Variables?\\w*");

    /** Read-only methods that take a map: correlation filters and the key-only correlateMessage. */
    private static boolean isRead(Method m) {
        String n = m.getName();
        return n.startsWith("get") || n.endsWith("Equal") || n.endsWith("Equals") || n.startsWith("create")
                && n.endsWith("Query");
    }

    private static boolean writes(Method m) {
        if (isRead(m)) {
            return false;
        }
        return WRITE_NAME.matcher(m.getName()).matches()
                || Arrays.stream(m.getParameterTypes()).anyMatch(Map.class::isAssignableFrom);
    }

    private static List<String> missing(Class<?> operaton, Class<?> sangam) {
        List<String> gaps = new ArrayList<>();
        for (Method m : operaton.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.isBridge() || m.isSynthetic()
                    || Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class || !writes(m)) {
                continue;
            }
            try {
                Method own = sangam.getDeclaredMethod(m.getName(), m.getParameterTypes());
                if (own.isBridge()) {
                    gaps.add(m.toGenericString());
                }
            } catch (NoSuchMethodException e) {
                gaps.add(m.toGenericString());
            }
        }
        return gaps;
    }

    @Test
    void i30_theEnginePinIsAVersionWhoseWriteSurfaceWasChecked() throws Exception {
        try (InputStream in = RuntimeServiceImpl.class.getResourceAsStream(
                "/META-INF/maven/org.operaton.bpm/operaton-engine/pom.properties")) {
            assertNotNull(in, "operaton-engine pom.properties on the classpath");
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            assertTrue(SUPPORTED.contains(v), "I-30: re-check EngineOwnedServices against operaton-engine " + v
                    + " (javap of the services and builders), then add it to SUPPORTED");
        }
    }

    @Test
    void i30_everyVariableWriteOfTheReplacedServicesIsOverridden() {
        Map<Class<?>, Class<?>> pairs = Map.ofEntries(
                Map.entry(RuntimeServiceImpl.class, EngineOwnedServices.Runtime.class),
                Map.entry(TaskServiceImpl.class, EngineOwnedServices.Tasks.class),
                Map.entry(FormServiceImpl.class, EngineOwnedServices.Forms.class),
                Map.entry(ExternalTaskServiceImpl.class, EngineOwnedServices.ExternalTasks.class),
                Map.entry(MessageCorrelationBuilderImpl.class, EngineOwnedServices.Correlations.class),
                Map.entry(MessageCorrelationAsyncBuilderImpl.class, EngineOwnedServices.AsyncCorrelations.class),
                Map.entry(ProcessInstantiationBuilderImpl.class, EngineOwnedServices.Instantiations.class),
                Map.entry(ProcessInstanceModificationBuilderImpl.class, EngineOwnedServices.Modifications.class),
                Map.entry(SignalEventReceivedBuilderImpl.class, EngineOwnedServices.Signals.class),
                Map.entry(ConditionEvaluationBuilderImpl.class, EngineOwnedServices.Conditions.class),
                Map.entry(MigrationPlanBuilderImpl.class, EngineOwnedServices.MigrationPlans.class));
        List<String> gaps = new ArrayList<>();
        pairs.forEach((op, own) -> {
            assertTrue(op.isAssignableFrom(own), own + " extends " + op);
            missing(op, own).forEach(g -> gaps.add(own.getSimpleName() + ": " + g));
        });
        assertEquals(List.of(), gaps, "I-30: variable writes not guarded by EngineOwnedServices");
    }

    @Test
    void i30_theBuilderFactoriesReturnTheGuardedBuilders() throws Exception {
        for (String factory : List.of("createMessageCorrelation", "createMessageCorrelationAsync", "createSignalEvent",
                "createProcessInstanceModification", "createProcessInstanceById", "createProcessInstanceByKey")) {
            EngineOwnedServices.Runtime.class.getDeclaredMethod(factory, String.class);
        }
        EngineOwnedServices.Runtime.class.getDeclaredMethod("createMigrationPlan", String.class, String.class);
        EngineOwnedServices.Runtime.class.getDeclaredMethod("createConditionEvaluation");
    }
}
