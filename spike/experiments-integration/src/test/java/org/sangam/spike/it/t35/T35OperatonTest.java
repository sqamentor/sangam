package org.sangam.spike.it.t35;

import org.sangam.spike.it.support.EngineKind;

/** T35 on Operaton 2.2.0, PostgreSQL only. */
class T35OperatonTest extends AbstractT35RestoreTest {
    @Override
    protected EngineKind kind() {
        return EngineKind.OPERATON;
    }
}