package org.sangam.spike.operaton;

/** The adapter tests on SQL Server 2022 ({@code mcr.microsoft.com/mssql/server:2022-latest}, Testcontainers). */
class OperatonAdapterSqlServerTest extends AbstractContainerDbTest {

    @Override
    protected String database() {
        return "sqlserver";
    }
}
