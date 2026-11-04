package org.sangam.spike.it.e5;

import org.sangam.spike.it.support.EngineKind;

class E5OperatonTest extends AbstractE5OutboxInboxTest {
    @Override
    protected EngineKind kind() {
        return EngineKind.OPERATON;
    }
}
