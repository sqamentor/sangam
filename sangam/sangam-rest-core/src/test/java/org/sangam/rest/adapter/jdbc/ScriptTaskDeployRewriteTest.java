// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.BoundaryEvent;
import org.operaton.bpm.model.bpmn.instance.ScriptTask;
import org.operaton.bpm.model.bpmn.instance.SequenceFlow;
import org.operaton.bpm.model.bpmn.instance.ServiceTask;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputOutput;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputParameter;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonOutputParameter;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;

/**
 * Decision 89 (M-061, ADR-D-8 script sandbox): the deploy-time rewrite of script tasks into the script-worker
 * convention. The rewritten BPMN is read back with the Operaton model API, so the tests also prove it stays valid.
 */
class ScriptTaskDeployRewriteTest {

    private static final String SCRIPT = "var total = a + b; // ${notAnExpression}\nexecution.setVariable('t', total)";

    private static String bpmn(String declarations, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" %s targetNamespace="t">
                  <process id="p" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="sc"/>
                    %s
                    <sequenceFlow id="f2" sourceRef="sc" targetRef="e"/>
                    <boundaryEvent id="be" attachedToRef="sc"><errorEventDefinition/></boundaryEvent>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(declarations, body);
    }

    private static final String OP = "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\"";

    private static Map<String, byte[]> pkg(String bpmn, Object... more) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("p.bpmn", bpmn.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < more.length; i += 2) {
            m.put((String) more[i], ((String) more[i + 1]).getBytes(StandardCharsets.UTF_8));
        }
        return m;
    }

    private static BpmnModelInstance model(Map<String, byte[]> out) {
        return Bpmn.readModelFromStream(new ByteArrayInputStream(out.get("p.bpmn")));
    }

    private static Map<String, String> inputs(ServiceTask t) {
        Map<String, String> m = new LinkedHashMap<>();
        for (ModelElementInstance x : t.getExtensionElements().getElements()) {
            if (x instanceof OperatonInputOutput io) {
                for (OperatonInputParameter p : io.getOperatonInputParameters()) {
                    m.put(p.getOperatonName(), p.getTextContent());
                }
            }
        }
        return m;
    }

    private static String code(Map<String, byte[]> resources) {
        return assertThrows(ApiProblem.class, () -> ScriptTaskDeployRewrite.rewrite(resources)).code();
    }

    @Test
    void anInlineJavascriptTaskBecomesAnExternalTaskWithTheScriptIntact() {
        String body = """
                <scriptTask id="sc" name="Add" scriptFormat="javascript" operaton:resultVariable="sum" operaton:asyncBefore="true">
                  <documentation>adds</documentation>
                  <extensionElements><operaton:inputOutput>
                    <operaton:inputParameter name="a">1</operaton:inputParameter>
                    <operaton:outputParameter name="out">${sum}</operaton:outputParameter>
                  </operaton:inputOutput></extensionElements>
                  <script><![CDATA[%s]]></script>
                </scriptTask>
                """.formatted(SCRIPT);
        Map<String, byte[]> in = pkg(bpmn(OP, body));
        byte[] before = in.get("p.bpmn").clone();
        BpmnModelInstance m = model(ScriptTaskDeployRewrite.rewrite(in));

        assertArrayEquals(before, in.get("p.bpmn"), "the stored package is not touched");
        assertEquals(0, m.getModelElementsByType(ScriptTask.class).size());
        ServiceTask t = m.getModelElementById("sc");
        assertEquals("Add", t.getName());
        assertEquals("external", t.getOperatonType());
        assertEquals("sangam.script.javascript", t.getOperatonTopic());
        assertTrue(t.isOperatonAsyncBefore());
        assertEquals("adds", t.getDocumentations().iterator().next().getTextContent());
        assertEquals("sc", ((SequenceFlow) m.getModelElementById("f1")).getTarget().getId(), "incoming flow kept");
        assertEquals("sc", ((SequenceFlow) m.getModelElementById("f2")).getSource().getId(), "outgoing flow kept");
        assertEquals("sc", ((BoundaryEvent) m.getModelElementById("be")).getAttachedTo().getId(), "boundary event kept");

        Map<String, String> inputs = inputs(t);
        assertEquals(List.of("a", "sangamScriptB64", "sangamScriptSha256", "sangamScriptResultVariable"),
                new ArrayList<>(inputs.keySet()), "convention parameters added after the authored ones");
        byte[] script = Base64.getDecoder().decode(inputs.get("sangamScriptB64"));
        assertEquals(SCRIPT, new String(script, StandardCharsets.UTF_8));
        assertEquals(Hashes.hex(Hashes.sha256(script)), inputs.get("sangamScriptSha256"));
        assertEquals("sum", inputs.get("sangamScriptResultVariable"));
        OperatonInputOutput io = (OperatonInputOutput) t.getExtensionElements().getElements().iterator().next();
        assertEquals(List.of("out"), io.getOperatonOutputParameters().stream()
                .map(OperatonOutputParameter::getOperatonName).toList(), "authored outputs kept");
    }

    @Test
    void aPythonScriptFromAPackageResourceIsInlinedAndTheNamespaceIsDeclaredWhenMissing() {
        String body = "<scriptTask id=\"sc\" scriptFormat=\"Python3\""
                + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" operaton:resource=\"deployment://scripts/s.py\"/>";
        String py = "from decimal import Decimal\nDecimal('0.10') * 3\n";
        // the namespace is declared only on the task: the rewrite reuses that binding
        BpmnModelInstance m = model(ScriptTaskDeployRewrite.rewrite(pkg(bpmn("", body), "scripts/s.py", py)));
        ServiceTask t = m.getModelElementById("sc");
        assertEquals("sangam.script.python", t.getOperatonTopic());
        Map<String, String> inputs = inputs(t);
        assertEquals(py, new String(Base64.getDecoder().decode(inputs.get("sangamScriptB64")), StandardCharsets.UTF_8));
        assertNull(inputs.get("sangamScriptResultVariable"));

        // no Operaton binding at all: one is declared on the root
        BpmnModelInstance m2 = model(ScriptTaskDeployRewrite.rewrite(pkg(bpmn("",
                "<scriptTask id=\"sc\" scriptFormat=\"js\"><script>1</script></scriptTask>"))));
        assertEquals("sangam.script.javascript", ((ServiceTask) m2.getModelElementById("sc")).getOperatonTopic());
    }

    @Test
    void theCamundaNamespaceIsUnderstood() {
        String body = "<scriptTask id=\"sc\" scriptFormat=\"ecmascript\" camunda:resultVariable=\"r\"><script>1</script>"
                + "</scriptTask>";
        BpmnModelInstance m = model(ScriptTaskDeployRewrite.rewrite(pkg(bpmn(
                "xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\" " + OP, body))));
        ServiceTask t = m.getModelElementById("sc");
        assertEquals("sangam.script.javascript", t.getOperatonTopic());
        assertEquals("r", inputs(t).get("sangamScriptResultVariable"));
    }

    @Test
    void resourcesWithoutScriptTasksPassThroughByteIdentical() {
        String plain = bpmn(OP, "<serviceTask id=\"sc\" operaton:type=\"external\" operaton:topic=\"x\"/>");
        Map<String, byte[]> in = pkg(plain, "f.form", "{\"components\":[]}", "notes.txt", "scriptTask in text");
        Map<String, byte[]> out = ScriptTaskDeployRewrite.rewrite(in);
        for (String k : in.keySet()) {
            assertSame(in.get(k), out.get(k), k);
        }
        assertEquals(new ArrayList<>(in.keySet()), new ArrayList<>(out.keySet()), "order kept");
    }

    @Test
    void unsupportedOrUnsafeScriptsAreRefusedWith422() {
        assertEquals("SCRIPT_FORMAT_UNSUPPORTED", code(pkg(bpmn(OP,
                "<scriptTask id=\"sc\" scriptFormat=\"groovy\"><script>1</script></scriptTask>"))));
        assertEquals("SCRIPT_FORMAT_UNSUPPORTED", code(pkg(bpmn(OP,
                "<scriptTask id=\"sc\" scriptFormat=\"graal.js\"><script>1</script></scriptTask>"))));
        assertEquals("SCRIPT_FORMAT_UNSUPPORTED", code(pkg(bpmn(OP, "<scriptTask id=\"sc\"><script>1</script></scriptTask>"))));
        assertEquals("SCRIPT_TASK_EMPTY", code(pkg(bpmn(OP, "<scriptTask id=\"sc\" scriptFormat=\"js\"/>"))));
        assertEquals("SCRIPT_RESOURCE_OUTSIDE_PACKAGE", code(pkg(bpmn(OP,
                "<scriptTask id=\"sc\" scriptFormat=\"js\" operaton:resource=\"classpath://x.js\"/>"))));
        assertEquals("SCRIPT_RESOURCE_OUTSIDE_PACKAGE", code(pkg(bpmn(OP,
                "<scriptTask id=\"sc\" scriptFormat=\"js\" operaton:resource=\"x.js\"/>"))));
        assertEquals("SCRIPT_RESOURCE_MISSING", code(pkg(bpmn(OP,
                "<scriptTask id=\"sc\" scriptFormat=\"js\" operaton:resource=\"deployment://x.js\"/>"))));
        assertEquals("SCRIPT_CONVENTION_CONFLICT", code(pkg(bpmn(OP, """
                <scriptTask id="sc" scriptFormat="js"><extensionElements><operaton:inputOutput>
                  <operaton:inputParameter name="sangamScriptB64">x</operaton:inputParameter>
                </operaton:inputOutput></extensionElements><script>1</script></scriptTask>"""))));
        assertEquals("SCRIPT_CONVENTION_CONFLICT", code(pkg(bpmn(OP,
                "<scriptTask id=\"sc\" scriptFormat=\"js\" operaton:resultVariable=\"1 bad\"><script>1</script></scriptTask>"))));
        assertEquals("MODEL_INVALID", code(pkg("<definitions><scriptTask")));
        ApiProblem p = assertThrows(ApiProblem.class, () -> ScriptTaskDeployRewrite.rewrite(pkg(bpmn(OP,
                "<scriptTask id=\"sc\" scriptFormat=\"groovy\"><script>1</script></scriptTask>"))));
        assertEquals(422, p.status());
    }

    @Test
    void doctypesAreRefused() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"><scriptTask id=\"a\">&x;</scriptTask>"
                + "</definitions>";
        assertEquals("MODEL_INVALID", code(pkg(xxe)));
    }

    @Test
    void formatsMapLikeTheWorker() {
        assertEquals("javascript", ScriptTaskDeployRewrite.language(" JS "));
        assertEquals("python", ScriptTaskDeployRewrite.language("py"));
        for (String f : new String[] {"groovy", "nashorn", "graal.js", "jython", "jruby", "", null}) {
            assertNull(ScriptTaskDeployRewrite.language(f), String.valueOf(f));
        }
    }
}
