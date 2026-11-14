// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PackageToolsPort.AssessmentFile;
import org.sangam.rest.port.PackageToolsPort.AssessmentRow;
import org.sangam.rest.port.PackageToolsPort.ValidationIssue;

/**
 * M-061 (ADR-D-8 script sandbox, owner decisions 53 and 89): the package tools know the script-worker convention. A
 * convertible script task is reported as rewritten at deploy (INFO); any other stays SCRIPT_IN_ENGINE; a converted
 * external task is checked for an intact script (language, Base64, SHA-256); the assessment maps convertible formats.
 */
class PackageToolsScriptConventionTest {

    private static final String SCRIPT = "execution.setVariable('total', a + b)";

    private static String bpmn(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="p" isExecutable="true">
                    <startEvent id="s"/>
                    %s
                  </process>
                </definitions>
                """.formatted(body);
    }

    private static String external(String topic, String b64, String sha) {
        return """
                <serviceTask id="st" operaton:type="external" operaton:topic="%s">
                  <extensionElements><operaton:inputOutput>
                    <operaton:inputParameter name="sangamScriptB64">%s</operaton:inputParameter>
                    <operaton:inputParameter name="sangamScriptSha256">%s</operaton:inputParameter>
                  </operaton:inputOutput></extensionElements>
                </serviceTask>
                """.formatted(topic, b64, sha);
    }

    private static List<ValidationIssue> validate(String xml) {
        PackagePort.Package p = new PackagePort.Package("pkg-1", "k", "n", 1, "DRAFT", null, Map.of(), null, null,
                null, null, null, null, null, null, null, List.of(), Map.of("p.bpmn", xml));
        PackagePort packages = (PackagePort) Proxy.newProxyInstance(PackagePort.class.getClassLoader(),
                new Class<?>[] {PackagePort.class}, (proxy, m, args) -> {
                    if (m.getName().equals("get")) {
                        return p;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        return new OperatonPackageToolsAdapter(packages, null, null, null).validate(null, "pkg-1");
    }

    private static List<ValidationIssue> withCode(List<ValidationIssue> issues, String code) {
        return issues.stream().filter(i -> code.equals(i.code())).toList();
    }

    @Test
    void aConvertibleScriptTaskIsRewrittenAtDeployAndTheRestIsAnError() {
        List<ValidationIssue> js = validate(bpmn(
                "<scriptTask id=\"sc\" scriptFormat=\"JavaScript\"><script>1+1</script></scriptTask>"));
        assertEquals(List.of(), withCode(js, "SCRIPT_IN_ENGINE"));
        List<ValidationIssue> info = withCode(js, "SCRIPT_REWRITTEN_AT_DEPLOY");
        assertEquals("INFO", info.get(0).severity());
        assertTrue(info.get(0).message().contains("sangam.script.javascript"), info.get(0).message());

        List<ValidationIssue> py = withCode(validate(bpmn("<scriptTask id=\"sc\" scriptFormat=\"python3\""
                + " operaton:resource=\"deployment://s.py\"/>")), "SCRIPT_REWRITTEN_AT_DEPLOY");
        assertTrue(py.get(0).message().contains("sangam.script.python"), py.get(0).message());

        List<ValidationIssue> groovy = withCode(validate(bpmn(
                "<scriptTask id=\"sc\" scriptFormat=\"groovy\"><script>1</script></scriptTask>")), "SCRIPT_IN_ENGINE");
        assertEquals("ERROR", groovy.get(0).severity());
        assertTrue(groovy.get(0).message().contains("no sandboxed runtime"), groovy.get(0).message());

        List<ValidationIssue> classpath = withCode(validate(bpmn("<scriptTask id=\"sc\" scriptFormat=\"js\""
                + " operaton:resource=\"classpath://x.js\"/>")), "SCRIPT_IN_ENGINE");
        assertEquals("ERROR", classpath.get(0).severity());
        assertTrue(classpath.get(0).message().contains("outside the package"), classpath.get(0).message());
    }

    @Test
    void anIntactConvertedTaskPasses() {
        byte[] b = SCRIPT.getBytes(StandardCharsets.UTF_8);
        String xml = bpmn(external("sangam.script.javascript", Base64.getEncoder().encodeToString(b),
                Hashes.hex(Hashes.sha256(b))));
        List<ValidationIssue> issues = validate(xml);
        assertEquals(List.of(), withCode(issues, "SCRIPT_CONVENTION_INVALID"), String.valueOf(issues));
        assertEquals(List.of(), withCode(issues, "SERVICE_TASK_UNBOUND"));
    }

    @Test
    void aBrokenConvertedTaskIsAnError() {
        byte[] b = SCRIPT.getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(b);
        String sha = Hashes.hex(Hashes.sha256(b));
        for (String xml : List.of(
                bpmn(external("sangam.script.groovy", b64, sha)),
                bpmn(external("sangam.script.python", b64, sha.replace(sha.charAt(0), sha.charAt(0) == 'a' ? 'b' : 'a'))),
                bpmn(external("sangam.script.python", "%%%", sha)),
                bpmn(external("sangam.script.python", "", sha)))) {
            List<ValidationIssue> bad = withCode(validate(xml), "SCRIPT_CONVENTION_INVALID");
            assertEquals(1, bad.size(), xml);
            assertEquals("ERROR", bad.get(0).severity());
            assertEquals("st", bad.get(0).elementId());
        }
    }

    @Test
    void conventionRulesDirectly() {
        byte[] b = "x".getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(b);
        String sha = Hashes.hex(Hashes.sha256(b));
        assertNull(OperatonPackageToolsAdapter.scriptConventionProblem("sangam.script.python",
                Map.of("sangamScriptB64", b64, "sangamScriptSha256", sha.toUpperCase())));
        assertTrue(OperatonPackageToolsAdapter.scriptConventionProblem("sangam.script.python",
                Map.of("sangamScriptB64", b64)).contains("sangamScriptSha256"));
        assertEquals("javascript", OperatonPackageToolsAdapter.scriptLanguage(" ECMAScript "));
        assertEquals("python", OperatonPackageToolsAdapter.scriptLanguage("py"));
        for (String dialect : new String[] {"groovy", "nashorn", "graal.js", "jython", "jruby", "", null}) {
            assertNull(OperatonPackageToolsAdapter.scriptLanguage(dialect), String.valueOf(dialect));
        }
    }

    @Test
    void theAssessmentTellsConvertibleScriptsFromTheRest() {
        String xml = bpmn("<scriptTask id=\"a\" scriptFormat=\"js\"><script>1</script></scriptTask>"
                + "<scriptTask id=\"b\" scriptFormat=\"groovy\"><script>1</script></scriptTask>"
                + "<scriptTask id=\"c\"><script>1</script></scriptTask>");
        List<AssessmentRow> rows = new OperatonPackageToolsAdapter(null, null, null, null).assess(null,
                List.of(new AssessmentFile("p.bpmn", xml.getBytes(StandardCharsets.UTF_8))));
        Map<String, AssessmentRow> scripts = new java.util.HashMap<>();
        rows.stream().filter(r -> "bpmn:scriptTask".equals(r.construct())).forEach(r -> scripts.put(r.element(), r));
        assertEquals("MAPPED", scripts.get("a").classification());
        assertTrue(scripts.get("a").note().contains("sangam.script.javascript"), scripts.get("a").note());
        assertEquals("REJECTED", scripts.get("b").classification());
        assertEquals("REJECTED", scripts.get("c").classification());
        assertTrue(scripts.get("c").note().contains("(none)"), scripts.get("c").note());
    }
}
