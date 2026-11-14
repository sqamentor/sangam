import java.sql.*;
public class Probe {
  public static void main(String[] a) throws Exception {
    for (String mode : new String[]{"Oracle","PostgreSQL"}) {
      try (Connection c = DriverManager.getConnection("jdbc:h2:mem:p" + mode + ";MODE=" + mode)) {
        Statement s = c.createStatement();
        s.execute("CREATE TABLE T(A VARCHAR(10) PRIMARY KEY, B TEXT, C BYTEA, D TIMESTAMP WITH TIME ZONE)");
        try_(s, mode, "INSERT INTO T(A) VALUES ('x') ON CONFLICT DO NOTHING");
        try_(s, mode, "INSERT INTO T(A) VALUES ('x') ON CONFLICT DO NOTHING");
        try_(s, mode, "SELECT SYSTIMESTAMP AS n FROM DUAL");
        try_(s, mode, "SELECT CURRENT_TIMESTAMP AS n");
        try_(s, mode, "SELECT A FROM T WHERE A='x' FOR UPDATE WAIT 2");
        try { s.execute("INSERT INTO T(A) VALUES ('x')"); } catch (SQLException e) { System.out.println(mode + " dup: state=" + e.getSQLState() + " code=" + e.getErrorCode()); }
        try { s.execute("INSERT INTO T(A,B) VALUES ('y','')"); ResultSet r = s.executeQuery("SELECT B IS NULL FROM T WHERE A='y'"); r.next(); System.out.println(mode + " ''->null: " + r.getBoolean(1)); } catch (SQLException e) { System.out.println(mode + " empty: " + e.getMessage()); }
      }
    }
  }
  static void try_(Statement s, String m, String sql) { try { s.execute(sql); System.out.println(m + " OK   " + sql); } catch (SQLException e) { System.out.println(m + " FAIL " + sql + " -> " + e.getMessage().split("\n")[0]); } }
}
