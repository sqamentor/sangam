// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** M-144 config-source seam: one typed reading of every key for every host. Written for the test phase (not run). */
class SangamPropertiesTest {

    @Test
    void absentAndBlankValuesGiveTheDefault() {
        SangamProperties p = SangamProperties.of(Map.of("blank", "  "));
        assertNull(p.get("missing"));
        assertEquals("d", p.get("missing", "d"));
        assertTrue(p.getBoolean("missing", true));
        assertFalse(p.getBoolean("blank", false));
        assertEquals(7, p.getInt("blank", 7));
        assertEquals(9L, p.getLong("missing", 9L));
        assertEquals(Duration.ofSeconds(3), p.getDuration("missing", Duration.ofSeconds(3)));
        assertEquals(List.of(), p.getList("blank"));
    }

    @Test
    void booleansAreReadAsSpringReadsThem() {
        for (String t : List.of("true", "TRUE", "on", "Yes", "1", " true ")) {
            assertTrue(SangamProperties.of(Map.of("k", t)).getBoolean("k", false), t);
        }
        for (String f : List.of("false", "OFF", "no", "0")) {
            assertFalse(SangamProperties.of(Map.of("k", f)).getBoolean("k", true), f);
        }
    }

    @Test
    void anUnreadableValueFailsNamingTheKeyAndNeverFallsBack() {
        SangamProperties p = SangamProperties.of(Map.of("flag", "maybe", "n", "ten", "d", "soon", "l", "1.5"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> p.getBoolean("flag", true));
        assertTrue(e.getMessage().contains("flag='maybe'"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> p.getInt("n", 1));
        assertThrows(IllegalArgumentException.class, () -> p.getLong("l", 1L));
        assertThrows(IllegalArgumentException.class, () -> p.getDuration("d", Duration.ZERO));
    }

    @Test
    void durationsInIsoAndSimpleStyle() {
        SangamProperties p = SangamProperties.of(Map.of("iso", "PT30S", "s", "30s", "ms", "500ms", "bare", "250",
                "m", "5m", "h", "2h", "d", "1d", "lower", "pt1m", "us", "3us", "ns", "7ns"));
        assertEquals(Duration.ofSeconds(30), p.getDuration("iso", null));
        assertEquals(Duration.ofSeconds(30), p.getDuration("s", null));
        assertEquals(Duration.ofMillis(500), p.getDuration("ms", null));
        assertEquals(Duration.ofMillis(250), p.getDuration("bare", null));
        assertEquals(Duration.ofMinutes(5), p.getDuration("m", null));
        assertEquals(Duration.ofHours(2), p.getDuration("h", null));
        assertEquals(Duration.ofDays(1), p.getDuration("d", null));
        assertEquals(Duration.ofMinutes(1), p.getDuration("lower", null));
        assertEquals(Duration.ofNanos(3000), p.getDuration("us", null));
        assertEquals(Duration.ofNanos(7), p.getDuration("ns", null));
    }

    @Test
    void listsAreTrimmedAndSkipEmptyElements() {
        SangamProperties p = SangamProperties.of(Map.of("l", " a, b ,,c "));
        assertEquals(List.of("a", "b", "c"), p.getList("l"));
    }

    @Test
    void theHostSourceWinsOverTheDefaults() {
        SangamProperties host = SangamProperties.of(Map.of("a", "host"));
        SangamProperties defaults = SangamProperties.of(Map.of("a", "default", "b", "default"));
        SangamProperties p = host.withDefaults(defaults);
        assertEquals("host", p.get("a"));
        assertEquals("default", p.get("b"));
        assertNull(p.get("c"));
        assertNull(SangamProperties.empty().get("a"));
    }

    @Test
    void moduleSwitchReadsOneKeyWithItsDefault() {
        assertEquals("sangam.modules.ledger.enabled", ModuleSwitch.key("ledger"));
        assertTrue(ModuleSwitch.enabled(SangamProperties.empty(), "ledger", true));
        assertFalse(ModuleSwitch.enabled(SangamProperties.empty(), "ledger", false));
        assertFalse(ModuleSwitch.enabled(SangamProperties.of(Map.of("sangam.modules.ledger.enabled", "false")),
                "ledger", true));
        assertThrows(IllegalArgumentException.class, () -> ModuleSwitch.key("Ledger"));
        assertThrows(IllegalArgumentException.class, () -> ModuleSwitch.key("a.b"));
        assertThrows(IllegalArgumentException.class, () -> ModuleSwitch.key(null));
    }
}
