// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.Capability;

/**
 * The host conformance kit (M-144, design note section 10.2) with the cross-host equivalence scenarios (M-150). A host
 * runs it by extending this class in its own test sources and returning its {@link HostHarness}; the kit starts the
 * host once for the class.
 *
 * <ul>
 *   <li>{@link #sameOperationSameBusinessResult()}: every {@link EquivalenceScenarios scenario} gives exactly its
 *       {@link GoldenResults golden result} on this host, so every host that passes gives the same business result
 *       for the same operation, inputs and rule versions;</li>
 *   <li>{@link #everyRequiredCapabilityIsCovered()}: each required capability of the catalogue is either removable
 *       (and then proven fail-closed by {@link HostFailClosedKit}) or a declared gap of the baseline host; a host other
 *       than the baseline may have no gap;</li>
 *   <li>{@link #theBaselineRunsWithoutOptionalHosts()}: the baseline host started with no optional host framework on
 *       its class path;</li>
 *   <li>{@link #thinnessReport()}: the host module's main-source size, for review (design note "proof of
 *       thinness").</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class HostConformanceKit {

    /** A fresh, not yet started harness of the host under test. */
    protected abstract HostHarness newHarness();

    private HostHarness host;
    private final Map<String, BusinessResult> observed = new TreeMap<>();

    @BeforeAll
    public void startHost() {
        host = newHarness();
        host.start();
    }

    @AfterAll
    public void stopHost() throws IOException {
        try {
            if (host != null) {
                host.stop();
            }
        } finally {
            if (host != null) {
                CrossHostEquivalence.report(host.hostName(), observed);
            }
        }
    }

    @TestFactory
    public Stream<DynamicTest> sameOperationSameBusinessResult() {
        GoldenResults golden = GoldenResults.load();
        assertEquals(golden.scenarios(), new java.util.TreeSet<>(EquivalenceScenarios.all().stream()
                .map(EquivalenceScenario::name).toList()), "every scenario has golden results and nothing else does");
        return EquivalenceScenarios.all().stream().map(s -> dynamicTest(s.toString(), () -> {
            BusinessResult actual = s.run().run(host);
            observed.put(s.name(), actual);
            List<String> diff = CrossHostEquivalence.differences(s.name(), golden.forScenario(s.name()), actual);
            assertTrue(diff.isEmpty(), host.hostName() + " differs from the golden result:\n" + String.join("\n", diff));
        }));
    }

    @Test
    public void everyRequiredCapabilityIsCovered() {
        Set<Capability> removable = host.removableCapabilities();
        Map<Capability, String> gaps = host.capabilityGaps();
        List<String> problems = new ArrayList<>();
        for (Capability c : removable) {
            if (!c.required()) {
                problems.add(c.id() + " is removable but not required (only required capabilities fail closed)");
            }
            if (gaps.containsKey(c)) {
                problems.add(c.id() + " is both removable and a gap");
            }
        }
        Set<Capability> covered = new HashSet<>(removable);
        covered.addAll(gaps.keySet());
        for (Capability c : Capabilities.required()) {
            if (!covered.contains(c)) {
                problems.add(c.id() + " is neither removable nor a declared gap: its fail-closed behaviour is unproven");
            }
        }
        gaps.forEach((c, why) -> {
            if (why == null || why.isBlank()) {
                problems.add(c.id() + " is a gap without a reason");
            }
        });
        if (!host.baseline() && !gaps.isEmpty()) {
            problems.add(host.hostName() + " is not the baseline host, so it may not have gaps: " + gaps.keySet());
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    public void theBaselineRunsWithoutOptionalHosts() {
        if (!host.baseline()) {
            return; // an optional host is itself on the class path of its own run
        }
        assertEquals(List.of(), OptionalHosts.onClassPath(host.getClass().getClassLoader()),
                "the supported baseline build and run need no optional host framework");
    }

    @Test
    public void thinnessReport() throws IOException {
        Path main = host.hostModule().resolve("src/main/java");
        assertTrue(Files.isDirectory(main), "host module sources: " + main);
        long files;
        long lines = 0;
        List<Path> sources;
        try (Stream<Path> s = Files.walk(main)) {
            sources = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        files = sources.size();
        for (Path p : sources) {
            lines += Files.readAllLines(p, StandardCharsets.UTF_8).size();
        }
        assertFalse(sources.isEmpty(), "a host has at least one class");
        System.out.println("[host-conformance] " + host.hostName() + ": " + files + " main classes, " + lines
                + " lines in " + host.hostModule().getFileName() + "; HTTP binding "
                + host.http().map(h -> h.basePath() + " (contract " + h.contractVersion() + ")").orElse("not offered"));
    }

    /** The started harness, for host-specific tests a subclass adds. */
    protected final HostHarness host() {
        return host;
    }
}
