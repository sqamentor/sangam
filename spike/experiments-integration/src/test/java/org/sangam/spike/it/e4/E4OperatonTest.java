package org.sangam.spike.it.e4;

import org.sangam.spike.it.support.EngineKind;

class E4OperatonTest extends AbstractE4IdempotencyTest {
    @Override
    protected EngineKind kind() {
        return EngineKind.OPERATON;
    }
}
