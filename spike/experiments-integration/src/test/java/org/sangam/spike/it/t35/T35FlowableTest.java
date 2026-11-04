package org.sangam.spike.it.t35;

import org.sangam.spike.it.support.EngineKind;

/** T35 on Flowable 8.0.0 (both engines are evaluated), PostgreSQL only. */
class T35FlowableTest extends AbstractT35RestoreTest {
    @Override
    protected EngineKind kind() {
        return EngineKind.FLOWABLE;
    }
}
