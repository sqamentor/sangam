// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.modeller.diff.ModelDiff.Change;
import org.sangam.modeller.diff.ModelDiff.Format;
import org.sangam.modeller.diff.ModelDiff.Kind;
import org.sangam.modeller.diff.ModelDiff.Level;
import org.sangam.modeller.diff.ModelDiff.Result;

/** M-125: semantic diff of BPMN, DMN and form versions (element, flow, expression and property level). */
class ModelDiffTest {

    static final String BPMN_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\""
            + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" id=\"defs\" targetNamespace=\"t\" exporter=\"%s\">"
            + "<bpmn:process id=\"p\" isExecutable=\"true\">";
    static final String BPMN_TAIL = "</bpmn:process>%s</bpmn:definitions>";

    static byte[] bpmn(String exporter, String body, String diagram) {
        return (String.format(BPMN_HEAD, exporter) + body + String.format(BPMN_TAIL, diagram))
                .getBytes(StandardCharsets.UTF_8);
    }

    static final String BASE = "<bpmn:startEvent id=\"start\"/>"
            + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"gw\"/>"
            + "<bpmn:exclusiveGateway id=\"gw\" name=\"Amount?\"/>"
            + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"gw\" targetRef=\"approve\">"
            + "<bpmn:conditionExpression>${amount &gt; 100}</bpmn:conditionExpression></bpmn:sequenceFlow>"
            + "<bpmn:userTask id=\"approve\" name=\"Approve\" operaton:assignee=\"clerk\"/>";

    static List<Change> of(Result r, Level level) {
        return r.changes().stream().filter(c -> c.level() == level).toList();
    }

    @Test
    void identicalModelsAndLayoutOnlyChangesHaveNoChanges() {
        byte[] a = bpmn("Modeler 1", BASE, "<bpmndi:BPMNDiagram id=\"d\"><bpmndi:BPMNPlane id=\"pl\" bpmnElement=\"p\">"
                + "<bpmndi:BPMNShape id=\"s\" bpmnElement=\"start\"><dc:Bounds x=\"1\" y=\"1\" width=\"36\" height=\"36\"/>"
                + "</bpmndi:BPMNShape></bpmndi:BPMNPlane></bpmndi:BPMNDiagram>");
        byte[] b = bpmn("Modeler 2", BASE, "<bpmndi:BPMNDiagram id=\"d\"><bpmndi:BPMNPlane id=\"pl\" bpmnElement=\"p\">"
                + "<bpmndi:BPMNShape id=\"s\" bpmnElement=\"start\"><dc:Bounds x=\"400\" y=\"9\" width=\"36\" height=\"36\"/>"
                + "</bpmndi:BPMNShape></bpmndi:BPMNPlane></bpmndi:BPMNDiagram>");
        Result r = ModelDiff.compare("order.bpmn", a, b);
        assertEquals(Format.BPMN, r.format());
        assertFalse(r.hasChanges(), r.toText());
        assertEquals(0, r.summary().get(Level.ELEMENT));
    }

    @Test
    void addedTaskRewiredFlowChangedConditionAndRenamedTask() {
        byte[] a = bpmn("x", BASE, "");
        String changed = BASE.replace("targetRef=\"approve\">", "targetRef=\"check\">")
                .replace("${amount &gt; 100}", "${amount &gt; 500}")
                .replace("name=\"Approve\"", "name=\"Approve payment\"")
                + "<bpmn:serviceTask id=\"check\" name=\"Check\" operaton:type=\"external\" operaton:topic=\"check\"/>";
        Result r = ModelDiff.compare("order.bpmn", a, bpmn("x", changed, ""));

        List<Change> elements = of(r, Level.ELEMENT);
        assertEquals(1, elements.size(), r.toText());
        assertEquals(Kind.ADDED, elements.get(0).kind());
        assertEquals("check", elements.get(0).elementId());
        assertEquals("serviceTask", elements.get(0).elementType());
        assertEquals("Check", elements.get(0).name());

        Change rewired = of(r, Level.FLOW).stream().filter(c -> c.elementId().equals("f2")).findFirst().orElseThrow();
        assertEquals("@targetRef", rewired.path());
        assertEquals("approve", rewired.before());
        assertEquals("check", rewired.after());

        List<Change> expressions = of(r, Level.EXPRESSION);
        assertEquals(1, expressions.size(), r.toText());
        assertEquals("f2", expressions.get(0).elementId());
        assertEquals("conditionExpression[1]/#text", expressions.get(0).path());
        assertEquals("${amount > 100}", expressions.get(0).before());
        assertEquals("${amount > 500}", expressions.get(0).after());

        Change renamed = of(r, Level.PROPERTY).stream().filter(c -> c.elementId().equals("approve")).findFirst()
                .orElseThrow();
        assertEquals("@name", renamed.path());
        assertEquals("Approve payment", renamed.name());
        assertTrue(r.toText().contains("+ ELEMENT serviceTask check \"Check\""), r.toText());
    }

    @Test
    void removedElementIsOneLineAndTypeChangeIsReported() {
        byte[] a = bpmn("x", BASE + "<bpmn:subProcess id=\"sub\"><bpmn:task id=\"inner\"/></bpmn:subProcess>", "");
        byte[] b = bpmn("x", BASE.replace("bpmn:userTask id=\"approve\" name=\"Approve\" operaton:assignee=\"clerk\"/",
                "bpmn:manualTask id=\"approve\" name=\"Approve\"/"), "");
        Result r = ModelDiff.compare("order.bpmn", a, b);
        List<Change> removed = r.changes().stream().filter(c -> c.kind() == Kind.REMOVED && c.path().isEmpty()).toList();
        assertEquals(1, removed.size(), "the inner task is covered by its removed sub-process: " + r.toText());
        assertEquals("sub", removed.get(0).elementId());
        Change type = r.changes().stream().filter(c -> c.path().equals("#type")).findFirst().orElseThrow();
        assertEquals("userTask", type.before());
        assertEquals("manualTask", type.after());
        assertTrue(r.changes().stream().anyMatch(c -> c.path().equals("@operaton:assignee") && c.kind() == Kind.REMOVED),
                r.toText());
    }

    @Test
    void extensionMappingIsAnExpressionChange() {
        String io = "<bpmn:serviceTask id=\"call\"><bpmn:extensionElements><operaton:inputOutput>"
                + "<operaton:inputParameter name=\"body\">%s</operaton:inputParameter>"
                + "</operaton:inputOutput></bpmn:extensionElements></bpmn:serviceTask>";
        Result r = ModelDiff.compare("m.bpmn", bpmn("x", String.format(io, "${a}"), ""),
                bpmn("x", String.format(io, "${b}"), ""));
        assertEquals(1, r.changes().size(), r.toText());
        Change c = r.changes().get(0);
        assertEquals(Level.EXPRESSION, c.level());
        assertEquals("extensionElements[1]/operaton:inputOutput[1]/operaton:inputParameter[1]/#text", c.path());
    }

    static byte[] dmn(String hitPolicy, String rules) {
        return ("<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\" name=\"D\" namespace=\"n\">"
                + "<decision id=\"fee\" name=\"Fee\"><decisionTable id=\"t\" hitPolicy=\"" + hitPolicy + "\">"
                + "<input id=\"i1\"><inputExpression id=\"ie1\" typeRef=\"number\"><text>amount</text></inputExpression></input>"
                + "<output id=\"o1\" name=\"fee\" typeRef=\"number\"/>" + rules
                + "</decisionTable></decision></definitions>").getBytes(StandardCharsets.UTF_8);
    }

    static String rule(String id, String in, String out) {
        return "<rule id=\"" + id + "\"><inputEntry id=\"" + id + "i\"><text>" + in + "</text></inputEntry>"
                + "<outputEntry id=\"" + id + "o\"><text>" + out + "</text></outputEntry></rule>";
    }

    @Test
    void dmnRuleEntryHitPolicyAndRuleOrder() {
        byte[] a = dmn("FIRST", rule("r1", "&lt; 100", "5") + rule("r2", ">= 100", "10"));
        byte[] b = dmn("UNIQUE", rule("r2", ">= 100", "12") + rule("r1", "&lt; 100", "5"));
        Result r = ModelDiff.compare("fee.dmn", a, b);
        assertEquals(Format.DMN, r.format());
        Change entry = of(r, Level.EXPRESSION).get(0);
        assertEquals("r2o", entry.elementId());
        assertEquals("text[1]/#text", entry.path());
        assertEquals("10", entry.before());
        assertEquals("12", entry.after());
        assertTrue(of(r, Level.PROPERTY).stream().anyMatch(c -> c.path().equals("@hitPolicy")
                && c.before().equals("FIRST") && c.after().equals("UNIQUE")), r.toText());
        Change order = of(r, Level.PROPERTY).stream().filter(c -> c.path().equals("#order")).findFirst().orElseThrow();
        assertEquals("t", order.elementId());
        assertEquals("i1,o1,r1,r2", order.before());
        assertEquals("i1,o1,r2,r1", order.after());
    }

    @Test
    void dmnRequirementIsAFlowChange() {
        String head = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\" name=\"D\" namespace=\"n\">"
                + "<inputData id=\"in1\" name=\"x\"/><inputData id=\"in2\" name=\"y\"/>";
        String a = head + "<decision id=\"dec\" name=\"Dec\"><informationRequirement id=\"ir\"><requiredInput href=\"#in1\"/>"
                + "</informationRequirement></decision></definitions>";
        Result r = ModelDiff.compare("x.dmn", a.getBytes(StandardCharsets.UTF_8),
                a.replace("#in1", "#in2").getBytes(StandardCharsets.UTF_8));
        assertEquals(1, r.changes().size(), r.toText());
        assertEquals(Level.FLOW, r.changes().get(0).level());
        assertEquals("ir", r.changes().get(0).elementId());
    }

    static byte[] form(String fields) {
        return ("{\"key\":\"assets/loss\",\"version\":\"1.0.1\",\"title\":{\"en\":\"Loss\"},\"fields\":[" + fields + "]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void formFieldsMatchedByIdNotPosition() {
        String desc = "{\"id\":\"description\",\"type\":\"text\",\"required\":true,\"label\":{\"en\":\"Description\"}}";
        String docs = "{\"id\":\"documents\",\"type\":\"attachments\",\"required\":false,\"label\":{\"en\":\"Documents\"}}";
        String amount = "{\"id\":\"amount\",\"type\":\"money\",\"currency\":\"INR\",\"exponent\":2,\"label\":{\"en\":\"Amount\"}}";
        Result r = ModelDiff.compare("loss.form.json", form(desc + "," + docs),
                form(docs.replace("\"required\":false", "\"required\":true") + "," + desc + "," + amount));
        assertEquals(Format.FORM, r.format());
        List<Change> elements = of(r, Level.ELEMENT);
        assertEquals(1, elements.size(), r.toText());
        assertEquals("amount", elements.get(0).elementId());
        assertEquals("money", elements.get(0).elementType());
        assertEquals("Amount", elements.get(0).name());
        Change required = of(r, Level.PROPERTY).stream().filter(c -> c.elementId().equals("documents")).findFirst()
                .orElseThrow();
        assertEquals("required", required.path());
        assertEquals("false", required.before());
        assertEquals("true", required.after());
        Change order = of(r, Level.PROPERTY).stream().filter(c -> c.path().equals("fields.#order")).findFirst()
                .orElseThrow();
        assertEquals("description,documents", order.before());
        assertEquals("documents,description", order.after());
    }

    @Test
    void formExpressionChange() {
        String a = "{\"components\":[{\"id\":\"c1\",\"key\":\"x\",\"type\":\"textfield\","
                + "\"conditional\":{\"hide\":\"=amount < 10\"}}]}";
        Result r = ModelDiff.compare("f.form.json", a.getBytes(StandardCharsets.UTF_8),
                a.replace("< 10", "< 20").getBytes(StandardCharsets.UTF_8));
        assertEquals(1, r.changes().size(), r.toText());
        assertEquals(Level.EXPRESSION, r.changes().get(0).level());
        assertEquals("c1", r.changes().get(0).elementId());
    }

    static List<String> ids(Result r, Level level) {
        return of(r, level).stream().map(Change::elementId).toList();
    }

    /**
     * Decision 329: an absent version (a new or deleted file) reports every element of the present one, each as one
     * whole-element line, as {@link ModelDiff#compare} documents. (This test used to expect only the root line.)
     */
    @Test
    void newAndDeletedFiles() {
        Result added = ModelDiff.compare("loss.form.json", null, form(""));
        assertEquals(Kind.ADDED, added.changes().get(0).kind());
        assertEquals(List.of("(form)"), ids(added, Level.ELEMENT), "a form without fields is the form alone");

        Result removed = ModelDiff.compare("order.bpmn", bpmn("x", BASE, ""), new byte[0]);
        assertTrue(removed.changes().stream().allMatch(c -> c.kind() == Kind.REMOVED));
        assertTrue(removed.changes().stream().allMatch(c -> c.path().isEmpty() && c.before() == null && c.after() == null),
                "whole-element lines only: " + removed.toText());
        assertEquals(List.of("approve", "defs", "gw", "p", "start"), ids(removed, Level.ELEMENT), removed.toText());
        assertEquals(List.of("f1", "f2"), ids(removed, Level.FLOW), "flows keep their level");
        assertEquals(7, removed.changes().size(), "every element of the deleted file: " + removed.toText());

        Result created = ModelDiff.compare("order.bpmn", null, bpmn("x", BASE, ""));
        assertTrue(created.changes().stream().allMatch(c -> c.kind() == Kind.ADDED));
        assertEquals(ids(removed, Level.ELEMENT), ids(created, Level.ELEMENT));
        assertEquals(ids(removed, Level.FLOW), ids(created, Level.FLOW));
        assertEquals("Approve", created.changes().stream().filter(c -> c.elementId().equals("approve")).findFirst()
                .orElseThrow().name());
    }

    @Test
    void aNewFileListsNestedElementsWhileAnAddedParentBetweenTwoVersionsStaysOneLine() {
        String sub = "<bpmn:subProcess id=\"sub\"><bpmn:startEvent id=\"s2\"/>"
                + "<bpmn:sequenceFlow id=\"f9\" sourceRef=\"s2\" targetRef=\"t2\"/><bpmn:userTask id=\"t2\"/></bpmn:subProcess>";
        Result created = ModelDiff.compare("order.bpmn", new byte[0], bpmn("x", BASE + sub, ""));
        assertEquals(List.of("approve", "defs", "gw", "p", "s2", "start", "sub", "t2"), ids(created, Level.ELEMENT));
        assertEquals(List.of("f1", "f2", "f9"), ids(created, Level.FLOW));

        Result grown = ModelDiff.compare("order.bpmn", bpmn("x", BASE, ""), bpmn("x", BASE + sub, ""));
        assertEquals(List.of("sub"), ids(grown, Level.ELEMENT), "between two versions the parent's line covers its children");
        assertEquals(List.of(), ids(grown, Level.FLOW));
    }

    @Test
    void aNewOrDeletedFormListsEveryField() {
        String desc = "{\"id\":\"description\",\"type\":\"text\",\"label\":{\"en\":\"Description\"}}";
        String amount = "{\"id\":\"amount\",\"type\":\"money\",\"label\":{\"en\":\"Amount\"}}";
        Result added = ModelDiff.compare("loss.form.json", null, form(desc + "," + amount));
        assertTrue(added.changes().stream().allMatch(c -> c.kind() == Kind.ADDED && c.level() == Level.ELEMENT
                && c.path().isEmpty()), added.toText());
        assertEquals(List.of("(form)", "amount", "description"), ids(added, Level.ELEMENT));
        Change field = added.changes().get(1);
        assertEquals("money", field.elementType());
        assertEquals("Amount", field.name());
        Result removed = ModelDiff.compare("loss.form.json", form(desc + "," + amount), null);
        assertTrue(removed.changes().stream().allMatch(c -> c.kind() == Kind.REMOVED));
        assertEquals(ids(added, Level.ELEMENT), ids(removed, Level.ELEMENT));

        String tree = "{\"components\":[{\"id\":\"g1\",\"type\":\"group\",\"components\":["
                + "{\"id\":\"c1\",\"key\":\"x\",\"type\":\"textfield\",\"values\":[\"a\",\"b\"]}]}]}";
        Result nested = ModelDiff.compare("f.form.json", new byte[0], tree.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("(form)", "c1", "g1"), ids(nested, Level.ELEMENT), "components at any depth");
    }

    @Test
    void reportIsDeterministicAndJsonShaped() {
        byte[] a = bpmn("x", BASE, "");
        byte[] b = bpmn("x", BASE.replace("Amount?", "Amount") + "<bpmn:endEvent id=\"end\"/>", "");
        Result r1 = ModelDiff.compare("o.bpmn", a, b);
        Result r2 = ModelDiff.compare("o.bpmn", a, b);
        assertEquals(r1.changes(), r2.changes());
        Map<String, Object> json = r1.toJson();
        assertEquals("BPMN", json.get("format"));
        assertEquals(Boolean.TRUE, json.get("changed"));
        assertEquals(Map.of("ELEMENT", 1, "FLOW", 0, "EXPRESSION", 0, "PROPERTY", 1), json.get("summary"));
        assertEquals(Level.ELEMENT, r1.changes().get(0).level(), "sorted by level first");
    }

    @Test
    void formatDetectionAndRefusals() {
        assertEquals(Format.BPMN, ModelDiff.detect("x.xml", bpmn("x", BASE, "")));
        assertEquals(Format.DMN, ModelDiff.detect("x.xml", dmn("FIRST", "")));
        assertThrows(IllegalArgumentException.class, () -> ModelDiff.compare("x.bpmn",
                "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>".getBytes(StandardCharsets.UTF_8),
                bpmn("x", BASE, "")), "DOCTYPE refused");
        assertThrows(IllegalArgumentException.class, () -> ModelDiff.compare("x", form(""), bpmn("x", BASE, "")));
        assertThrows(IllegalArgumentException.class, () -> ModelDiff.compare("x.bpmn", null, new byte[0]),
                "nothing to compare");
        assertThrows(IllegalArgumentException.class, () -> ModelDiff.compare("x.form.json",
                "[1]".getBytes(StandardCharsets.UTF_8), form("")));
    }
}
