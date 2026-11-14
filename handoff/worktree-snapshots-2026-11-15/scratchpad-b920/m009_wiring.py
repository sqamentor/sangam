import io, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.chdir(r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam")


def edit(p, pairs):
    s = io.open(p, encoding='utf-8', newline='').read()
    for old, new in pairs:
        old = old.replace('\r\n', '\n'); new = new.replace('\r\n', '\n')
        if '\r\n' in s:
            old = old.replace('\n', '\r\n'); new = new.replace('\n', '\r\n')
        assert s.count(old) == 1, (p, old[:60], s.count(old))
        s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)


edit('sangam-server/src/main/java/org/sangam/server/config/ComplianceWiring.java', [
("""import org.springframework.beans.factory.ObjectProvider;
""", """import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
"""),
("""import org.springframework.core.env.Environment;
""", """import org.springframework.core.env.Environment;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
"""),
(""" *       transactions when sangam-engine is disabled) with {@link ComplianceConfigProvider} (jurisdiction packs from
 *       {@code sangam.compliance.jurisdictions}, no default), then {@code ComplianceRuntime.install(...)};</li>
""", """ *       transactions when sangam-engine is disabled) with {@link ComplianceConfigProvider} (jurisdiction packs from
 *       {@code sangam.compliance.jurisdictions}, no default), then {@code ComplianceRuntime.install(...)};</li>
 *   <li>M-009: denial audits (purpose binding, child safeguards) commit on their own through the leaf pool
 *       {@code sangam-compliance-audit} ({@link ComplianceAuditPool}), so they survive the refused operation's
 *       rollback without taking a second connection from the pool the caller holds (SL-43);</li>
"""),
("""    @Bean
    public SangamCompliance sangamCompliance(TxSupport tx, ObjectProvider<EngineAdapter> engineAdapter,
            ComplianceConfigProvider config, ProcessEngine engine, ObjectProvider<ErasureService> erasure,
            ObjectProvider<OutboxWriter> outbox, ObjectProvider<ConnectorInvoker> invoker,
            ObjectProvider<PublisherRegistry> publishers, Environment env) {
""", """    /**
     * M-009 / SL-43: the leaf pool for denial audits. A denial is recorded while the caller holds a connection of the
     * main pool (engine command, REST transaction) and must commit on its own, because the refusal rolls the caller
     * back. A second connection from the main pool there is the SL-43 pool-locking deadlock once every slot is held by a
     * caller waiting for its second one. A connection of this pool is never held while another connection is
     * requested (the only work on it is one {@code AuditChain.append} on a denial-only chain), so waits for it cannot
     * form a cycle. Same database and settings as the main pool; {@code sangam.compliance.denial-audit.pool.maximum-size}
     * (default 2) connections.
     */
    static final class ComplianceAuditPool implements DisposableBean {
        final HikariDataSource pool;

        ComplianceAuditPool(HikariDataSource pool) {
            this.pool = pool;
        }

        @Override
        public void destroy() {
            pool.close();
        }
    }

    public static final String AUDIT_POOL_NAME = "sangam-compliance-audit";

    @Bean
    public ComplianceAuditPool sangamComplianceAuditPool(DataSource ds, Environment env) {
        return new ComplianceAuditPool(leafPool(ds,
                env.getProperty("sangam.compliance.denial-audit.pool.maximum-size", Integer.class, 2)));
    }

    /** A pool on the main pool's database and settings, separate from it (never the pool the callers hold). */
    static HikariDataSource leafPool(DataSource ds, int maximumSize) {
        HikariConfig cfg = new HikariConfig();
        try {
            if (!ds.isWrapperFor(HikariDataSource.class)) {
                throw new IllegalStateException("M-009 compliance denial audits need a HikariCP main pool to derive their"
                        + " own pool from (found " + ds.getClass().getName() + "); or set"
                        + " sangam.modules.compliance.enabled=false");
            }
            ds.unwrap(HikariDataSource.class).copyStateTo(cfg);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot derive the compliance audit pool from the main DataSource", e);
        }
        cfg.setPoolName(AUDIT_POOL_NAME);
        int size = Math.max(1, maximumSize);
        cfg.setMaximumPoolSize(size);
        cfg.setMinimumIdle(Math.min(1, size));
        cfg.setAutoCommit(true);
        return new HikariDataSource(cfg);
    }

    @Bean
    public SangamCompliance sangamCompliance(TxSupport tx, ObjectProvider<EngineAdapter> engineAdapter,
            ComplianceConfigProvider config, ProcessEngine engine, ObjectProvider<ErasureService> erasure,
            ObjectProvider<OutboxWriter> outbox, ObjectProvider<ConnectorInvoker> invoker,
            ObjectProvider<PublisherRegistry> publishers, Environment env, ComplianceAuditPool auditPool) {
"""),
("""        SangamCompliance compliance = new SangamCompliance(tx.dialect(), ctx, config, ports);
""", """        SangamCompliance compliance = new SangamCompliance(tx.dialect(), ctx, config, ports,
                Transactions.of(auditPool.pool));
"""),
])
print("ok")
