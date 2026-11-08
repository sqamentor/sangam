// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.MasterStore;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;

/**
 * The generated bill process ({@code sangam-ifms-bill.bpmn}) calls {@code config.documentType} with the connector's
 * contract names: the bill type the DDO enters (a form number such as Rajasthan's {@code FVC}) reaches
 * {@code documentType.get} as {@code formNumber}, and the check runs against the returned catalogue id. Before the fix
 * the process sent {@code billType}, which the connector never reads: the type was never looked up and the bill went
 * on with the default {@code docValid=true}. The same holds for the head of account ({@code code} under the
 * profile's {@code classification.defaultScheme}) and the PAO chain's signature count ({@code chainId} = the type's
 * approval chain); no validation result has a passing default. The payloads are built from the BPMN's own {@code inputs} fields with
 * the {@code variable>parameter} rule of {@code Vars.payloadOf} (sangam-packs).
 */
class BillConnectorContractTest {

    static final Path PACKS = Path.of("..", "sangam-packs", "src", "main", "resources");
    static final Pattern TASK = Pattern.compile("<serviceTask id=\"(bl_type|bl_check|bl_cls|pc_sigs)\".*?</serviceTask>",
            Pattern.DOTALL);
    static final Pattern INPUTS = Pattern.compile(
            "<operaton:field name=\"inputs\"><operaton:string>([^<]*)</operaton:string>");

    @Test
    void billTypeReachesTheCatalogueAndTheCheckRunsAgainstIt() throws Exception {
        Path dir = PACKS.resolve("profiles");
        List<ProfileDocument> docs = ProfileLoader.loadDirectory(dir);
        LayerModel layers = LayerModel.from(ProfileLoader.parseTree(Files.readString(dir.resolve("layers.yaml"))).get("layers"));
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(docs), layers);
        ConfigConnector c = new ConfigConnector(r, new ClassificationRegistry(new MasterStore.InMemory()), new NumberSeries.InMemory());

        String bpmn = Files.readString(PACKS.resolve("packs/ifms-bill/sangam-ifms-bill.bpmn"))
                + Files.readString(PACKS.resolve("packs/ifms-pao-chain/sangam-ifms-pao-chain.bpmn"));
        Map<String, String> inputs = new LinkedHashMap<>();
        Matcher m = TASK.matcher(bpmn);
        while (m.find()) {
            Matcher in = INPUTS.matcher(m.group());
            assertTrue(in.find(), m.group(1) + " has inputs");
            inputs.put(m.group(1), in.group(1).replace("&gt;", ">")); // XML text, as the engine reads the field
        }
        assertEquals(4, inputs.size(), "bl_type, bl_check, bl_cls (bill) and pc_sigs (PAO chain): " + inputs.keySet());

        Map<String, Object> vars = new LinkedHashMap<>(Map.of("billType", "FVC", "headOfAccount", "05-2054-00-001-01-00-13",
                "amount", "1000"));
        ConnectorResult.Success get = assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(request("documentType.get", payload(inputs.get("bl_type"), vars))),
                "the bill type must reach the catalogue lookup");
        assertEquals("RJ_FVC", get.output().get("id"));
        assertEquals("CONTINGENT", get.output().get("family"));
        // ConnectorCallDelegate writes the result as <prefix><Key>: prefix doc
        get.output().forEach((k, v) -> vars.put("doc" + Character.toUpperCase(k.charAt(0)) + k.substring(1), v));

        ConnectorResult.Success chk = assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(request("documentType.check", payload(inputs.get("bl_check"), vars))));
        assertEquals(Boolean.TRUE, chk.output().get("valid"), "findings: " + chk.output().get("findings"));

        // the head of account reaches the classification check as its code, under the profile's default scheme
        ConnectorResult clsResult = c.invoke(request("classification.validate", payload(inputs.get("bl_cls"), vars)));
        ConnectorResult.Success cls = assertInstanceOf(ConnectorResult.Success.class, clsResult,
                "the head of account must reach the classification check: " + clsResult);
        assertTrue(cls.output().containsKey("valid"), "classification result: " + cls.output());
        // the signature count uses the bill type's approval chain (called process receives all bill variables)
        ConnectorResult.Success sig = assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(request("chain.signatures", payload(inputs.get("pc_sigs"), vars))),
                "the bill type's approval chain must reach the signature lookup");
        assertTrue(sig.output().get("signatures") instanceof Integer n && n >= 1, "signatures: " + sig.output());
        Map<String, Object> noChain = new LinkedHashMap<>(vars);
        noChain.remove("docApprovalChain");
        assertFalse(c.invoke(request("chain.signatures", payload(inputs.get("pc_sigs"), noChain)))
                instanceof ConnectorResult.Success, "no chain: a failure, never a default of one signature");

        // honest defaults (ADR-D-26): a validation that did not run never counts as passed
        for (String dishonest : List.of("docValid=true", "clsValid=true", "chainId=PAO_3", "chainSignatures=1",
                "docApprovalChain=PAO_3")) {
            assertFalse(bpmn.contains(dishonest), "default " + dishonest + " in the generated processes");
        }

        // an unknown bill type is not waved through: the check reports TYPE_UNKNOWN
        vars.put("billType", "NO_SUCH_FORM");
        vars.remove("docId");
        assertFalse(c.invoke(request("documentType.get", payload(inputs.get("bl_type"), vars)))
                instanceof ConnectorResult.Success);
        ConnectorResult.Success bad = assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(request("documentType.check", payload(inputs.get("bl_check"), vars))));
        assertEquals(Boolean.FALSE, bad.output().get("valid"));
        assertTrue(String.valueOf(bad.output().get("findings")).contains("TYPE_UNKNOWN"));
    }

    /** {@code Vars.payloadOf}: comma list, {@code variable>parameter} sends the variable under the contract name. */
    static Map<String, Object> payload(String csv, Map<String, Object> vars) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (String f : csv.split(",")) {
            String[] vp = f.trim().split(">", 2);
            p.put(vp.length == 2 ? vp[1].trim() : vp[0], vars.get(vp[0].trim()));
        }
        return p;
    }

    static ConnectorRequest request(String op, Map<String, Object> input) {
        Map<String, Object> in = new LinkedHashMap<>(input);
        in.put("asOf", LocalDate.of(2026, 11, 5).toString());
        in.values().removeIf(java.util.Objects::isNull);
        return new ConnectorRequest("RJ", UUID.randomUUID(), "a", 1, null, null, op, in, null);
    }
}
