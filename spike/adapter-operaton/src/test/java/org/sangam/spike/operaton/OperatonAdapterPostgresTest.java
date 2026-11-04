package org.sangam.spike.operaton;

/** The adapter tests on PostgreSQL 17 ({@code postgres:17}, Testcontainers through {@code Databases}). */
class OperatonAdapterPostgresTest extends AbstractContainerDbTest {

    @Override
    protected String database() {
        return "postgres";
    }
}
