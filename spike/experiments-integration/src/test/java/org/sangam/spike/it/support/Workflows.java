package org.sangam.spike.it.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.api.EngineAdapter.TaskView;

/** Drives the corpus workflows (corpus/MODELS.md) through the engine-neutral adapter. */
public final class Workflows {

    public static final String[] WF1_FILES = {"wf1-bill.bpmn", "wf1-delegation-of-powers.dmn"};
    public static final String[] WF2_FILES = {"wf2-file.bpmn"};
    public static final String[] WF3_FILES = {"wf3-licence.bpmn"};

    private Workflows() {
    }

    public static Map<String, Object> wf1Vars(String billId, long amount, String checkerGroup, String account) {
        Map<String, Object> v = new HashMap<>();
        v.put("billId", billId);
        v.put("amount", amount);
        v.put("beneficiary", "Synthetic Supplier " + billId);
        v.put("beneficiaryAccount", account);
        v.put("budgetHead", "2059-MAINT");
        v.put("initiatorSeat", "seat-initiator");
        v.put("checkerGroup", checkerGroup);
        return v;
    }

    public static Map<String, Object> wf2Vars(String l1Group) {
        Map<String, Object> v = new HashMap<>();
        v.put("initiatorSeat", "seat-initiator");
        v.put("l1Group", l1Group);
        v.put("l2Group", "seat-l2");
        v.put("l3Group", "seat-l3");
        v.put("sectionAGroup", "seat-sec-a");
        v.put("sectionBGroup", "seat-sec-b");
        v.put("disposalGroup", "seat-disposal");
        return v;
    }

    public static TaskView singleTask(EngineAdapter engine, String tenant, String instanceId, String taskKey) {
        List<TaskView> tasks = engine.queryTasks(new EngineAdapter.TaskQuery(tenant, null, null, instanceId, taskKey, 0, 10));
        if (tasks.size() != 1) {
            throw new IllegalStateException("expected one task " + taskKey + " in " + instanceId + ", found " + tasks.size());
        }
        return tasks.get(0);
    }

    /**
     * Starts WF-1 and completes checker review (forward) and approval (approve), so the instance waits in the
     * external task {@code wf1_payment}, topic {@code payment}. Amount 50,000 routes to SECTION_OFFICER.
     */
    public static String wf1ToPayment(EngineAdapter engine, String tenant, String businessKey, long amount, String account) {
        String pid = engine.startProcess(tenant, "wf1-bill", businessKey,
                wf1Vars(businessKey, amount, "seat-checker", account));
        engine.completeTask(singleTask(engine, tenant, pid, "wf1_checkerReview").id(), Map.of("checkerDecision", "forward"));
        engine.completeTask(singleTask(engine, tenant, pid, "wf1_approve").id(), Map.of("approverDecision", "approve"));
        return pid;
    }
}
