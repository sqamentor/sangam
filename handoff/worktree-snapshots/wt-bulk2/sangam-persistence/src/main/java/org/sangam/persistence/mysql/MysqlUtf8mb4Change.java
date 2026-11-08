// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence.mysql;

import java.sql.SQLException;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Liquibase wrapper of {@link MysqlUtf8mb4Patch} (P-2), used by {@code sangam-engine-patches.xml}. */
public class MysqlUtf8mb4Change implements CustomTaskChange {

    private static final Logger LOG = LoggerFactory.getLogger(MysqlUtf8mb4Change.class);
    private MysqlUtf8mb4Patch.Result result;

    @Override
    public void execute(Database database) throws CustomChangeException {
        try {
            result = MysqlUtf8mb4Patch.apply(MysqlDatetimePrecisionChange.jdbc(database));
            if (!result.skippedWidestIndexBytes().isEmpty()) {
                LOG.warn("P-2: tables left in utf8mb3 because an index would exceed {} bytes: {}",
                        MysqlUtf8mb4Patch.MAX_INDEX_BYTES, result.skippedWidestIndexBytes());
            }
        } catch (SQLException e) {
            throw new CustomChangeException("P-2 MySQL utf8mb4 conversion failed", e);
        }
    }

    @Override
    public String getConfirmationMessage() {
        return result == null ? "P-2: not run"
                : "P-2: " + result.converted().size() + " ACT_* tables converted to utf8mb4, "
                + result.skippedWidestIndexBytes().size() + " skipped";
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
