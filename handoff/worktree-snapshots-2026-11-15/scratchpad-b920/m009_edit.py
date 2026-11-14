import io, os
os.chdir(r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\sangam-compliance\src\main\java\org\sangam\compliance")


def edit(p, pairs):
    s = io.open(p, encoding='utf-8', newline='').read()
    for old, new in pairs:
        old = old.replace('\r\n', '\n'); new = new.replace('\r\n', '\n')
        if '\r\n' in s:
            old = old.replace('\n', '\r\n'); new = new.replace('\n', '\r\n')
        assert s.count(old) == 1, (p, old[:60], s.count(old))
        s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)


edit('ComplianceAudit.java', [
("""import org.sangam.core.layer.Hashes;
""", """import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Transactions;
"""),
(""" *   <li>The workflow root's own chain for events that belong to a case (erasure, purpose completion).</li>
 * </ul>
 * Payloads hold references and counts, not personal data; where a value is needed as proof, its SHA-256 is stored.
 */""", """ *   <li>The workflow root's own chain for events that belong to a case (erasure, purpose completion).</li>
 *   <li>{@code cmp:d:<32 hex>} — one <b>denial</b> chain per data principal (same hash as {@code cmp:p:}) for refusals
 *       that roll the caller back ({@link #denial}: purpose binding, child safeguards).</li>
 * </ul>
 * Payloads hold references and counts, not personal data; where a value is needed as proof, its SHA-256 is stored.
 *
 * <p><b>Denials (M-009, XII.4 #3).</b> A refusal throws, so the caller's transaction rolls back and an in-line event
 * would be lost. {@link #denial} therefore commits the event on its own, through {@code denialTx}. Two waits could hang
 * the caller there, and both are excluded:
 * <ul>
 *   <li><i>Pool wait (the SL-43 deadlock pattern).</i> The caller already holds a main-pool connection (engine command,
 *       REST transaction). {@code denialTx} must therefore be a <b>leaf</b> pool, separate from the one callers hold,
 *       whose connections are never held while another connection is requested (the server wires
 *       {@code sangam-compliance-audit} in {@code ComplianceWiring}, as {@code QuotaWiring} does for the quota audit).
 *       A wait for a leaf connection can never be part of a wait cycle.</li>
 *   <li><i>Row-lock wait ({@code ROOT_SEQ}).</i> {@code AuditChain.append} locks the chain's {@code ROOT_SEQ} row. If
 *       the separate transaction appended to a chain the caller had already appended to (the principal chain, after a
 *       consent in the same command), it would wait for the caller, which waits for it. The denial chain is written only
 *       by {@link #denial}, so only other short denial transactions ever hold its row, and they never wait for a caller
 *       (the {@code quota:<tenant>} chain of the quota audit follows the same rule).</li>
 * </ul>
 * With {@code denialTx == null} (tests, embedders without a separate pool) the event is appended in-line, on the
 * denial chain, and rolls back with the caller.
 */"""),
("""    private final AuditChain chain;

    public ComplianceAudit(AuditChain chain) {
        this.chain = chain;
    }
""", """    private final AuditChain chain;
    private final Transactions denialTx;

    public ComplianceAudit(AuditChain chain) {
        this(chain, null);
    }

    /** @param denialTx leaf transactions for {@link #denial} (see the class comment); null = in-line */
    public ComplianceAudit(AuditChain chain, Transactions denialTx) {
        this.chain = chain;
        this.denialTx = denialTx;
    }
"""),
("""    public static String principalChain(String tenantId, String principalRef) {
        return "cmp:p:" + Hashes.hex(Hashes.sha256(tenantId + "\\u0000" + principalRef)).substring(0, 32);
    }
""", """    public static String principalChain(String tenantId, String principalRef) {
        return "cmp:p:" + principalHash(tenantId, principalRef);
    }

    /** Per-principal denial chain id ({@link #denial}); never appended to in a caller's transaction. */
    public static String denialChain(String tenantId, String principalRef) {
        return "cmp:d:" + principalHash(tenantId, principalRef);
    }

    private static String principalHash(String tenantId, String principalRef) {
        return Hashes.hex(Hashes.sha256(tenantId + "\\u0000" + principalRef)).substring(0, 32);
    }
"""),
("""    public AuditChain.AuditEvent root(Connection c, String tenantId, String rootId, String eventType, Map<String, ?> payload) {
        return chain.append(c, tenantId, rootId, eventType, payload);
    }
""", """    public AuditChain.AuditEvent root(Connection c, String tenantId, String rootId, String eventType, Map<String, ?> payload) {
        return chain.append(c, tenantId, rootId, eventType, payload);
    }

    /**
     * A refusal that the caller follows with a throw: appended to the principal's {@linkplain #denialChain denial
     * chain} and committed on its own through {@code denialTx}, so it survives the caller's rollback (M-009). In-line on
     * {@code c} when no {@code denialTx} is configured.
     */
    public AuditChain.AuditEvent denial(Connection c, String tenantId, String principalRef, String eventType,
                                        Map<String, ?> payload) {
        String chainId = denialChain(tenantId, principalRef);
        if (denialTx == null) {
            return chain.append(c, tenantId, chainId, eventType, payload);
        }
        return denialTx.inTransaction(leaf -> chain.append(leaf, tenantId, chainId, eventType, payload));
    }
"""),
])

edit('consent/PurposeBindingGuard.java', [
(""" * child safeguards run last. A refusal writes {@code CMP_PURPOSE_BINDING_DENIED} on the subject's chain.
""", """ * child safeguards run last. A refusal writes {@code CMP_PURPOSE_BINDING_DENIED} on the subject's denial chain
 * ({@link ComplianceAudit#denial}), committed on its own so it survives the rollback that the refusal causes (M-009).
"""),
("""        audit.principal(c, tenantId, principalRef, ComplianceAudit.PURPOSE_BINDING_DENIED, Sql.kv("purpose", purposeCode, "reason", reason));""",
 """        audit.denial(c, tenantId, principalRef, ComplianceAudit.PURPOSE_BINDING_DENIED, Sql.kv("purpose", purposeCode, "reason", reason));"""),
])

edit('consent/ChildDataGuard.java', [
(""" * closed), so age is established first.
 */""", """ * closed), so age is established first. A refusal writes {@code CMP_CHILD_PROCESSING_BLOCKED} on the subject's denial
 * chain ({@link ComplianceAudit#denial}), committed on its own so it survives the rollback that the refusal causes (M-009).
 */"""),
("""        audit.principal(c, tenantId, principalRef, ComplianceAudit.CHILD_PROCESSING_BLOCKED, Sql.kv("purpose", purpose.code(),""",
 """        audit.denial(c, tenantId, principalRef, ComplianceAudit.CHILD_PROCESSING_BLOCKED, Sql.kv("purpose", purpose.code(),"""),
])

edit('SangamCompliance.java', [
("""    public SangamCompliance(Dialect dialect, Transactions tx, ComplianceConfig.Provider config, Ports p) {
        this.dialect = dialect;
        this.tx = tx;
        this.config = config;
        AuditChain chain = new AuditChain(dialect);
        this.audit = new ComplianceAudit(chain);
""", """    public SangamCompliance(Dialect dialect, Transactions tx, ComplianceConfig.Provider config, Ports p) {
        this(dialect, tx, config, p, null);
    }

    /**
     * @param denialTx leaf transactions in which denial audits commit on their own (M-009; {@link ComplianceAudit}
     *                 class comment: a pool separate from the one callers hold); null = in-line in the caller's
     *                 transaction, so a denial audit rolls back with the refused operation
     */
    public SangamCompliance(Dialect dialect, Transactions tx, ComplianceConfig.Provider config, Ports p,
                            Transactions denialTx) {
        this.dialect = dialect;
        this.tx = tx;
        this.config = config;
        AuditChain chain = new AuditChain(dialect);
        this.audit = new ComplianceAudit(chain, denialTx);
"""),
])
print("ok")
