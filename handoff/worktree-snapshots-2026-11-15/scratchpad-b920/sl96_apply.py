"""SL-96 targeted edits on the integration tree (CRLF preserved). Each replacement must match exactly the expected count."""
import pathlib
import re
import sys

ROOT = pathlib.Path(r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
                    r"\sangam-server\src\test\java\org\sangam\server\acceptance")
C = ROOT / "contracts"


def load(p):
    b = p.read_bytes()
    return b.decode("utf-8")


def save_crlf(p, text):
    text = text.replace("\r\n", "\n").replace("\n", "\r\n")
    p.write_bytes(text.encode("utf-8"))


def sub(text, old, new, count, name):
    n = text.count(old)
    if n != count:
        sys.exit(f"{name}: expected {count} of {old!r}, found {n}")
    return text.replace(old, new)


# 1. files written whole by the agent: normalise to CRLF
for f in ["IsolationPlan.java", "IsolationPlanTest.java", "IsolatedProductTables.java", "LayerSpecSupport.java"]:
    save_crlf(C / f, load(C / f))

# 2. AcceptanceDatabase: jdbcUrl(database) and the lane administrator
p = ROOT / "AcceptanceDatabase.java"
t = load(p).replace("\r\n", "\n")
t = sub(t, """    /** The JDBC URL the server uses (SQL Server: the {@code sangam} database with RCSI on). */
    public static String jdbcUrl() {
        JdbcDatabaseContainer<?> c = container();
        return switch (name()) {
            case "sqlserver" -> c.getJdbcUrl() + ";databaseName=sangam;encrypt=true;trustServerCertificate=true;"
                    + "sendStringParametersAsUnicode=true";
            case "mysql" -> "jdbc:mariadb://" + c.getHost() + ":" + c.getMappedPort(3306) + "/sangam"
                    + "?useServerPrepStmts=true&sessionVariables=transaction_isolation='READ-COMMITTED'"
                    + "&connectionCollation=utf8mb4_bin&permitMysqlScheme";
            default -> c.getJdbcUrl();
        };
    }

    public static String username() {
        return container().getUsername();
    }

    public static String password() {
        return container().getPassword();
    }
""", """    /** The JDBC URL the server uses (SQL Server: the {@code sangam} database with RCSI on). */
    public static String jdbcUrl() {
        return jdbcUrl("sangam");
    }

    /**
     * The server's JDBC URL with another database of the same server (SQL Server and MySQL, where a database is the
     * namespace unqualified names resolve in); same driver options. PostgreSQL and Oracle take only {@code sangam}: their
     * isolation unit is a schema/user of the one database.
     */
    public static String jdbcUrl(String database) {
        JdbcDatabaseContainer<?> c = container();
        return switch (name()) {
            case "sqlserver" -> c.getJdbcUrl() + ";databaseName=" + database + ";encrypt=true;trustServerCertificate=true;"
                    + "sendStringParametersAsUnicode=true";
            case "mysql" -> "jdbc:mariadb://" + c.getHost() + ":" + c.getMappedPort(3306) + "/" + database
                    + "?useServerPrepStmts=true&sessionVariables=transaction_isolation='READ-COMMITTED'"
                    + "&connectionCollation=utf8mb4_bin&permitMysqlScheme";
            default -> {
                if (!"sangam".equals(database)) {
                    throw new IllegalArgumentException(name() + ": one database per container, isolate with a schema/user");
                }
                yield c.getJdbcUrl();
            }
        };
    }

    public static String username() {
        return container().getUsername();
    }

    public static String password() {
        return container().getPassword();
    }

    /**
     * The lane's administrator account, for test-harness set-up only (never the server): PostgreSQL's image user is the
     * superuser and SQL Server's is {@code sa} (the container user); MySQL {@code root} and Oracle {@code system} get the
     * container password (Testcontainers sets MYSQL_ROOT_PASSWORD and ORACLE_PASSWORD to it).
     */
    public static String adminUsername() {
        return switch (name()) {
            case "mysql" -> "root";
            case "oracle" -> "system";
            default -> username();
        };
    }

    public static String adminPassword() {
        return password();
    }
""", 1, "AcceptanceDatabase")
save_crlf(p, t)

# 3. the eight specs: PG -> DIALECT, and their PostgreSQL-only SQL
ITS = ["AuditChainIT", "AuthorizationPolicyIT", "IdempotencyStoreIT", "InboxIT", "OperationLedgerIT",
       "OutboxRelayIT", "TombstonesIT", "WebhookRelayIT"]
EXPECTED_PG = {"AuthorizationPolicyIT": 13, "AuditChainIT": 3, "IdempotencyStoreIT": 3, "InboxIT": 4,
               "WebhookRelayIT": 5, "OutboxRelayIT": 5, "OperationLedgerIT": 3, "TombstonesIT": 4}
for it in ITS:
    p = C / (it + ".java")
    t = load(p).replace("\r\n", "\n")
    n = len(re.findall(r"\bPG\b", t))
    if n != EXPECTED_PG[it]:
        sys.exit(f"{it}: expected {EXPECTED_PG[it]} PG, found {n}")
    t = re.sub(r"\bPG\b", "DIALECT", t)
    if it == "AuthorizationPolicyIT":
        t = sub(t, """        try (Connection c = DS.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS ACT_RU_TASK_SIM (ID_ VARCHAR(64) PRIMARY KEY, TENANT_ID_ VARCHAR(64) NOT NULL, NAME_ VARCHAR(255))");
            st.execute("TRUNCATE ACT_RU_TASK_SIM");
""", """        createTableIfMissing("ACT_RU_TASK_SIM",
                "CREATE TABLE ACT_RU_TASK_SIM (ID_ {str64} PRIMARY KEY, TENANT_ID_ {str64} NOT NULL, NAME_ {str})");
        try (Connection c = DS.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE ACT_RU_TASK_SIM");
""", 1, it)
        t = sub(t, """                        + " ORDER BY r.ID_ LIMIT 7 OFFSET " + offset, pred);""",
                """                        + " ORDER BY r.ID_" + page(7, offset), pred);""", 1, it)
    if it == "IdempotencyStoreIT":
        t = sub(t, """        try (Connection c = DS.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS BUSINESS_START (id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64))");
        }
""", """        createTableIfMissing("BUSINESS_START", "CREATE TABLE BUSINESS_START (id {str64} PRIMARY KEY, tenant_id {str64})");
""", 1, it)
    if it == "WebhookRelayIT":
        t = sub(t, """"UPDATE OUTBOX_DELIVERY SET next_attempt_at=CURRENT_TIMESTAMP WHERE outbox_id=?\"""",
                """"UPDATE OUTBOX_DELIVERY SET next_attempt_at=" + DIALECT.now() + " WHERE outbox_id=?\"""", 2, it)
    save_crlf(p, t)
print("ok")
