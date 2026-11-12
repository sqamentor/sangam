// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.sdk.model.TypedValue;

class ScriptWorkerMainTest {

    @Test
    void languagesAreParsedOnceEach() {
        assertEquals(List.of(Language.JAVASCRIPT, Language.PYTHON), ScriptWorkerMain.languages("js, python ,javascript"));
        assertThrows(IllegalArgumentException.class, () -> ScriptWorkerMain.languages("groovy"));
        assertThrows(IllegalArgumentException.class, () -> ScriptWorkerMain.languages(" , "));
    }

    @Test
    void theLeaseOutlivesTheWallLimitAndTheKillGrace() {
        assertEquals(Duration.ofSeconds(30), ScriptWorkerMain.lockDuration(ScriptLimits.DEFAULTS));
        ScriptLimits slow = new ScriptLimits(60, Duration.ofSeconds(60), 256L << 20, 16, 0, 1, 0, 1);
        assertEquals(Duration.ofSeconds(124), ScriptWorkerMain.lockDuration(slow));
    }

    @Test
    void decimalsGoBackTyped() {
        Map<String, Object> out = ScriptTaskHandler.typed(Map.of("amount", new BigDecimal("12.10"), "n", 3));
        assertEquals(TypedValue.decimal(new BigDecimal("12.10")), out.get("amount"));
        assertEquals(3, out.get("n"));
    }
}
