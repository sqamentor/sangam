// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeParseException;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.dmn.Dmn;
import org.operaton.bpm.model.dmn.DmnModelInstance;
import org.operaton.bpm.model.xml.ModelException;
import org.operaton.bpm.model.xml.impl.util.ModelIoException;
import org.sangam.feel.Dmn16Support;
import org.sangam.packs.spi.ManifestPack;
import org.sangam.packs.spi.ModelGuard;

/**
 * M-095 (Jazzer) parser targets of the pack layer. Each target states the only refusals its parser may give for a
 * malformed input; any other throwable (a NullPointerException, ClassCastException, StackOverflowError, an endless
 * loop) is a finding.
 * <ul>
 *   <li>{@link #packManifest}: {@link ManifestPack#load} on any bytes as {@code pack.json} refuses with
 *       {@link UncheckedIOException} (unreadable JSON) or {@link IllegalArgumentException} (a bad field, e.g. an
 *       unknown resource kind) only.</li>
 *   <li>{@link #bpmnModel} and {@link #dmnModel}: the Operaton model readers refuse with a {@link ModelException}
 *       (or {@link ModelIoException}) only, and a model they accept writes back out; DMN is read as production reads
 *       it, through {@code Dmn16Support.readModel}, which may also refuse with its {@code Dmn16Exception} (an
 *       unreadable document, ONNX functions or B-FEEL); a BPMN model they accept also
 *       goes through the deploy-time {@link ModelGuard#check}, which never throws: a missing, blank, non-numeric or
 *       out-of-range {@code sangam.maxCardinality} is a reported violation (SL-148). The seeds
 *       {@code maxcardinality-without-value.bpmn} (the first finding of this target, a NullPointerException at deploy
 *       before SL-148) and {@code maxcardinality-not-a-number.bpmn} keep that fixed.</li>
 *   <li>{@link #acceptanceSteps}: the SL-32 {@code run.steps} parser ({@link AcceptanceSteps}) refuses with
 *       {@link IllegalArgumentException} or {@link DateTimeParseException} (a bad ISO-8601 {@code advance}) only.</li>
 * </ul>
 * Without {@code JAZZER_FUZZ} each target runs once per seed in {@code PackParserFuzzTestInputs/<target>/} (plus the
 * empty input), as a regression test; with it set, Jazzer fuzzes the target. Runs in the test phase only.
 */
class PackParserFuzzTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANIFEST = "fuzz/pack.json";

    /** A class loader holding exactly one resource: the fuzzed manifest (a seed path names nothing else). */
    private static ClassLoader manifestLoader(byte[] manifest) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return MANIFEST.equals(name) ? new ByteArrayInputStream(manifest) : null;
            }
        };
    }

    @FuzzTest(maxDuration = "2m")
    void packManifest(byte[] manifest) {
        try {
            ManifestPack.load(manifestLoader(manifest), MANIFEST);
        } catch (UncheckedIOException | IllegalArgumentException refused) {
            // malformed manifest: refused as documented
        }
    }

    @FuzzTest(maxDuration = "2m")
    void bpmnModel(byte[] xml) {
        BpmnModelInstance m;
        try {
            m = Bpmn.readModelFromStream(new ByteArrayInputStream(xml));
        } catch (ModelException | ModelIoException refused) {
            return;
        }
        Bpmn.convertToString(m);
        ModelGuard.check("fuzz.bpmn", xml); // SL-148: violations are returned, never thrown
    }

    @FuzzTest(maxDuration = "2m")
    void dmnModel(byte[] xml) {
        DmnModelInstance m;
        try {
            m = Dmn16Support.readModel(xml); // as production reads DMN (sangam-feel, DMN 1.1 to 1.6)
        } catch (ModelException | ModelIoException | Dmn16Support.Dmn16Exception refused) {
            return;
        }
        Dmn.convertToString(m);
    }

    @FuzzTest(maxDuration = "2m")
    void acceptanceSteps(byte[] json) {
        JsonNode steps;
        try {
            steps = JSON.readTree(new String(json, StandardCharsets.UTF_8));
        } catch (JacksonException notJson) {
            return; // not under test: Jackson's own parser
        }
        if (steps == null) {
            return;
        }
        try {
            new AcceptanceSteps(null, null, "FUZZ", steps);
        } catch (IllegalArgumentException | DateTimeParseException refused) {
            // malformed step: refused as documented
        }
    }
}
