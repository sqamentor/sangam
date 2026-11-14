"""Targeted edits, server and acceptance side (approve-401, T35, D-REFERRAL, AT-1). Each must match once."""
import sys
sys.argv = sys.argv[:2]
exec(open(__file__.replace('_server.py', '.py'), encoding='utf-8').read().split("\nedit(", 1)[0])  # edit() only

S = 'sangam-server/src/main/java/org/sangam/server/config/SecurityConfig.java'
edit(S, '''                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health",''',
'''                .authorizeHttpRequests(a -> a
                        // the container's error dispatch renders the failure of a request that was already
                        // authorised; re-checking it answered 401 and hid the real status (approve-401)
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health",''')

T = 'sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java'
edit(T, '''                "--sangam.security.bootstrap.enabled=true",
                "--sangam.security.bootstrap.admin-groups=sangam-admin",
''', '''                "--sangam.security.bootstrap.enabled=true",
                "--sangam.security.bootstrap.admin-groups=sangam-admin",
                // the server serves only tenants that exist (TenantAdminService.requireActive); without a dev token
                // the bootstrap creates 'dev', not this run's tenant, so every call was 401 TENANT_UNAVAILABLE
                "--sangam.security.bootstrap.tenants=" + tenant,
''')

P = 'sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java'
edit(P, '''            if (inst.path("ended").asBoolean()) {
                return;''', '''            if (inst.path("ended").asBoolean()) {
                assertThat(biz.path("reachesWaitState").asBoolean())
                        .as("the scenario expects a wait state, the run ended (e.g. rejected): " + inst).isFalse();
                return;''')
edit(P, '''                assertThat(again.path("state").asText()).as(again.toString()).isNotEqualTo("CANCELLED");
                return;''', '''                assertThat(again.path("state").asText()).as(again.toString()).isNotEqualTo("CANCELLED");
                assertThat(again.path("ended").asBoolean()).as("the run must wait, not end: " + again).isFalse();
                // the scenario's connector outcomes (e.g. the bill type resolved by the catalogue): proves the
                // process data reached the connectors instead of running on defaults
                JsonNode vars = t.call("GET", "/api/v1/process-instances/" + pid + "/variables", null);
                biz.path("expectVariables").fields().forEachRemaining(e -> assertThat(vars.path(e.getKey()))
                        .as(e.getKey() + " in " + vars).isEqualTo(e.getValue()));
                return;''')

E = 'sangam-server/src/test/java/org/sangam/server/acceptance/gov/E17bDomainIT.java'
edit(E, '''            eq(ctx, "New district court complex", law.variables().get("subject"), "in-mapping");
            adapter.completeTask(fin.id(), m("concurred", true, "remarks", "Within budget head 4059"));
            adapter.completeTask(law.id(), m("concurred", false, "remarks", "Land title to be verified"));
            eq(ctx, "Land title to be verified", var(p, "lawRemarks"), "out-mapping with renaming");
            eq(ctx, 1, tasksIn(p, "ref_prepare").size(), "returned for revision");
            completeTask(p, "ref_prepare", m("revision", 1));
            for (TaskView t : forGroups("dept-works", "concurrence-officer")) {
                adapter.completeTask(t.id(), t.variables().containsKey("amount") ? m("concurred", true, "remarks", "Concurred")
                        : m("concurred", true, "remarks", "Vetted"));
            }
            completeTask(p, "ref_approve", m("approvedBy", "Secretary PWD"));
            yes(ctx, ended(p), "referral ended");
            eq(ctx, 4, subProcessInstances(p).size(), "two referral rounds x two departments");''',
'''            eq(ctx, "New district court complex", law.variables().get("subject"), "in-mapping");
            eq(ctx, 182_000_000L, fin.variables().get("amount"), "in-mapping of the amount to Finance");
            eq(ctx, "PWD/BLDG/2026/45", fin.variables().get("fileNo"), "in-mapping of the file number");
            adapter.completeTask(fin.id(), m("concurred", true, "remarks", "Within budget head 4059"));
            eq(ctx, 0, tasksIn(p, "ref_prepare").size(), "the join waits for Law");
            adapter.completeTask(law.id(), m("concurred", false, "remarks", "Land title to be verified"));
            eq(ctx, "Land title to be verified", var(p, "lawRemarks"), "out-mapping with renaming");
            eq(ctx, "Within budget head 4059", var(p, "finRemarks"), "out-mapping with renaming (Finance)");
            eq(ctx, true, var(p, "finConcurred"), "Finance concurred");
            eq(ctx, false, var(p, "lawConcurred"), "Law did not concur");
            eq(ctx, 0, tasksIn(p, "ref_approve").size(), "no approval without both concurrences");
            eq(ctx, 1, tasksIn(p, "ref_prepare").size(), "returned for revision");
            completeTask(p, "ref_prepare", m("revision", 1));
            List<String> round2 = subProcessInstances(p);
            eq(ctx, 4, round2.size(), "second referral round started two more called instances");
            eq(ctx, Set.of(tn("dept-works")), new HashSet<>(round2.stream().map(this::instanceTenant).toList()),
                    "second-round callees run in the caller's tenant (SL-13)");
            eq(ctx, 0, forGroups("dept-fin", "concurrence-officer").size() + forGroups("dept-law", "concurrence-officer").size(),
                    "nothing in another tenant in the second round (SL-13)");
            List<TaskView> again = forGroups("dept-works", "concurrence-officer");
            eq(ctx, 2, again.size(), "both departments referred again");
            eq(ctx, 1L, again.stream().filter(t -> t.variables().containsKey("amount")).count(),
                    "Finance again receives the amount, Law does not");
            for (TaskView t : again) {
                adapter.completeTask(t.id(), t.variables().containsKey("amount") ? m("concurred", true, "remarks", "Concurred")
                        : m("concurred", true, "remarks", "Vetted"));
            }
            eq(ctx, "Vetted", var(p, "lawRemarks"), "second-round out-mapping (Law)");
            eq(ctx, "Concurred", var(p, "finRemarks"), "second-round out-mapping (Finance)");
            eq(ctx, true, var(p, "lawConcurred"), "Law concurred after revision");
            eq(ctx, 0, tasksIn(p, "ref_prepare").size(), "not returned again");
            completeTask(p, "ref_approve", m("approvedBy", "Secretary PWD"));
            yes(ctx, ended(p), "referral ended");
            eq(ctx, 4, subProcessInstances(p).size(), "two referral rounds x two departments");
            eq(ctx, 0, forGroups("dept-fin").size() + forGroups("dept-law").size(), "no task ever in Finance/Law tenants");''')

for J in ('sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json',
          'sangam-onboarding/src/test/resources/acceptance/AT-1.json'):
    edit(J, '"sections":["documentTypes","approvalChains","paymentRails"]',
         '"sections":["documentTypes","approvalChains","paymentRails","classification"]')
    edit(J, '"variables":{"amount":"1000","billType":"FVC"}',
         '"variables":{"amount":"1000","billType":"FVC","headOfAccount":"05-2054-00-001-01-00-13"}')
    edit(J, '"reachesWaitState":true}}',
         '"reachesWaitState":true,"expectVariables":{"docId":"RJ_FVC","docFamily":"CONTINGENT","docValid":true,'
         '"clsValid":true}}}')

# K3: an officer acts on a task they hold; the run's officer claims each open task first (claim needs visibility only)
edit(P, """                String tid = task.path("id").asText();
                Map<String, Object> done = new LinkedHashMap<>();""", """                String tid = task.path("id").asText();
                // K3: only the holder (or an unassigned candidate) may act; the officer takes the task first
                t.call("POST", "/api/v1/tasks/" + tid + "/claim", null);
                Map<String, Object> done = new LinkedHashMap<>();""")
