// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.bpmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.sangam.core.engine.DocumentSlotNames;

/**
 * Owner decision 387 (1): the geo delegates name their result after the process variable {@code geoResultVar}, which a
 * case can set; neither that name nor a name derived from it (for example {@code <name>Path}) may be a declared DSC
 * document slot, so a geo step can never write one (only the document route sets a slot).
 */
class GeoResultVariableTest {

    @AfterEach
    void reset() {
        DocumentSlotNames.reset();
    }

    static DelegateExecution execution(Map<String, Object> vars) {
        return (DelegateExecution) Proxy.newProxyInstance(DelegateExecution.class.getClassLoader(),
                new Class<?>[] {DelegateExecution.class}, (proxy, m, args) -> {
                    if (m.getName().equals("getVariable") && args.length == 1) {
                        return vars.get((String) args[0]);
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void aResultNamedAfterASlotIsRefused() {
        DocumentSlotNames.install(() -> Set.of("sanctionDocumentId", "fencePath"));
        assertThrows(DocumentSlotNames.Refused.class, () -> GeoDelegates.resultVar(
                execution(Map.of("geoResultVar", "sanctionDocumentId")), "jurisdiction", "OrgUnit", "Path"));
        DocumentSlotNames.Refused e = assertThrows(DocumentSlotNames.Refused.class, () -> GeoDelegates.resultVar(
                execution(Map.of("geoResultVar", "fence")), "jurisdiction", "OrgUnit", "Path"));
        assertEquals(Set.of("fencePath"), e.variables(), "a derived name is checked too");
    }

    @Test
    void otherResultNamesAndTheDefaultPass() {
        DocumentSlotNames.install(() -> Set.of("sanctionDocumentId"));
        assertEquals("ward", GeoDelegates.resultVar(execution(Map.of("geoResultVar", "ward")), "jurisdiction", "Path"));
        assertEquals("jurisdiction", GeoDelegates.resultVar(execution(Map.of()), "jurisdiction", "Path"));
    }
}
