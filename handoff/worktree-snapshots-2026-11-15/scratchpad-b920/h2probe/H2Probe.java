import java.sql.*;
public class H2Probe {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:p;MODE=PostgreSQL"); Statement s = c.createStatement()) {
      for (String q : new String[]{"SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'LOCK_TIMEOUT'"}) {
        try (ResultSet r = s.executeQuery(q)) { System.out.println("default: " + (r.next() ? r.getString(1) : "<no row>")); }
      }
      s.execute("SET LOCK_TIMEOUT 2000");
      try (ResultSet r = s.executeQuery("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'LOCK_TIMEOUT'")) { System.out.println("after SET 2000: " + (r.next() ? r.getString(1) : "<no row>")); }
      c.setAutoCommit(false);
      s.execute("SET LOCK_TIMEOUT 1234");
      c.rollback();
      try (ResultSet r = s.executeQuery("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'LOCK_TIMEOUT'")) { System.out.println("after SET 1234 + rollback (session-scoped?): " + (r.next() ? r.getString(1) : "<no row>")); }
    }
  }
}
