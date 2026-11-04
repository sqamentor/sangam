package org.sangam.spike.flowable;

/** The adapter tests on PostgreSQL 17 (postgres:17), Testcontainers through Databases. */
class FlowableAdapterPostgresTest extends AbstractContainerDbTest {

    @Override
    protected String database() {
        return "postgres";
    }
}
