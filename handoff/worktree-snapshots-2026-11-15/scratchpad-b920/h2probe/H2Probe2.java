import java.sql.*;
public class H2Probe2 {
  static String q(Statement s) throws SQLException { try (ResultSet r = s.executeQuery("SELECT LOCK_TIMEOUT()")) { r.next(); return r.getString(1); } }
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:p2;MODE=PostgreSQL"); Statement s = c.createStatement()) {
      System.out.println("default: " + q(s));
      c.setAutoCommit(false);
      s.execute("SET LOCK_TIMEOUT 2000");
      System.out.println("in tx after SET 2000: " + q(s));
      c.rollback();
      System.out.println("after rollback: " + q(s) + "  (unchanged => session-scoped, leaks)");
      s.execute("SET LOCK_TIMEOUT 1000");
      c.commit();
      c.setAutoCommit(true);
      System.out.println("after restore SET 1000 + commit: " + q(s));
    }
  }
}
