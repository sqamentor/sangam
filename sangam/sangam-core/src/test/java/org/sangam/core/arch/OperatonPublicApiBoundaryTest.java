// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.arch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RM-52 (Operaton exit readiness, X3 R-13): a domain module reaches Operaton only through its public API (delegates,
 * services, the variable, model and DMN APIs), never through an internal {@code impl} package of any Operaton artifact
 * ({@code engine.impl}, {@code engine.variable.impl}, {@code dmn.feel.impl}, {@code impl.juel}, ...). Engine glue has
 * its own modules ({@link #ENGINE_GLUE_MODULES}); its {@code engine.impl} use is recorded per file in
 * {@code sangam-engine/PATCHES.md} (EnginePatchLedgerGuardTest).
 *
 * <p>The per-class split of rule against glue: {@link #GLUE_IN_DOMAIN_MODULES} names, file by file, the classes that
 * are engine glue (engine plugins, parse listeners, value serializers, command-context access) although they live in a
 * domain module. The list only shrinks: a new internal use in a domain module fails until it moves to a glue module or
 * is recorded here, and a listed file that no longer uses an internal package fails until it is removed.
 */
class OperatonPublicApiBoundaryTest {

    /** Any Operaton package with an {@code impl} segment, in code (comment lines are skipped by codeHits). */
    static final Pattern INTERNAL = Pattern.compile("\\borg\\.operaton\\.bpm\\.(?:[a-z_][a-z0-9_]*\\.)*impl\\.");

    /** Reactor modules whose job is to bind Operaton: the engine, the ports and work adapters, and the hosts. */
    static final Set<String> ENGINE_GLUE_MODULES = Set.of(
            "sangam-engine", "sangam-flowable-ports", "sangam-feel", "sangam-integration", "sangam-rest-core",
            "sangam-rest", "sangam-assembly", "sangam-server", "sangam-spring-boot-starter", "sangam-quarkus-extension");

    /** Engine glue that lives in a domain module, measured 2026-11-16 (RM-52). Shrink only. */
    static final Set<String> GLUE_IN_DOMAIN_MODULES = Set.of(
            "sangam-ai/src/main/java/org/sangam/ai/expr/ExpressionPlayground.java",
            "sangam-case/src/main/java/org/sangam/casefile/cmmn/CaseFileCmmnListener.java",
            "sangam-case/src/main/java/org/sangam/casefile/cmmn/CaseFileEnginePlugin.java",
            "sangam-case/src/main/java/org/sangam/casefile/delegate/DelegateSupport.java",
            "sangam-cell/src/main/java/org/sangam/cell/id/CellIdGeneratorPlugin.java",
            "sangam-cell/src/main/java/org/sangam/cell/id/OperatonCellIdGenerator.java",
            "sangam-geo/src/main/java/org/sangam/geo/feel/GeoEnginePlugin.java",
            "sangam-geo/src/main/java/org/sangam/geo/feel/GeoFeelFunctionProvider.java",
            "sangam-geo/src/main/java/org/sangam/geo/feel/GeoTenant.java",
            "sangam-ops/src/main/java/org/sangam/ops/flowable/OperatonEngineStateWriter.java",
            "sangam-ops/src/main/java/org/sangam/ops/listener/GlobalTaskListeners.java",
            "sangam-packs/src/main/java/org/sangam/packs/core/Vars.java",
            "sangam-packs/src/main/java/org/sangam/packs/engine/DecimalValueSerializer.java",
            "sangam-packs/src/main/java/org/sangam/packs/engine/DecimalValueType.java",
            "sangam-packs/src/main/java/org/sangam/packs/engine/LocalDateValueSerializer.java",
            "sangam-packs/src/main/java/org/sangam/packs/engine/LocalDateValueType.java",
            "sangam-registry/src/main/java/org/sangam/registry/bpmn/DelegateSupport.java",
            "sangam-resources/src/main/java/org/sangam/resources/bpmn/ResourcesRuntime.java",
            "sangam-security/src/main/java/org/sangam/security/assignment/EngineConnections.java",
            "sangam-security/src/main/java/org/sangam/security/assignment/SangamSecurityEnginePlugin.java",
            "sangam-security/src/main/java/org/sangam/security/assignment/SeatAssignmentParseListener.java",
            "sangam-security/src/main/java/org/sangam/security/tenant/SangamTenantIdProvider.java");

    static String module(String rel) {
        int slash = rel.indexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    /** Domain-module main sources that use an internal Operaton package, with their hits. */
    static Map<String, List<String>> scan(Path root) throws IOException {
        Map<String, List<String>> out = new TreeMap<>();
        for (Path p : ArchitectureGuardTest.files(root)) {
            String r = ArchitectureGuardTest.rel(root, p);
            if (!r.endsWith(".java") || !ArchitectureGuardTest.isMain(r) || ENGINE_GLUE_MODULES.contains(module(r))) {
                continue;
            }
            String text = ArchitectureGuardTest.read(p);
            if (text == null) {
                continue;
            }
            List<String> hits = ArchitectureGuardTest.codeHits(r, text, INTERNAL);
            if (!hits.isEmpty()) {
                out.put(r, hits);
            }
        }
        return out;
    }

    static List<String> violations(Map<String, List<String>> found, Set<String> allowed) {
        List<String> out = new ArrayList<>();
        found.forEach((file, hits) -> {
            if (!allowed.contains(file)) {
                out.add("internal Operaton package in a domain module (use the public API, or move the class to an "
                        + "engine glue module): " + hits.get(0));
            }
        });
        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(found.keySet());
        stale.forEach(f -> out.add("listed as glue but uses no internal Operaton package any more (remove it): " + f));
        return out;
    }

    @Test
    void domainModulesUseOnlyOperatonsPublicApi() throws IOException {
        Map<String, List<String>> found = scan(ArchitectureGuardTest.root());
        List<String> v = violations(found, GLUE_IN_DOMAIN_MODULES);
        assertTrue(v.isEmpty(), String.join("\n", v));
    }

    @Test
    void theGlueModulesExist() {
        Path root = ArchitectureGuardTest.root();
        for (String m : ENGINE_GLUE_MODULES) {
            assertTrue(root.resolve(m).resolve("pom.xml").toFile().isFile(), "engine glue module " + m + " exists");
        }
        for (String f : GLUE_IN_DOMAIN_MODULES) {
            assertFalse(ENGINE_GLUE_MODULES.contains(module(f)), f + " is in a glue module, so it needs no entry");
        }
    }

    @Test
    void theInternalPatternFindsEveryImplPackageAndNothingElse() {
        for (String internal : List.of("import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;",
                "import org.operaton.bpm.engine.variable.impl.value.UntypedValueImpl;",
                "import org.operaton.bpm.dmn.feel.impl.scala.function.FeelCustomFunctionProvider;",
                "import org.operaton.bpm.impl.juel.ExpressionFactoryImpl;",
                "import static org.operaton.bpm.engine.impl.context.Context.getCommandContext;",
                "x = (org.operaton.bpm.engine.impl.persistence.entity.TaskEntity) task;")) {
            assertTrue(INTERNAL.matcher(internal).find(), internal);
        }
        for (String publicApi : List.of("import org.operaton.bpm.engine.delegate.DelegateExecution;",
                "import org.operaton.bpm.engine.RuntimeService;", "import org.operaton.bpm.engine.variable.Variables;",
                "import org.operaton.bpm.model.bpmn.instance.UserTask;", "import org.operaton.bpm.dmn.engine.DmnEngine;",
                "import org.operaton.bpm.engine.simpl.Other;", "import org.sangam.impl.Thing;")) {
            assertFalse(INTERNAL.matcher(publicApi).find(), publicApi);
        }
    }

    @Test
    void theRatchetReportsANewUseAndAStaleEntry() {
        Map<String, List<String>> found = new TreeMap<>();
        found.put("sangam-x/src/main/java/New.java",
                List.of("sangam-x/src/main/java/New.java:3: import org.operaton.bpm.engine.impl.Foo;"));
        found.put("sangam-x/src/main/java/Kept.java", List.of("sangam-x/src/main/java/Kept.java:4: import x;"));
        List<String> v = violations(found, Set.of("sangam-x/src/main/java/Kept.java", "sangam-x/src/main/java/Gone.java"));
        assertEquals(2, v.size(), v.toString());
        assertTrue(v.get(0).endsWith("New.java:3: import org.operaton.bpm.engine.impl.Foo;"), v.get(0));
        assertTrue(v.get(1).endsWith("Gone.java"), v.get(1));
    }
}
