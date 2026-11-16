// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.sangam.core.engine.DocumentSlotNames;

/**
 * Owner decision 387 (1): a pack delegate that writes a variable whose name it does not fix in code (a connector
 * response key, a decision output, a step name read from the case: ConnectorCallDelegate, DecisionDelegate,
 * ExternalOperationDelegate) writes through {@link Vars#setDynamic}, which refuses a declared DSC document slot, as
 * does {@link Vars#setJsonDocument}: only the document route sets a slot.
 */
class DynamicVariableNamesTest {

    @AfterEach
    void reset() {
        DocumentSlotNames.reset();
    }

    static VariableScope scope(Map<String, Object> written) {
        return (VariableScope) Proxy.newProxyInstance(VariableScope.class.getClassLoader(),
                new Class<?>[] {VariableScope.class}, (proxy, m, args) -> {
                    if (m.getName().equals("setVariable") && args.length == 2) {
                        written.put((String) args[0], args[1]);
                        return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void aDynamicNameThatIsADeclaredSlotIsRefusedAndNothingIsWritten() {
        DocumentSlotNames.install(() -> Set.of("sanctionDocumentId"));
        Map<String, Object> written = new HashMap<>();
        VariableScope s = scope(written);
        // a connector call with prefix "sanction" answering documentId would write sanctionDocumentId
        DocumentSlotNames.Refused e = assertThrows(DocumentSlotNames.Refused.class,
                () -> Vars.setDynamic(s, "sanction" + "DocumentId", "forged"));
        assertEquals(Set.of("sanctionDocumentId"), e.variables());
        assertThrows(DocumentSlotNames.Refused.class, () -> Vars.setJsonDocument(s, "sanctionDocumentId", Map.of()));
        assertFalse(written.containsKey("sanctionDocumentId"));
    }

    @Test
    void otherDynamicNamesAreWritten() {
        DocumentSlotNames.install(() -> Set.of("sanctionDocumentId"));
        Map<String, Object> written = new HashMap<>();
        VariableScope s = scope(written);
        Vars.setDynamic(s, "op_utr", "U-1");
        Vars.setJsonDocument(s, "docRows", java.util.List.of());
        assertEquals("U-1", written.get("op_utr"));
        assertTrue(written.containsKey("docRows"));
    }
}
