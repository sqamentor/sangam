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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sangam.config.catalogue.DocumentTypeCatalogue;
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
 * The generated bill process ({@code sangam-ifms-bill.bpmn}) calls {@code config.documentType} and
 * {@code config.classification} with the connector's contract names, built from the BPMN's own {@code inputs} fields
 * with the {@code variable>parameter} rule of {@code Vars.payloadOf} (sangam-packs). Before the fix the process sent
 * {@code billType} and {@code headOfAccount}, which the connector never reads: the type was never looked up and the
 * bill went on with the default {@code docValid=true}.
 *
 * <p>The bill type is a reference into the catalogue and the catalogue data resolves it: a Rajasthan catalogue id
 * ({@code RJ_FVC} in RJ.yaml) and a Union pack's catalogue id ({@code GPF_WITHDRAWAL} in GOI.yaml, the GPF pack's bill)
 * both reach their type; the FVC form {@code RJ:GA 84} carries the FVC bill and its twelve sub-types, so as a reference
 * it is ambiguous (APPROVE-401 step 2b: resolution by catalogue id). The head of account is validated under the
 * scheme the type declares (GOI.yaml {@code classification.scheme: HOA}), else under the profile's
 * {@code classification.defaultScheme} (RJ.yaml {@code HOA_RJ}). The PAO chain's signature count uses the type's approval chain; no validation result has a passing
 * default.
 */
class BillConnectorContractTest {

    static final Path PACKS = Path.of("..", "sangam-packs", "src", "main", "resources");
    static final Pattern TASK = Pattern.compile("<serviceTask id=\"(bl_type|bl_check|bl_cls|pc_sigs)\".*?</serviceTask>",
            Pattern.DOTALL);
    static final Pattern INPUTS = Pattern.compile(
            "<operaton:field name=\"inputs\"><operaton:string>([^<]*)</operaton:string>");

    static ConfigConnector connector;
    static String bpmn;
    static final Map<String, String> INPUT_FIELDS = new LinkedHashMap<>();

    @BeforeAll
    static void load() throws Exception {
        Path dir = PACKS.resolve("profiles");
        List<ProfileDocument> docs = ProfileLoader.loadDirectory(dir);
        LayerModel layers = LayerModel.from(ProfileLoader.parseTree(Files.readString(dir.resolve("layers.yaml"))).get("layers"));
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(docs), layers);
        connector = new ConfigConnector(r, new ClassificationRegistry(new MasterStore.InMemory()), new NumberSeries.InMemory());
        bpmn = Files.readString(PACKS.resolve("packs/ifms-bill/sangam-ifms-bill.bpmn"))
                + Files.readString(PACKS.resolve("packs/ifms-pao-chain/sangam-ifms-pao-chain.bpmn"));
        Matcher m = TASK.matcher(bpmn);
        while (m.find()) {
            Matcher in = INPUTS.matcher(m.group());
            assertTrue(in.find(), m.group(1) + " has inputs");
            INPUT_FIELDS.put(m.group(1), in.group(1).replace("&gt;", ">")); // XML text, as the engine reads the field
        }
        assertEquals(4, INPUT_FIELDS.size(), "bl_type, bl_check, bl_cls (bill) and pc_sigs (PAO chain): "
                + INPUT_FIELDS.keySet());
    }

    /** The bill's own value of {@code billFormKind}, read from the generated {@code bl_defaults} task. */
    static String billFormKind() {
        Matcher m = Pattern.compile("<serviceTask id=\"bl_defaults\".*?<operaton:field name=\"values\"><operaton:string>"
                + "([^<]*)</operaton:string>", Pattern.DOTALL).matcher(bpmn);
        assertTrue(m.find(), "bl_defaults sets values");
        Matcher k = Pattern.compile("(?:^|;)billFormKind=([^;]*)").matcher(m.group(1));
        assertTrue(k.find(), "the bill sets billFormKind as a value (never a default): " + m.group(1));
        return k.group(1);
    }

    /** Runs the bill's catalogue steps as the process does and returns the variables they leave. */
    static Map<String, Object> catalogueSteps(String tenant, Map<String, Object> start) {
        Map<String, Object> vars = new LinkedHashMap<>(start);
        vars.put("billFormKind", billFormKind()); // set by bl_defaults before bl_type, as in the process
        ConnectorResult.Success get = assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(request(tenant, "documentType.get", payload(INPUT_FIELDS.get("bl_type"), vars))),
                "the bill type must reach the catalogue lookup");
        // ConnectorCallDelegate writes the result as <prefix><Key>: prefix doc
        get.output().forEach((k, v) -> vars.put("doc" + Character.toUpperCase(k.charAt(0)) + k.substring(1), v));
        return vars;
    }

    @Test
    void aStateCatalogueIdReachesItsTypeAndTheChecksRunAgainstIt() {
        Map<String, Object> vars = catalogueSteps("RJ", Map.of("billType", "RJ_FVC",
                "headOfAccount", "05-2054-00-001-01-00-13", "amount", "1000"));
        assertEquals("RJ_FVC", vars.get("docId"));
        assertEquals("CONTINGENT", vars.get("docFamily"));
        assertEquals("RJ:GA 84", vars.get("docFormNumber"));
        // the shared FVC form is not a bill-type reference: it names the FVC bill and every FVC sub-type
        Map<String, Object> byForm = new LinkedHashMap<>(Map.of("billType", "RJ:GA 84"));
        ConnectorResult.BusinessFailure amb = assertInstanceOf(ConnectorResult.BusinessFailure.class,
                connector.invoke(request("RJ", "documentType.get", payload(INPUT_FIELDS.get("bl_type"), byForm))));
        assertEquals("TYPE_AMBIGUOUS", amb.code());

        ConnectorResult.Success chk = assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(request("RJ", "documentType.check", payload(INPUT_FIELDS.get("bl_check"), vars))));
        assertEquals(Boolean.TRUE, chk.output().get("valid"), "findings: " + chk.output().get("findings"));

        // RJ_FVC declares no scheme: the head of account is checked under the State's defaultScheme HOA_RJ
        ConnectorResult clsResult = connector.invoke(request("RJ", "classification.validate",
                payload(INPUT_FIELDS.get("bl_cls"), vars)));
        ConnectorResult.Success cls = assertInstanceOf(ConnectorResult.Success.class, clsResult,
                "the head of account must reach the classification check: " + clsResult);
        assertTrue(cls.output().containsKey("valid"), "classification result: " + cls.output());
        assertEquals("05", cls.output().get("demand"), "parsed under HOA_RJ (demand segment): " + cls.output());

        // the signature count uses the bill type's approval chain (the called process receives all bill variables)
        ConnectorResult.Success sig = assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(request("RJ", "chain.signatures", payload(INPUT_FIELDS.get("pc_sigs"), vars))),
                "the bill type's approval chain must reach the signature lookup");
        assertTrue(sig.output().get("signatures") instanceof Integer n && n >= 1, "signatures: " + sig.output());
        Map<String, Object> noChain = new LinkedHashMap<>(vars);
        noChain.remove("docApprovalChain");
        assertFalse(connector.invoke(request("RJ", "chain.signatures", payload(INPUT_FIELDS.get("pc_sigs"), noChain)))
                instanceof ConnectorResult.Success, "no chain: a failure, never a default of one signature");
    }

    @Test
    void aUnionCatalogueIdReachesItsTypeAndItsOwnScheme() {
        // the GPF pack starts its bill with billType=GPF_WITHDRAWAL (gov_ifms.py), a GOI.yaml catalogue id
        Map<String, Object> vars = catalogueSteps("GOI", Map.of("billType", "GPF_WITHDRAWAL",
                "headOfAccount", "2054-00-001-01-00-13", "amount", "50000"));
        assertEquals("GPF_WITHDRAWAL", vars.get("docId"));
        assertEquals("PAO_3", vars.get("docApprovalChain"));
        // GOI.yaml has no classification.defaultScheme: the scheme is the one the type declares (HOA)
        ConnectorResult clsResult = connector.invoke(request("GOI", "classification.validate",
                payload(INPUT_FIELDS.get("bl_cls"), vars)));
        ConnectorResult.Success cls = assertInstanceOf(ConnectorResult.Success.class, clsResult,
                "the head of account must be checked under the type's scheme: " + clsResult);
        assertEquals("2054", cls.output().get("major"), "parsed under HOA: " + cls.output());
        ConnectorResult.Success sig = assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(request("GOI", "chain.signatures", payload(INPUT_FIELDS.get("pc_sigs"), vars))));
        assertTrue(sig.output().get("signatures") instanceof Integer n && n >= 1, "signatures: " + sig.output());
    }

    @Test
    void anUnknownBillTypeIsNotWavedThrough() {
        // honest defaults: a validation that did not run never counts as passed
        for (String dishonest : List.of("docValid=true", "clsValid=true", "chainId=PAO_3", "chainSignatures=1",
                "docApprovalChain=PAO_3")) {
            assertFalse(bpmn.contains(dishonest), "default " + dishonest + " in the generated processes");
        }
        Map<String, Object> vars = new LinkedHashMap<>(Map.of("billType", "NO_SUCH_FORM",
                "headOfAccount", "05-2054-00-001-01-00-13", "amount", "1000"));
        ConnectorResult get = connector.invoke(request("RJ", "documentType.get", payload(INPUT_FIELDS.get("bl_type"), vars)));
        assertInstanceOf(ConnectorResult.BusinessFailure.class, get);
        ConnectorResult.Success bad = assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(request("RJ", "documentType.check", payload(INPUT_FIELDS.get("bl_check"), vars))));
        assertEquals(Boolean.FALSE, bad.output().get("valid"));
        assertTrue(String.valueOf(bad.output().get("findings")).contains("TYPE_UNKNOWN"));
    }

    /**
     * Defence in depth (coordinator Q2 on APPROVE-401 step 2b): the bill asks the catalogue for a BILL row
     * ({@code billFormKind>formKind}), so a row of another kind that a tenant leaves in {@code documentTypes} is never
     * returned as a bill type, by its id or by its form number; the same lookup without the kind would return it.
     */
    @Test
    void theBillLookupNeverReturnsARowThatIsNotABill() {
        assertEquals("BILL", billFormKind());
        assertTrue(INPUT_FIELDS.get("bl_type").contains("billFormKind>formKind"), INPUT_FIELDS.get("bl_type"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentTypes", List.of(
                Map.of("id", "ZZ_BILL", "formKind", "BILL", "formNumber", "ZZ:1", "approvalChain", "STD"),
                Map.of("id", "ZZ_REPORT", "formKind", "REPORT", "formNumber", "ZZ:2"),
                Map.of("id", "ZZ_SHARED_REPORT", "formKind", "REPORT", "formNumber", "ZZ:1")));
        data.put("approvalChains", List.of(Map.of("id", "STD", "steps", List.of(Map.of("role", "DDO")))));
        ConfigConnector zz = new ConfigConnector(new ProfileResolver(InMemoryProfileRepository.active(List.of(
                new ProfileDocument("ZZ", "government", "1", LocalDate.of(2026, 1, 1), null, List.of(), List.of(),
                        Map.of("en", "Test"), "test", Map.of(), List.of(), data))), LayerModel.open()),
                new ClassificationRegistry(new MasterStore.InMemory()), new NumberSeries.InMemory());
        for (String ref : List.of("ZZ_REPORT", "ZZ:2")) {
            Map<String, Object> vars = new LinkedHashMap<>(Map.of("billType", ref, "billFormKind", billFormKind()));
            ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class,
                    zz.invoke(request("ZZ", "documentType.get", payload(INPUT_FIELDS.get("bl_type"), vars))), ref);
            assertEquals("TYPE_UNKNOWN", f.code(), ref);
            Map<String, Object> noKind = new LinkedHashMap<>(Map.of("typeRef", ref));
            assertInstanceOf(ConnectorResult.Success.class, zz.invoke(request("ZZ", "documentType.get", noKind)),
                    "without the kind the report row is found: " + ref);
        }
        // a form shared by a bill and a report: the bill lookup sees the bill alone
        Map<String, Object> vars = new LinkedHashMap<>(Map.of("billType", "ZZ:1", "billFormKind", billFormKind()));
        ConnectorResult.Success ok = assertInstanceOf(ConnectorResult.Success.class,
                zz.invoke(request("ZZ", "documentType.get", payload(INPUT_FIELDS.get("bl_type"), vars))));
        assertEquals("ZZ_BILL", ok.output().get("id"));
    }

    @Test
    void aReferenceNamingTwoTypesIsAmbiguousNeverTheFirst() {
        DocumentTypeCatalogue cat = new DocumentTypeCatalogue(List.of(
                Map.<String, Object>of("id", "GIA", "formNumber", "RPR-34"),
                Map.<String, Object>of("id", "ST_GIA", "formNumber", "GIA"),
                Map.<String, Object>of("id", "ST_OLD", "formNumber", "ST-9", "legacyForms", List.of("OLD-9"))), List.of());
        assertEquals(List.of("GIA", "ST_GIA"), cat.byReference("GIA").stream().map(t -> t.id()).toList());
        assertEquals(List.of("ST_GIA"), cat.byReference("ST_GIA").stream().map(t -> t.id()).toList());
        assertEquals(List.of("GIA"), cat.byReference("RPR-34").stream().map(t -> t.id()).toList());
        assertEquals(List.of("ST_OLD"), cat.byReference("OLD-9").stream().map(t -> t.id()).toList());
        assertTrue(cat.byReference("NONE").isEmpty());
        assertTrue(cat.byReference(null).isEmpty());
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

    static ConnectorRequest request(String tenant, String op, Map<String, Object> input) {
        Map<String, Object> in = new LinkedHashMap<>(input);
        in.put("asOf", LocalDate.of(2026, 11, 5).toString());
        in.values().removeIf(java.util.Objects::isNull);
        return new ConnectorRequest(tenant, UUID.randomUUID(), "a", 1, null, null, op, in, null);
    }
}
