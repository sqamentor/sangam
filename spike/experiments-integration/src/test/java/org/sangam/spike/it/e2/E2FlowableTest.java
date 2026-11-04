package org.sangam.spike.it.e2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flowable.engine.TaskService;
import org.flowable.task.api.NativeTaskQuery;
import org.flowable.task.api.Task;
import org.sangam.spike.flowable.FlowableEngineAdapter;
import org.sangam.spike.it.support.EngineKind;
import org.sangam.spike.layer.SqlPredicate;

class E2FlowableTest extends AbstractE2AuthorizationTest {

    @Override
    protected EngineKind kind() {
        return EngineKind.FLOWABLE;
    }

    private TaskService taskService() {
        return ((FlowableEngineAdapter) env.engine).processEngine().getTaskService();
    }

    private NativeTaskQuery nativeQuery(String select, SqlPredicate p) {
        NamedSql n = named(p);
        NativeTaskQuery q = taskService().createNativeTaskQuery().sql(select + " FROM ACT_RU_TASK RES WHERE " + n.sql());
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

    @Override
    protected Map<String, Object> engineAuthorizationProbe(List<TaskRow> tenantTasks, String tenant) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engineAuthorizationService", "none: Flowable 8.0.0 has no authorization service or permission tables "
                + "(no ACT_RU_AUTHORIZATION); access is modelled only as identity links (ACT_RU_IDENTITYLINK: candidate "
                + "user/group, assignee, owner)");
        out.put("taskQueryCanTakePredicate", false);
        out.put("taskQueryOptions", "taskCandidateUser / taskCandidateGroup(In) / taskInvolvedUser / taskTenantId: OR of "
                + "identity links, no deny, no wildcard resource, no permission bits, no extension hook for an extra SQL clause");
        out.put("nativeTaskQuery", "yes: TaskService.createNativeTaskQuery() accepts raw SQL with #{named} parameters and "
                + "returns Task entities and counts (used in E2-6/E2-7); no engine patch");
        out.put("whatItWouldNeed", "keep the product predicate: a product query builder over ACT_RU_TASK (plain SQL or "
                + "NativeTaskQuery) plus the same for ACT_HI_* history tables; or a custom MyBatis mapper. Either way the "
                + "engine's TaskQuery API cannot be the read path for protected lists (VI.4 rule 3)");
        return out;
    }
}
