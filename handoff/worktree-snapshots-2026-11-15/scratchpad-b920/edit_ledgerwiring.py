import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()


def rep(a, b):
    global s
    assert s.count(a) == 1, a
    s = s.replace(a, b)


rep('''import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Outbox;
import org.sangam.integration.connector.ConnectorInvoker;''', '''import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.integration.connector.ConnectorInvoker;''')
rep('''import org.sangam.ledger.LedgerControlStore;
import org.sangam.ledger.LedgerPorts;''', '''import org.sangam.ledger.LedgerControlStore;
import org.sangam.ledger.LedgerJournal;
import org.sangam.ledger.LedgerPorts;''')
rep('''import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;''', '''import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.support.TxSupport;''')
rep(''' *       decision first, then the shared one); {@code AuditLink} to the class-B audit chain ({@code LEDGER_POSTED});
 *       {@code AccountingSink} to the general-ledger connector through the outbox ({@value LedgerGlPublisher#DESTINATION},
 *       only when {@code sangam.ledger.gl-connector} is set; the {@code LDG_ACCOUNTING} table always holds the
 *       entries). {@code ExternalLedgerPort} (SHADOW mode) is not bound: no FMIS connector exists yet.</li>''',
    ''' *       decision first, then the shared one). {@code ExternalLedgerPort} (SHADOW mode) is not bound: no FMIS connector
 *       exists yet.</li>
 *   <li>RM-04: the store's {@link LedgerJournal} writes each posting's class-B {@code LEDGER_POSTED} audit row (through
 *       the SL-133 pending audit, drained to the chain) and its general-ledger outbox rows
 *       ({@value LedgerGlPublisher#DESTINATION}) in the posting's own transaction, atomically with {@code LDG_ENTRY}.</li>
 *   <li>Start-up refusals: RM-05, a GL connector with no outbox writer, relay publisher registry or connector invoker
 *       (integration off) would drop every accounting entry; owner decision 126, in production the GL export is
 *       required wherever the ledger module is enabled; and the ledger needs the pending-audit drainer of the
 *       integration module for its audit rows.</li>''')
rep('''    @Bean
    public JdbcLedgerStore sangamLedgerStore(TxSupport tx, Environment env) {
        if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {''', '''    /**
     * The start-up rules of the GL export (RM-05, owner decision 126); package-private for the guard test.
     *
     * @return the GL connector id, or null when none is configured (allowed outside production only)
     */
    static String requireGlExport(String glConnector, boolean production, boolean outboxWriter, boolean publishers,
                                  boolean invoker) {
        boolean set = glConnector != null && !glConnector.isBlank();
        if (!set) {
            if (production) {
                throw new IllegalStateException("sangam.ledger.gl-connector must be set in production while the ledger"
                        + " module is enabled (owner decision 126: the GL export is required where the ledger is enabled);"
                        + " set it, or disable the ledger with sangam.modules.ledger.enabled=false");
            }
            return null;
        }
        if (!outboxWriter || !publishers || !invoker) {
            String missing = !outboxWriter ? "OutboxWriter" : !publishers ? "PublisherRegistry" : "ConnectorInvoker";
            throw new IllegalStateException("sangam.ledger.gl-connector=" + glConnector + " is set but the integration"
                    + " module provides no " + missing + ": every accounting entry would be dropped (RM-05); enable"
                    + " sangam.modules.integration.enabled or unset the GL connector");
        }
        return glConnector.trim();
    }

    /** The ledger's audit rows need the SL-133 pending-audit drainer (integration module). */
    static PendingAuditDrainer requirePendingAudit(PendingAuditDrainer drainer) {
        if (drainer == null) {
            throw new IllegalStateException("the ledger writes LEDGER_POSTED through the pending audit (SL-133), which"
                    + " the integration module provides: enable sangam.modules.integration.enabled or disable the ledger"
                    + " with sangam.modules.ledger.enabled=false");
        }
        return drainer;
    }

    /** The store's posting journal: audit always, GL rows when a GL connector is configured. */
    static LedgerJournal journal(PendingAuditDrainer drainer, String gl, OutboxWriter writer) {
        return new LedgerJournal(requirePendingAudit(drainer), gl == null ? null
                : new LedgerJournal.GlExport(LedgerGlPublisher.DESTINATION, LedgerGlPublisher.EVENT_TYPE,
                        reference -> ComplianceConnectors.id64("ledger:", reference), writer::append));
    }

    @Bean
    public JdbcLedgerStore sangamLedgerStore(TxSupport tx, Environment env, ObjectProvider<PendingAuditDrainer> drainer,
            ObjectProvider<OutboxWriter> outbox, ObjectProvider<PublisherRegistry> publishers,
            ObjectProvider<ConnectorInvoker> invoker) {
        OutboxWriter writer = outbox.getIfAvailable();
        String gl = requireGlExport(env.getProperty("sangam.ledger.gl-connector"), ProductionEnvironment.isProduction(env),
                writer != null, publishers.getIfAvailable() != null, invoker.getIfAvailable() != null);
        LedgerJournal journal = journal(drainer.getIfAvailable(), gl, writer);
        if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {''')
rep('''        return new JdbcLedgerStore(tx.dataSource(), tx.dialect());
    }''', '''        return new JdbcLedgerStore(tx.dataSource(), tx.dialect(), journal);
    }''')
rep('''    public LedgerService sangamLedgerService(JdbcLedgerStore store, LedgerControlStore control,
            LedgerPorts.DecisionPort decisions, ObjectProvider<ProfileResolver> profiles, ObjectProvider<AuditPort> audit,
            ObjectProvider<OutboxWriter> outbox, TxSupport tx, Environment env) {''', '''    public LedgerService sangamLedgerService(JdbcLedgerStore store, LedgerControlStore control,
            LedgerPorts.DecisionPort decisions, ObjectProvider<ProfileResolver> profiles, Environment env) {''')
i = s.index('        service.withAudit((tenant, reference, operation, auditRef, actor, summary) -> {')
j = s.index('        return service;\n    }', i)
s = s[:i] + '''        // RM-04: no after-commit AuditLink or AccountingSink: the store's journal writes both with the posting
''' + s[j:]
rep('''            String gl = env.getProperty("sangam.ledger.gl-connector");
            PublisherRegistry p = publishers.getIfAvailable();
            ConnectorInvoker inv = invoker.getIfAvailable();
            if (gl != null && !gl.isBlank() && p != null && inv != null) {
                p.register(''', '''            PublisherRegistry p = publishers.getIfAvailable();
            ConnectorInvoker inv = invoker.getIfAvailable();
            String gl = requireGlExport(env.getProperty("sangam.ledger.gl-connector"),
                    ProductionEnvironment.isProduction(env), true, p != null, inv != null);
            if (gl != null) {
                p.register(''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("ok")
