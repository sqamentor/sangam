// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.miwg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

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
import org.sangam.ports.PortsH2Engine;

/**
 * R10-bpmn MIWG harness, ports level (H2): every BPMN MIWG reference model (bpmn-miwg-test-suite, Reference folder,
 * CC BY 3.0, see {@code miwg/reference/ATTRIBUTION.txt}) goes through the Sangam pre-deploy validator, Operaton's BPMN
 * model API and a real deployment with the ports plugin; the models with an executable scenario are run.
 *
 * <p>The outcome of every model is compared with {@code miwg/expected.tsv}; any difference fails (regression gate).
 * The report {@code deploy/evidence/MIWG-REPORT.md} is generated from the same results ({@link MiwgReport}); this test
 * fails when the committed report differs, so it cannot go stale. Rewrite both with {@code -Dmiwg.write=true}.
 * The same models run on PostgreSQL, Oracle, SQL Server and MySQL through the full server in MiwgConformanceIT.
 */
class MiwgConformanceH2Test {

    @Test
    void everyReferenceModelBehavesAsExpected() {
        List<MiwgHarness.Result> results = new ArrayList<>();
        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            MiwgHarness harness = new MiwgHarness(h2.engine, h2::executeDueJobs);
            for (String model : MiwgModels.names()) {
                results.add(harness.check(model));
            }
        }
        Map<String, String> actual = new LinkedHashMap<>();
        results.forEach(r -> actual.put(r.model(), r.row()));
        Path report = MiwgModels.evidence().resolve("MIWG-REPORT.md");
        String md = MiwgReport.markdown(results);
        if (Boolean.getBoolean("miwg.write")) {
            write(MiwgModels.expectedFile(), MiwgReport.expectedTsv(results));
            write(report, md);
        }
        Map<String, String> expected = MiwgModels.expected();
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, String> e : actual.entrySet()) {
            String want = expected.get(e.getKey());
            if (!e.getValue().equals(want)) {
                diffs.add(e.getKey() + "\n    expected: " + want + "\n    actual:   " + e.getValue());
            }
        }
        expected.keySet().stream().filter(k -> !actual.containsKey(k)).forEach(k -> diffs.add(k + ": expected but not run"));
        if (!diffs.isEmpty()) {
            fail("MIWG regression (rewrite with -Dmiwg.write=true only after review):\n" + String.join("\n", diffs));
        }
        assertEquals(read(report), md, "deploy/evidence/MIWG-REPORT.md is stale (run with -Dmiwg.write=true)");
    }

    private static void write(Path p, String text) {
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n") : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
