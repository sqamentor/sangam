package org.sangam.spike.operaton;

/** The adapter tests on Oracle 23 Free ({@code gvenzl/oracle-free:23-slim-faststart}, Testcontainers). */
class OperatonAdapterOracleTest extends AbstractContainerDbTest {

    @Override
    protected String database() {
        return "oracle";
    }
}
