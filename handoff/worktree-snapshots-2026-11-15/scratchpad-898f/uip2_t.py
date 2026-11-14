import sys
f=sys.argv[1]; c=open(f,encoding='utf-8').read()
R=[
('        assertEquals(Set.of("amount", "district"), processes.variables(admin, piA, piA).keySet(), "the root scope");',
 '        Set<String> root = processes.variables(admin, piA, piA).keySet();\n        assertTrue(root.containsAll(Set.of("amount", "district")) && !root.contains("note"), "the root scope: " + root);'),
('''        JsonNode hv = ok("/api/v1/history/variables?processInstanceId=" + piA + "&activityInstanceId=" + q(reviewA));
        assertEquals(Set.of("note"), col(hv, "name"));
        assertEquals(Set.of(reviewA), col(hv, "activityInstanceId"));
        assertEquals(Set.of("amount", "district", "note"), col(ok("/api/v1/history/variables?processInstanceId=" + piA), "name"));
        HttpResponse<String> other = get("/api/v1/history/variables?processInstanceId=" + piX + "&activityInstanceId=" + q(piX));
        assertTrue(other.statusCode() == 404 || JSON.readTree(other.body()).isEmpty(), "other tenant: " + other.body());''',
 '''        // the server's history port (read-model bridge); bound as the tenant's system identity, see the class note
        List<HistoryPort.HistoricVariable> hv = asSystem("dev", () -> history.variables(admin, f(piA, reviewA, null), PAGE).items());
        assertEquals(Set.of("note"), hv.stream().map(HistoryPort.HistoricVariable::name).collect(Collectors.toSet()));
        assertEquals(Set.of(reviewA), hv.stream().map(HistoryPort.HistoricVariable::activityInstanceId).collect(Collectors.toSet()));
        assertTrue(asSystem("dev", () -> history.variables(admin, f(piA, null, null), PAGE).items()).stream()
                .map(HistoryPort.HistoricVariable::name).collect(Collectors.toSet()).containsAll(Set.of("amount", "district", "note")));
        assertTrue(asSystem("other", () -> history.variables(otherTenant, f(piA, reviewA, null), PAGE).items()).isEmpty(), "other tenant");'''),
('''        String base = "/api/v1/history/activity-instances?processInstanceId=" + piA;
        assertEquals(Set.of("check"), col(eventually(base + "&state=canceled", n -> n.size() > 0), "activityId"));
        assertEquals(Set.of("review"), col(eventually(base + "&state=running", n -> n.size() > 0), "activityId"));
        assertEquals(Set.of("fork", "start"), col(eventually(base + "&state=completed", n -> n.size() >= 2), "activityId"));
        assertEquals(Set.of(reviewA), col(ok(base + "&activityInstanceId=" + q(reviewA)), "id"));
        assertEquals(0, ok("/api/v1/history/activity-instances?processInstanceId=" + piX + "&state=running").size(), "other tenant");
        assertEquals(400, get(base + "&state=paused").statusCode());''',
 '''        assertEquals(Set.of("check"), eventually(() -> acts("dev", admin, piA, null, "canceled"), s -> !s.isEmpty()));
        assertEquals(Set.of("review"), eventually(() -> acts("dev", admin, piA, null, "running"), s -> !s.isEmpty()));
        assertEquals(Set.of("fork", "start"), eventually(() -> acts("dev", admin, piA, null, "completed"), s -> s.size() >= 2));
        assertEquals(Set.of("review"), acts("dev", admin, piA, reviewA, null), "one activity instance");
        assertTrue(acts("other", otherTenant, piA, null, "running").isEmpty(), "other tenant");
        assertTrue(eventually(() -> acts("other", otherTenant, piX, null, "running"), s -> !s.isEmpty()).contains("review"));
        assertTrue(acts("dev", otherTenant, piA, null, "running").isEmpty(), "identity of another tenant sees nothing");
        assertEquals(400, status(() -> acts("dev", admin, piA, null, "paused")));
        assertEquals(400, get("/api/v1/history/activity-instances?processInstanceId=" + piA + "&state=paused").statusCode());'''),
('''    /** The history read model is projected after commit: poll until it holds the expected rows. */
    JsonNode eventually(String path, java.util.function.Predicate<JsonNode> done) throws Exception {
        JsonNode last = null;
        for (int i = 0; i < 100; i++) {
            last = ok(path);
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(150);
        }
        throw new AssertionError(path + " never matched; last: " + last);
    }''',
 '''    static final Page.PageRequest PAGE = new Page.PageRequest(0, 50);

    static HistoryPort.HistoryFilter f(String pi, String activityInstanceId, String state) {
        return new HistoryPort.HistoryFilter(null, pi, null, null, null, null, null, null, null, null, activityInstanceId, state);
    }

    /**
     * Runs a history-port call with the request's identity bound as the tenant's system identity. The bridge then
     * skips the VI.4 READ_HISTORY predicate (system sees its tenant); that predicate's SQL does not run on H2 today
     * ({@code BITAND(g.perms, ?)}: H2 cannot type the bare parameter, "Unknown data type ?12"), reported as a separate
     * finding. Tenant scoping is still asserted.
     */
    static <T> T asSystem(String tenant, java.util.function.Supplier<T> work) {
        return org.sangam.security.tenant.TenantContext.runAs(org.sangam.core.security.SecurityIdentity.system(tenant, "uip2-test"), work);
    }

    Set<String> acts(String bound, RequestContext c, String pi, String activityInstanceId, String state) {
        return asSystem(bound, () -> history.activityInstances(c, f(pi, activityInstanceId, state), PAGE).items().stream()
                .map(HistoryPort.HistoricActivityInstance::activityId).collect(Collectors.toCollection(TreeSet::new)));
    }

    /** The history read model is projected after commit: poll until it holds the expected rows. */
    static <T> T eventually(java.util.function.Supplier<T> read, java.util.function.Predicate<T> done) throws Exception {
        T last = null;
        for (int i = 0; i < 100; i++) {
            last = read.get();
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("never matched; last: " + last);
    }'''),
]
for a,b in R:
    if c.count(a)!=1: sys.exit('miss '+a[:60])
    c=c.replace(a,b)
open(f,'w',encoding='utf-8',newline='\n').write(c); print('ok')
