// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Owner decision 324: the JDK-only ECS JSON formatter of the sidecars. Whatever a message holds (CR, LF, other control
 * characters, the JavaScript line separators U+2028/U+2029, an unpaired surrogate, quotes, back-slashes), the entry is
 * exactly one line of valid JSON and the message parses back unchanged; the levels map to the ECS names.
 */
class EcsJsonLogFormatterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyCharacterThatCouldEndALineIsEscaped() throws Exception {
        String hostile = "a\r\nFAKE\u0000\u001b[31m\u007f  \"\\\t" + '\ud800' + "z" + '\udc00';
        LogRecord r = new LogRecord(Level.WARNING, hostile);
        r.setLoggerName("org.sangam.Probe");
        String line = new EcsJsonLogFormatter("sangam-test").format(r);
        assertTrue(line.endsWith("\n"));
        String body = line.substring(0, line.length() - 1);
        for (char c : body.toCharArray()) {
            assertTrue(c >= 0x20 && c != 0x7F && c != ' ' && c != ' ' && !Character.isSurrogate(c),
                    "a raw control, separator or surrogate character in the output: " + (int) c);
        }
        JsonNode json = JSON.readTree(body);
        assertEquals(hostile, json.at("/message").asText());
        assertEquals("WARN", json.at("/log/level").asText());
        assertEquals("org.sangam.Probe", json.at("/log/logger").asText());
        assertEquals("sangam-test", json.at("/service/name").asText());
        assertFalse(json.has("error"));
    }

    @Test
    void levelsUseTheEcsNamesAndABlankServiceIsRefused() {
        assertEquals("ERROR", EcsJsonLogFormatter.level(Level.SEVERE));
        assertEquals("WARN", EcsJsonLogFormatter.level(Level.WARNING));
        assertEquals("INFO", EcsJsonLogFormatter.level(Level.INFO));
        assertEquals("DEBUG", EcsJsonLogFormatter.level(Level.CONFIG));
        assertEquals("DEBUG", EcsJsonLogFormatter.level(Level.FINE));
        assertEquals("TRACE", EcsJsonLogFormatter.level(Level.FINEST));
        assertThrows(IllegalArgumentException.class, () -> new EcsJsonLogFormatter(" "));
    }
}
