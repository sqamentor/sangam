// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.sangam.packs.spi.PackVersionRewriter;

/** SL-54 roll-forward: only the version-bearing parts of a pack's content are restated under the new version. */
class PackVersionRewriterTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn" id="d" targetNamespace="t" exporter="sangam-packs" exporterVersion="1.0.0">
              <process id="own" name="Own 1.0.0 label" isExecutable="true" operaton:historyTimeToLive="3650" operaton:versionTag="1.0.0">
                <extensionElements><operaton:executionListener event="start" class="org.sangam.packs.core.PackStartListener"><operaton:field name="packId"><operaton:string>p</operaton:string></operaton:field><operaton:field name="packVersion"><operaton:string>1.0.0</operaton:string></operaton:field></operaton:executionListener></extensionElements>
                <callActivity id="c1" calledElement="ownSub" operaton:calledElementBinding="versionTag" operaton:calledElementVersionTag="1.0.0"/>
                <callActivity id="c2" calledElement="sangam-core-sanction" operaton:calledElementBinding="versionTag" operaton:calledElementVersionTag="1.0.0"/>
                <callActivity id="c3" calledElement="ownSub"/>
                <businessRuleTask id="r1" operaton:decisionRef="own-rule" operaton:decisionRefBinding="versionTag" operaton:decisionRefVersionTag="1.0.0"/>
                <businessRuleTask id="r2" operaton:decisionRef="core-rule" operaton:decisionRefBinding="versionTag" operaton:decisionRefVersionTag="1.0.0"/>
              </process>
              <process id="ownSub" isExecutable="true" operaton:historyTimeToLive="3650" operaton:versionTag="0.9.0"/>
            </definitions>
            """;

    private static final String DMN = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:operaton="http://operaton.org/schema/1.0/dmn" id="d" name="d" namespace="n">
              <decision id="own-rule" name="Rule 1.0.0" operaton:historyTimeToLive="3650" operaton:versionTag="1.0.0">
                <decisionTable id="t"/>
              </decision>
            </definitions>
            """;

    private static final String FORM = """
            {
              "key": "p/form",
              "version": "1.0.0",
              "pack": "p",
              "fields": [{"id": "x", "version": "1.0.0", "label": {"en": "1.0.0"}}]
            }
            """;

    private static String rewritten(String name, String content) {
        Map<String, byte[]> in = new LinkedHashMap<>();
        in.put("packs/p/m.bpmn", BPMN.getBytes(StandardCharsets.UTF_8));
        in.put("packs/p/r.dmn", DMN.getBytes(StandardCharsets.UTF_8));
        in.put(name, content.getBytes(StandardCharsets.UTF_8));
        return new String(PackVersionRewriter.rewrite(in, "1.0.0", "1.0.2").get(name), StandardCharsets.UTF_8);
    }

    @Test
    void bpmnRestatesItsOwnVersionAndLeavesPinsToOtherPacksAlone() {
        String x = rewritten("packs/p/m.bpmn", BPMN);
        String want = BPMN
                .replace("exporterVersion=\"1.0.0\"", "exporterVersion=\"1.0.2\"")
                .replace("operaton:historyTimeToLive=\"3650\" operaton:versionTag=\"1.0.0\"",
                        "operaton:historyTimeToLive=\"3650\" operaton:versionTag=\"1.0.2\"")
                .replace("operaton:versionTag=\"0.9.0\"", "operaton:versionTag=\"1.0.2\"")
                .replace("<operaton:string>1.0.0</operaton:string>", "<operaton:string>1.0.2</operaton:string>")
                .replace("calledElement=\"ownSub\" operaton:calledElementBinding=\"versionTag\" operaton:calledElementVersionTag=\"1.0.0\"",
                        "calledElement=\"ownSub\" operaton:calledElementBinding=\"versionTag\" operaton:calledElementVersionTag=\"1.0.2\"")
                .replace("operaton:decisionRef=\"own-rule\" operaton:decisionRefBinding=\"versionTag\" operaton:decisionRefVersionTag=\"1.0.0\"",
                        "operaton:decisionRef=\"own-rule\" operaton:decisionRefBinding=\"versionTag\" operaton:decisionRefVersionTag=\"1.0.2\"");
        assertEquals(want, x);
        // untouched: the label, the pin into another pack (c2), the decision pin into another pack (r2)
        for (String kept : List.of("name=\"Own 1.0.0 label\"",
                "calledElement=\"sangam-core-sanction\" operaton:calledElementBinding=\"versionTag\" operaton:calledElementVersionTag=\"1.0.0\"",
                "operaton:decisionRef=\"core-rule\" operaton:decisionRefBinding=\"versionTag\" operaton:decisionRefVersionTag=\"1.0.0\"")) {
            assertEquals(true, x.contains(kept), kept);
        }
    }

    @Test
    void dmnRestatesEveryDecisionVersionTagOnly() {
        assertEquals(DMN.replace("operaton:versionTag=\"1.0.0\"", "operaton:versionTag=\"1.0.2\""),
                rewritten("packs/p/r.dmn", DMN));
    }

    @Test
    void formRestatesOnlyItsTopLevelVersion() {
        assertEquals(FORM.replace("\"version\": \"1.0.0\",\n  \"pack\"", "\"version\": \"1.0.2\",\n  \"pack\""),
                rewritten("packs/p/forms/f.form.json", FORM));
        String other = FORM.replace("\"version\": \"1.0.0\",\n  \"pack\"", "\"version\": \"3\",\n  \"pack\"");
        assertEquals(other, rewritten("packs/p/forms/f.form.json", other), "a version that is not the pack's is kept");
    }

    @Test
    void otherResourcesAreByteExact() {
        byte[] seed = "{\"version\": \"1.0.0\", \"x\": \"\r\n\"}".getBytes(StandardCharsets.UTF_8);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '1', '.', '0', '.', '0'};
        Map<String, byte[]> in = new LinkedHashMap<>();
        in.put("packs/p/seed.json", seed);
        in.put("packs/p/logo.png", png);
        Map<String, byte[]> out = PackVersionRewriter.rewrite(in, "1.0.0", "1.0.2");
        assertArrayEquals(seed, out.get("packs/p/seed.json"));
        assertArrayEquals(png, out.get("packs/p/logo.png"));
        assertEquals(List.of("packs/p/seed.json", "packs/p/logo.png"), List.copyOf(out.keySet()), "order kept");
    }
}
