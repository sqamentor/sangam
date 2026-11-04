package org.sangam.spike.operaton;

/**
 * The adapter tests on MySQL 8.4 ({@code mysql:8.4}, Testcontainers, utf8mb4_bin, READ-COMMITTED), with the
 * datetime(3) schema patch P-1 applied after the engine created its tables (see {@link AbstractContainerDbTest}).
 * Driver: MySQL Connector/J by default, MariaDB Connector/J with {@code -Dspike.mysql.driver=mariadb} (LIC-4).
 */
class OperatonAdapterMysqlTest extends AbstractContainerDbTest {

    @Override
    protected String database() {
        return "mysql";
    }
}
