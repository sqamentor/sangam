import pathlib, re, sys
R = pathlib.Path(sys.argv[1])
def find(name):
    return [p for p in R.glob('sangam-*/src/main/java/**/' + name)][0]
def tail(name, code):
    p = find(name); s = p.read_text(encoding='utf-8'); i = s.rstrip().rfind('}')
    if code.strip().splitlines()[0].strip() in s: return
    p.write_text(s[:i] + code + '}\n', encoding='utf-8')
def rep(path, a, b):
    p = path if isinstance(path, pathlib.Path) else find(path); s = p.read_text(encoding='utf-8')
    if b in s: return
    assert a in s, (str(p), a)
    p.write_text(s.replace(a, b, 1), encoding='utf-8')

tail('AgentRuntime.java', '''
    /** One logged step, for the run detail screen. */
    public record StepView(int seq, String kind, Map<String, Object> payload, java.time.Instant at) {
    }

    /** An approval with the time it was requested. */
    public record ApprovalView(Approval approval, java.time.Instant requestedAt) {
    }

    public List<StepView> stepViews(String runId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT step_no,kind,payload_json,created_at FROM AI_AGENT_STEP WHERE run_id=? ORDER BY step_no",
                rs -> new StepView(rs.getInt(1), rs.getString(2), Json.readMap(rs.getString(3)), rs.getTimestamp(4).toInstant()), runId));
    }

    public Optional<java.time.Instant> startedAt(String runId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT created_at FROM AI_AGENT_RUN WHERE run_id=?", rs -> rs.getTimestamp(1).toInstant(), runId));
    }

    /** Approvals of a tenant, newest first; {@code stateOrNull} filters by PENDING, APPROVED or REJECTED. */
    public List<ApprovalView> approvals(String tenantId, String stateOrNull) {
        String sql = "SELECT approval_id,tenant_id,run_id,tool_call_id,tool_name,args_json,args_hash,state,requested_by,decided_by,reason,created_at "
                + "FROM AI_AGENT_APPROVAL WHERE tenant_id=?" + (stateOrNull == null ? "" : " AND state=?") + " ORDER BY created_at DESC";
        Object[] params = stateOrNull == null ? new Object[]{tenantId} : new Object[]{tenantId, stateOrNull};
        return tx.inTransaction(c -> Jdbc.query(c, sql, rs -> new ApprovalView(mapApproval(rs), rs.getTimestamp(12).toInstant()), params));
    }
''')
tail('KnowledgeBaseService.java', '''
    /** The active version of a knowledge base, else its newest. */
    public record KbInfo(String key, int version, String status, long chunks, java.time.Instant updatedAt) {
    }

    public List<KbInfo> list(String tenantId) {
        List<KbInfo> all = tx.inTransaction(c -> Jdbc.query(c, "SELECT v.kb_key,v.version,v.state,v.created_at,(SELECT COUNT(*) FROM AI_KB_CHUNK k "
                + "WHERE k.tenant_id=v.tenant_id AND k.kb_key=v.kb_key AND k.version=v.version) FROM AI_KB_VERSION v WHERE v.tenant_id=? ORDER BY v.kb_key, v.version DESC",
                rs -> new KbInfo(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getLong(5), rs.getTimestamp(4).toInstant()), tenantId));
        java.util.Map<String, KbInfo> out = new java.util.LinkedHashMap<>();
        for (KbInfo k : all) {
            KbInfo seen = out.get(k.key());
            if (seen == null || ("ACTIVE".equals(k.status()) && !"ACTIVE".equals(seen.status()))) {
                out.put(k.key(), k);
            }
        }
        return List.copyOf(out.values());
    }

    /** Stops retrieval from a knowledge base. Returns false when it had no active version. */
    public boolean retire(String tenantId, String kb) {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE AI_KB_VERSION SET state='RETIRED' WHERE tenant_id=? AND kb_key=? AND state='ACTIVE'", tenantId, kb)) > 0;
    }
''')
tail('DraftService.java', '''
    /** A draft without its content, for lists. */
    public record DraftSummary(String draftId, Kind kind, State state, String createdBy, java.time.Instant createdAt) {
    }

    /** Newest first. {@code ownerOrNull} limits the list to one requester. */
    public List<DraftSummary> list(String tenantId, String ownerOrNull, String stateOrNull) {
        StringBuilder sql = new StringBuilder("SELECT draft_id,kind,state,created_by,created_at FROM AI_DRAFT WHERE tenant_id=?");
        List<Object> params = new java.util.ArrayList<>(List.of(tenantId));
        if (ownerOrNull != null) {
            sql.append(" AND created_by=?");
            params.add(ownerOrNull);
        }
        if (stateOrNull != null) {
            sql.append(" AND state=?");
            params.add(stateOrNull);
        }
        sql.append(" ORDER BY created_at DESC");
        return tx.inTransaction(c -> Jdbc.query(c, sql.toString(), rs -> new DraftSummary(rs.getString(1), Kind.valueOf(rs.getString(2)), State.valueOf(rs.getString(3)),
                rs.getString(4), rs.getTimestamp(5).toInstant()), params.toArray()));
    }
''')
tail('DocumentClassifier.java', '''
    /** A stored classification. {@code status}: REVIEWED once corrected, else PENDING_REVIEW for human routing, else AUTO. */
    public record Row(String id, String subjectRef, String label, BigDecimal confidence, String status) {
    }

    public List<Row> list(String tenantId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT id,source_ref,label,confidence,route,corrected_label,corrected_by FROM AI_CLASSIFICATION WHERE tenant_id=? ORDER BY created_at DESC",
                rs -> new Row(rs.getString(1), rs.getString(2), rs.getString(6) != null ? rs.getString(6) : rs.getString(3), rs.getBigDecimal(4),
                        rs.getString(7) != null ? "REVIEWED" : Route.HUMAN.name().equals(rs.getString(5)) ? "PENDING_REVIEW" : "AUTO"), tenantId));
    }
''')
tail('OptInRegistry.java', '''
    /** A registered mapping. The evidence stays server-side. */
    public record OptIn(String channel, String externalId, String principalId, String state, java.time.Instant updatedAt) {
    }

    public java.util.List<OptIn> list(String tenantId, String channelOrNull) {
        String sql = "SELECT channel,external_id,principal_id,state,updated_at FROM CH_OPT_IN WHERE tenant_id=?" + (channelOrNull == null ? "" : " AND channel=?") + " ORDER BY channel, external_id";
        Object[] params = channelOrNull == null ? new Object[]{tenantId} : new Object[]{tenantId, channelOrNull};
        return tx.inTransaction(c -> Jdbc.query(c, sql, rs -> new OptIn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()), params));
    }
''')
tail('ConversationService.java', '''
    /** Conversations the actor participates in, newest first; {@code caseRefOrNull} narrows to one case. */
    public List<Conversation> list(String tenantId, String actor, String caseRefOrNull, int limit) {
        String sql = "SELECT k.id,k.tenant_id,k.title,k.case_ref,k.retention_class FROM CH_CONVERSATION k JOIN CH_PARTICIPANT p ON p.conversation_id=k.id "
                + "WHERE k.tenant_id=? AND p.principal_id=?" + (caseRefOrNull == null ? "" : " AND k.case_ref=?") + " ORDER BY k.created_at DESC";
        Object[] params = caseRefOrNull == null ? new Object[]{tenantId, actor} : new Object[]{tenantId, actor, caseRefOrNull};
        List<Conversation> all = tx.inTransaction(c -> Jdbc.query(c, sql, rs -> new Conversation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), params));
        return all.size() > limit ? List.copyOf(all.subList(0, limit)) : all;
    }
''')
tail('ActionModels.java', '''
    /** All active actions of a tenant, for the administrator's list; no per-object permission filter. */
    public List<Action> listActive(String tenantId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT document FROM MOD_ACTION WHERE tenant_id=? AND active=1 ORDER BY action_key", rs -> Json.MAPPER.convertValue(Json.readMap(rs.getString(1)), Action.class), tenantId));
    }

    /** Deactivates an action; past runs stay. Returns false when no active version existed. */
    public boolean retire(String tenantId, String key, String actor, String reason) {
        return tx.inTransaction(c -> {
            if (Jdbc.update(c, "UPDATE MOD_ACTION SET active=0 WHERE tenant_id=? AND action_key=? AND active=1", tenantId, key) == 0) {
                return false;
            }
            audit.append(c, tenantId, "actions:" + tenantId, "ACTION_RETIRED", Map.of("key", key, "actor", actor, "reason", reason));
            return true;
        });
    }
''')
rep('ActionModels.java', 'a.scopeType().toLowerCase(Locale.ROOT).equals(scopeType.toLowerCase(Locale.ROOT))', 'a.scopeType().equalsIgnoreCase(scopeType)')
rep('ElementTemplates.java', 'public enum Kind { ELEMENT, PALETTE, FORM_COMPONENT }', 'public enum Kind { ELEMENT, PALETTE, FORM_COMPONENT, PROCESS, DMN }')
tail('SequenceAdmin.java', '''
    public List<String> tableNames() {
        return tables.keySet().stream().sorted().toList();
    }
''')
tail('SearchIndexMaintenance.java', '''
    /** A tenant's rebuild jobs, newest first, with the time of their last update. */
    public record JobRow(Job job, java.time.Instant updatedAt) {
    }

    public List<JobRow> jobs(String tenantId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT generation,state,indexed,expected,message,updated_at FROM OPS_INDEX_JOB WHERE tenant_id=? ORDER BY generation DESC",
                rs -> new JobRow(new Job(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5)), rs.getTimestamp(6).toInstant()), tenantId));
    }
''')
# Service mocks: runtime rules and a listing
sm = find('ServiceMocks.java')
rep(sm, 'private final List<Rule> rules;', 'private final List<Rule> rules;\n    private volatile java.util.function.Consumer<String> onNewService = s -> { };')
rep(sm, 'this.rules = List.copyOf(rules);', 'this.rules = new java.util.concurrent.CopyOnWriteArrayList<>(rules);')
tail('ServiceMocks.java', '''
    public boolean globalEnabled() {
        return globalEnabled;
    }

    public boolean serviceEnabled(String service) {
        return !disabledServices.contains(service);
    }

    public List<Rule> rules() {
        return List.copyOf(rules);
    }

    /** Called with a rule's service when a rule is added, so the binding can wrap that connector. */
    public void onNewService(java.util.function.Consumer<String> hook) {
        onNewService = hook;
    }

    /** Adds a rule for this process's lifetime. Refused on the production profile. */
    public void addRule(Rule rule) {
        if (productionProfile) {
            throw new IllegalStateException("MOCKS_FORBIDDEN_ON_PRODUCTION");
        }
        rules.add(rule);
        onNewService.accept(rule.service());
    }
''')
mw = find('ModellerWiring.java')
s = mw.read_text(encoding='utf-8')
old = s[s.index('    private static void applyMocks(ConnectorRegistry registry, Environment env) {'):s.index('    private static void applyTokenExchange(')]
new = '''    private static void applyMocks(ConnectorRegistry registry, Environment env, ServiceMocks mocks) {
        if (mocks == null) {
            String ignored = env.getProperty("sangam.modeller.mocks.file");
            if (ignored != null) {
                LOG.warn("sangam.modeller.mocks.file is ignored on a production profile (R80)");
            }
            return;
        }
        java.util.Set<String> wrapped = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.function.Consumer<String> wrap = id -> {
            if (wrapped.add(id)) {
                registry.find(null, id).ifPresentOrElse(c -> registry.register(mocks.wrap(c)), () -> {
                    wrapped.remove(id);
                    LOG.warn("mock rule names unknown connector {}", id);
                });
            }
        };
        mocks.rules().stream().map(ServiceMocks.Rule::service).distinct().forEach(wrap);
        mocks.onNewService(wrap);
    }

    /** Null on a production profile, so the mock routes answer "not installed" there (R80). */
    @Bean
    public ServiceMocks sangamServiceMocks(Environment env) {
        if (Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.contains("prod"))) {
            return null;
        }
        String file = env.getProperty("sangam.modeller.mocks.file");
        try {
            ServiceMocks.Rule[] rules = file == null ? new ServiceMocks.Rule[0] : Json.MAPPER.readValue(Files.readString(Path.of(file)), ServiceMocks.Rule[].class);
            return new ServiceMocks(false, List.of(rules));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("sangam.modeller.mocks.file could not be read: " + e.getMessage(), e);
        }
    }

'''
s = s.replace(old, new).replace('applyMocks(registry, env);', 'applyMocks(registry, env, mocks.getIfAvailable());').replace('ObjectProvider<SecretResolver> secrets, Environment env) {\n        return () -> {\n            ConnectorRegistry registry = connectors.getIfAvailable();', 'ObjectProvider<SecretResolver> secrets, Environment env, ObjectProvider<ServiceMocks> mocks) {\n        return () -> {\n            ConnectorRegistry registry = connectors.getIfAvailable();')
mw.write_text(s, encoding='utf-8')
print('patched; ModellerWiring needs holder param')
