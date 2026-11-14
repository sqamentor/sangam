import re, os
os.chdir(r"D:\GITHUB  Project\Custom WorkFlow\sangam")

def sub(p, old, new):
    s = open(p, encoding='utf-8').read()
    if old not in s:
        raise SystemExit("MISSING in " + p + ": " + old[:80])
    s = s.replace(old, new, 1)
    open(p, 'w', encoding='utf-8', newline='\n').write(s)

kb = 'sangam-ai/src/main/java/org/sangam/ai/rag/KnowledgeBaseService.java'
sub(kb, 'import org.sangam.core.layer.AuthorizationPolicy;\n', '')
sub(kb, 'import org.sangam.core.layer.Transactions;\n', 'import org.sangam.core.layer.Transactions;\nimport org.sangam.core.security.SecurityIdentity;\n\nimport java.sql.Connection;\n')
sub(kb, " * or a count. Ranking", " * or a count. The predicate comes from a {@link Visibility} binding (sangam-server: the sangam-security {@code PolicyCompiler},\n * whose denies win at every scope, A-13). Without a binding every retrieval is empty (fail closed). Ranking")
sub(kb, '''    private final java.util.function.Function<AuthorizationPolicy.Principal, SqlPredicate> visibility;

    public KnowledgeBaseService(Dialect dialect, Transactions tx, LlmPort llm, int chunkChars, int overlapChars, int maxCandidates) {
        this(dialect, tx, llm, chunkChars, overlapChars, maxCandidates, p -> new AuthorizationPolicy(dialect, "document",
                new AuthorizationPolicy.ResourceColumns("tenant_id", "document_id")).visible(p, AuthorizationPolicy.Action.READ, "k"));
    }

    /** {@code visibility} yields a predicate over alias {@code k}. */
    public KnowledgeBaseService(Dialect dialect, Transactions tx, LlmPort llm, int chunkChars, int overlapChars, int maxCandidates,
                                java.util.function.Function<AuthorizationPolicy.Principal, SqlPredicate> visibility) {''', '''    private final Visibility visibility;

    /** READ predicate over {@code AI_KB_CHUNK} alias {@code k}, computed in the query's own transaction. */
    @FunctionalInterface
    public interface Visibility {
        SqlPredicate visible(Connection c, SecurityIdentity who);
    }

    /** No binding: fail closed, every retrieval is empty. */
    public KnowledgeBaseService(Dialect dialect, Transactions tx, LlmPort llm, int chunkChars, int overlapChars, int maxCandidates) {
        this(dialect, tx, llm, chunkChars, overlapChars, maxCandidates, null);
    }

    /** {@code visibility} yields a predicate over alias {@code k}; null means fail closed. */
    public KnowledgeBaseService(Dialect dialect, Transactions tx, LlmPort llm, int chunkChars, int overlapChars, int maxCandidates,
                                Visibility visibility) {''')
sub(kb, '''    public List<Hit> retrieve(AuthorizationPolicy.Principal principal, String kb, String query, int k) {
        float[] q = llm.embed(principal.tenantId(), query);
        record Row(String doc, String ver, int seq, String text, byte[] vec) {
        }
        SqlPredicate visible = visibility.apply(principal);
        List<Object> params = new ArrayList<>(List.of(principal.tenantId(), kb, principal.tenantId(), kb));
        params.addAll(visible.params());
        String sql = "SELECT k.document_id,k.doc_version,k.seq,k.text_value,k.embedding FROM AI_KB_CHUNK k WHERE k.tenant_id=? AND k.kb_key=? AND k.version="
                + "(SELECT v.version FROM AI_KB_VERSION v WHERE v.tenant_id=? AND v.kb_key=? AND v.state='ACTIVE') AND (" + visible.sql() + ") ORDER BY k.id";
        List<Row> rows = tx.inTransaction(c -> Jdbc.query(c, sql, rs -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getBytes(5)),
                params.toArray()));''', '''    public List<Hit> retrieve(SecurityIdentity who, String kb, String query, int k) {
        if (visibility == null) {
            return List.of();
        }
        float[] q = llm.embed(who.tenantId(), query);
        record Row(String doc, String ver, int seq, String text, byte[] vec) {
        }
        List<Row> rows = tx.inTransaction(c -> {
            SqlPredicate visible = visibility.visible(c, who);
            List<Object> params = new ArrayList<>(List.of(who.tenantId(), kb, who.tenantId(), kb));
            params.addAll(visible.params());
            String sql = "SELECT k.document_id,k.doc_version,k.seq,k.text_value,k.embedding FROM AI_KB_CHUNK k WHERE k.tenant_id=? AND k.kb_key=? AND k.version="
                    + "(SELECT v.version FROM AI_KB_VERSION v WHERE v.tenant_id=? AND v.kb_key=? AND v.state='ACTIVE') AND (" + visible.sql() + ") ORDER BY k.id";
            return Jdbc.query(c, sql, rs -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getBytes(5)), params.toArray());
        });''')

ar = 'sangam-ai/src/main/java/org/sangam/ai/agent/AgentRuntime.java'
SI = 'org.sangam.core.security.SecurityIdentity'
sub(ar, 'private final java.util.function.BiFunction<String, String, Set<String>> groupsOf;', 'private final java.util.function.BiFunction<String, String, ' + SI + '> identityOf;')
sub(ar, 'this(dialect, tx, audit, llm, tools, knowledgeOrNull, leaseDuration, (tenant, principal) -> Set.of());', 'this(dialect, tx, audit, llm, tools, knowledgeOrNull, leaseDuration, null);')
sub(ar, "/** {@code groupsOf} gives the principal's current group memberships (tenant, principal), read at each retrieval. */",
    "/**\n     * {@code identityOf} resolves the run principal's full current identity (groups, roles, seats) at each retrieval, so every\n     * deny applies. Null means no retrieval (fail closed): a partial identity could miss a deny.\n     */")
sub(ar, '''java.util.function.BiFunction<String, String, Set<String>> groupsOf) {
        this.groupsOf = groupsOf;''', 'java.util.function.BiFunction<String, String, ' + SI + '''> identityOf) {
        this.identityOf = identityOf;''')
sub(ar, '''        var hits = knowledge.retrieve(new AuthorizationPolicy.Principal(run.tenantId(), run.principalId(), groupsOf.apply(run.tenantId(), run.principalId())),
                run.definition().knowledgeBase(), run.input(), 5);''', '''        var who = identityOf == null ? null : identityOf.apply(run.tenantId(), run.principalId());
        List<KnowledgeBaseService.Hit> hits = who == null || !who.tenantId().equals(run.tenantId()) ? List.of()
                : knowledge.retrieve(who, run.definition().knowledgeBase(), run.input(), 5);''')
s = open(ar, encoding='utf-8').read()
if s.count('AuthorizationPolicy') == 1:
    sub(ar, 'import org.sangam.core.layer.AuthorizationPolicy;\n', '')

gs = 'sangam-ops/src/main/java/org/sangam/ops/search/GlobalSearch.java'
s = open(gs, encoding='utf-8').read()
s = s.replace('import org.sangam.core.layer.AuthorizationPolicy;\n', '').replace('import org.sangam.core.layer.Dialect;\n', '')
s = s.replace('import org.sangam.core.layer.Transactions;\n', 'import org.sangam.core.layer.Transactions;\nimport org.sangam.core.security.SecurityIdentity;\n\nimport java.sql.Connection;\n')
s = s.replace('import java.util.function.Function;\n', 'import java.util.Optional;\n')
s = s.replace(' * Client-side section matching belongs to the UI.', ' * Client-side section matching belongs to the UI. Visibility is bound by the integrator to the sangam-security\n * {@code PolicyCompiler} (deny wins at every scope, A-13). There is no default predicate in this module.')
s = s.replace('''     * @param visibility predicate over alias {@code r}
     */
    public record Source(String type, String from, String idColumn, String titleColumn, List<String> matchColumns, Function<AuthorizationPolicy.Principal, SqlPredicate> visibility) {
    }''', '''     * @param visibility READ predicate over alias {@code r}, computed in the query's transaction
     */
    public record Source(String type, String from, String idColumn, String titleColumn, List<String> matchColumns, Visibility visibility) {
        public Source {
            java.util.Objects.requireNonNull(visibility, "visibility");
        }
    }

    @FunctionalInterface
    public interface Visibility {
        SqlPredicate visible(Connection c, SecurityIdentity who);
    }''')
a = s.index("    /** The engine's task")
b = s.index('    public Map<String, TypeResult> search(')
s = s[:a] + '''    /**
     * The engine's task, historic instance and definition tables. {@code visibility} receives the sangam-security resource type
     * ({@code TASK}, {@code HISTORIC_PROCESS_INSTANCE}, {@code PROCESS_DEFINITION}) and the alias.
     */
    public static List<Source> engineSources(java.util.function.BiFunction<String, String, Visibility> visibility) {
        return List.of(
                new Source("task", "ACT_RU_TASK r", "r.ID_", "r.NAME_", List.of("r.NAME_", "r.DESCRIPTION_"), visibility.apply("TASK", "r")),
                new Source("process-instance", "ACT_HI_PROCINST r", "r.ID_", "r.BUSINESS_KEY_", List.of("r.BUSINESS_KEY_", "r.PROC_DEF_KEY_"),
                        visibility.apply("HISTORIC_PROCESS_INSTANCE", "r")),
                new Source("process-definition", "ACT_RE_PROCDEF r", "r.ID_", "r.NAME_", List.of("r.NAME_", "r.KEY_"), visibility.apply("PROCESS_DEFINITION", "r")));
    }

    /** The visibility of one source type, for other queries over the same table (outlier analysis). */
    public Optional<Visibility> visibility(String type) {
        return sources.stream().filter(x -> x.type().equals(type)).findFirst().map(Source::visibility);
    }

''' + s[b:]
s = s.replace('public Map<String, TypeResult> search(AuthorizationPolicy.Principal principal, String text, int capPerType) {', 'public Map<String, TypeResult> search(SecurityIdentity who, String text, int capPerType) {')
s = s.replace('out.put(s.type(), one(s, s.visibility().apply(principal), needle, capPerType));', 'out.put(s.type(), one(s, who, needle, capPerType));')
s = s.replace('private TypeResult one(Source s, SqlPredicate visible, String needle, int cap) {', 'private TypeResult one(Source s, SecurityIdentity who, String needle, int cap) {')
open(gs, 'w', encoding='utf-8', newline='\n').write(s)
i = s.index('private TypeResult one(')
print(s[i:i+900])
