package org.sangam.spike.it.e5;

import org.sangam.spike.it.support.EngineKind;

class E5FlowableTest extends AbstractE5OutboxInboxTest {
    @Override
    protected EngineKind kind() {
        return EngineKind.FLOWABLE;
    }
}
