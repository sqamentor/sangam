// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.sangam.packs.core.Vars;

/** {@code ConnectorCallDelegate} inputs: {@code variable>parameter} sends a variable under the connector's name. */
class ConnectorInputRenameTest {

    static VariableScope scope(Map<String, Object> vars) {
        return (VariableScope) Proxy.newProxyInstance(VariableScope.class.getClassLoader(), new Class<?>[] {VariableScope.class},
                (p, m, a) -> "getVariable".equals(m.getName()) ? vars.get((String) a[0]) : null);
    }

    @Test
    void renamesToTheContractNameAndKeepsPlainNames() {
        VariableScope s = scope(Map.of("billType", "FVC", "docId", "RJ_FVC", "amount", 1000L));
        assertEquals(Map.of("formNumber", "FVC", "amount", 1000L), Vars.payloadOf(s, List.of("billType>formNumber", "amount")));
        assertEquals(Map.of("typeId", "RJ_FVC"), Vars.payloadOf(s, List.of(" docId > typeId ")));
        assertThrows(IllegalArgumentException.class, () -> Vars.payloadOf(s, List.of("billType>")));
    }
}
