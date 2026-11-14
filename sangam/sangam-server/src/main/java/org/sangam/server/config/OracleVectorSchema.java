// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

import javax.sql.DataSource;

import org.sangam.persistence.SangamSchema;

/**
 * Owner decision 247: the optional Oracle AI Vector Search column {@code AI_KB_CHUNK.embedding_vec} is applied only
 * when {@code sangam.ai.kb.vector-index=oracle}; it lives in its own changelog
 * ({@link SangamSchema#ORACLE_VECTOR_CHANGELOG}, Liquibase context {@link SangamSchema#ORACLE_VECTOR_CONTEXT}) that the
 * master never includes, so without that setting the context is never added. With it, start-up is refused unless the
 * database is Oracle AI Database 23ai or later (JDBC {@link DatabaseMetaData#getDatabaseMajorVersion()} at least
 * {@value #MIN_ORACLE_MAJOR}); Oracle 19c and 21c have no {@code VECTOR} type.
 */
final class OracleVectorSchema {

    static final String PROPERTY = "sangam.ai.kb.vector-index";
    static final int MIN_ORACLE_MAJOR = 23;

    private OracleVectorSchema() {
    }

    /** True only for {@code oracle}, read the way {@code VectorIndex.named} reads it (trimmed, default portable). */
    static boolean wanted(String vectorIndex) {
        return vectorIndex != null && "oracle".equals(vectorIndex.trim());
    }

    /** Refuses start-up unless {@code dataSource} is Oracle AI Database 23ai or later. */
    static void requireOracle23ai(DataSource dataSource) {
        String product;
        int major;
        String version;
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            product = md.getDatabaseProductName();
            major = md.getDatabaseMajorVersion();
            version = md.getDatabaseProductVersion();
        } catch (SQLException e) {
            throw new IllegalStateException(PROPERTY + "=oracle: cannot read the database version (decision 247)", e);
        }
        if (product == null || !product.toLowerCase(Locale.ROOT).startsWith("oracle")) {
            throw new IllegalStateException(PROPERTY + "=oracle needs Oracle AI Database 23ai or later; this database is "
                    + product + " (decision 247)");
        }
        if (major < MIN_ORACLE_MAJOR) {
            throw new IllegalStateException(PROPERTY + "=oracle needs Oracle AI Database 23ai or later (VECTOR type);"
                    + " this database is Oracle major version " + major + " (" + version + "), decision 247");
        }
    }

    /** Applies the oracle-vector changelog; call after the product changelog, after {@link #requireOracle23ai}. */
    static void apply(DataSource dataSource) {
        SangamSchema.applyOracleVector(dataSource);
    }
}
