// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence.mysql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

/** Liquibase wrapper of {@link MysqlDatetimePrecisionPatch} (P-1), used by {@code sangam-engine-patches.xml}. */
public class MysqlDatetimePrecisionChange implements CustomTaskChange {

    private int altered;

    @Override
    public void execute(Database database) throws CustomChangeException {
        try {
            List<MysqlDatetimePrecisionPatch.Column> cols = MysqlDatetimePrecisionPatch.apply(jdbc(database));
            altered = cols.size();
        } catch (SQLException e) {
            throw new CustomChangeException("P-1 MySQL datetime precision patch failed", e);
        }
    }

    static Connection jdbc(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection jc)) {
            throw new CustomChangeException("P-1/P-2 need a JDBC connection");
        }
        return jc.getUnderlyingConnection();
    }

    @Override
    public String getConfirmationMessage() {
        return "P-1: " + altered + " ACT_* time columns set to millisecond precision";
    }

    @Override
    public void setUp() throws SetupException {
        // no parameters
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
        // not used
    }

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
