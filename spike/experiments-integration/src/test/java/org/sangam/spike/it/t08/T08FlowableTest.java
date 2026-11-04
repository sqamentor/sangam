package org.sangam.spike.it.t08;

import org.sangam.spike.it.support.EngineKind;

/** T08 on Flowable 8.0.0 (the relay runs in Flowable's engine transaction). */
class T08FlowableTest extends AbstractT08PoisonOutboxTest {
    @Override
    protected EngineKind kind() {
        return EngineKind.FLOWABLE;
    }
}
