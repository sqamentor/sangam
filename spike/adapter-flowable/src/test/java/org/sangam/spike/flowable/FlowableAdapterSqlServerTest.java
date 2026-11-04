package org.sangam.spike.flowable;

/** The adapter tests on SQL Server 2022 (RCSI database), Testcontainers through Databases. */
class FlowableAdapterSqlServerTest extends AbstractContainerDbTest {

    @Override
    protected String database() {
        return "sqlserver";
    }
}
