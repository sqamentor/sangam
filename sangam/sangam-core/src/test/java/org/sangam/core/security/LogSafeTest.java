// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** CWE-117: caller text cannot forge a log line through {@link LogSafe}. */
class LogSafeTest {

    @Test
    void lineBreaksCannotStartAForgedLine() {
        assertEquals("t1_2026-11-17 INFO forged", LogSafe.text("t1\n2026-11-17 INFO forged"));
        assertEquals("a_b_c", LogSafe.text("a\r\nb\rc".replace("\r\n", "\n")));
        assertEquals("a__b", LogSafe.text("a\r\nb"));
    }

    @Test
    void otherControlCharactersAndUnicodeSeparatorsAreMasked() {
        assertEquals("x?[31my", LogSafe.text("x\u001b[31my"));
        assertEquals("a?b?c?d", LogSafe.text("a\tb c d"));
        assertEquals("nul?", LogSafe.text("nul\u0000"));
    }

    @Test
    void ordinaryValuesAreUnchanged() {
        assertEquals("tenant-01/acme:x", LogSafe.text("tenant-01/acme:x"));
        assertEquals("null", LogSafe.text(null));
        assertEquals("{a=1}", LogSafe.text(Map.of("a", 1)));
        assertEquals("", LogSafe.text(""));
    }
}
