package org.sangam.spike.operaton;

import java.util.UUID;

/** Fast run of the adapter tests on in-memory H2. */
class OperatonAdapterH2Test extends AbstractOperatonAdapterTest {

    @Override
    protected OperatonEngineAdapter newAdapter() {
        String url = "jdbc:h2:mem:operaton-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        return new OperatonEngineAdapter(url, "sa", "");
    }
}
