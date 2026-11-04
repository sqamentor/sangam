package org.sangam.spike.flowable;

/** The adapter tests on MySQL 8.4 (Connector/J, utf8mb4_bin, READ-COMMITTED), Testcontainers through Databases. */
class FlowableAdapterMysqlTest extends AbstractContainerDbTest {

    @Override
    protected String database() {
        return "mysql";
    }
}
