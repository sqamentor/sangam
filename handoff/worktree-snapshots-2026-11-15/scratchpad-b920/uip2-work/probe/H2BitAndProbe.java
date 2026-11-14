import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Probe: how H2 types a bind parameter inside a bitwise AND (scratch only, not product code). */
public class H2BitAndProbe {
    public static void main(String[] args) throws Exception {
        for (String mode : new String[]{";MODE=PostgreSQL", ""}) {
            try (Connection c = DriverManager.getConnection("jdbc:h2:mem:p" + System.nanoTime() + mode)) {
                c.createStatement().execute("CREATE TABLE g (perms BIGINT, iperms INTEGER)");
                c.createStatement().execute("INSERT INTO g VALUES (6, 6)");
                String[] sqls = {
                    "SELECT COUNT(*) FROM g WHERE BITAND(g.perms, ?) <> 0",
                    "SELECT COUNT(*) FROM g WHERE BITAND(g.perms, CAST(? AS BIGINT)) <> 0",
                    "SELECT COUNT(*) FROM g WHERE BITAND(g.iperms, CAST(? AS BIGINT)) <> 0",
                    "SELECT COUNT(*) FROM g WHERE (g.perms & ?) <> 0",
                    "SELECT COUNT(*) FROM g WHERE (g.perms & CAST(? AS BIGINT)) <> 0",
                };
                for (String sql : sqls) {
                    for (Object v : new Object[]{4L, 1L, 4}) {
                        String r;
                        try (PreparedStatement ps = c.prepareStatement(sql)) {
                            ps.setObject(1, v);
                            try (ResultSet rs = ps.executeQuery()) {
                                rs.next();
                                r = "rows=" + rs.getLong(1);
                            }
                        } catch (Exception e) {
                            r = "ERROR " + e.getMessage().split("\n")[0];
                        }
                        System.out.println("[" + (mode.isEmpty() ? "REGULAR" : "PG") + "] " + sql + "  bind=" + v
                                + " (" + v.getClass().getSimpleName() + ") -> " + r);
                    }
                }
            }
        }
    }
}
