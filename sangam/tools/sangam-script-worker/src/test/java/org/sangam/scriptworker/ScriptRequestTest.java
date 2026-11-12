// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** The topic convention and the checks made before anything runs. */
class ScriptRequestTest {

    private static final String SCRIPT = "execution.setVariable('total', a + b)";

    private static Map<String, Object> vars(String script) {
        byte[] b = script.getBytes(StandardCharsets.UTF_8);
        Map<String, Object> v = new HashMap<>();
        v.put(ScriptTopics.VAR_SOURCE, Base64.getEncoder().encodeToString(b));
        v.put(ScriptTopics.VAR_SHA256, ScriptRequest.sha256(b));
        v.put("a", 1);
        v.put("b", 2);
        return v;
    }

    @Test
    void aValidRequestDecodesAndDropsTheConventionVariables() throws Exception {
        Map<String, Object> v = vars(SCRIPT);
        v.put(ScriptTopics.VAR_RESULT, "sum");
        ScriptRequest r = ScriptRequest.from("sangam.script.javascript", v, ScriptLimits.DEFAULTS);
        assertEquals(Language.JAVASCRIPT, r.language());
        assertEquals(SCRIPT, r.source());
        assertEquals("sum", r.resultVariable());
        assertEquals(Map.of("a", 1, "b", 2), r.inputs());
    }

    @Test
    void theResultVariableIsOptional() throws Exception {
        assertNull(ScriptRequest.from("sangam.script.python", vars("a"), ScriptLimits.DEFAULTS).resultVariable());
    }

    @Test
    void aChangedScriptFailsTheIntegrityCheck() {
        Map<String, Object> v = vars(SCRIPT);
        v.put(ScriptTopics.VAR_SOURCE, Base64.getEncoder().encodeToString("evil()".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ScriptRequest.InvalidScriptException.class,
                () -> ScriptRequest.from("sangam.script.javascript", v, ScriptLimits.DEFAULTS));
        Map<String, Object> noHash = vars(SCRIPT);
        noHash.remove(ScriptTopics.VAR_SHA256);
        assertThrows(ScriptRequest.InvalidScriptException.class,
                () -> ScriptRequest.from("sangam.script.javascript", noHash, ScriptLimits.DEFAULTS));
    }

    @Test
    void anUpperCaseHashIsAccepted() throws Exception {
        Map<String, Object> v = vars(SCRIPT);
        v.put(ScriptTopics.VAR_SHA256, ((String) v.get(ScriptTopics.VAR_SHA256)).toUpperCase());
        assertEquals(SCRIPT, ScriptRequest.from("sangam.script.javascript", v, ScriptLimits.DEFAULTS).source());
    }

    @Test
    void foreignTopicsMissingSourceBadBase64AndBadNamesAreRejected() {
        assertThrows(ScriptRequest.InvalidScriptException.class,
                () -> ScriptRequest.from("payment", vars(SCRIPT), ScriptLimits.DEFAULTS));
        assertThrows(ScriptRequest.InvalidScriptException.class,
                () -> ScriptRequest.from("sangam.script.groovy", vars(SCRIPT), ScriptLimits.DEFAULTS));
        assertThrows(ScriptRequest.InvalidScriptException.class,
                () -> ScriptRequest.from("sangam.script.javascript", Map.of("a", 1), ScriptLimits.DEFAULTS));
        Map<String, Object> bad = vars(SCRIPT);
        bad.put(ScriptTopics.VAR_SOURCE, "%%%not-base64%%%");
        assertThrows(ScriptRequest.InvalidScriptException.class,
                () -> ScriptRequest.from("sangam.script.javascript", bad, ScriptLimits.DEFAULTS));
        Map<String, Object> name = vars(SCRIPT);
        name.put(ScriptTopics.VAR_RESULT, "1x; drop");
        assertThrows(ScriptRequest.InvalidScriptException.class,
                () -> ScriptRequest.from("sangam.script.javascript", name, ScriptLimits.DEFAULTS));
    }

    @Test
    void anOversizedScriptIsRejectedBeforeAndAfterDecoding() {
        ScriptLimits tiny = new ScriptLimits(5, Duration.ofSeconds(10), 256L << 20, 16, 0, 1, 0, 8);
        assertThrows(ScriptRequest.InvalidScriptException.class,
                () -> ScriptRequest.from("sangam.script.javascript", vars("x".repeat(9)), tiny));
        assertThrows(ScriptRequest.InvalidScriptException.class,
                () -> ScriptRequest.from("sangam.script.javascript", vars("x".repeat(100)), tiny));
    }

    @Test
    void topicsAndFormats() {
        assertEquals("sangam.script.javascript", ScriptTopics.topic(Language.JAVASCRIPT));
        assertEquals("sangam.script.python", ScriptTopics.topic(Language.PYTHON));
        assertEquals(Optional.of(Language.PYTHON), ScriptTopics.languageOfTopic("sangam.script.python"));
        assertEquals(Optional.empty(), ScriptTopics.languageOfTopic("sangam.script."));
        assertEquals(Optional.of(Language.JAVASCRIPT), ScriptTopics.language(" ECMAScript "));
        assertEquals(Optional.of(Language.PYTHON), ScriptTopics.language("python3"));
        for (String dialect : new String[] {"groovy", "nashorn", "graal.js", "jython", "jruby", null}) {
            assertEquals(Optional.empty(), ScriptTopics.language(dialect), "not mapped: " + dialect);
        }
    }
}
