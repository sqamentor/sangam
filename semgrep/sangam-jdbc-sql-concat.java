// SPDX-License-Identifier: Apache-2.0
// semgrep --test cases for sangam-jdbc-sql-concat.yaml (not compiled; outside the Maven reactor)
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

class SqlConcatCases {

    static final String TABLE = "sangam_task";

    ResultSet concatenatedValue(Statement stmt, String tenantId) throws Exception {
        // ruleid: sangam-jdbc-sql-concat
        return stmt.executeQuery("SELECT * FROM sangam_task WHERE tenant_id = '" + tenantId + "'");
    }

    PreparedStatement concatenatedThroughALocal(Connection c, String name) throws Exception {
        // ruleid: sangam-jdbc-sql-concat
        String sql = "SELECT id FROM sangam_party WHERE name = '" + name + "'";
        return c.prepareStatement(sql);
    }

    int springUpdate(org.springframework.jdbc.core.JdbcTemplate jdbc, String status) {
        // ruleid: sangam-jdbc-sql-concat
        return jdbc.update("UPDATE sangam_task SET status = " + status);
    }

    PreparedStatement boundParameter(Connection c, String tenantId) throws Exception {
        // ok: sangam-jdbc-sql-concat
        PreparedStatement ps = c.prepareStatement("SELECT * FROM sangam_task WHERE tenant_id = ?");
        ps.setString(1, tenantId);
        return ps;
    }

    PreparedStatement constantTableName(Connection c) throws Exception {
        // ok: sangam-jdbc-sql-concat
        return c.prepareStatement("SELECT * FROM " + TABLE + " WHERE id = ?");
    }

    PreparedStatement qualifiedConstant(Connection c) throws Exception {
        // ok: sangam-jdbc-sql-concat
        return c.prepareStatement("SELECT * FROM " + SqlConcatCases.TABLE);
    }

    void notJdbc(StringBuilder out, String name) {
        // ok: sangam-jdbc-sql-concat
        out.append("hello " + name);
    }
}
