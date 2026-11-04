package org.sangam.spike.it.t08;

import org.sangam.spike.it.support.EngineKind;

/** T08 on Operaton 2.2.0 (the relay runs in Operaton's engine transaction). */
class T08OperatonTest extends AbstractT08PoisonOutboxTest {
    @Override
    protected EngineKind kind() {
        return EngineKind.OPERATON;
    }
}