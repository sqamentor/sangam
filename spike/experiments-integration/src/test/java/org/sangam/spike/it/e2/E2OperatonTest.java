package org.sangam.spike.it.e2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.AuthorizationService;
import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.authorization.Authorization;
import org.operaton.bpm.engine.authorization.Permissions;
import org.operaton.bpm.engine.authorization.Resources;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.task.NativeTaskQuery;
import org.operaton.bpm.engine.task.Task;
import org.sangam.spike.it.support.EngineKind;
import org.sangam.spike.layer.SqlPredicate;
import org.sangam.spike.operaton.OperatonEngineAdapter;

class E2OperatonTest extends AbstractE2AuthorizationTest {

    @Override
    protected EngineKind kind() {
        return EngineKind.OPERATON;
    }

    private ProcessEngine pe() {
        return ((OperatonEngineAdapter) env.engine).processEngine();
    }

    private NativeTaskQuery nativeQuery(String select, SqlPredicate p) {
        NamedSql n = named(p);
        NativeTaskQuery q = pe().getTaskService().createNativeTaskQuery().sql(select + " FROM ACT_RU_TASK RES WHERE " + n.sql());
        n.params().forEach(q::parameter);
        return q;
    }

    @Override
    protected List<String> nativeList(SqlPredicate p, int maxResults) {
        NativeTaskQuery q = nativeQuery("SELECT RES.*", p);
        List<Task> tasks = maxResults == Integer.MAX_VALUE ? q.list() : q.listPage(0, maxResults);
        List<String> ids = new ArrayList<>(tasks.size());
        tasks.forEach(t -> ids.add(t.getId()));
        return ids;
    }

    @Override
    protected long nativeCount(SqlPredicate p) {
        return nativeQuery("SELECT count(*)", p).count();
    }

    /**
     * Operaton's built-in authorization (ACT_RU_AUTHORIZATION), switched on at runtime for this probe only: does its
     * TaskQuery filtering give A-3 semantics (explicit deny wins at the same or broader scope)?
     */
    @Override
    protected Map<String, Object> engineAuthorizationProbe(List<TaskRow> tenantTasks, String tenant) {
        Map<String, Object> out = new LinkedHashMap<>();
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) pe().getProcessEngineConfiguration();
        AuthorizationService as = pe().getAuthorizationService();
        IdentityService ids = pe().getIdentityService();
        TaskService ts = pe().getTaskService();
        String x = tenantTasks.get(0).id();
        List<String> created = new ArrayList<>();
        try {
            created.add(save(as, Authorization.AUTH_TYPE_GRANT, null, "op-A", "*", true));     // group A: READ on all tasks
            created.add(save(as, Authorization.AUTH_TYPE_REVOKE, null, "op-B", x, false));    // group B: READ revoked on x
            created.add(save(as, Authorization.AUTH_TYPE_GRANT, "op-user2", null, x, true));  // user 2: READ on x
            created.add(save(as, Authorization.AUTH_TYPE_GRANT, null, "op-C", x, true));      // group C: READ on x
            created.add(save(as, Authorization.AUTH_TYPE_REVOKE, null, "op-D", "*", false));  // group D: READ revoked on all
            cfg.setAuthorizationEnabled(true);

            ids.setAuthentication("op-user1", List.of("op-A", "op-B"), List.of(tenant));
            boolean aVisible = ts.createTaskQuery().taskId(x).count() == 1;
            long aCount = ts.createTaskQuery().count();
            ids.setAuthentication("op-user2", List.of("op-B"), List.of(tenant));
            boolean bVisible = ts.createTaskQuery().taskId(x).count() == 1;
            ids.setAuthentication("op-user3", List.of("op-C", "op-D"), List.of(tenant));
            boolean cVisible = ts.createTaskQuery().taskId(x).count() == 1;

            // 300 groups through the engine's own authorization check (group A plus 299 groups without rows)
            List<String> many = new ArrayList<>();
            many.add("op-A");
            for (int i = 1; i < GROUPS; i++) {
                many.add("op-filler-" + i);
            }
            ids.setAuthentication("op-user4", many, List.of(tenant));
            Map<String, Object> latency = time(() -> ts.createTaskQuery().orderByTaskCreateTime().desc().listPage(0, 50).size(), 30, 300);

            out.put("engineAuthorizationService", "yes: AuthorizationService + ACT_RU_AUTHORIZATION (GRANT / REVOKE / GLOBAL), "
                    + "applied to TaskQuery when authorizationEnabled and an authentication is set");
            out.put("caseA_groupGrantAll_plus_otherGroupRevokeX_xVisible", aVisible);
            out.put("caseA_visibleCount", aCount);
            out.put("caseB_userGrantX_plus_groupRevokeX_xVisible", bVisible);
            out.put("caseC_groupGrantX_plus_groupRevokeAll_xVisible", cVisible);
            out.put("productRuleExpects", "x hidden in A, B and C (any matching deny wins, compiled as NOT EXISTS)");
            out.put("satisfiesA3DenyWins", !aVisible && !bVisible);
            out.put("a3Reading", "A and B are inside A-3's wording (deny vs a grant at the same or broader scope). C (a "
                    + "broader deny vs a narrower grant) is outside the wording but inside the compiled NOT EXISTS rule; "
                    + "Operaton lets the narrower grant win");
            out.put("builtInAuthorizedTaskQuery300GroupsPage50", latency);
        } finally {
            ids.clearAuthentication();
            cfg.setAuthorizationEnabled(false);
            created.forEach(as::deleteAuthorization);
        }
        out.put("taskQueryCanTakePredicate", false);
        out.put("taskQueryOptions", "candidate user/group(In), tenantIdIn, OR-queries; no hook for an extra SQL clause");
        out.put("nativeTaskQuery", "yes: TaskService.createNativeTaskQuery() with raw SQL and #{named} parameters (used in "
                + "E2-6/E2-7); no engine patch; native queries are not checked by the engine's authorization");
        out.put("whatItWouldNeed", "built-in authorization would need deny precedence over user-level grants and over grants "
                + "in other groups (A-3), the product's permission bits/conditions and tenant binding; otherwise keep it off "
                + "(as the adapter does) and use the product predicate through plain SQL or NativeTaskQuery");
        return out;
    }

    private static String save(AuthorizationService as, int type, String user, String group, String resourceId, boolean grant) {
        Authorization a = as.createNewAuthorization(type);
        if (user != null) {
            a.setUserId(user);
        } else {
            a.setGroupId(group);
        }
        a.setResource(Resources.TASK);
        a.setResourceId(resourceId);
        if (grant) {
            a.addPermission(Permissions.READ);
        } else {
            a.removePermission(Permissions.READ);
        }
        return as.saveAuthorization(a).getId();
    }
}
