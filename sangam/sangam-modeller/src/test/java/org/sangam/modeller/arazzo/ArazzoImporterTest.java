// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.arazzo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.CallActivity;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.Process;
import org.operaton.bpm.model.bpmn.instance.ServiceTask;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonProperty;
import org.sangam.modeller.arazzo.ArazzoImport.Diagnostic;
import org.sangam.modeller.arazzo.ArazzoImport.Severity;
import org.sangam.modeller.arazzo.ArazzoImport.Status;

/**
 * M-069 / decision 159: a synthetic Arazzo corpus. A supported document becomes a schema-valid BPMN draft of
 * operationRef service tasks (contractId#operationId, topic sangam.operation, Arazzo request, criteria and outputs as
 * static properties); every unsupported construct is an ERROR and gives no BPMN.
 */
class ArazzoImporterTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static final String HEAD = "{\"arazzo\":\"1.0.1\",\"info\":{\"title\":\"Pets\",\"version\":\"1\"},"
            + "\"sourceDescriptions\":[{\"name\":\"petStore\",\"url\":\"https://example.test/pets.json\",\"type\":\"openapi\"}],";

    static byte[] doc(String workflows) {
        return (HEAD + "\"workflows\":[" + workflows + "]}").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] wf(String steps) {
        return doc("{\"workflowId\":\"adopt\",\"summary\":\"Adopt a pet\",\"steps\":[" + steps + "]}");
    }

    static final String FIND = "{\"stepId\":\"find\",\"description\":\"Find pets\",\"operationId\":\"findPets\","
            + "\"parameters\":[{\"name\":\"status\",\"in\":\"query\",\"value\":\"$inputs.status\"}],"
            + "\"successCriteria\":[{\"condition\":\"$statusCode == 200\"}],"
            + "\"outputs\":{\"petId\":\"$response.body#/0/id\"}}";
    static final String ADOPT = "{\"stepId\":\"adopt\",\"operationId\":\"adoptPet\","
            + "\"parameters\":[{\"name\":\"petId\",\"in\":\"path\",\"value\":\"$steps.find.outputs.petId\"}],"
            + "\"requestBody\":{\"contentType\":\"application/json\",\"payload\":{\"note\":\"${not-juel}\"}}}";

    static Set<String> codes(ArazzoImport r, Severity s) {
        return r.diagnostics().stream().filter(d -> d.severity() == s).map(Diagnostic::code).collect(Collectors.toSet());
    }

    static BpmnModelInstance model(ArazzoImport r) {
        return Bpmn.readModelFromStream(new ByteArrayInputStream(r.bpmn()));
    }

    static Map<String, String> props(ServiceTask t) {
        return t.getExtensionElements().getElementsQuery().filterByType(
                        org.operaton.bpm.model.bpmn.instance.operaton.OperatonProperties.class).singleResult()
                .getOperatonProperties().stream()
                .collect(Collectors.toMap(OperatonProperty::getOperatonName, OperatonProperty::getOperatonValue));
    }

    @Test
    void operationStepsBecomeOperationRefTasksInStepOrder() throws Exception {
        ArazzoImport r = ArazzoImporter.importDocument("pets.arazzo.json", wf(FIND + "," + ADOPT),
                new ArazzoImporter.Options(Map.of(), Map.of(), "P30D"));
        assertEquals(Status.REVIEW_REQUIRED, r.status(), r.diagnostics().toString());
        assertTrue(r.errors().isEmpty());
        assertEquals(Map.of("adopt", "adopt"), r.processes());
        assertEquals("petStore", r.contracts().get(0).contractId());
        BpmnModelInstance m = model(r);
        Bpmn.validateModel(m);
        Process p = m.getModelElementById("adopt");
        assertTrue(p.isExecutable());
        assertEquals("P30D", p.getOperatonHistoryTimeToLiveString());
        ServiceTask find = m.getModelElementById("adopt.find");
        assertEquals("Find pets", find.getName());
        assertEquals("external", find.getOperatonType());
        assertEquals(ArazzoImporter.TOPIC, find.getOperatonTopic());
        assertEquals("petStore#findPets", find.getAttributeValueNs(ArazzoBpmnWriter.SANGAM_NS, "operationRef"));
        Map<String, String> fp = props(find);
        JsonNode request = JSON.readTree(fp.get(ArazzoImporter.PROP_REQUEST));
        assertEquals("$inputs.status", request.at("/parameters/0/value").asText());
        assertEquals("query", request.at("/parameters/0/in").asText());
        assertEquals("$statusCode == 200", JSON.readTree(fp.get(ArazzoImporter.PROP_CRITERIA)).at("/0/condition").asText());
        assertEquals("$response.body#/0/id", JSON.readTree(fp.get(ArazzoImporter.PROP_OUTPUTS)).get("petId").asText());
        assertEquals("1.0.1", fp.get(ArazzoImporter.PROP_VERSION));
        ServiceTask adopt = m.getModelElementById("adopt.adopt");
        assertEquals("${not-juel}", JSON.readTree(props(adopt).get(ArazzoImporter.PROP_REQUEST))
                .at("/requestBody/payload/note").asText(), "a payload is kept as text, never an expression");
        assertEquals(List.of("adopt.adopt"), find.getOutgoing().stream().map(f -> f.getTarget().getId()).toList());
        assertEquals(List.of("adopt..end"), adopt.getOutgoing().stream().map(f -> f.getTarget().getId()).toList());
        assertTrue(codes(r, Severity.WARNING).contains("ARAZZO-OPERATION-UNCHECKED"));
        assertTrue(codes(r, Severity.INFO).containsAll(Set.of("ARAZZO-CONTRACT", "ARAZZO-UNPINNED")));
    }

    @Test
    void successGotoAndEndAreSequenceFlowsAndWorkflowStepsAreCallActivities() {
        String steps = "{\"stepId\":\"a\",\"operationId\":\"findPets\",\"onSuccess\":[{\"name\":\"skip\",\"type\":\"goto\","
                + "\"stepId\":\"c\"}]},{\"stepId\":\"b\",\"operationId\":\"adoptPet\"},"
                + "{\"stepId\":\"c\",\"workflowId\":\"notify\",\"onSuccess\":[{\"name\":\"done\",\"type\":\"end\"}]},"
                + "{\"stepId\":\"d\",\"operationId\":\"adoptPet\"}";
        ArazzoImport r = ArazzoImporter.importDocument("x.json", doc(
                "{\"workflowId\":\"main\",\"steps\":[" + steps + "]},"
                        + "{\"workflowId\":\"notify\",\"steps\":[{\"stepId\":\"n\",\"operationId\":\"notifyOwner\"}]}"));
        assertEquals(Status.REVIEW_REQUIRED, r.status(), r.diagnostics().toString());
        BpmnModelInstance m = model(r);
        Bpmn.validateModel(m);
        FlowNode a = m.getModelElementById("main.a");
        assertEquals("main.c", a.getOutgoing().iterator().next().getTarget().getId());
        CallActivity c = m.getModelElementById("main.c");
        assertEquals("notify", c.getCalledElement());
        assertEquals("deployment", c.getOperatonCalledElementBinding());
        assertEquals("main..end", c.getOutgoing().iterator().next().getTarget().getId());
        assertTrue(codes(r, Severity.WARNING).contains("ARAZZO-NO-HISTORY-TTL"));
    }

    @Test
    void sourcesMapToContractIdsAndQualifiedOperationIds() {
        byte[] d = ("{\"arazzo\":\"1.1.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"sourceDescriptions\":["
                + "{\"name\":\"pets\",\"url\":\"p.json\",\"type\":\"openapi\"},{\"name\":\"bank\",\"url\":\"b.json\"}],"
                + "\"workflows\":[{\"workflowId\":\"w\",\"steps\":[{\"stepId\":\"s\","
                + "\"operationId\":\"$sourceDescriptions.bank.charge\"}]}]}").getBytes(StandardCharsets.UTF_8);
        ArazzoImport r = ArazzoImporter.importDocument("x.json", d,
                new ArazzoImporter.Options(Map.of("bank", "bank-api"), Map.of(), null));
        assertEquals(Status.REVIEW_REQUIRED, r.status(), r.diagnostics().toString());
        ServiceTask s = model(r).getModelElementById("w.s");
        assertEquals("bank-api#charge", s.getAttributeValueNs(ArazzoBpmnWriter.SANGAM_NS, "operationRef"));
        assertTrue(codes(r, Severity.INFO).contains("ARAZZO-1-1-UNVERIFIED"));
    }

    @Test
    void operationPathResolvesOnlyAgainstASuppliedDocument() {
        String openApi = "{\"openapi\":\"3.1.0\",\"paths\":{\"/pets/{id}\":{\"get\":{\"operationId\":\"getPet\"}}}}";
        byte[] d = wf("{\"stepId\":\"g\",\"operationPath\":\"{$sourceDescriptions.petStore.url}#/paths/~1pets~1{id}/get\","
                + "\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"value\":1}]}");
        ArazzoImport without = ArazzoImporter.importDocument("x.json", d);
        assertEquals(Status.MANUAL, without.status());
        assertTrue(codes(without, Severity.ERROR).contains("ARAZZO-OPERATION-PATH-UNRESOLVED"));
        ArazzoImport with = ArazzoImporter.importDocument("x.json", d, new ArazzoImporter.Options(Map.of(),
                Map.of("petStore", openApi.getBytes(StandardCharsets.UTF_8)), "P1D"));
        assertEquals(Status.REVIEW_REQUIRED, with.status(), with.diagnostics().toString());
        ServiceTask g = model(with).getModelElementById("adopt.g");
        assertEquals("petStore#getPet", g.getAttributeValueNs(ArazzoBpmnWriter.SANGAM_NS, "operationRef"));
        assertFalse(codes(with, Severity.WARNING).contains("ARAZZO-OPERATION-UNCHECKED"));
        ArazzoImport missing = ArazzoImporter.importDocument("x.json", wf("{\"stepId\":\"z\",\"operationId\":\"nope\"}"),
                new ArazzoImporter.Options(Map.of(), Map.of("petStore", openApi.getBytes(StandardCharsets.UTF_8)), null));
        assertTrue(codes(missing, Severity.ERROR).contains("ARAZZO-OPERATION-NOT-FOUND"));
    }

    @Test
    void reusableComponentsAndWorkflowParametersAreResolved() throws Exception {
        byte[] d = (HEAD + "\"components\":{\"parameters\":{\"auth\":{\"name\":\"X-Key\",\"in\":\"header\",\"value\":\"k\"}}},"
                + "\"workflows\":[{\"workflowId\":\"w\",\"parameters\":[{\"name\":\"tenant\",\"in\":\"query\",\"value\":\"t1\"}],"
                + "\"steps\":[{\"stepId\":\"s\",\"operationId\":\"findPets\",\"parameters\":[{\"reference\":"
                + "\"$components.parameters.auth\",\"value\":\"k2\"},{\"name\":\"tenant\",\"in\":\"query\",\"value\":\"t2\"}]}]}]}")
                .getBytes(StandardCharsets.UTF_8);
        ArazzoImport r = ArazzoImporter.importDocument("x.json", d);
        assertEquals(Status.REVIEW_REQUIRED, r.status(), r.diagnostics().toString());
        JsonNode params = JSON.readTree(props(model(r).getModelElementById("w.s")).get(ArazzoImporter.PROP_REQUEST))
                .get("parameters");
        assertEquals(2, params.size());
        Map<String, String> byName = new java.util.HashMap<>();
        params.forEach(p -> byName.put(p.get("name").asText(), p.get("value").asText()));
        assertEquals(Map.of("X-Key", "k2", "tenant", "t2"), byName);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "ARAZZO-UNSUPPORTED-RETRY|{'stepId':'a','operationId':'findPets','onFailure':[{'name':'r','type':'retry','retryAfter':1,'retryLimit':3}]}",
            "ARAZZO-UNSUPPORTED-FAILURE-ACTION|{'stepId':'a','operationId':'findPets','onFailure':[{'name':'g','type':'goto','stepId':'a'}]}",
            "ARAZZO-UNSUPPORTED-CONDITIONAL-ACTION|{'stepId':'a','operationId':'findPets','onSuccess':[{'name':'c','type':'end','criteria':[{'condition':'$statusCode == 201'}]}]}",
            "ARAZZO-UNSUPPORTED-MULTIPLE-ACTIONS|{'stepId':'a','operationId':'findPets','onSuccess':[{'name':'x','type':'end'},{'name':'y','type':'end'}]}",
            "ARAZZO-UNSUPPORTED-GOTO-WORKFLOW|{'stepId':'a','operationId':'findPets','onSuccess':[{'name':'x','type':'goto','workflowId':'other'}]}",
            "ARAZZO-UNKNOWN-STEP|{'stepId':'a','operationId':'findPets','onSuccess':[{'name':'x','type':'goto','stepId':'nowhere'}]}",
            "ARAZZO-EXTERNAL-WORKFLOW|{'stepId':'a','workflowId':'$sourceDescriptions.petStore.other'}",
            "ARAZZO-UNKNOWN-WORKFLOW|{'stepId':'a','workflowId':'missing'}",
            "ARAZZO-STEP-TARGET|{'stepId':'a','operationId':'findPets','workflowId':'adopt'}",
            "ARAZZO-UNKNOWN-FIELD|{'stepId':'a','operationId':'findPets','channelPath':'x'}",
            "ARAZZO-PARAMETER|{'stepId':'a','operationId':'findPets','parameters':[{'name':'p','value':1}]}",
            "ARAZZO-PARAMETER|{'stepId':'a','operationId':'findPets','parameters':[{'name':'p','in':'body','value':1}]}",
            "ARAZZO-DUPLICATE-ID|{'stepId':'a','operationId':'findPets'},{'stepId':'a','operationId':'adoptPet'}",
            "ARAZZO-INVALID-ID|{'stepId':'a b','operationId':'findPets'}",
            "ARAZZO-OPERATION-ID|{'stepId':'a','operationId':'find pets'}",
            "ARAZZO-UNKNOWN-REFERENCE|{'stepId':'a','operationId':'findPets','parameters':[{'reference':'$components.parameters.none'}]}",
            "ARAZZO-OUTPUT|{'stepId':'a','operationId':'findPets','outputs':{'bad name':'$statusCode'}}",
            "ARAZZO-CRITERION|{'stepId':'a','operationId':'findPets','successCriteria':[{'context':'$response.body'}]}",
    })
    void unsupportedConstructsGiveAnErrorAndNoBpmn(String code, String steps) {
        ArazzoImport r = ArazzoImporter.importDocument("x.json", wf(steps.replace('\'', '"')));
        assertEquals(Status.MANUAL, r.status(), r.diagnostics().toString());
        assertNull(r.bpmn());
        assertTrue(r.processes().isEmpty());
        assertTrue(codes(r, Severity.ERROR).contains(code), r.diagnostics().toString());
        assertEquals("MANUAL", r.toJson().get("status"));
    }

    @Test
    void documentLevelRefusals() {
        assertTrue(codes(ArazzoImporter.importDocument("x", null), Severity.ERROR).contains("ARAZZO-EMPTY"));
        ArazzoImport yamlHead = ArazzoImporter.importDocument("x.yaml", "arazzo: 1.0.1\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(codes(yamlHead, Severity.ERROR).contains("ARAZZO-NO-SOURCE"),
                "YAML is read (decision 204) and then checked like JSON");
        assertTrue(codes(yamlHead, Severity.INFO).contains("ARAZZO-YAML"));
        assertTrue(codes(ArazzoImporter.importDocument("x", "{\"arazzo\":".getBytes(StandardCharsets.UTF_8)),
                Severity.ERROR).contains("ARAZZO-UNREADABLE"));
        assertTrue(codes(ArazzoImporter.importDocument("x", "{\"arazzo\":\"1.0.0\",\"arazzo\":\"1.0.1\"}"
                .getBytes(StandardCharsets.UTF_8)), Severity.ERROR).contains("ARAZZO-UNREADABLE"), "duplicate keys");
        assertTrue(codes(ArazzoImporter.importDocument("x", "{\"arazzo\":\"2.0.0\"}".getBytes(StandardCharsets.UTF_8)),
                Severity.ERROR).contains("ARAZZO-VERSION"));
        assertTrue(codes(ArazzoImporter.importDocument("x", new byte[ArazzoImporter.MAX_BYTES + 1]), Severity.ERROR)
                .contains("ARAZZO-TOO-LARGE"));
        ArazzoImport noSource = ArazzoImporter.importDocument("x", ("{\"arazzo\":\"1.0.1\",\"sourceDescriptions\":[],"
                + "\"workflows\":[{\"workflowId\":\"w\",\"steps\":[{\"stepId\":\"s\",\"operationId\":\"o\"}]}]}")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(codes(noSource, Severity.ERROR).contains("ARAZZO-NO-SOURCE"));
        ArazzoImport twoSources = ArazzoImporter.importDocument("x", ("{\"arazzo\":\"1.0.1\",\"sourceDescriptions\":["
                + "{\"name\":\"a\",\"url\":\"a\"},{\"name\":\"b\",\"url\":\"b\"}],\"workflows\":[{\"workflowId\":\"w\","
                + "\"steps\":[{\"stepId\":\"s\",\"operationId\":\"o\"}]}]}").getBytes(StandardCharsets.UTF_8));
        assertTrue(codes(twoSources, Severity.ERROR).contains("ARAZZO-AMBIGUOUS-OPERATION"));
        ArazzoImport badContract = ArazzoImporter.importDocument("x", wf(FIND),
                new ArazzoImporter.Options(Map.of("petStore", "bad id"), Map.of(), null));
        assertTrue(codes(badContract, Severity.ERROR).contains("ARAZZO-CONTRACT-ID"));
    }

    @Test
    void stepIdsThatLookLikeWriterIdsDoNotCollide() {
        ArazzoImport r = ArazzoImporter.importDocument("x", doc("{\"workflowId\":\"9w\",\"steps\":["
                + "{\"stepId\":\"start\",\"operationId\":\"findPets\"},{\"stepId\":\"end\",\"operationId\":\"findPets\"},"
                + "{\"stepId\":\"flow1\",\"operationId\":\"findPets\"}]}"));
        assertEquals(Status.REVIEW_REQUIRED, r.status(), r.diagnostics().toString());
        assertEquals("_9w", r.processes().get("9w"));
        BpmnModelInstance m = model(r);
        Bpmn.validateModel(m);
        assertNotNull(m.getModelElementById("_9w.start"));
        assertNotNull(m.getModelElementById("_9w..start"));
        assertEquals(3, m.getModelElementsByType(ServiceTask.class).size());
    }
}
