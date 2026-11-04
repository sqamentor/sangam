package org.sangam.spike.flowable;

/** The adapter tests on Oracle 23 Free (gvenzl/oracle-free:23-slim-faststart), Testcontainers through Databases. */
class FlowableAdapterOracleTest extends AbstractContainerDbTest {

    @Override
    protected String database() {
        return "oracle";
    }
}
