import java.sql.*;
public class I { public static void main(String[] a) throws Exception {
  try (Connection c = DriverManager.getConnection(a[0], a.length>1?a[1]:"sa", a.length>2?a[2]:"");
       PreparedStatement p = c.prepareStatement("select CURRENT_TIMESTAMP, (CURRENT_TIMESTAMP + (CAST(? AS BIGINT) * INTERVAL '0.001' SECOND))")) {
    p.setLong(1, 1500L); try (ResultSet r = p.executeQuery()) { r.next(); System.out.println(r.getString(1) + " | " + r.getString(2)); }
  } } }
