package org.sangam.spike.gova;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.sangam.spike.api.EngineAdapter.TaskQuery;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.gova.support.BizCal;
import org.sangam.spike.gova.support.Db;
import org.sangam.spike.gova.support.GovBase;
import org.sangam.spike.gova.support.IfmsClient;
import org.sangam.spike.gova.support.Rec;
import org.sangam.spike.gova.support.Results;
import org.sangam.spike.sim.Fault;
import org.sangam.spike.sim.ifms.IfmsJitSimulator;

/** E16-a: government patterns GP-01 to GP-20 on one engine (selected by -Dgov.engine). See ../GOV-PATTERNS.md. */
@TestMethodOrder(MethodOrderer.MethodName.class)
class E16PatternsTest extends GovBase {

    private static final Results RESULTS = new Results("E16-a", "Government patterns GP-01..GP-20 (E16, part a)");
    private static String engineId;

    @Override
    protected Results results() {
        if (engineId == null && a != null) {
            engineId = a.engineId();
        }
        return RESULTS;
    }

    @AfterAll
    static void write() {
        RESULTS.put("scaleN", scaleN());
        RESULTS.put("threads", "GP-08: 4 worker threads for start and for completion on both engines; GP-09: 1 thread "
                + "(completions of one multi-instance body conflict by design on both engines)");
        RESULTS.write(kit(), engineId == null ? kit().engine() : engineId);
    }

    private String start(String key, String bk, Object... kv) {
        return a.startProcess(null, key, bk, vars(kv));
    }

    // =====================================================================================================  GP-01

    @Test
    void gp01_makerCheckerApproverByAmountBand() {
        scenario("GP-01", "Maker -> checker -> approver by seat, with amount bands (DMN FIRST)", r -> {
            r.feature("userTask candidate group by seat: potentialOwner group(${seat})",
                    "DMN 1.3 decision table, hit policy FIRST, called from BPMN", "process variables");
            r.variant(k().engine().equals("operaton")
                    ? "DMN call: businessRuleTask operaton:decisionRef + operaton:resultVariable + operaton:mapDecisionResult=singleEntry"
                    : "DMN call: serviceTask flowable:type=dmn + field decisionTableReferenceKey (outputs become process variables)");
            String dmn = dmn("gp01-dfpr", "FIRST", "amount", "number", "approverSeat", "string", List.of(
                    new String[]{"<= 100000", "\"SEAT-SO\""},
                    new String[]{"<= 1000000", "\"SEAT-DDO\""},
                    new String[]{"<= 10000000", "\"SEAT-HOD\""},
                    new String[]{"-", "\"SEAT-SECY\""}));
            String bpmn = defs("<process id=\"gp01\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                    + flow("f1", "s", "maker") + userTask("maker", "${makerSeat}")
                    + flow("f2", "maker", "checker") + userTask("checker", "${checkerSeat}")
                    + flow("f3", "checker", "dop") + k().dmnTask("dop", "gp01-dfpr", "approverSeat", false)
                    + flow("f4", "dop", "approver") + userTask("approver", "${approverSeat}")
                    + flow("f5", "approver", "e") + "<endEvent id=\"e\"/></process>");
            deploy("gp01", "gp01.bpmn", bpmn, "gp01-dfpr.dmn", dmn);

            Map<Long, String> bands = new LinkedHashMap<>();
            bands.put(50_000L, "SEAT-SO");
            bands.put(500_000L, "SEAT-DDO");
            bands.put(5_000_000L, "SEAT-HOD");
            bands.put(20_000_000L, "SEAT-SECY");
            Map<String, Object> seen = new LinkedHashMap<>();
            for (Map.Entry<Long, String> band : bands.entrySet()) {
                String pid = start("gp01", "BILL-" + band.getKey(), "amount", band.getKey(), "makerSeat", "SEAT-DH",
                        "checkerSeat", "SEAT-AAO");
                r.check(tasksForGroups(pid, List.of("SEAT-DH")).size() == 1, "maker task visible to seat SEAT-DH");
                TaskView maker = one(pid, "maker");
                a.claimTask(maker.id(), "u.maker");
                a.completeTask(maker.id(), Map.of());
                TaskView checker = one(pid, "checker");
                r.check(tasksForGroups(pid, List.of("SEAT-AAO")).size() == 1, "checker task visible to seat SEAT-AAO");
                a.claimTask(checker.id(), "u.checker");
                a.completeTask(checker.id(), Map.of());
                List<TaskView> appr = tasksForGroups(pid, List.of(band.getValue()));
                seen.put(String.valueOf(band.getKey()), var(pid, "approverSeat"));
                r.check(appr.size() == 1 && "approver".equals(appr.get(0).taskDefinitionKey()),
                        "amount " + band.getKey() + " -> approver seat " + band.getValue() + " (got " + var(pid, "approverSeat") + ")");
                for (String other : bands.values()) {
                    if (!other.equals(band.getValue())) {
                        r.check(tasksForGroups(pid, List.of(other)).isEmpty(), "approver task not visible to " + other);
                    }
                }
                a.claimTask(appr.get(0).id(), "u.approver");
                a.completeTask(appr.get(0).id(), Map.of());
                r.check(ended(pid), "instance ended after approval");
            }
            r.detail("approverSeatByAmount", seen);
            // Segregation of duties: does the engine stop the maker from also acting as checker?
            String pid = start("gp01", "BILL-SOD", "amount", 1000L, "makerSeat", "SEAT-DH", "checkerSeat", "SEAT-DH");
            TaskView maker = one(pid, "maker");
            a.claimTask(maker.id(), "u.same");
            a.completeTask(maker.id(), Map.of());
            TaskView checker = one(pid, "checker");
            a.claimTask(checker.id(), "u.same");
            r.detail("sameUserCouldClaimMakerAndChecker", true);
            r.note("Neither engine enforces segregation of duties (maker != checker != approver): the same user claimed both "
                    + "tasks. SoD by person and by seat is a product-layer check (R19 / A-3, IX.5 item 5).");
        });
    }

    // =====================================================================================================  GP-02

    @Test
    void gp02_hierarchyWithReturnToAnyLevel() {
        scenario("GP-02", "Multi-level hierarchy with return to any earlier level (e-Office noting)", r -> {
            r.feature("exclusive gateway router (one gateway, conditions on ${next})", "loops", "process variables",
                    "candidate group by level seat");
            String[] levels = {"L1", "L2", "L3", "L4"};
            String[] seats = {"SEAT-DA", "SEAT-SO", "SEAT-US", "SEAT-JS"};
            StringBuilder p = new StringBuilder("<process id=\"gp02\" isExecutable=\"true\"><startEvent id=\"s\"/>")
                    .append(flow("fs", "s", "L1"));
            for (int i = 0; i < 4; i++) {
                p.append(userTask(levels[i], seats[i]));
                p.append(flow("fo" + i, levels[i], "route"));
            }
            p.append("<exclusiveGateway id=\"route\"/>");
            for (String l : levels) {
                p.append(flow("to" + l, "route", l, "${next == '" + l + "'}"));
            }
            p.append(flow("toEnd", "route", "e", "${next == 'END'}")).append("<endEvent id=\"e\"/></process>");
            deploy("gp02", "gp02.bpmn", defs(p.toString()));
            String pid = start("gp02", "FILE-1");
            String[][] steps = {{"L1", "L2"}, {"L2", "L3"}, {"L3", "L4"}, {"L4", "L1"}, {"L1", "L2"}, {"L2", "L3"},
                    {"L3", "L2"}, {"L2", "L3"}, {"L3", "L4"}, {"L4", "END"}};
            List<String> path = new ArrayList<>();
            for (String[] st : steps) {
                List<TaskView> open = tasks(pid);
                r.check(open.size() == 1 && open.get(0).taskDefinitionKey().equals(st[0]),
                        "open task is " + st[0] + " (found " + names(open) + ")");
                path.add(st[0] + "->" + st[1]);
                a.completeTask(open.get(0).id(), Map.of("next", st[1]));
            }
            r.check(ended(pid), "file disposed (instance ended)");
            r.detail("path", path);
            r.check(k().historicActivityCount(a, pid, "L2") == 3 && k().historicActivityCount(a, pid, "L4") == 2,
                    "history counts visits per level (L2=3, L4=2)");
            r.nativeApi(k().api("historicActivityCount"));
            // Native alternative: return from L4 to L1 by instance modification, without a modelled flow.
            String pid2 = start("gp02", "FILE-2");
            for (String n : List.of("L2", "L3", "L4")) {
                a.completeTask(tasks(pid2).get(0).id(), Map.of("next", n));
            }
            k().moveActivity(a, pid2, "L4", "L1");
            r.nativeApi(k().api("moveActivity"));
            r.check(names(tasks(pid2)).equals(List.of("L1")), "instance modification moved the file from L4 back to L1");
            r.check(tasksForGroups(pid2, List.of("SEAT-DA")).size() == 1, "moved task is at seat SEAT-DA");
            r.note("Return to any level is modelled (router gateway); the native instance-modification API was also "
                    + "checked as an alternative for out-of-model returns.");
        });
    }

    // =====================================================================================================  GP-03 / 04

    private String committeeModel(String key, String completionCondition) {
        String mi = "<multiInstanceLoopCharacteristics isSequential=\"false\"><loopCardinality xsi:type=\"tFormalExpression\">"
                + "${memberCount}</loopCardinality>"
                + (completionCondition == null ? "" : "<completionCondition xsi:type=\"tFormalExpression\">"
                + completionCondition.replace(">", "&gt;") + "</completionCondition>")
                + "</multiInstanceLoopCharacteristics>";
        return defs("<process id=\"" + key + "\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "sign")
                + "<userTask id=\"sign\" name=\"sign\"><humanPerformer><resourceAssignmentExpression><formalExpression>"
                + "${members.split(';')[loopCounter]}</formalExpression></resourceAssignmentExpression></humanPerformer>"
                + mi + "</userTask>" + flow("f2", "sign", "recorded") + userTask("recorded", "SEAT-SECY")
                + flow("f3", "recorded", "e") + "<endEvent id=\"e\"/></process>");
    }

    @Test
    void gp03_committeeAllMustSign() {
        scenario("GP-03", "Committee: all members must sign", r -> {
            r.feature("parallel multi-instance userTask, loopCardinality", "humanPerformer expression with loopCounter",
                    "completion = all instances");
            deploy("gp03", "gp03.bpmn", committeeModel("gp03", null));
            String members = "m.chair;m.finance;m.technical;m.user;m.external";
            String pid = start("gp03", "TEC-1", "members", members, "memberCount", 5);
            List<TaskView> signs = tasks(pid, "sign");
            r.check(signs.size() == 5, "5 sign tasks (found " + signs.size() + ")");
            List<String> assignees = new ArrayList<>();
            signs.forEach(t -> assignees.add(t.assignee()));
            r.check(assignees.containsAll(List.of(members.split(";"))), "one task per member, assigned by name: " + assignees);
            for (int i = 0; i < 4; i++) {
                a.completeTask(signs.get(i).id(), Map.of());
            }
            r.check(tasks(pid, "recorded").isEmpty() && tasks(pid, "sign").size() == 1,
                    "after 4 of 5 signatures the committee is still open");
            a.completeTask(signs.get(4).id(), Map.of());
            r.check(tasks(pid, "recorded").size() == 1, "after the 5th signature the decision is recorded");
            r.detail("assignees", assignees);
        });
    }

    @Test
    void gp04_committeeQuorum() {
        scenario("GP-04", "Committee quorum (n of m)", r -> {
            r.feature("parallel multi-instance userTask", "completionCondition ${nrOfCompletedInstances >= quorum}");
            deploy("gp04", "gp04.bpmn", committeeModel("gp04", "${nrOfCompletedInstances >= quorum}"));
            String pid = start("gp04", "DPC-1", "members", "b1;b2;b3;b4;b5", "memberCount", 5, "quorum", 3);
            List<TaskView> signs = tasks(pid, "sign");
            r.check(signs.size() == 5, "5 board tasks");
            a.completeTask(signs.get(0).id(), Map.of());
            a.completeTask(signs.get(1).id(), Map.of());
            r.check(tasks(pid, "sign").size() == 3 && tasks(pid, "recorded").isEmpty(), "2 of 5: no quorum yet");
            a.completeTask(signs.get(2).id(), Map.of());
            r.check(tasks(pid, "sign").isEmpty(), "quorum 3 of 5 reached: the 2 remaining tasks are cancelled");
            r.check(tasks(pid, "recorded").size() == 1, "decision recorded after quorum");
            r.detail("historicSignInstances", k().historicActivityCount(a, pid, "sign"));
            r.nativeApi(k().api("historicActivityCount"));
        });
    }

    // =====================================================================================================  GP-05

    @Test
    void gp05_chainedSanctions() {
        scenario("GP-05", "Chained sanctions AA -> TS -> ES (call activities)", r -> {
            r.feature("callActivity (reusable sanction sub-process)", "variables in/out of the called process",
                    "candidate group by seat inside the called process");
            r.variant(k().engine() + ":in sourceExpression / " + k().engine()
                    + ":out source on callActivity (BPMN 2.0.2 has no executable call-activity variable mapping)");
            String sanction = defs("<process id=\"gp05-sanction\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                    + flow("f1", "s", "sanction") + userTask("sanction", "${seat}") + flow("f2", "sanction", "e")
                    + "<endEvent id=\"e\"/></process>");
            String parent = defs("<process id=\"gp05\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "aa")
                    + k().callActivity("aa", "gp05-sanction", List.of(new String[]{"${'AA'}", "stage"},
                    new String[]{"${'SEAT-EE'}", "seat"}, new String[]{"${workName}", "workName"}), List.<String[]>of(new String[]{"sanctionNo", "aaNo"}))
                    + flow("f2", "aa", "ts")
                    + k().callActivity("ts", "gp05-sanction", List.of(new String[]{"${'TS'}", "stage"},
                    new String[]{"${'SEAT-SE'}", "seat"}, new String[]{"${workName}", "workName"}), List.<String[]>of(new String[]{"sanctionNo", "tsNo"}))
                    + flow("f3", "ts", "es")
                    + k().callActivity("es", "gp05-sanction", List.of(new String[]{"${'ES'}", "stage"},
                    new String[]{"${'SEAT-FD'}", "seat"}, new String[]{"${workName}", "workName"}), List.<String[]>of(new String[]{"sanctionNo", "esNo"}))
                    + flow("f4", "es", "workOrder") + userTask("workOrder", "SEAT-EE")
                    + flow("f5", "workOrder", "e") + "<endEvent id=\"e\"/></process>");
            deploy("gp05", "gp05-sanction.bpmn", sanction, "gp05.bpmn", parent);
            String pid = start("gp05", "WORK-7", "workName", "Rural road R-7");
            String[][] stages = {{"AA", "SEAT-EE"}, {"TS", "SEAT-SE"}, {"ES", "SEAT-FD"}};
            for (String[] st : stages) {
                List<TaskView> t = a.queryTasks(new TaskQuery(null, null, List.of(st[1]), null, "sanction", 0, 0));
                r.check(t.size() == 1, st[0] + " sanction task at seat " + st[1] + " (found " + t.size() + ")");
                if (t.size() == 1) {
                    r.check(!t.get(0).processInstanceId().equals(pid), st[0] + " runs in a called (child) instance");
                    r.check("Rural road R-7".equals(var(t.get(0).processInstanceId(), "workName")),
                            st[0] + ": workName passed into the called process");
                    a.completeTask(t.get(0).id(), Map.of("sanctionNo", st[0] + "/2026/17"));
                }
            }
            r.check("AA/2026/17".equals(var(pid, "aaNo")) && "TS/2026/17".equals(var(pid, "tsNo"))
                    && "ES/2026/17".equals(var(pid, "esNo")), "sanction numbers mapped back to the parent");
            r.check(tasks(pid, "workOrder").size() == 1, "work order after the third sanction");
            r.check(k().subProcessInstances(a, pid).size() == 3, "3 called instances linked to the parent");
            r.nativeApi(k().api("instances") + " (superProcessInstanceId)");
        });
    }

    // =====================================================================================================  GP-06

    @Test
    void gp06_slaWithWorkingDaysRemindersEscalation() {
        scenario("GP-06", "SLA with working-day calendar, holidays, reminders, escalation", r -> {
            r.feature("boundary timer non-interrupting (timeCycle R2/PT24H reminders)",
                    "boundary timer non-interrupting timeDate ${slaDue} (due date computed by the product calendar)",
                    "boundary timer non-interrupting timeDuration P3D on a working-day business calendar",
                    "escalation user task");
            BizCal cal = new BizCal(Set.of(LocalDate.parse("2026-11-09")));
            k().registerWorkingDayCalendar(a, "govWorkingDays", cal);
            r.nativeApi(k().api("registerWorkingDayCalendar"));
            if (k().engine().equals("flowable")) {
                r.variant("flowable:businessCalendarName=\"govWorkingDays\" on the P3D timer (named calendar per timer)");
            } else {
                r.workaround("Operaton has no per-timer business calendar: the engine-wide 'duration' calendar is replaced "
                        + "by a working-day calendar (impl API), which changes EVERY duration timer of the engine", 30);
            }
            String bpmn = defs("<process id=\"gp06\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "app")
                    + userTask("app", "${pioSeat}") + flow("f2", "app", "e") + "<endEvent id=\"e\"/>"
                    + "<boundaryEvent id=\"rem\" attachedToRef=\"app\" cancelActivity=\"false\"><timerEventDefinition>"
                    + "<timeCycle xsi:type=\"tFormalExpression\">R2/PT24H</timeCycle></timerEventDefinition></boundaryEvent>"
                    + flow("f3", "rem", "remSent") + "<intermediateThrowEvent id=\"remSent\"/>" + flow("f4", "remSent", "remEnd")
                    + "<endEvent id=\"remEnd\"/>"
                    + "<boundaryEvent id=\"slaA\" attachedToRef=\"app\" cancelActivity=\"false\"><timerEventDefinition>"
                    + "<timeDate xsi:type=\"tFormalExpression\">${slaDue}</timeDate></timerEventDefinition></boundaryEvent>"
                    + flow("f5", "slaA", "escA") + userTask("escA", "${hodSeat}") + flow("f6", "escA", "escAEnd") + "<endEvent id=\"escAEnd\"/>"
                    + "<boundaryEvent id=\"slaB\" attachedToRef=\"app\" cancelActivity=\"false\"><timerEventDefinition "
                    + k().timerCalendarAttr("govWorkingDays") + ">"
                    + "<timeDuration xsi:type=\"tFormalExpression\">P3D</timeDuration></timerEventDefinition></boundaryEvent>"
                    + flow("f7", "slaB", "escB") + userTask("escB", "${hodSeat}") + flow("f8", "escB", "escBEnd") + "<endEvent id=\"escBEnd\"/>"
                    + "</process>");
            deploy("gp06", "gp06.bpmn", bpmn);
            clock("2026-11-05T10:00:00"); // Thursday
            Date slaDue = cal.addWorkingDays(ist("2026-11-05T10:00:00"), 3);
            String expected = ist("2026-11-11T10:00:00").toInstant().toString(); // Fri 6, (Sat, Sun, Mon 9 holiday), Tue 10, Wed 11
            r.check(slaDue.toInstant().toString().equals(expected), "product calendar: 3 working days = Wed 11 Nov 10:00 IST");
            String pid = start("gp06", "RTI-1", "pioSeat", "SEAT-PIO", "hodSeat", "SEAT-HOD", "slaDue", slaDue);
            List<String> dues = new ArrayList<>();
            k().timers(a, pid).forEach(t -> dues.add(t[1]));
            r.nativeApi(k().api("timers"));
            r.detail("timerDueDatesUtc", dues);
            long atExpected = dues.stream().filter(expected::equals).count();
            r.check(atExpected == 2, "both SLA timers due Wed 11 Nov 10:00 IST (product date and working-day P3D); got " + dues);
            clock("2026-11-06T10:01:00");
            jobs();
            clock("2026-11-07T10:01:00");
            jobs();
            clock("2026-11-08T10:01:00");
            jobs();
            long reminders = k().historicActivityCount(a, pid, "remSent");
            r.check(reminders == 2, "2 reminders (R2/PT24H), got " + reminders);
            r.check(tasks(pid, "escA").isEmpty() && tasks(pid, "escB").isEmpty(),
                    "no escalation on Sun 8 Nov (3 calendar days would have escalated here)");
            clock("2026-11-11T10:01:00");
            jobs();
            r.check(tasks(pid, "escA").size() == 1, "escalation A (product-computed date) on Wed 11 Nov");
            r.check(tasks(pid, "escB").size() == 1, "escalation B (working-day calendar P3D) on Wed 11 Nov");
            r.check(tasks(pid, "app").size() == 1, "application task still open (non-interrupting)");
            r.nativeApi(k().api("historicActivityCount"));
            r.note("Portable form: the product layer computes the due date with its holiday calendar and the model uses "
                    + "timeDate ${slaDue} (no variant). Engine-calendar form: Flowable selects a named calendar per timer; "
                    + "Operaton can only replace the engine-wide calendar of a timer type.");
            if (r.hasFailures()) {
                return;
            }
            r.result(k().engine().equals("flowable") ? Rec.PASS : Rec.WORKAROUND);
        });
    }

    // =====================================================================================================  GP-07

    @Test
    void gp07_deadlineExtensionOfRunningInstances() {
        scenario("GP-07", "Deadline extension of running instances (tender corrigendum)", r -> {
            r.feature("intermediate timer catch timeDate ${bidDeadline}", "timer job due-date change on running instances");
            String bpmn = defs("<process id=\"gp07\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "deadline")
                    + "<intermediateCatchEvent id=\"deadline\"><timerEventDefinition><timeDate xsi:type=\"tFormalExpression\">"
                    + "${bidDeadline}</timeDate></timerEventDefinition></intermediateCatchEvent>"
                    + flow("f2", "deadline", "opening") + userTask("opening", "SEAT-TOC")
                    + flow("f3", "opening", "e") + "<endEvent id=\"e\"/></process>");
            deploy("gp07", "gp07.bpmn", bpmn);
            clock("2026-11-05T10:00:00");
            List<String> pids = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                pids.add(start("gp07", "NIT-" + i, "bidDeadline", ist("2026-11-20T15:00:00")));
            }
            Date newDeadline = ist("2026-11-27T15:00:00");
            List<String> jobIds = new ArrayList<>();
            for (String pid : pids) {
                for (String[] t : k().timers(a, pid)) {
                    jobIds.add(k().rescheduleTimer(a, t[0], newDeadline));
                }
            }
            r.nativeApi(k().api("timers"));
            r.nativeApi(k().api("rescheduleTimer"));
            List<String> dues = new ArrayList<>();
            for (String pid : pids) {
                k().timers(a, pid).forEach(t -> dues.add(t[1]));
            }
            r.detail("dueDatesAfterCorrigendumUtc", dues);
            r.check(dues.size() == 3 && dues.stream().allMatch(newDeadline.toInstant().toString()::equals),
                    "all 3 running timers now due 27 Nov 15:00 IST");
            clock("2026-11-21T10:00:00");
            jobs();
            r.check(pids.stream().allMatch(p -> tasks(p, "opening").isEmpty()), "nothing opens at the old deadline");
            clock("2026-11-27T15:01:00");
            jobs();
            r.check(pids.stream().allMatch(p -> tasks(p, "opening").size() == 1), "bid opening at the new deadline");
            r.detail("jobIdKeptAfterReschedule", k().engine().equals("operaton"));
            r.note(k().engine().equals("operaton")
                    ? "setJobDuedate keeps the job id; recalculateJobDuedate(jobId, false) re-evaluates the expression too"
                    : "rescheduleTimeDateJob deletes the timer job and creates a new one (new job id)");
            r.note("Model-based alternative without native API: event-based gateway with a 'corrigendum' message that loops "
                    + "back to the timer with the new date (used in D-TENDER, E17-a).");
        });
    }

    // =====================================================================================================  GP-08

    @Test
    void gp08_bulkIntakeAndBulkDecision() {
        scenario("GP-08", "Bulk intake: >= 5,000 instances, bulk approve / reject / withdraw", r -> {
            int n = scaleN();
            int threads = 4;
            r.feature("mass start", "task queries by seat and by process variable", "bulk instance deletion (withdrawn)",
                    "bulk task completion (approve / reject)");
            String bpmn = defs("<process id=\"gp08\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "scrutiny")
                    + userTask("scrutiny", "SEAT-SCRUTINY")
                    + flow("f2", "scrutiny", "gw") + "<exclusiveGateway id=\"gw\" default=\"fr\"/>"
                    + flow("fa", "gw", "approved", "${decision == 'approve'}") + flow("fr", "gw", "rejected")
                    + "<endEvent id=\"approved\"/><endEvent id=\"rejected\"/></process>");
            deploy("gp08", "gp08.bpmn", bpmn);
            Map<String, Long> rows0 = Db.engineTableRows(db);
            long heap0 = heapUsedAfterGcMb();
            resetPeaks();

            long t0 = System.nanoTime();
            List<String> pids = parallel(threads, n, i -> start("gp08", "APP-" + i, "district", "D" + (i % 30),
                    "applicantNo", i));
            long startMs = ms(t0);
            r.timing("start_" + n + "_instances", startMs);

            t0 = System.nanoTime();
            long count = a.countTasks(new TaskQuery(null, null, List.of("SEAT-SCRUTINY"), null, null, 0, 0));
            r.timing("countTasksBySeat", ms(t0));
            t0 = System.nanoTime();
            List<TaskView> page = a.queryTasks(new TaskQuery(null, null, List.of("SEAT-SCRUTINY"), null, null, 0, 50));
            r.timing("firstPage50BySeat", ms(t0));
            t0 = System.nanoTime();
            long d07 = k().countTasksWithProcessVariable(a, "gp08", "district", "D7");
            r.timing("countTasksByProcessVariable", ms(t0));
            r.nativeApi(k().api("countTasksWithProcessVariable"));
            r.check(count == n, "task count by seat = " + n + " (got " + count + ")");
            r.check(page.size() == 50, "first page of 50");
            long expectedD7 = 0;
            for (int i = 0; i < n; i++) {
                if (i % 30 == 7) {
                    expectedD7++;
                }
            }
            r.check(d07 == expectedD7, "variable filter district=D7: " + d07 + " (expected " + expectedD7 + ")");
            Map<String, Long> rowsStarted = Db.engineTableRows(db);

            // bulk withdraw: 10 % deleted in one engine call
            List<String> withdrawn = new ArrayList<>(pids.subList(0, n / 10));
            t0 = System.nanoTime();
            k().bulkDelete(a, withdrawn, "WITHDRAWN_BY_APPLICANT");
            r.timing("bulkDelete_" + withdrawn.size(), ms(t0));
            r.nativeApi(k().api("bulkDelete"));

            // bulk decision on the rest: no bulk-complete API in either engine -> paged ids, 4 threads
            t0 = System.nanoTime();
            AtomicInteger done = new AtomicInteger();
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            try {
                while (true) {
                    List<String> ids = k().taskIds(a, null, "gp08", "scrutiny", 2000);
                    if (ids.isEmpty()) {
                        break;
                    }
                    List<Future<?>> fs = new ArrayList<>();
                    int chunk = (ids.size() + threads - 1) / threads;
                    for (int tIdx = 0; tIdx < threads; tIdx++) {
                        List<String> part = ids.subList(Math.min(ids.size(), tIdx * chunk), Math.min(ids.size(), (tIdx + 1) * chunk));
                        fs.add(ex.submit(() -> {
                            for (String id : part) {
                                int c = done.incrementAndGet();
                                a.completeTask(id, Map.of("decision", c % 2 == 0 ? "approve" : "reject"));
                            }
                        }));
                    }
                    for (Future<?> f : fs) {
                        f.get();
                    }
                }
            } finally {
                ex.shutdown();
            }
            long decideMs = ms(t0);
            r.timing("bulkDecision_" + done.get() + "_tasks", decideMs);
            r.nativeApi(k().api("taskIds"));
            r.workaround("bulk approve/reject: neither engine has a bulk task-completion API; the product layer pages task "
                    + "ids and completes them with 4 threads", 25);
            r.check(done.get() == n - withdrawn.size(), "decided " + done.get() + " (expected " + (n - withdrawn.size()) + ")");
            r.check(k().runningInstances(a, "gp08") == 0, "no instance left running");
            r.check(k().finishedInstances(a, "gp08") == n, "history has all " + n + " instances (incl. withdrawn)");
            r.nativeApi(k().api("instances"));
            Map<String, Long> rows1 = Db.engineTableRows(db);
            r.detail("instances", n);
            r.detail("threads", threads);
            r.detail("startThroughputPerSec", Math.round(n * 1000.0 / Math.max(1, startMs)));
            r.detail("decisionThroughputPerSec", Math.round(done.get() * 1000.0 / Math.max(1, decideMs)));
            r.detail("dbRowsAfterStart", rowDelta(rows0, rowsStarted));
            r.detail("dbRowsAtEnd", rowDelta(rows0, rows1));
            r.detail("heapUsedAfterGcMbBefore", heap0);
            r.detail("heapUsedAfterGcMbAfter", heapUsedAfterGcMb());
            r.detail("peakHeapMb", peakHeapMb());
        });
    }

    private interface IntFn<T> {
        T apply(int i) throws Exception;
    }

    private static <T> List<T> parallel(int threads, int n, IntFn<T> fn) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<T>>> fs = new ArrayList<>();
            int chunk = (n + threads - 1) / threads;
            for (int t = 0; t < threads; t++) {
                int from = t * chunk;
                int to = Math.min(n, from + chunk);
                fs.add(ex.submit(() -> {
                    List<T> out = new ArrayList<>();
                    for (int i = from; i < to; i++) {
                        out.add(fn.apply(i));
                    }
                    return out;
                }));
            }
            List<T> all = new ArrayList<>();
            for (Future<List<T>> f : fs) {
                all.addAll(f.get());
            }
            return all;
        } finally {
            ex.shutdown();
            ex.awaitTermination(1, TimeUnit.MINUTES);
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    // =====================================================================================================  GP-09

    @Test
    void gp09_largeMultiInstance() {
        scenario("GP-09", "Large multi-instance (>= 5,000 items in one instance)", r -> {
            int n = scaleN();
            long capMs = Long.getLong("gov.gp09.capMs", 180_000L);
            r.feature("parallel multi-instance userTask, loopCardinality ${n}", "history volume of one large instance");
            String bpmn = defs("<process id=\"gp09\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "verify")
                    + "<userTask id=\"verify\" name=\"verify\"><potentialOwner><resourceAssignmentExpression><formalExpression>"
                    + "group(SEAT-VERIFIER)</formalExpression></resourceAssignmentExpression></potentialOwner>"
                    + "<multiInstanceLoopCharacteristics isSequential=\"false\"><loopCardinality xsi:type=\"tFormalExpression\">"
                    + "${n}</loopCardinality></multiInstanceLoopCharacteristics></userTask>"
                    + flow("f2", "verify", "summary") + userTask("summary", "SEAT-DDO") + flow("f3", "summary", "e")
                    + "<endEvent id=\"e\"/></process>");
            deploy("gp09", "gp09.bpmn", bpmn);
            Map<String, Long> rows0 = Db.engineTableRows(db);
            long heap0 = heapUsedAfterGcMb();
            resetPeaks();
            long t0 = System.nanoTime();
            String pid = start("gp09", "BENEFICIARY-LIST-1", "n", n);
            long createMs = ms(t0);
            r.timing("start_with_" + n + "_items", createMs);
            Map<String, Long> rowsCreated = Db.engineTableRows(db);
            long open = a.countTasks(new TaskQuery(null, null, List.of("SEAT-VERIFIER"), pid, null, 0, 0));
            r.check(open == n, n + " item tasks open (got " + open + ")");
            long heapCreated = heapUsedAfterGcMb();
            t0 = System.nanoTime();
            int completed = 0;
            boolean capped = false;
            List<Long> per1000 = new ArrayList<>();
            long lap = System.nanoTime();
            outer:
            while (true) {
                List<String> ids = k().taskIds(a, pid, null, "verify", 1000);
                if (ids.isEmpty()) {
                    break;
                }
                for (String id : ids) {
                    a.completeTask(id, Map.of());
                    completed++;
                    if (completed % 1000 == 0) {
                        per1000.add(ms(lap));
                        lap = System.nanoTime();
                    }
                    if (ms(t0) > capMs) {
                        capped = true;
                        break outer;
                    }
                }
            }
            long completeMs = ms(t0);
            r.timing("complete_" + completed + "_items_1_thread", completeMs);
            r.nativeApi(k().api("taskIds"));
            r.detail("msPer1000Completions", per1000);
            r.check(!capped, "all " + n + " items completed within the cap of " + capMs + " ms (completed " + completed
                    + " in " + completeMs + " ms; per-completion cost grows with the number of open items)");
            Map<String, Long> rows1 = Db.engineTableRows(db);
            if (!capped) {
                r.check(tasks(pid, "summary").size() == 1, "summary task after the last item");
                a.completeTask(one(pid, "summary").id(), Map.of());
                r.check(ended(pid), "instance ended");
                rows1 = Db.engineTableRows(db);
            } else {
                r.detail("projectedTotalCompletionMsLinear", Math.round(completeMs * (double) n / Math.max(1, completed)));
                long tc = System.nanoTime();
                a.cancelInstance(pid, "GP-09 cap reached");
                r.timing("cancelInstanceWithOpenItems", ms(tc));
                r.note("Not finished within the cap: completing one item of a parallel multi-instance body costs time "
                        + "proportional to the open items (total ~ n^2). Workaround for beneficiary lists: one instance per "
                        + "item (GP-08 shape, linear) or chunked sub-processes; not measured here.");
            }
            r.detail("items", n);
            r.detail("completed", completed);
            r.detail("dbRowsAfterCreate", rowDelta(rows0, rowsCreated));
            r.detail("dbRowsAtEnd", rowDelta(rows0, rows1));
            r.detail("heapUsedAfterGcMbBefore", heap0);
            r.detail("heapUsedAfterGcMbWithInstanceOpen", heapCreated);
            r.detail("peakHeapMb", peakHeapMb());
        });
    }

    // =====================================================================================================  GP-10

    @Test
    void gp10_multiYearMonthlyCycles() {
        scenario("GP-10", "Multi-year process with monthly cycles (36 monthly bills)", r -> {
            r.feature("boundary timer non-interrupting timeCycle R36/P1M", "long-lived instance", "monthly user tasks");
            String bpmn = defs("<process id=\"gp10\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "contract")
                    + userTask("contract", "SEAT-EE") + flow("f2", "contract", "e") + "<endEvent id=\"e\"/>"
                    + "<boundaryEvent id=\"month\" attachedToRef=\"contract\" cancelActivity=\"false\"><timerEventDefinition>"
                    + "<timeCycle xsi:type=\"tFormalExpression\">R36/P1M</timeCycle></timerEventDefinition></boundaryEvent>"
                    + flow("f3", "month", "bill") + userTask("bill", "SEAT-AE") + flow("f4", "bill", "billEnd")
                    + "<endEvent id=\"billEnd\"/></process>");
            deploy("gp10", "gp10.bpmn", bpmn);
            clock("2026-01-31T10:00:00");
            String pid = start("gp10", "AMC-2026-01");
            List<String> dues = new ArrayList<>();
            java.time.ZonedDateTime t = ist("2026-01-31T10:00:00").toInstant().atZone(BizCal.IST);
            Map<String, Long> rows0 = null;
            for (int m = 1; m <= 37; m++) {
                List<String[]> tm = k().timers(a, pid);
                if (tm.isEmpty()) {
                    dues.add("none");
                    t = t.plusMonths(1);
                    clock(t.plusMinutes(1).toInstant());
                    jobs();
                    continue;
                }
                String due = tm.get(0)[1];
                dues.add(java.time.Instant.parse(due).atZone(BizCal.IST).toLocalDateTime().toString());
                clock(java.time.Instant.parse(due).plusSeconds(60));
                jobs();
                for (TaskView b : tasks(pid, "bill")) {
                    a.completeTask(b.id(), Map.of());
                }
                if (m == 1) {
                    rows0 = Db.engineTableRows(db);
                }
            }
            r.nativeApi(k().api("timers"));
            long bills = k().historicActivityCount(a, pid, "bill");
            r.check(bills == 36, "36 monthly bills over 3 years (got " + bills + ")");
            r.check(k().timers(a, pid).isEmpty(), "no 37th cycle");
            r.check(tasks(pid, "contract").size() == 1, "contract still running after 3 years");
            Map<String, Long> rows36 = Db.engineTableRows(db);
            long ru0 = rows0.entrySet().stream().filter(e -> e.getKey().contains("_RU_")).mapToLong(Map.Entry::getValue).sum();
            long ru36 = rows36.entrySet().stream().filter(e -> e.getKey().contains("_RU_")).mapToLong(Map.Entry::getValue).sum();
            r.detail("cycleDueDatesIst", dues);
            r.detail("runtimeRowsAfterMonth1", ru0);
            r.detail("runtimeRowsAfterMonth36", ru36);
            r.detail("historyRowsGrowthMonth1To36", rowDelta(rows0, rows36).get("historyRowsDelta"));
            Map<String, Object> ruGrowth = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : rows36.entrySet()) {
                long d = e.getValue() - rows0.getOrDefault(e.getKey(), 0L);
                if (e.getKey().contains("_RU_") && d != 0) {
                    ruGrowth.put(e.getKey(), d);
                }
            }
            r.detail("runtimeRowGrowthPerTableMonth1To36", ruGrowth);
            if (ru36 > ru0) {
                r.note("Runtime rows of the running instance grow with every cycle (" + ruGrowth + "): a multi-year "
                        + "instance carries its activity trail in runtime tables until it ends");
            }
            a.completeTask(one(pid, "contract").id(), Map.of());
            r.check(ended(pid), "contract closed");
            r.note("BPMN has no continue-as-new; the instance stays one instance for 3 years and history grows per cycle.");
            String d1 = dues.get(0);
            String d2 = dues.get(1);
            r.detail("monthEndBehaviour", "start 31 Jan; 1st cycle " + d1 + ", 2nd cycle " + d2);
            if (d2.startsWith("2026-03-28")) {
                r.note("P1M repeats from the previous due date, so a cycle started on the 31st drifts to the 28th after "
                        + "February; a fixed billing day needs a cron-like cycle or a date computed by the product");
            }
        });
    }

    // =====================================================================================================  GP-11

    @Test
    void gp11_budgetEncumbranceReleasedOnCancel() {
        scenario("GP-11", "Budget availability + encumbrance (idempotent), released on cancel by compensation", r -> {
            r.feature("external task (pull worker) with lease", "operationId from activityInstanceId (VI.3.2)",
                    "compensation boundary + isForCompensation handler (external task)", "intermediate throw compensate",
                    "interrupting message boundary (cancel)");
            r.variant("external task: " + (k().engine().equals("operaton") ? "operaton:type=external operaton:topic"
                    : "flowable:type=external-worker flowable:topic"));
            String bpmn = defs("<message id=\"mCancel\" name=\"cancelBill\"/>"
                    + "<process id=\"gp11\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "enc")
                    + k().externalTask("enc", "budget-encumber", "")
                    + "<boundaryEvent id=\"encComp\" attachedToRef=\"enc\"><compensateEventDefinition/></boundaryEvent>"
                    + k().externalTask("rel", "budget-release", "isForCompensation=\"true\"")
                    + flow("f2", "enc", "approve") + userTask("approve", "SEAT-DDO")
                    + "<boundaryEvent id=\"cancel\" attachedToRef=\"approve\"><messageEventDefinition messageRef=\"mCancel\"/></boundaryEvent>"
                    + flow("f3", "approve", "cons") + k().externalTask("cons", "budget-consume", "") + flow("f4", "cons", "paid")
                    + "<endEvent id=\"paid\"/>"
                    + flow("f5", "cancel", "comp") + "<intermediateThrowEvent id=\"comp\"><compensateEventDefinition/></intermediateThrowEvent>"
                    + flow("f6", "comp", "cancelled") + "<endEvent id=\"cancelled\"/>"
                    + "<association id=\"as1\" associationDirection=\"One\" sourceRef=\"encComp\" targetRef=\"rel\"/>"
                    + "</process>");
            deploy("gp11", "gp11.bpmn", bpmn);
            Map<String, Long> available = new ConcurrentHashMap<>(Map.of("2059-01-101", 1_000_000L));
            Map<String, Long> encumbered = new ConcurrentHashMap<>();
            AtomicInteger effects = new AtomicInteger();
            Handler encumber = w -> {
                String op = operationId(w);
                long amt = ((Number) w.variables().get("amount")).longValue();
                String head = (String) w.variables().get("head");
                if (encumbered.putIfAbsent(op, amt) == null) {
                    available.merge(head, -amt, Long::sum);
                    effects.incrementAndGet();
                }
                return Outcome.complete(Map.of("encOpId", op));
            };
            clock("2026-11-05T10:00:00");
            // A: encumbered, worker crashes after the effect, lease expires, re-delivered; then cancelled
            String pa = start("gp11", "BILL-A", "amount", 100_000L, "head", "2059-01-101");
            List<String> firstOps = new ArrayList<>();
            work("budget-encumber", w -> {
                encumber.handle(w);
                firstOps.add(operationId(w));
                return Outcome.crash();
            });
            clock("2026-11-05T10:06:00");
            List<String> secondOps = new ArrayList<>();
            int redelivered = work("budget-encumber", w -> {
                secondOps.add(operationId(w));
                return encumber.handle(w);
            });
            r.check(redelivered == 1 && firstOps.equals(secondOps), "re-delivery after lease expiry carries the same operationId");
            r.check(effects.get() == 1 && available.get("2059-01-101") == 900_000L, "encumbered exactly once (idempotent)");
            r.check(tasks(pa, "approve").size() == 1, "bill waits for approval");
            AtomicInteger releases = new AtomicInteger();
            Handler release = w -> {
                String op = (String) w.variables().get("encOpId");
                Long amt = encumbered.remove(op);
                if (amt != null) {
                    available.merge((String) w.variables().get("head"), amt, Long::sum);
                    releases.incrementAndGet();
                }
                return Outcome.complete(Map.of());
            };
            String compError = null;
            try {
                a.correlateMessage(null, "cancelBill", "BILL-A", Map.of());
            } catch (RuntimeException e) {
                compError = Rec.errorText(e);
            }
            if (compError == null) {
                int rel = work("budget-release", release);
                r.check(rel == 1 && releases.get() == 1, "compensation ran the release handler once (fetched " + rel + ")");
                r.check(available.get("2059-01-101") == 1_000_000L, "budget restored after cancel");
                r.check(ended(pa) && k().historicActivityCount(a, pa, "cancelled") == 1, "bill ended as cancelled");
            } else {
                r.detail("compensationError", compError);
                r.note("Compensation with a wait-state handler (external task) failed on this engine; the cancel is "
                        + "rolled back. Fallback: the cancel path is modelled as an explicit release step.");
                r.workaround("no working compensation with a wait-state handler: cancel boundary -> explicit release "
                        + "external task -> cancelled (no compensation event)", 4);
                String explicit = defs("<message id=\"mCancel\" name=\"cancelBillX\"/>"
                        + "<process id=\"gp11x\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "enc")
                        + k().externalTask("enc", "budget-encumber", "") + flow("f2", "enc", "approve") + userTask("approve", "SEAT-DDO")
                        + "<boundaryEvent id=\"cancel\" attachedToRef=\"approve\"><messageEventDefinition messageRef=\"mCancel\"/></boundaryEvent>"
                        + flow("f3", "approve", "paid") + "<endEvent id=\"paid\"/>"
                        + flow("f5", "cancel", "rel") + k().externalTask("rel", "budget-release", "")
                        + flow("f6", "rel", "cancelled") + "<endEvent id=\"cancelled\"/></process>");
                deploy("gp11x", "gp11x.bpmn", explicit);
                a.cancelInstance(pa, "test cleanup after the failed compensation");
                encumbered.clear();
                available.put("2059-01-101", 1_000_000L);
                effects.set(0);
                String px = start("gp11x", "BILL-AX", "amount", 100_000L, "head", "2059-01-101");
                work("budget-encumber", encumber);
                a.correlateMessage(null, "cancelBillX", "BILL-AX", Map.of());
                int rel = work("budget-release", release);
                r.check(rel == 1 && available.get("2059-01-101") == 1_000_000L && ended(px),
                        "explicit release path restored the budget");
            }
            // B: approved -> consumed, no release
            String pb = start("gp11", "BILL-B", "amount", 250_000L, "head", "2059-01-101");
            work("budget-encumber", encumber);
            a.completeTask(one(pb, "approve").id(), Map.of());
            work("budget-consume", w -> {
                encumbered.remove((String) w.variables().get("encOpId"));
                return Outcome.complete(Map.of());
            });
            r.check(work("budget-release", w -> Outcome.complete(Map.of())) == 0, "no release on the approved path");
            r.check(ended(pb) && available.get("2059-01-101") == 750_000L, "approved bill consumed the encumbrance");
            r.productLayer("encumbrance ledger and its idempotency check (operationId) live in the product layer", 12);
        });
    }

    // =====================================================================================================  GP-12

    @Test
    void gp12_paymentUnknownOutcomeAndReconciliation() {
        scenario("GP-12", "Payment with unknown outcome + reconciliation (IFMS JIT simulator)", r -> {
            r.feature("external task", "BPMN error boundary UNKNOWN_OUTCOME on an external task", "retry by failure",
                    "reconciliation loop (gateway back to payment)");
            r.variant("external task topic attributes (" + k().engine() + ")");
            String bpmn = defs("<error id=\"eUnknown\" errorCode=\"UNKNOWN_OUTCOME\"/>"
                    + "<process id=\"gp12\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "pay")
                    + k().externalTask("pay", "ifms-pay", "")
                    + "<boundaryEvent id=\"unknown\" attachedToRef=\"pay\"><errorEventDefinition errorRef=\"eUnknown\"/></boundaryEvent>"
                    + flow("f2", "pay", "paid") + "<endEvent id=\"paid\"/>"
                    + flow("f3", "unknown", "recon") + k().externalTask("recon", "ifms-status", "")
                    + flow("f4", "recon", "gw") + "<exclusiveGateway id=\"gw\" default=\"fFail\"/>"
                    + flow("fOk", "gw", "paidAfterRecon", "${paymentStatus == 'SUCCESS'}")
                    + flow("fRetry", "gw", "pay", "${paymentStatus == 'NOT_FOUND'}")
                    + flow("fFail", "gw", "failure") + "<endEvent id=\"paidAfterRecon\"/>"
                    + userTask("failure", "SEAT-DDO") + flow("f5", "failure", "failedEnd") + "<endEvent id=\"failedEnd\"/>"
                    + "</process>");
            deploy("gp12", "gp12.bpmn", bpmn);
            try (IfmsJitSimulator sim = new IfmsJitSimulator().failAccount("ACC-BAD").start()) {
                IfmsClient ifms = new IfmsClient(sim);
                ifms.status("warm-up"); // authenticate first, so the injected faults hit the PI call itself
                Handler pay = w -> {
                    // stable across the model-level retry loop: one payment per bill instance
                    String op = UUID.nameUUIDFromBytes(("gov-a|pay|" + w.processInstanceId()).getBytes()).toString();
                    try {
                        String ref = ifms.pay(op, (String) w.variables().get("billId"),
                                ((Number) w.variables().get("amount")).longValue(), (String) w.variables().get("account"));
                        return Outcome.complete(Map.of("paymentRef", ref, "operationId", op));
                    } catch (IOException e) {
                        return Outcome.bpmnError("UNKNOWN_OUTCOME", Map.of("operationId", op));
                    } catch (IfmsClient.PartnerError e) {
                        return Outcome.fail(e.getMessage());
                    }
                };
                Handler recon = w -> Outcome.complete(Map.of("paymentStatus",
                        ifms.status((String) w.variables().get("operationId"))));
                // A: response lost after the payment was made
                String pa = start("gp12", "BILL-A", "billId", "BILL-A", "amount", 125_000L, "account", "ACC-1");
                sim.faults().once(Fault.dropResponse().onRoute(IfmsJitSimulator.DATA_PATH));
                work("ifms-pay", pay);
                work("ifms-status", recon);
                String opA = (String) var(pa, "operationId");
                r.check(ended(pa) && k().historicActivityCount(a, pa, "paidAfterRecon") == 1,
                        "lost response -> UNKNOWN_OUTCOME -> reconciliation found SUCCESS -> paid");
                r.check(sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, opA) == 1, "paid exactly once");
                // B: definite partner error (nothing done) -> retry -> paid
                String pb = start("gp12", "BILL-B", "billId", "BILL-B", "amount", 75_000L, "account", "ACC-2");
                sim.faults().once(Fault.error(503).onRoute(IfmsJitSimulator.DATA_PATH));
                work("ifms-pay", pay);
                work("ifms-pay", pay);
                r.check(ended(pb) && k().historicActivityCount(a, pb, "paid") == 1, "503 -> failure with retry -> paid");
                // C: response lost, bank rejected the account -> reconciliation FAILED -> failure handling task
                String pc = start("gp12", "BILL-C", "billId", "BILL-C", "amount", 10_000L, "account", "ACC-BAD");
                sim.faults().once(Fault.dropResponse().onRoute(IfmsJitSimulator.DATA_PATH));
                work("ifms-pay", pay);
                work("ifms-status", recon);
                r.check(tasks(pc, "failure").size() == 1, "reconciliation FAILED -> failure-handling task");
                r.check(!sim.ledger().hasDuplicates(IfmsJitSimulator.EFFECT_PAYMENT), "no duplicate payment overall");
                r.detail("ifmsRequests", sim.requestsReceived());
            }
            r.productLayer("operationId derived from (tenant, instance, 'pay') instead of activityInstanceId: the activity "
                    + "instance id changes on the model-level retry loop on both engines (EngineAdapter.ExternalWork doc)", 2);
        });
    }

    // =====================================================================================================  GP-13

    @Test
    void gp13_refundViaCompensation() {
        scenario("GP-13", "EMD refund via compensation (multi-instance collected, all refunded on cancellation)", r -> {
            r.feature("compensation boundary on a parallel multi-instance activity", "user-task compensation handler",
                    "intermediate throw compensate");
            String bpmn = defs("<process id=\"gp13\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "collect")
                    + "<userTask id=\"collect\" name=\"collect\"><potentialOwner><resourceAssignmentExpression><formalExpression>"
                    + "group(SEAT-CASHIER)</formalExpression></resourceAssignmentExpression></potentialOwner>"
                    + "<multiInstanceLoopCharacteristics isSequential=\"false\"><loopCardinality xsi:type=\"tFormalExpression\">"
                    + "${bidders}</loopCardinality></multiInstanceLoopCharacteristics></userTask>"
                    + "<boundaryEvent id=\"collectComp\" attachedToRef=\"collect\"><compensateEventDefinition/></boundaryEvent>"
                    + "<userTask id=\"refund\" name=\"refund\" isForCompensation=\"true\"><potentialOwner><resourceAssignmentExpression>"
                    + "<formalExpression>group(SEAT-TREASURY)</formalExpression></resourceAssignmentExpression></potentialOwner></userTask>"
                    + flow("f2", "collect", "evaluate") + userTask("evaluate", "SEAT-TEC") + flow("f3", "evaluate", "gw")
                    + "<exclusiveGateway id=\"gw\" default=\"fAward\"/>"
                    + flow("fCancel", "gw", "comp", "${tenderCancelled == true}") + flow("fAward", "gw", "awarded")
                    + "<endEvent id=\"awarded\"/>"
                    + "<intermediateThrowEvent id=\"comp\"><compensateEventDefinition/></intermediateThrowEvent>"
                    + flow("f4", "comp", "cancelled") + "<endEvent id=\"cancelled\"/>"
                    + "<association id=\"as1\" associationDirection=\"One\" sourceRef=\"collectComp\" targetRef=\"refund\"/>"
                    + "</process>");
            deploy("gp13", "gp13.bpmn", bpmn);
            String pid = start("gp13", "TENDER-13", "bidders", 3);
            List<TaskView> collects = tasks(pid, "collect");
            r.check(collects.size() == 3, "3 EMD collections");
            int i = 0;
            for (TaskView t : collects) {
                a.completeTask(t.id(), Map.of("emdRef" + (i++), "EMD-" + i));
            }
            String compError = null;
            try {
                a.completeTask(one(pid, "evaluate").id(), Map.of("tenderCancelled", true));
            } catch (RuntimeException e) {
                compError = Rec.errorText(e);
                r.detail("compensationError", compError);
            }
            r.check(compError == null, "throwing compensation with user-task refund handlers works");
            if (compError == null) {
                List<TaskView> refunds = tasks(pid, "refund");
                r.detail("refundTasksAfterCompensation", refunds.size());
                r.check(refunds.size() == 3, "compensation creates one refund per collected EMD (got " + refunds.size() + ")");
                for (TaskView t : refunds) {
                    a.completeTask(t.id(), Map.of());
                }
                r.check(ended(pid) && k().historicActivityCount(a, pid, "cancelled") == 1, "tender ended as cancelled after refunds");
            } else {
                r.note("Workaround available (not counted as a pass for a compensation pattern): an explicit refund step "
                        + "on the cancel path instead of compensation (see GP-11 fallback).");
            }
            r.detail("compensationHandlerMatrix", compensationMatrix());
            // awarded path: no refund by compensation
            String p2 = start("gp13", "TENDER-14", "bidders", 2);
            for (TaskView t : tasks(p2, "collect")) {
                a.completeTask(t.id(), Map.of());
            }
            a.completeTask(one(p2, "evaluate").id(), Map.of("tenderCancelled", false));
            r.check(ended(p2) && k().historicActivityCount(a, p2, "refund") == 0, "awarded path: no compensation");
        });
    }

    /**
     * Compensation probe: handler type (userTask / manualTask / external task) x trigger (normal flow / interrupting
     * message boundary) x compensated activity (single / parallel multi-instance of 2). Each cell: OK + handler count, or
     * the error.
     */
    private Map<String, String> compensationMatrix() {
        Map<String, String> out = new LinkedHashMap<>();
        int n = 0;
        for (String handler : new String[]{"userTask", "manualTask", "externalTask"}) {
            for (boolean msg : new boolean[]{false, true}) {
                for (boolean mi : new boolean[]{false, true}) {
                    n++;
                    String key = "cm" + n;
                    String cell = handler + (msg ? " / message-boundary" : " / normal-flow") + (mi ? " / multi-instance" : " / single");
                    String h = switch (handler) {
                        case "userTask" -> "<userTask id=\"rel\" isForCompensation=\"true\"/>";
                        case "manualTask" -> "<manualTask id=\"rel\" isForCompensation=\"true\"/>";
                        default -> k().externalTask("rel", "cm-rel", "isForCompensation=\"true\"");
                    };
                    String xml = defs("<message id=\"m" + n + "\" name=\"cmCancel" + n + "\"/><process id=\"" + key
                            + "\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "act") + "<userTask id=\"act\">"
                            + (mi ? "<multiInstanceLoopCharacteristics isSequential=\"false\"><loopCardinality xsi:type=\"tFormalExpression\">2"
                            + "</loopCardinality></multiInstanceLoopCharacteristics>" : "") + "</userTask>"
                            + "<boundaryEvent id=\"bc\" attachedToRef=\"act\"><compensateEventDefinition/></boundaryEvent>" + h
                            + flow("f2", "act", "next") + "<userTask id=\"next\"/>"
                            + (msg ? "<boundaryEvent id=\"cx\" attachedToRef=\"next\"><messageEventDefinition messageRef=\"m" + n
                            + "\"/></boundaryEvent>" + flow("f3", "cx", "comp") : flow("f3", "next", "comp"))
                            + "<intermediateThrowEvent id=\"comp\"><compensateEventDefinition/></intermediateThrowEvent>"
                            + flow("f4", "comp", "e") + "<endEvent id=\"e\"/>"
                            + "<association id=\"as\" associationDirection=\"One\" sourceRef=\"bc\" targetRef=\"rel\"/></process>");
                    try {
                        deploy(key, key + ".bpmn", xml);
                        String pid = start(key, "CM-" + n);
                        for (TaskView t : tasks(pid, "act")) {
                            a.completeTask(t.id(), Map.of());
                        }
                        if (msg) {
                            a.correlateMessage(null, "cmCancel" + n, "CM-" + n, Map.of());
                        } else {
                            a.completeTask(one(pid, "next").id(), Map.of());
                        }
                        int handlers;
                        if (handler.equals("externalTask")) {
                            handlers = work("cm-rel", w -> Outcome.complete(Map.of()));
                        } else {
                            List<TaskView> hs = tasks(pid, "rel");
                            handlers = handler.equals("manualTask") ? (int) k().historicActivityCount(a, pid, "rel") : hs.size();
                            for (TaskView t : hs) {
                                a.completeTask(t.id(), Map.of());
                            }
                        }
                        out.put(cell, "OK handlers=" + handlers + " ended=" + ended(pid));
                    } catch (Throwable t) {
                        String e = Rec.errorText(t);
                        int fk = e.indexOf("Referential integrity");
                        int pgFk = e.indexOf("violates foreign key");
                        out.put(cell, "ERROR " + e.substring(0, Math.min(140, e.length()))
                                + (fk >= 0 || pgFk >= 0 ? " [foreign-key violation on ACT_RU_EXECUTION]" : ""));
                    }
                }
            }
        }
        r().nativeApi(k().api("historicActivityCount"));
        return out;
    }

    private Rec r() {
        return rec;
    }

    // =====================================================================================================  GP-14

    @Test
    void gp14_withdrawalOrCancellationAtAnyStage() {
        scenario("GP-14", "Withdrawal / cancellation at any stage (interrupting event sub-processes)", r -> {
            r.feature("interrupting event sub-process with message start (withdraw, per instance)",
                    "interrupting event sub-process with signal start (tender cancelled, broadcast)");
            String bpmn = defs("<message id=\"mW\" name=\"withdraw\"/><signal id=\"sC\" name=\"tenderCancelled\"/>"
                    + "<process id=\"gp14\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "s1")
                    + userTask("s1", "SEAT-A") + flow("f2", "s1", "s2") + userTask("s2", "SEAT-B") + flow("f3", "s2", "s3")
                    + userTask("s3", "SEAT-C") + flow("f4", "s3", "e") + "<endEvent id=\"e\"/>"
                    + "<subProcess id=\"evW\" triggeredByEvent=\"true\"><startEvent id=\"evWs\" isInterrupting=\"true\">"
                    + "<messageEventDefinition messageRef=\"mW\"/></startEvent>" + flow("w1", "evWs", "recordW")
                    + userTask("recordW", "SEAT-A") + flow("w2", "recordW", "evWe") + "<endEvent id=\"evWe\"/></subProcess>"
                    + "<subProcess id=\"evC\" triggeredByEvent=\"true\"><startEvent id=\"evCs\" isInterrupting=\"true\">"
                    + "<signalEventDefinition signalRef=\"sC\"/></startEvent>" + flow("c1", "evCs", "evCe")
                    + "<endEvent id=\"evCe\"/></subProcess></process>");
            deploy("gp14", "gp14.bpmn", bpmn);
            String p1 = start("gp14", "APP-1");
            String p2 = start("gp14", "APP-2");
            a.completeTask(one(p2, "s1").id(), Map.of());
            String p3 = start("gp14", "APP-3");
            a.completeTask(one(p3, "s1").id(), Map.of());
            a.completeTask(one(p3, "s2").id(), Map.of());
            a.correlateMessage(null, "withdraw", "APP-2", Map.of("withdrawReason", "applicant request"));
            r.check(names(tasks(p2)).equals(List.of("recordW")), "APP-2 withdrawn at stage 2: only the withdrawal task remains");
            a.completeTask(one(p2, "recordW").id(), Map.of());
            r.check(ended(p2), "APP-2 closed as withdrawn");
            k().broadcastSignal(a, "tenderCancelled");
            r.nativeApi(k().api("broadcastSignal") + " (EngineAdapter has no signal call)");
            r.check(ended(p1) && ended(p3), "signal cancels every running instance at any stage (APP-1 at s1, APP-3 at s3)");
            r.check(k().historicActivityCount(a, p3, "evCe") == 1, "APP-3 ended through the cancellation event sub-process");
        });
    }

    // =====================================================================================================  GP-15

    @Test
    void gp15_externalCallbacksIncludingEarlyArrival() {
        scenario("GP-15", "External portal callbacks, including early arrival and duplicates", r -> {
            r.feature("intermediate message catch, correlation by business key", "parallel gateway opening the subscription early");
            String naive = defs("<message id=\"mFee\" name=\"feePaid\"/><process id=\"gp15\" isExecutable=\"true\">"
                    + "<startEvent id=\"s\"/>" + flow("f1", "s", "prepare") + userTask("prepare", "SEAT-CLERK")
                    + flow("f2", "prepare", "waitFee") + "<intermediateCatchEvent id=\"waitFee\"><messageEventDefinition messageRef=\"mFee\"/></intermediateCatchEvent>"
                    + flow("f3", "waitFee", "scrutiny") + userTask("scrutiny", "SEAT-SCRUTINY") + flow("f4", "scrutiny", "e")
                    + "<endEvent id=\"e\"/></process>");
            String early = defs("<message id=\"mFee2\" name=\"feePaidEarly\"/><process id=\"gp15b\" isExecutable=\"true\">"
                    + "<startEvent id=\"s\"/>" + flow("f1", "s", "fork") + "<parallelGateway id=\"fork\"/>"
                    + flow("f2", "fork", "prepare") + userTask("prepare", "SEAT-CLERK") + flow("f3", "prepare", "join")
                    + flow("f4", "fork", "waitFee") + "<intermediateCatchEvent id=\"waitFee\"><messageEventDefinition messageRef=\"mFee2\"/></intermediateCatchEvent>"
                    + flow("f5", "waitFee", "join") + "<parallelGateway id=\"join\"/>" + flow("f6", "join", "scrutiny")
                    + userTask("scrutiny", "SEAT-SCRUTINY") + flow("f7", "scrutiny", "e") + "<endEvent id=\"e\"/></process>");
            deploy("gp15", "gp15.bpmn", naive, "gp15b.bpmn", early);
            // normal arrival
            String p0 = start("gp15", "APP-0");
            a.completeTask(one(p0, "prepare").id(), Map.of());
            a.correlateMessage(null, "feePaid", "APP-0", Map.of("challanNo", "CH-0"));
            r.check(tasks(p0, "scrutiny").size() == 1, "on-time callback correlated");
            // early arrival on the naive model
            String p1 = start("gp15", "APP-1");
            String earlyError = null;
            try {
                a.correlateMessage(null, "feePaid", "APP-1", Map.of("challanNo", "CH-1"));
            } catch (RuntimeException e) {
                earlyError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            r.detail("earlyCallbackOnNaiveModel", earlyError == null ? "accepted (unexpected)" : "rejected: " + earlyError);
            r.check(earlyError != null, "engine does not buffer a message that arrives before the subscription exists");
            // product inbox (VI.2): buffer, deliver when the subscription appears, dedup by callback id
            Map<String, Map<String, Object>> inbox = new LinkedHashMap<>();
            Set<String> seen = ConcurrentHashMap.newKeySet();
            if (seen.add("CB-1")) {
                inbox.put("APP-1", Map.of("challanNo", "CH-1"));
            }
            boolean dupAccepted = seen.add("CB-1");
            a.completeTask(one(p1, "prepare").id(), Map.of());
            int delivered = 0;
            for (var it = inbox.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (k().hasMessageSubscription(a, "feePaid", e.getKey())) {
                    a.correlateMessage(null, "feePaid", e.getKey(), e.getValue());
                    it.remove();
                    delivered++;
                }
            }
            r.nativeApi(k().api("hasMessageSubscription"));
            r.check(delivered == 1 && tasks(p1, "scrutiny").size() == 1 && "CH-1".equals(var(p1, "challanNo")),
                    "inbox delivered the early callback once the instance waits");
            r.check(!dupAccepted, "duplicate callback dropped by the inbox (callback id)");
            String dupError = null;
            try {
                a.correlateMessage(null, "feePaid", "APP-1", Map.of());
            } catch (RuntimeException e) {
                dupError = e.getClass().getSimpleName();
            }
            r.detail("lateDuplicateWithoutInbox", dupError == null ? "accepted (unexpected)" : "rejected: " + dupError);
            r.workaround("product inbox: buffer callbacks by business key + callback id, deliver when the engine has the "
                    + "message subscription (neither engine buffers early messages)", 20);
            // model-level alternative: subscription opened at start
            String p2 = start("gp15b", "APP-2");
            a.correlateMessage(null, "feePaidEarly", "APP-2", Map.of("challanNo", "CH-2"));
            a.completeTask(one(p2, "prepare").id(), Map.of());
            r.check(tasks(p2, "scrutiny").size() == 1, "model alternative: subscription opened at start accepts the early callback");
            r.workaround("model alternative: parallel gateway that opens the message catch at instance start", 6);
        });
    }

    // =====================================================================================================  GP-16

    @Test
    void gp16_documentChecklistClarificationLoop() {
        scenario("GP-16", "Document checklist + clarification loop with deemed closure", r -> {
            r.feature("event-based gateway (message vs timer P30D)", "loop back to scrutiny", "message correlation");
            String bpmn = defs("<message id=\"mC\" name=\"clarificationReceived\"/><process id=\"gp16\" isExecutable=\"true\">"
                    + "<startEvent id=\"s\"/>" + flow("f1", "s", "merge") + "<exclusiveGateway id=\"merge\"/>"
                    + flow("f2", "merge", "scrutiny") + userTask("scrutiny", "SEAT-SCRUTINY") + flow("f3", "scrutiny", "gw")
                    + "<exclusiveGateway id=\"gw\" default=\"fAsk\"/>"
                    + flow("fOk", "gw", "complete", "${docsComplete == true}") + flow("fAsk", "gw", "ebg")
                    + "<endEvent id=\"complete\"/><eventBasedGateway id=\"ebg\"/>"
                    + flow("f4", "ebg", "reply") + "<intermediateCatchEvent id=\"reply\"><messageEventDefinition messageRef=\"mC\"/></intermediateCatchEvent>"
                    + flow("f5", "reply", "merge")
                    + flow("f6", "ebg", "noReply") + "<intermediateCatchEvent id=\"noReply\"><timerEventDefinition>"
                    + "<timeDuration xsi:type=\"tFormalExpression\">P30D</timeDuration></timerEventDefinition></intermediateCatchEvent>"
                    + flow("f7", "noReply", "closed") + "<endEvent id=\"closed\"/></process>");
            deploy("gp16", "gp16.bpmn", bpmn);
            clock("2026-11-05T10:00:00");
            String pid = start("gp16", "LIC-1");
            for (int round = 1; round <= 2; round++) {
                a.completeTask(one(pid, "scrutiny").id(), Map.of("docsComplete", false, "deficiency",
                        round == 1 ? "affidavit;site plan" : "site plan"));
                r.check(tasks(pid).isEmpty(), "round " + round + ": waiting for the applicant");
                a.correlateMessage(null, "clarificationReceived", "LIC-1", Map.of("round", round));
                r.check(tasks(pid, "scrutiny").size() == 1, "round " + round + ": back to scrutiny");
            }
            a.completeTask(one(pid, "scrutiny").id(), Map.of("docsComplete", true));
            r.check(ended(pid) && k().historicActivityCount(a, pid, "scrutiny") == 3, "accepted after 2 clarifications");
            String p2 = start("gp16", "LIC-2");
            a.completeTask(one(p2, "scrutiny").id(), Map.of("docsComplete", false, "deficiency", "fee"));
            clock("2026-12-05T10:01:00");
            jobs();
            r.check(ended(p2) && k().historicActivityCount(a, p2, "closed") == 1, "no reply in 30 days -> deemed closed");
        });
    }

    // =====================================================================================================  GP-17

    @Test
    void gp17_inspectionSchedulingAndReassignment() {
        scenario("GP-17", "Field inspection scheduling (due date), rescheduling and reassignment", r -> {
            r.feature("user task due date expression", "humanPerformer assignee expression", "task reassignment",
                    "assignment history");
            r.variant(k().dueDateAttr("${inspectionDate}") + " (BPMN 2.0.2 has no task due date)");
            String bpmn = defs("<process id=\"gp17\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "inspect")
                    + "<userTask id=\"inspect\" name=\"inspect\" " + k().dueDateAttr("${inspectionDate}") + ">"
                    + "<humanPerformer><resourceAssignmentExpression><formalExpression>${inspector}</formalExpression>"
                    + "</resourceAssignmentExpression></humanPerformer></userTask>"
                    + flow("f2", "inspect", "report") + userTask("report", "SEAT-AO") + flow("f3", "report", "e")
                    + "<endEvent id=\"e\"/></process>");
            deploy("gp17", "gp17.bpmn", bpmn);
            Date planned = ist("2026-11-12T11:00:00");
            String pid = start("gp17", "FACTORY-9", "inspectionDate", planned, "inspector", "insp.a");
            TaskView t = one(pid, "inspect");
            r.check("insp.a".equals(t.assignee()), "assigned to inspector A");
            r.check(planned.equals(k().dueDate(a, t.id())), "due date from the schedule");
            Date moved = ist("2026-11-14T11:00:00");
            k().setDueDate(a, t.id(), moved);
            r.nativeApi(k().api("setDueDate"));
            r.check(moved.equals(k().dueDate(a, t.id())), "inspection rescheduled");
            k().setAssignee(a, t.id(), "insp.b");
            r.nativeApi(k().api("setAssignee") + " (EngineAdapter.claimTask fails on an assigned task)");
            r.check("insp.b".equals(k().assignee(a, t.id())), "reassigned to inspector B");
            List<String> hist = k().assignmentHistory(a, t.id());
            r.nativeApi(k().api("assignmentHistory"));
            r.detail("assignmentHistory", hist);
            boolean both = hist.stream().anyMatch(h -> h.contains("insp.a")) && hist.stream().anyMatch(h -> h.contains("insp.b"));
            r.detail("historyNamesBothInspectors", both);
            if (!hist.stream().anyMatch(h -> h.contains("assignee") && h.contains("insp.a"))) {
                r.note("Engine history does not keep the assignee sequence (A then B) for the task; the product audit "
                        + "chain (X.2.3) must record reassignments");
            }
            a.completeTask(t.id(), Map.of("inspectionResult", "satisfactory"));
            r.check(tasks(pid, "report").size() == 1, "report after inspection");
        });
    }

    // =====================================================================================================  GP-18

    @Test
    void gp18_transferAdditionalChargeDelegation() {
        scenario("GP-18", "Transfer / additional charge / leave delegation mid-flow (seat-based)", r -> {
            r.feature("tasks assigned to a seat (candidate group)", "query by several seats (additional charge)",
                    "unclaim / reassign on transfer", "delegation with owner (acted-for)");
            String bpmn = defs("<process id=\"gp18\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "sanction")
                    + userTask("sanction", "${seat}") + flow("f2", "sanction", "e") + "<endEvent id=\"e\"/></process>");
            deploy("gp18", "gp18.bpmn", bpmn);
            List<String> pids = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                pids.add(start("gp18", "SAN-" + i, "seat", "SEAT-DDO-JPR"));
            }
            // seat register (product layer, effective-dated): A holds SEAT-DDO-JPR
            Map<String, List<String>> seatsOf = new HashMap<>(Map.of("off.a", List.of("SEAT-DDO-JPR"),
                    "off.b", List.of("SEAT-AO-JPR"), "off.c", List.of("SEAT-AO-AJM")));
            r.check(a.queryTasks(new TaskQuery(null, null, seatsOf.get("off.a"), null, null, 0, 0)).size() == 3, "A sees 3 seat tasks");
            TaskView claimed = a.queryTasks(new TaskQuery(null, null, seatsOf.get("off.a"), pids.get(0), null, 0, 0)).get(0);
            a.claimTask(claimed.id(), "off.a");
            // transfer: B takes over the seat; tasks follow the seat, the claimed one must be released
            seatsOf.put("off.a", List.of());
            seatsOf.put("off.b", List.of("SEAT-AO-JPR", "SEAT-DDO-JPR"));
            long visibleToB = a.countTasks(new TaskQuery(null, null, List.of("SEAT-DDO-JPR"), null, null, 0, 0));
            r.detail("seatTasksVisibleToSuccessorBeforeRelease", visibleToB);
            k().setAssignee(a, claimed.id(), null);
            r.nativeApi(k().api("setAssignee"));
            long afterRelease = a.countTasks(new TaskQuery(null, null, List.of("SEAT-DDO-JPR"), null, null, 0, 0));
            r.check(afterRelease == 3, "after release on transfer the successor sees all 3 seat tasks (got " + afterRelease + ")");
            // additional charge: C holds own seat plus SEAT-DDO-JPR
            long cSees = a.countTasks(new TaskQuery(null, null, List.of("SEAT-AO-AJM", "SEAT-DDO-JPR"), null, null, 0, 0));
            r.check(cSees == 3, "additional charge: C sees the seat tasks with both seats in the query");
            // leave: B delegates one task to C (acted for), C resolves, B completes
            TaskView t2 = a.queryTasks(new TaskQuery(null, null, List.of("SEAT-DDO-JPR"), pids.get(1), null, 0, 0)).get(0);
            a.claimTask(t2.id(), "off.b");
            k().delegate(a, t2.id(), "off.c");
            r.nativeApi(k().api("delegate"));
            r.check("off.c".equals(k().assignee(a, t2.id())) && "off.b".equals(k().owner(a, t2.id())),
                    "delegated: assignee C, owner B (acted for)");
            k().resolve(a, t2.id(), Map.of("note", "checked by C for B"));
            r.check("off.b".equals(k().assignee(a, t2.id())), "resolved: back with B");
            a.completeTask(t2.id(), Map.of());
            r.check(ended(pids.get(1)), "B completed the sanction");
            r.productLayer("seat register and transfer handling (release the predecessor's claimed tasks) in the product "
                    + "layer (IX.5); the engine only knows candidate groups and assignees", 15);
            r.note("Assigned tasks: the Operaton adapter includes assigned tasks in candidate-group queries "
                    + "(includeAssignedTasks), the Flowable adapter does not; seen by the successor before release: " + visibleToB);
        });
    }

    // =====================================================================================================  GP-19

    @Test
    void gp19_ruleChangeMidFlight() {
        scenario("GP-19", "Rule change mid-flight: running instances keep the old rule (DMN version binding)", r -> {
            r.feature("DMN deployed with the process", "decision binding to the same deployment", "default binding = latest");
            r.variant(k().engine().equals("operaton") ? "operaton:decisionRefBinding=\"deployment\""
                    : "flowable field sameDeployment=true on the DMN service task");
            java.util.function.BiFunction<Integer, Integer, String[]> build = (low, high) -> new String[]{
                    dmn("gp19-fee", "FIRST", "area", "number", "fee", "number",
                            List.of(new String[]{"<= 100", String.valueOf(low)}, new String[]{"-", String.valueOf(high)})),
                    defs("<process id=\"gp19\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "apply")
                            + userTask("apply", "SEAT-CLERK") + flow("f2", "apply", "fee")
                            + k().dmnTask("fee", "gp19-fee", "fee", true) + flow("f3", "fee", "pay")
                            + userTask("pay", "SEAT-CASH") + flow("f4", "pay", "e") + "<endEvent id=\"e\"/></process>"),
                    defs("<process id=\"gp19L\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "apply")
                            + userTask("apply", "SEAT-CLERK") + flow("f2", "apply", "fee")
                            + k().dmnTask("fee", "gp19-fee", "fee", false) + flow("f3", "fee", "pay")
                            + userTask("pay", "SEAT-CASH") + flow("f4", "pay", "e") + "<endEvent id=\"e\"/></process>")};
            String[] v1 = build.apply(1000, 5000);
            deploy("gp19-v1", "gp19-fee.dmn", v1[0], "gp19.bpmn", v1[1], "gp19L.bpmn", v1[2]);
            String pA = start("gp19", "OLD-BOUND", "area", 50L);
            String pAL = start("gp19L", "OLD-LATEST", "area", 50L);
            String[] v2 = build.apply(1500, 7500); // new fee notification
            deploy("gp19-v2", "gp19-fee.dmn", v2[0], "gp19.bpmn", v2[1], "gp19L.bpmn", v2[2]);
            String pB = start("gp19", "NEW-BOUND", "area", 50L);
            for (String p : List.of(pA, pAL, pB)) {
                a.completeTask(one(p, "apply").id(), Map.of());
            }
            double feeA = ((Number) var(pA, "fee")).doubleValue();
            double feeB = ((Number) var(pB, "fee")).doubleValue();
            double feeAL = ((Number) var(pAL, "fee")).doubleValue();
            r.detail("feeOldInstanceBoundToDeployment", feeA);
            r.detail("feeNewInstance", feeB);
            r.detail("feeOldInstanceWithLatestBinding", feeAL);
            r.check(feeA == 1000, "old instance keeps the old rule (fee 1000), got " + feeA);
            r.check(feeB == 1500, "new instance uses the new rule (fee 1500), got " + feeB);
            if (feeAL == 1500) {
                r.note("Without the binding attribute (default 'latest') the old instance evaluated the NEW rule (fee "
                        + feeAL + "): the binding must be set explicitly.");
            } else {
                r.note("Without the binding attribute the old instance still evaluated the OLD rule (fee " + feeAL
                        + "): this engine's default lookup prefers the decision of the process's own (parent) deployment.");
            }
        });
    }

    // =====================================================================================================  GP-20

    @Test
    void gp20_migrateRunningInstances() {
        scenario("GP-20", "Migrate running instances to a new process version (revised procedure)", r -> {
            r.feature("process instance migration API", "activity mapping (equal ids + one renamed activity)");
            String v1 = defs("<process id=\"gp20\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "draft")
                    + userTask("draft", "SEAT-DA") + flow("f2", "draft", "review") + userTask("review", "SEAT-SO")
                    + flow("f3", "review", "e") + "<endEvent id=\"e\"/></process>");
            String v2 = defs("<process id=\"gp20\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "draft")
                    + userTask("draft", "SEAT-DA") + flow("f2", "draft", "examine") + userTask("examine", "SEAT-SO")
                    + flow("f3", "examine", "concurrence") + userTask("concurrence", "SEAT-FIN")
                    + flow("f4", "concurrence", "e") + "<endEvent id=\"e\"/></process>");
            deploy("gp20-v1", "gp20.bpmn", v1);
            String atDraft = start("gp20", "CASE-1", "x", 1L);
            String atReview = start("gp20", "CASE-2", "x", 2L);
            a.completeTask(one(atReview, "draft").id(), Map.of());
            String oldDef = k().definitionIdOf(a, atDraft);
            deploy("gp20-v2", "gp20.bpmn", v2);
            String newDef = k().latestDefinitionId(a, "gp20");
            r.nativeApi(k().api("definitions"));
            long t0 = System.nanoTime();
            k().migrate(a, List.of(atDraft, atReview), newDef, Map.of("review", "examine"));
            r.timing("migrate_2_instances", (System.nanoTime() - t0) / 1_000_000);
            r.nativeApi(k().api("migrate"));
            r.check(!oldDef.equals(newDef), "a new version exists");
            r.check(newDef.equals(k().definitionIdOf(a, atDraft)) && newDef.equals(k().definitionIdOf(a, atReview)),
                    "both instances now on the new version");
            r.check(names(tasks(atReview)).equals(List.of("examine")), "the 'review' token was mapped to 'examine'");
            r.check(Long.valueOf(2L).equals(var(atReview, "x")), "variables kept");
            a.completeTask(one(atReview, "examine").id(), Map.of());
            r.check(tasks(atReview, "concurrence").size() == 1, "migrated instance follows the new step 'concurrence'");
            a.completeTask(one(atDraft, "draft").id(), Map.of());
            a.completeTask(one(atDraft, "examine").id(), Map.of());
            a.completeTask(one(atDraft, "concurrence").id(), Map.of());
            r.check(ended(atDraft), "instance migrated at 'draft' runs the whole new procedure");
        });
    }
}
