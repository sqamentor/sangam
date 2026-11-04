package org.sangam.spike.flowable;

import java.util.UUID;

/** Fast run on in-memory H2 (one database per class). */
class H2FlowableAdapterTest extends AbstractFlowableAdapterTest {

    @Override
    protected FlowableEngineAdapter newAdapter() {
        String url = "jdbc:h2:mem:flowable-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        return FlowableEngineAdapter.create(url, "sa", "");
    }
}
