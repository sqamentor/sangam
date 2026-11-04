package org.sangam.spike.govc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.govc.support.GovBase;
import org.sangam.spike.govc.support.Native;

/**
 * E16-c: pattern tests GP-41..GP-55 (GOV-PATTERNS.md "Added after validation") on both engines with the same models,
 * data and thread counts. Each scenario runs on a fresh engine and database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E16cPatternTest extends GovBase {

    static final Duration DAY = Duration.ofDays(1);

    @BeforeAll
    void setUp() {
        open("E16-c", "Government pattern tests GP-41..GP-55 (added after validation)");
    }

    @AfterAll
    void tearDown() {
        write();
    }

    // ================================================================== GP-41
    @Test
    void gp41() {
        scenario("GP-41", "Security-instrument (BG) lifecycle: verify, extend or encash before expiry, release", ctx -> {
            ctx.feature("message correlation by business key", "event-based gateway (message + timer)", "timeDate timer from a variable",
                    "timer re-armed by the model (event-gateway loop)", "timer re-armed on the running instance (native)",
                    "extension/expiry race");
            deploy("gp41-bg.bpmn");
            Instant t0 = now;
            String a = adapter.startProcess(null, "gp41-bg", "BG-1",
                    vars("bgExpiry", date(t0.plus(Duration.ofDays(180))), "reminderAt", date(t0.plus(Duration.ofDays(150)))));
            adapter.correlateMessage(null, "bgConfirmed", "BG-1", vars("genuine", true));
            ctx.check("after bank confirmation: reminder timer due at reminderAt (+150d)",
                    dueIs(timerAt(a, "gp41_reminderDue"), t0.plus(Duration.ofDays(150))));

            // (1) native re-arm from the model expression after the date variable changed
            nat.setVariable(adapter, a, "reminderAt", date(t0.plus(Duration.ofDays(120))));
            String note = nat.recalculateTimerFromModel(adapter, timerAt(a, "gp41_reminderDue").jobId());
            if (note != null) {
                ctx.workaround(note, 18);
            }
            ctx.check("native recalculation moved the reminder to the new reminderAt (+120d)",
                    dueIs(timerAt(a, "gp41_reminderDue"), t0.plus(Duration.ofDays(120))));
            // (2) native re-arm to an absolute date
            nat.setTimerDue(adapter, timerAt(a, "gp41_reminderDue").jobId(), t0.plus(Duration.ofDays(125)));
            ctx.check("native set-due-date moved the reminder to +125d", dueIs(timerAt(a, "gp41_reminderDue"), t0.plus(Duration.ofDays(125))));
            ctx.check("still exactly one timer", nat.timers(adapter, a).size() == 1);

            // (3) model re-arm: the bank extends the BG; the gateway loop re-creates the timer from the new variables
            adapter.correlateMessage(null, "bgExtended", "BG-1",
                    vars("bgExpiry", date(t0.plus(Duration.ofDays(365))), "reminderAt", date(t0.plus(Duration.ofDays(335)))));
            ctx.check("model re-arm: one timer, due at the new reminderAt (+335d)",
                    nat.timers(adapter, a).size() == 1 && dueIs(timerAt(a, "gp41_reminderDue"), t0.plus(Duration.ofDays(335))));

            advance(Duration.ofDays(336));
            adapter.executeDueJobs();
            complete(ctx, a, "gp41_requestExtension", "contracts", null);
            ctx.check("expiry timer armed at bgExpiry (+365d)", dueIs(timerAt(a, "gp41_expiryDue"), t0.plus(Duration.ofDays(365))));

            // (4) race: expiry has passed but the timer has not run yet; the extension arrives first and wins
            advance(Duration.ofDays(30)); // day 366
            adapter.correlateMessage(null, "bgExtended", "BG-1",
                    vars("bgExpiry", date(t0.plus(Duration.ofDays(500))), "reminderAt", date(t0.plus(Duration.ofDays(470)))));
            adapter.executeDueJobs();
            ctx.check("race: extension correlated before the overdue expiry job ran -> no encashment",
                    tasks(a, "gp41_encash").isEmpty());
            ctx.check("race: watch re-armed at +470d", dueIs(timerAt(a, "gp41_reminderDue"), t0.plus(Duration.ofDays(470))));

            // (5) no extension this time: expiry fires, late extension is refused
            advance(Duration.ofDays(105)); // 471
            adapter.executeDueJobs();
            complete(ctx, a, "gp41_requestExtension", "contracts", null);
            advance(Duration.ofDays(30)); // 501
            adapter.executeDueJobs();
            ctx.check("expiry fired -> encash task", tasks(a, "gp41_encash").size() == 1);
            ctx.check("late extension after expiry is refused (no waiting subscription)",
                    throwsOn(() -> adapter.correlateMessage(null, "bgExtended", "BG-1", vars("bgExpiry", date(now)))));
            complete(ctx, a, "gp41_encash", "accounts", null);
            ctx.check("instance ended at gp41_end_encashed", ended(a) && visited(a, "gp41_end_encashed"));

            // release path and non-genuine path
            String b = adapter.startProcess(null, "gp41-bg", "BG-2",
                    vars("bgExpiry", date(now.plus(Duration.ofDays(180))), "reminderAt", date(now.plus(Duration.ofDays(150)))));
            adapter.correlateMessage(null, "bgConfirmed", "BG-2", vars("genuine", true));
            adapter.correlateMessage(null, "contractClosed", "BG-2", null);
            complete(ctx, b, "gp41_release", "accounts", null);
            ctx.check("release path ended", ended(b) && visited(b, "gp41_end_released"));
            String c = adapter.startProcess(null, "gp41-bg", "BG-3",
                    vars("bgExpiry", date(now.plus(Duration.ofDays(180))), "reminderAt", date(now.plus(Duration.ofDays(150)))));
            adapter.correlateMessage(null, "bgConfirmed", "BG-3", vars("genuine", false));
            ctx.check("non-genuine BG goes to vigilance", countForGroup(c, "gp41_reportFake", "vigilance") == 1);
        });
    }

    // ================================================================== GP-42
    @Test
    void gp42() {
        scenario("GP-42", "Cumulative-threshold re-sanction (10 % over AA) while work continues", ctx -> {
            ctx.feature("expression service task (aggregate)", "exclusive gateway on aggregate", "non-interrupting conditional boundary event",
                    "call activity with in/out mapping", "history of variable updates");
            deploy("gp42-cumulative.bpmn", "gp42-revised-sanction.bpmn");
            String p = adapter.startProcess(null, "gp42-cumulative", "WORK-42",
                    vars("sanctioned", 1_000_000L, "cumulative", 0L, "revisionPending", false, "excessNoted", false, "done", false));
            boolean[] manualEval = {false};
            for (long amt : new long[]{400_000L, 450_000L}) {
                complete(ctx, p, "gp42_raBill", "ee", vars("billAmount", amt, "done", false));
            }
            complete(ctx, p, "gp42_raBill", "ee", vars("billAmount", 200_000L, "done", false));
            ctx.check("cumulative 1,050,000 within 10 %: excess noted by SE", countForGroup(p, "gp42_excessApproval", "se") == 1);
            complete(ctx, p, "gp42_excessApproval", "se", vars("excessNoted", true));
            ctx.check("no revision yet", nat.subInstances(adapter, p).isEmpty());
            complete(ctx, p, "gp42_raBill", "ee", vars("billAmount", 100_000L, "done", false));
            List<String> subs = nat.subInstances(adapter, p);
            if (subs.isEmpty()) {
                if (nat.evaluateConditionalEvents(adapter, p)) {
                    manualEval[0] = true;
                    ctx.workaround("conditional boundary event is not evaluated on variable change: the product must call"
                            + " RuntimeService.evaluateConditionalEvents(processInstanceId) after every change that can"
                            + " affect a condition", 3);
                }
                subs = nat.subInstances(adapter, p);
            }
            ctx.detail("conditionalEvaluatedByEngineOnVariableChange", !manualEval[0]);
            ctx.check("cumulative 1,150,000 > 110 %: revised-sanction child started", subs.size() == 1);
            ctx.check("billing continues meanwhile (RA bill task open)", tasks(p, "gp42_raBill").size() == 1);
            ctx.require("child exists", !subs.isEmpty());
            complete(ctx, subs.get(0), "gp42r_approve", "aa-authority", vars("revisedSanction", 1_300_000L));
            ctx.check("parent sanction updated from the child (out-mapping): 1,300,000",
                    Long.valueOf(1_300_000L).equals(var(p, "sanctioned")));
            complete(ctx, p, "gp42_raBill", "ee", vars("billAmount", 50_000L, "done", true));
            if (manualEval[0] && !ended(p)) {
                nat.evaluateConditionalEvents(adapter, p);
            }
            ctx.check("revision ran exactly once", countVisits(p, "gp42_revise") == 1);
            ctx.check("instance ended", ended(p));
            ctx.check("cumulative = 1,200,000", Long.valueOf(1_200_000L).equals(var(p, "cumulative")));
            List<Object> sanc = nat.variableHistory(adapter, p, "sanctioned");
            ctx.detail("sanctionedHistory", sanc);
            ctx.check("history keeps old and new sanction [1000000, 1300000]",
                    sanc.size() >= 2 && Long.valueOf(1_000_000L).equals(sanc.get(0)) && Long.valueOf(1_300_000L).equals(sanc.get(sanc.size() - 1)));
        });
    }

    // ================================================================== GP-43
    @Test
    void gp43() {
        scenario("GP-43", "Live contract amendment: EoT re-arms the LD timer, child updates parent, extra item added", ctx -> {
            ctx.feature("non-interrupting message event sub-process", "call activity in/out mapping", "boundary timeDate timer",
                    "parent variable update from a running child (native)", "timer re-armed on the running instance (native)",
                    "instance modification: start an extra activity (native)", "history of variable updates");
            deploy("gp43-contract.bpmn", "gp43-amendment.bpmn");
            Instant t0 = now;
            Instant stipulated = t0.plus(Duration.ofDays(365));
            String p = adapter.startProcess(null, "gp43-contract", "WO-43",
                    vars("completionDate", date(stipulated), "contractValue", 10_000_000L));
            TaskView workTask = single(ctx, p, "gp43_execution");
            ctx.check("LD timer at the stipulated date", dueIs(timerAt(p, "gp43_ldStart"), stipulated));

            // EoT request (hindrance) runs as a child; the child's officer records hindrance days on the PARENT live
            advance(Duration.ofDays(200));
            Instant extended = stipulated.plus(Duration.ofDays(90));
            adapter.correlateMessage(null, "amendmentRequested", "WO-43",
                    vars("amendType", "EOT", "proposedCompletion", date(extended), "extraAmount", 0L));
            List<String> subs = nat.subInstances(adapter, p);
            ctx.require("amendment child started", subs.size() == 1);
            String child = subs.get(0);
            String parentOfChild = nat.superInstance(adapter, child);
            ctx.check("child finds its parent", p.equals(parentOfChild));
            nat.setVariable(adapter, parentOfChild, "hindranceDays", 90);
            ctx.check("parent sees the child's update while the child is still running",
                    Integer.valueOf(90).equals(var(p, "hindranceDays")) && !ended(child));
            complete(ctx, child, "gp43a_examine", "ee", null);
            complete(ctx, child, "gp43a_approve", "se", vars("approved", true, "approvedCompletion", date(extended), "approvedExtra", 0L));
            ctx.check("parent completionDate updated from the child result",
                    var(p, "completionDate") instanceof Date d && d.toInstant().equals(extended));
            boolean followed = dueIs(timerAt(p, "gp43_ldStart"), extended);
            ctx.detail("ldTimerFollowedVariableByItself", followed);
            ctx.check("without engine action the LD timer still points at the OLD date (timers are not re-evaluated)",
                    dueIs(timerAt(p, "gp43_ldStart"), stipulated));
            String note = nat.recalculateTimerFromModel(adapter, timerAt(p, "gp43_ldStart").jobId());
            if (note != null) {
                ctx.workaround(note, 18);
            }
            ctx.check("LD timer re-armed to the extended date", dueIs(timerAt(p, "gp43_ldStart"), extended));

            // extra item on the running contract
            String modNote = nat.startAdditionalActivity(adapter, p, "gp43_extraItem", "gp43_execution");
            if (modNote != null) {
                ctx.workaround(modNote, 1);
            }
            ctx.check("extra item task created in the running instance", tasks(p, "gp43_extraItem").size() == 1);
            List<TaskView> work = tasks(p, "gp43_execution");
            ctx.check("main work task still exactly one", work.size() == 1);
            boolean sameTask = work.size() == 1 && work.get(0).id().equals(workTask.id());
            ctx.detail("mainWorkTaskKeptItsId", sameTask);
            ctx.check("LD timer still at the extended date after the modification", dueIs(timerAt(p, "gp43_ldStart"), extended));
            adapter.correlateMessage(null, "amendmentRequested", "WO-43",
                    vars("amendType", "EXTRA_ITEM", "proposedCompletion", date(extended), "extraAmount", 450_000L));
            String child2 = nat.subInstances(adapter, p).get(0);
            complete(ctx, child2, "gp43a_examine", "ee", null);
            complete(ctx, child2, "gp43a_approve", "se", vars("approved", true, "approvedCompletion", date(extended), "approvedExtra", 450_000L));
            ctx.check("contract value increased to 10,450,000", Long.valueOf(10_450_000L).equals(var(p, "contractValue")));
            complete(ctx, p, "gp43_extraItem", "ee", null);

            // LD fires at the extended date, not at the stipulated one
            clock(stipulated.plus(DAY));
            adapter.executeDueJobs();
            ctx.check("no LD notice the day after the OLD date", tasks(p, "gp43_ldNotice").isEmpty());
            clock(extended.plus(DAY));
            adapter.executeDueJobs();
            ctx.check("LD notice the day after the EXTENDED date", tasks(p, "gp43_ldNotice").size() == 1);
            List<Object> hist = nat.variableHistory(adapter, p, "completionDate");
            ctx.detail("completionDateHistory", hist.stream().map(String::valueOf).toList());
            ctx.check("history keeps old and new completion date", hist.size() >= 2);
            complete(ctx, p, "gp43_ldNotice", "ee", null);
            complete(ctx, p, "gp43_execution", "ee", null);
            complete(ctx, p, "gp43_finalBill", "accounts", null);
            ctx.check("contract ended", ended(p));
        });
    }

    // ================================================================== GP-44
    @Test
    void gp44() {
        scenario("GP-44", "Deemed outcome on timeout (RTI s7(2) deemed refusal opens the appeal)", ctx -> {
            ctx.feature("interrupting boundary timer (P30D)", "outcome as an event in history", "event-based gateway appeal window");
            deploy("gp44-deemed.bpmn");
            Instant t0 = now;
            String a = adapter.startProcess(null, "gp44-deemed", "RTI-1", Map.of());
            ctx.check("deadline at +30 days", dueIs(timerAt(a, "gp44_deadline"), t0.plus(Duration.ofDays(30))));
            TaskView stale = single(ctx, a, "gp44_pioDecide");
            advance(Duration.ofDays(30).plusMinutes(1));
            adapter.executeDueJobs();
            ctx.check("deemed refusal reached", visited(a, "gp44_deemedRefusal"));
            ctx.check("PIO task withdrawn", tasks(a, "gp44_pioDecide").isEmpty());
            ctx.check("late decision by the PIO is blocked (task no longer exists)",
                    throwsOn(() -> adapter.completeTask(stale.id(), Map.of())));
            adapter.correlateMessage(null, "firstAppealFiled", "RTI-1", null);
            ctx.check("first appeal goes to the FAA", countForGroup(a, "gp44_faaHearing", "faa") == 1);

            String b = adapter.startProcess(null, "gp44-deemed", "RTI-2", Map.of());
            complete(ctx, b, "gp44_pioDecide", "pio", null);
            ctx.check("decided in time ends normally", ended(b) && visited(b, "gp44_end_decided"));

            String c = adapter.startProcess(null, "gp44-deemed", "RTI-3", Map.of());
            advance(Duration.ofDays(31));
            adapter.executeDueJobs();
            advance(Duration.ofDays(31));
            adapter.executeDueJobs();
            ctx.check("no appeal within 30 days of the deemed refusal -> lapsed", ended(c) && visited(c, "gp44_end_lapsed"));
        });
    }

    // ================================================================== GP-45
    @Test
    void gp45() {
        scenario("GP-45", "Pausable statutory clock (RTI s7(3) fee period excluded) with remaining-time resume", ctx -> {
            ctx.feature("interrupting boundary timeDate timer", "instance suspension (native)", "timer due date set on resume (native)",
                    "remaining-time arithmetic");
            deploy("gp45-pausable.bpmn");
            Instant t0 = now;
            String p = adapter.startProcess(null, "gp45-pausable", "RTI-45", vars("slaDue", date(t0.plus(Duration.ofDays(30)))));
            TaskView task = single(ctx, p, "gp45_process");
            advance(Duration.ofDays(10));
            // pause: fee intimation sent
            Native.Timer sla = timerAt(p, "gp45_sla");
            Duration remaining = Duration.between(now, sla.due());
            Instant pausedAt = now;
            nat.suspendInstance(adapter, p);
            ctx.workaround("remaining time (due - pausedAt) and net elapsed time are computed and kept by the product;"
                    + " neither engine keeps a paused-clock balance", 6);
            ctx.check("instance suspended", nat.isSuspended(adapter, p));
            Native.Timer sus = timerAt(p, "gp45_sla");
            ctx.check("SLA timer suspended with the instance", sus != null && sus.suspended());
            ctx.check("officer cannot complete the task while the clock is stopped",
                    throwsOn(() -> adapter.completeTask(task.id(), Map.of())));
            advance(Duration.ofDays(35)); // day 45, beyond the original due date
            adapter.executeDueJobs();
            ctx.check("suspended timer did not fire past its original due date", tasks(p, "gp45_escalate").isEmpty());
            Duration paused = Duration.between(pausedAt, now);
            // resume: due = now + remaining. Tried in order, each verified by reading the timer back:
            //  A: set the due date on the suspended timer and activate, in ONE transaction;
            //  B: set the due date while suspended (tx 1), then activate (tx 2): no window, the timer stays suspended;
            //  C: activate (tx 1), then set the due date (tx 2): an overdue timer could fire in between.
            Instant newDue = now.plus(remaining);
            String path = null;
            List<String> attempts = new ArrayList<>();
            try {
                adapter.inEngineTransaction(c -> {
                    nat.setTimerDue(adapter, timerAt(p, "gp45_sla").jobId(), newDue);
                    nat.activateInstance(adapter, p);
                    return null;
                });
                attempts.add("A: no error");
            } catch (RuntimeException e) {
                attempts.add("A: " + error(e));
            }
            attempts.add("A: afterwards suspended=" + nat.isSuspended(adapter, p) + " due=" + timerAt(p, "gp45_sla").due());
            if (!nat.isSuspended(adapter, p) && dueIs(timerAt(p, "gp45_sla"), newDue)) {
                path = "A: set due date on the suspended timer + activate in one transaction";
            }
            if (path == null && nat.isSuspended(adapter, p)) {
                try {
                    nat.setTimerDue(adapter, timerAt(p, "gp45_sla").jobId(), newDue);
                    attempts.add("B1: no error, due now " + timerAt(p, "gp45_sla").due());
                } catch (RuntimeException e) {
                    attempts.add("B1: " + error(e));
                }
                nat.activateInstance(adapter, p);
                if (dueIs(timerAt(p, "gp45_sla"), newDue)) {
                    path = "B: set due date while suspended, then activate (two transactions, no window)";
                    ctx.workaround("re-dating the suspended timer and activating cannot share one transaction; re-date"
                            + " while suspended, then activate in a second transaction", 3);
                } else {
                    attempts.add("B2: after activate due=" + timerAt(p, "gp45_sla").due());
                }
            }
            if (path == null) {
                if (nat.isSuspended(adapter, p)) {
                    nat.activateInstance(adapter, p);
                }
                nat.setTimerDue(adapter, timerAt(p, "gp45_sla").jobId(), newDue);
                path = "C: activate, then set the due date (two transactions: an overdue timer can fire in between"
                        + " under a running job executor)";
                ctx.workaround("a suspended timer cannot be re-dated (no effect or error); activate first and re-date in a"
                        + " second transaction, which leaves a window in which the overdue timer can fire", 4);
            }
            ctx.detail("resumeAttempts", attempts);
            ctx.detail("resumePath", path);
            ctx.check("resumed with the remaining 20 days: due = day 65", dueIs(timerAt(p, "gp45_sla"), t0.plus(Duration.ofDays(65))));
            advance(Duration.ofDays(19)); // day 64
            adapter.executeDueJobs();
            long netElapsedDays = Duration.between(t0, now).minus(paused).toDays();
            ctx.detail("netElapsedDays", netElapsedDays);
            ctx.check("day 64: no breach, net elapsed 29 days", tasks(p, "gp45_escalate").isEmpty() && netElapsedDays == 29);
            advance(Duration.ofDays(2)); // day 66
            adapter.executeDueJobs();
            ctx.check("day 66: SLA breached after 30 net days", tasks(p, "gp45_escalate").size() == 1);
        });
    }

    // ================================================================== GP-46
    @Test
    void gp46() {
        scenario("GP-46", "Third-party objection window (RTI s11 / LA s15): per-party message or window close", ctx -> {
            ctx.feature("parallel multi-instance sub-process (loopCardinality)", "event-based gateway per party",
                    "per-branch message delivery (native, by loopCounter)", "timeDate window");
            deploy("gp46-objections.bpmn");
            Instant end = now.plus(Duration.ofDays(30));
            String p = adapter.startProcess(null, "gp46-objections", "S11-1", vars("partyCount", 3, "windowEnd", date(end)));
            complete(ctx, p, "gp46_notice", "pio", null);
            ctx.check("three party branches waiting", nat.messageSubscriptions(adapter, p, "thirdPartyObjection").size() == 3);
            ctx.workaround("per-party correlation: the product finds the waiting execution whose loopCounter is the party"
                    + " and delivers to that execution (RuntimeService.messageEventReceived(name, executionId)); the"
                    + " adapter's correlateMessage(businessKey) is ambiguous with several branches waiting", 12);
            advance(Duration.ofDays(5));
            ctx.check("party 0 objects", objectAs(p, 0, "objection by party 0"));
            advance(Duration.ofDays(5));
            ctx.check("party 2 objects", objectAs(p, 2, "objection by party 2"));
            ctx.check("decision waits for the silent party", tasks(p, "gp46_decide").isEmpty());
            advance(Duration.ofDays(21));
            adapter.executeDueJobs();
            ctx.check("window closed -> decision", tasks(p, "gp46_decide").size() == 1);
            ctx.check("2 objections, 1 silent", countVisits(p, "gp46_endObjected") == 2 && countVisits(p, "gp46_endSilent") == 1);
            ctx.check("late objection finds no waiting branch", nat.messageSubscriptions(adapter, p, "thirdPartyObjection").isEmpty());

            String q = adapter.startProcess(null, "gp46-objections", "S11-2", vars("partyCount", 3, "windowEnd", date(now.plus(Duration.ofDays(30)))));
            complete(ctx, q, "gp46_notice", "pio", null);
            for (int i = 0; i < 3; i++) {
                objectAs(q, i, "objection " + i);
            }
            ctx.check("all parties answered early -> decision at once", tasks(q, "gp46_decide").size() == 1);
        });
    }

    private boolean objectAs(String pid, int party, String text) {
        for (String ex : nat.messageSubscriptions(adapter, pid, "thirdPartyObjection")) {
            Object lc = nat.getVariable(adapter, ex, "loopCounter");
            if (lc instanceof Number n && n.intValue() == party) {
                String scope = nat.executionWithLocalVariable(adapter, pid, "loopCounter", party);
                nat.deliverMessage(adapter, ex, "thirdPartyObjection", scope, vars("objection", text));
                return true;
            }
        }
        return false;
    }

    // ================================================================== GP-47
    @Test
    void gp47() {
        scenario("GP-47", "Provisional result -> paid challenge window -> bulk recompute -> final", ctx -> {
            ctx.feature("timer-gated window", "one instance per challenge with fee callback", "parallel multi-instance external work (bulk)",
                    "product result table in the engine transaction");
            deploy("gp47-answer-key.bpmn", "gp47-challenge.bpmn", "probe-mi-external.bpmn");
            // diagnostic: parallel multi-instance external work with the engine defaults
            String probe = adapter.startProcess(null, "probe-mi-external", "PROBE", Map.of());
            List<ExternalWork> pf = adapter.fetchAndLock("w-probe", "probe-mi", 10, Duration.ofMinutes(5));
            List<ExternalWork> pf1 = pf.isEmpty() ? adapter.fetchAndLock("w-probe", "probe-mi", 1, Duration.ofMinutes(5)) : List.of();
            ctx.detail("probeDefaultMiFetch10", pf.size());
            ctx.detail("probeDefaultMiFetch1", pf1.size());
            for (ExternalWork w : pf) {
                adapter.unlockExternal(w.id(), "w-probe", w.lockVersion());
            }
            for (ExternalWork w : pf1) {
                adapter.unlockExternal(w.id(), "w-probe", w.lockVersion());
            }
            adapter.cancelInstance(probe, "probe done");
            int n = 300;
            sql("create table gov_result(candidate int, version int, score int, primary key(candidate, version))");
            adapter.inEngineTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement("insert into gov_result values (?, 1, ?)")) {
                    for (int i = 0; i < n; i++) {
                        ps.setInt(1, i);
                        ps.setInt(2, 100 + i % 50);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
            Instant windowEnd = now.plus(Duration.ofDays(7));
            String key = adapter.startProcess(null, "gp47-answer-key", "EXAM-47", vars("windowEnd", date(windowEnd), "candidateCount", n));
            complete(ctx, key, "gp47_publishProvisional", "exam-cell", null);
            List<String> ch = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                ch.add(adapter.startProcess(null, "gp47-challenge", "CH-" + i, vars("windowEnd", date(windowEnd))));
            }
            for (int i = 0; i < 4; i++) {
                adapter.correlateMessage(null, "challengeFeePaid", "CH-" + i, vars("question", 10 + i));
            }
            advance(Duration.ofDays(7).plusMinutes(1));
            adapter.executeDueJobs();
            ctx.check("late fee after the window is refused",
                    throwsOn(() -> adapter.correlateMessage(null, "challengeFeePaid", "CH-5", null)));
            long admitted = ch.stream().filter(id -> visited(id, "gp47c_admitted")).count();
            long lapsed = ch.stream().filter(id -> visited(id, "gp47c_lapsed")).count();
            ctx.check("4 paid challenges admitted, 2 lapsed", admitted == 4 && lapsed == 2);
            complete(ctx, key, "gp47_review", "expert-committee", vars("correctedQuestions", 2));
            if ("flowable".equals(nat.engine())) {
                ctx.workaround("external-worker jobs are exclusive by default; with exclusive jobs a fetch of several jobs"
                        + " of ONE instance returns nothing (second process-instance lock fails, acquire rolled back), so the"
                        + " multi-instance task carries flowable:exclusive=\"false\" (Flowable-only attribute)", 1);
            }
            long t = System.nanoTime();
            int done = 0;
            int missingIndex = 0;
            int[] fallback = {0};
            for (int round = 0; round < 20 && done < n; round++) {
                List<ExternalWork> got = adapter.fetchAndLock("w-recompute", "recompute", 100, Duration.ofMinutes(5));
                if (got.isEmpty()) {
                    break;
                }
                for (ExternalWork w : got) {
                    Object lc = w.variables().get("loopCounter");
                    if (!(lc instanceof Number)) {
                        missingIndex++;
                        lc = fallback[0]++; // any bijection works for the recompute; recorded below
                    }
                    int cand = ((Number) lc).intValue();
                    adapter.inEngineTransaction(c -> {
                        try (PreparedStatement ps = c.prepareStatement(
                                "insert into gov_result select candidate, 2, score + case when mod(candidate, 3) = 0 then 2 else 0 end"
                                        + " from gov_result where candidate = ? and version = 1")) {
                            ps.setInt(1, cand);
                            ps.executeUpdate();
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                        adapter.completeExternal(w.id(), "w-recompute", w.lockVersion(), Map.of());
                        return null;
                    });
                    done++;
                }
            }
            long ms = (System.nanoTime() - t) / 1_000_000;
            ctx.detail("candidates", n);
            ctx.detail("recomputeMs", ms);
            ctx.detail("workItemsWithoutLoopCounterVariable", missingIndex);
            if (missingIndex > 0) {
                ctx.workaround("the fetched work does not carry the multi-instance loopCounter; read it from the execution", 3);
            }
            ctx.check("all " + n + " candidates recomputed", done == n && count("select count(*) from gov_result where version = 2") == n);
            ctx.check("recomputed scores correct (+2 where candidate % 3 == 0)",
                    count("select count(*) from gov_result a join gov_result b on a.candidate = b.candidate and b.version = 2"
                            + " where a.version = 1 and b.score = a.score + case when mod(a.candidate, 3) = 0 then 2 else 0 end") == n);
            complete(ctx, key, "gp47_publishFinal", "exam-cell", null);
            ctx.check("final published, instance ended", ended(key));
        });
    }

    // ================================================================== GP-48
    @Test
    void gp48() {
        scenario("GP-48", "Ledger shared across instances and years (leave account) under concurrency", ctx -> {
            ctx.feature("external work + product ledger in the engine transaction", "BPMN error boundary on external work",
                    "8 concurrent workers on one ledger row", "idempotent debit", "timer start event (yearly cycle)");
            deploy("gp48-leave.bpmn", "gp48-yearend.bpmn");
            sql("create table gov_leave(emp varchar(20), yr int, balance int, primary key(emp, yr))");
            sql("create table gov_leave_applied(request_id varchar(40) primary key)");
            sql("insert into gov_leave values ('E1', 2027, 20)");
            sql("insert into gov_leave values ('E2', 2027, 25)");
            int requests = 30;
            List<String> pids = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                pids.add(adapter.startProcess(null, "gp48-leave", "LV-" + i, vars("emp", "E1", "days", 1, "requestId", "LV-" + i)));
            }
            for (String pid : pids) {
                complete(ctx, pid, "gp48_approve", "controlling-officer", null);
            }
            int threads = 8;
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            AtomicInteger granted = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();
            AtomicInteger retries = new AtomicInteger();
            ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
            CountDownLatch go = new CountDownLatch(1);
            long t = System.nanoTime();
            for (int w = 0; w < threads; w++) {
                String worker = "w-leave-" + w;
                ex.submit(() -> {
                    try {
                        go.await();
                        for (int idle = 0; idle < 3; ) {
                            List<ExternalWork> got = adapter.fetchAndLock(worker, "leave-debit", 2, Duration.ofMinutes(5));
                            if (got.isEmpty()) {
                                idle++;
                                continue;
                            }
                            for (ExternalWork wk : got) {
                                for (int attempt = 0; ; attempt++) {
                                    try {
                                        boolean ok = debit(wk, worker);
                                        (ok ? granted : refused).incrementAndGet();
                                        break;
                                    } catch (RuntimeException e) {
                                        if (attempt >= 8) {
                                            errors.add(error(e));
                                            break;
                                        }
                                        retries.incrementAndGet();
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        errors.add(error(e));
                    }
                });
            }
            go.countDown();
            ex.shutdown();
            ex.awaitTermination(5, TimeUnit.MINUTES);
            ctx.detail("threads", threads);
            ctx.detail("ledgerMs", (System.nanoTime() - t) / 1_000_000);
            ctx.detail("retriesAfterConflict", retries.get());
            ctx.detail("errors", new ArrayList<>(errors));
            ctx.check("exactly 20 granted, 10 refused (balance 20, 30 requests of 1 day)", granted.get() == 20 && refused.get() == 10);
            ctx.check("ledger balance 0, never negative", count("select balance from gov_leave where emp = 'E1' and yr = 2027") == 0);
            long grantedEnds = pids.stream().filter(id -> visited(id, "gp48_end_granted")).count();
            long informTasks = pids.stream().filter(id -> tasks(id, "gp48_inform").size() == 1).count();
            ctx.check("20 instances granted, 10 routed to 'inform' by the BPMN error", grantedEnds == 20 && informTasks == 10);
            // idempotency: a redelivered debit for an applied request changes nothing
            boolean replay = adapter.inEngineTransaction(c -> applyDebit(c, "LV-0", "E1", 1));
            ctx.check("replayed debit of an applied request is a no-op", replay && count("select balance from gov_leave where emp = 'E1' and yr = 2027") == 0);
            // year-end carry forward (cap 15, +30 credit) by the yearly timer start event
            List<Native.Timer> st = nat.startTimers(adapter, "gp48-yearend");
            ctx.check("year-end timer armed for 2027-12-31T18:30Z (1 Jan IST)",
                    st.size() == 1 && dueIs(st.get(0), Instant.parse("2027-12-31T18:30:00Z")));
            clock(Instant.parse("2027-12-31T18:31:00Z"));
            adapter.executeDueJobs();
            List<String> ye = nat.runningInstances(adapter, "gp48-yearend");
            ctx.require("year-end instance started", ye.size() == 1);
            ExternalWork cf = fetchOne(ctx, ye.get(0), "leave-carry-forward");
            adapter.inEngineTransaction(c -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("insert into gov_leave select emp, 2028, case when balance > 15 then 15 else balance end + 30"
                            + " from gov_leave where yr = 2027");
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
                adapter.completeExternal(cf.id(), "w-leave-carry-forward", cf.lockVersion(), Map.of());
                return null;
            });
            ctx.check("2028 balances: E1 = 30 (0 + 30), E2 = 45 (cap 15 + 30)",
                    count("select balance from gov_leave where emp = 'E1' and yr = 2028") == 30
                            && count("select balance from gov_leave where emp = 'E2' and yr = 2028") == 45);
            List<Native.Timer> st2 = nat.startTimers(adapter, "gp48-yearend");
            ctx.check("next year-end timer armed for 2028-12-31T18:30Z",
                    st2.size() == 1 && dueIs(st2.get(0), Instant.parse("2028-12-31T18:30:00Z")));
        });
    }

    private boolean debit(ExternalWork wk, String worker) {
        String req = String.valueOf(wk.variables().get("requestId"));
        String emp = String.valueOf(wk.variables().get("emp"));
        int days = ((Number) wk.variables().get("days")).intValue();
        return adapter.inEngineTransaction(c -> {
            boolean ok = applyDebit(c, req, emp, days);
            if (ok) {
                adapter.completeExternal(wk.id(), worker, wk.lockVersion(), Map.of());
            } else {
                adapter.bpmnErrorExternal(wk.id(), worker, wk.lockVersion(), "INSUFFICIENT_BALANCE", Map.of());
            }
            return ok;
        });
    }

    /** true = debited (or already applied before); false = insufficient balance. */
    private static boolean applyDebit(Connection c, String requestId, String emp, int days) {
        try {
            try (PreparedStatement q = c.prepareStatement("select count(*) from gov_leave_applied where request_id = ?")) {
                q.setString(1, requestId);
                try (ResultSet rs = q.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return true;
                    }
                }
            }
            int n;
            try (PreparedStatement u = c.prepareStatement(
                    "update gov_leave set balance = balance - ? where emp = ? and yr = 2027 and balance >= ?")) {
                u.setInt(1, days);
                u.setString(2, emp);
                u.setInt(3, days);
                n = u.executeUpdate();
            }
            try (PreparedStatement i = c.prepareStatement("insert into gov_leave_applied values (?)")) {
                i.setString(1, requestId);
                i.executeUpdate();
            }
            return n == 1;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================== GP-49
    @Test
    void gp49() {
        scenario("GP-49", "Partial batch failure: per-item result, routing by reason code, resend only failures", ctx -> {
            ctx.feature("parallel multi-instance sub-process", "per-item message delivery to one branch (native)",
                    "local variable per branch (native)", "gateway routing by reason code", "loop back with a smaller batch",
                    "product item table");
            deploy("gp49-dbt.bpmn");
            sql("create table gov_dbt_item(item int primary key, status varchar(30))");
            sql("create table gov_dbt_sent(round int, slot int, item int, primary key(round, slot))");
            for (int i = 0; i < 10; i++) {
                sql("insert into gov_dbt_item values (" + i + ", 'NEW')");
            }
            String p = adapter.startProcess(null, "gp49-dbt", "BATCH-49", vars("itemCount", 10, "round", 1));
            Map<Integer, String> round1 = new HashMap<>();
            for (int i = 0; i < 10; i++) {
                round1.put(i, "PAID");
            }
            round1.put(7, "ACCOUNT_INVALID");
            round1.put(8, "AADHAAR_NOT_SEEDED");
            round1.put(9, "NAME_MISMATCH");
            int deliveries = 0;
            List<Integer> sentRound2 = new ArrayList<>();
            for (int round = 1; round <= 3; round++) {
                int r = round;
                ExternalWork send = fetchOne(ctx, p, "dbt-send");
                List<Integer> sent = adapter.inEngineTransaction(c -> {
                    List<Integer> items = queryInts(c, "select item from gov_dbt_item where status <> 'PAID' order by item");
                    try (PreparedStatement ps = c.prepareStatement("insert into gov_dbt_sent values (?, ?, ?)")) {
                        for (int s = 0; s < items.size(); s++) {
                            ps.setInt(1, r);
                            ps.setInt(2, s);
                            ps.setInt(3, items.get(s));
                            ps.executeUpdate();
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                    adapter.completeExternal(send.id(), "w-dbt-send", send.lockVersion(), Map.of());
                    return items;
                });
                if (round == 2) {
                    sentRound2.addAll(sent);
                }
                List<String> waiting = nat.messageSubscriptions(adapter, p, "itemResult");
                ctx.check("round " + round + ": one waiting branch per sent item (" + sent.size() + ")", waiting.size() == sent.size());
                for (String exId : waiting) {
                    int slot = ((Number) nat.getVariable(adapter, exId, "loopCounter")).intValue();
                    int item = sent.get(slot);
                    String status = round == 1 ? round1.get(item) : "PAID";
                    String scope = nat.executionWithLocalVariable(adapter, p, "loopCounter", slot);
                    nat.deliverMessage(adapter, exId, "itemResult", scope, vars("status", status, "itemId", item));
                    sql("update gov_dbt_item set status = '" + status + "' where item = " + item);
                    deliveries++;
                }
                if (round == 1) {
                    ctx.check("ACCOUNT_INVALID -> bank desk", countForGroup(p, "gp49_fixAccount", "bank-desk") == 1);
                    ctx.check("AADHAAR_NOT_SEEDED -> DBT cell", countForGroup(p, "gp49_seedAadhaar", "dbt-cell") == 1);
                    ctx.check("other reason -> DDO", countForGroup(p, "gp49_ddoReview", "ddo") == 1);
                    complete(ctx, p, "gp49_fixAccount", "bank-desk", null);
                    complete(ctx, p, "gp49_seedAadhaar", "dbt-cell", null);
                    complete(ctx, p, "gp49_ddoReview", "ddo", null);
                }
                ExternalWork close = fetchOne(ctx, p, "dbt-close-round");
                int failed = adapter.inEngineTransaction(c -> {
                    int f = queryInts(c, "select item from gov_dbt_item where status <> 'PAID'").size();
                    adapter.completeExternal(close.id(), "w-dbt-close-round", close.lockVersion(), vars("itemCount", f, "round", r + 1));
                    return f;
                });
                if (failed == 0) {
                    break;
                }
            }
            ctx.workaround("per-item correlation: the product finds the waiting branch by its loopCounter and delivers to that"
                    + " execution, with the result as LOCAL variables of that branch (setVariablesLocal on the branch execution found"
                    + " by its local loopCounter + messageEventReceived, one transaction)", 14);
            ctx.detail("deliveries", deliveries);
            ctx.detail("sentInRound2", sentRound2);
            ctx.check("round 2 resent exactly the 3 failures [7, 8, 9]", sentRound2.equals(List.of(7, 8, 9)));
            ctx.check("13 per-item results correlated", deliveries == 13);
            ctx.check("all items paid, batch instance ended", ended(p) && count("select count(*) from gov_dbt_item where status = 'PAID'") == 10);
        });
    }

    // ================================================================== GP-50
    @Test
    void gp50() {
        scenario("GP-50", "Return to the originating seat under the same identity (treasury objection loop)", ctx -> {
            ctx.feature("message correlation by business key", "loop across a system boundary", "expression counter");
            deploy("gp50-objection-loop.bpmn");
            Map<String, String> pid = new LinkedHashMap<>();
            for (int i = 1; i <= 5; i++) {
                String bk = "BILL-" + i;
                pid.put(bk, adapter.startProcess(null, "gp50-objection-loop", bk, vars("objectionCount", 0)));
                complete(ctx, pid.get(bk), "gp50_prepare", "ddo", null);
                work(ctx, pid.get(bk), "treasury-submit", null);
            }
            for (int i = 1; i <= 5; i++) {
                if (i != 3) {
                    String got = adapter.correlateMessage(null, "treasuryVerdict", "BILL-" + i, vars("verdict", "PASSED"));
                    ctx.check("BILL-" + i + " verdict reached its own instance", pid.get("BILL-" + i).equals(got));
                }
            }
            String p3 = pid.get("BILL-3");
            for (int k = 0; k < 2; k++) {
                String got = adapter.correlateMessage(null, "treasuryVerdict", "BILL-3", vars("verdict", "OBJECTED", "objection", "O-" + k));
                ctx.check("objection " + k + " correlated to the same instance", p3.equals(got));
                ctx.check("verdict for a bill not waiting is refused", throwsOn(() ->
                        adapter.correlateMessage(null, "treasuryVerdict", "BILL-3", vars("verdict", "PASSED"))));
                complete(ctx, p3, "gp50_rectify", "ddo", null);
                work(ctx, p3, "treasury-submit", null);
            }
            adapter.correlateMessage(null, "treasuryVerdict", "BILL-3", vars("verdict", "PASSED"));
            ctx.check("BILL-3 passed after 2 objections, same instance id and business key",
                    ended(p3) && num(var(p3, "objectionCount")) == 2
                            && "BILL-3".equals(adapter.getInstance(p3).businessKey()));
            ctx.check("3 submissions on the one identity", countVisits(p3, "gp50_submit") == 3);
            ctx.check("all 5 bills ended", pid.values().stream().allMatch(this::ended));
        });
    }

    // ================================================================== GP-51
    @Test
    void gp51() {
        scenario("GP-51", "Hearing series with dates set by the court; brief deadline follows a reschedule", ctx -> {
            ctx.feature("message-supplied timer dates", "event-gateway loop (model re-arm of the hearing timer)",
                    "boundary timer re-armed on the running instance (native)", "parallel branches", "adjournment loop");
            deploy("gp51-hearings.bpmn");
            Instant t0 = now;
            String p = adapter.startProcess(null, "gp51-hearings", "WP-123-2027", Map.of());
            adapter.correlateMessage(null, "hearingListed", "WP-123-2027",
                    vars("nextHearing", date(t0.plus(Duration.ofDays(30))), "briefDue", date(t0.plus(Duration.ofDays(23)))));
            ctx.check("hearing timer +30d, brief deadline +23d",
                    dueIs(timerAt(p, "gp51_hearingDay"), t0.plus(Duration.ofDays(30))) && dueIs(timerAt(p, "gp51_briefDue"), t0.plus(Duration.ofDays(23))));
            advance(Duration.ofDays(5));
            adapter.correlateMessage(null, "hearingRescheduled", "WP-123-2027",
                    vars("nextHearing", date(t0.plus(Duration.ofDays(45))), "briefDue", date(t0.plus(Duration.ofDays(38)))));
            ctx.check("hearing timer re-armed by the model to +45d", dueIs(timerAt(p, "gp51_hearingDay"), t0.plus(Duration.ofDays(45))));
            ctx.check("brief deadline NOT moved by the model (still +23d)", dueIs(timerAt(p, "gp51_briefDue"), t0.plus(Duration.ofDays(23))));
            String note = nat.recalculateTimerFromModel(adapter, timerAt(p, "gp51_briefDue").jobId());
            if (note != null) {
                ctx.workaround(note, 18);
            }
            ctx.check("brief deadline re-armed natively to +38d", dueIs(timerAt(p, "gp51_briefDue"), t0.plus(Duration.ofDays(38))));
            clock(t0.plus(Duration.ofDays(30)));
            adapter.executeDueJobs();
            ctx.check("no hearing and no escalation on the old dates", tasks(p, "gp51_attend").isEmpty() && tasks(p, "gp51_escalateBrief").isEmpty());
            clock(t0.plus(Duration.ofDays(40)));
            adapter.executeDueJobs();
            ctx.check("brief overdue on +38d -> escalated to JS (Legal)", countForGroup(p, "gp51_escalateBrief", "js-legal") == 1);
            complete(ctx, p, "gp51_brief", "law-officer", null);
            complete(ctx, p, "gp51_escalateBrief", "js-legal", null);
            clock(t0.plus(Duration.ofDays(46)));
            adapter.executeDueJobs();
            complete(ctx, p, "gp51_attend", "law-officer", vars("outcome", "ADJOURNED",
                    "nextHearing", date(t0.plus(Duration.ofDays(75))), "briefDue", date(t0.plus(Duration.ofDays(68)))));
            ctx.check("adjourned: next hearing +75d armed", dueIs(timerAt(p, "gp51_hearingDay"), t0.plus(Duration.ofDays(75))));
            complete(ctx, p, "gp51_brief", "law-officer", null);
            clock(t0.plus(Duration.ofDays(76)));
            adapter.executeDueJobs();
            complete(ctx, p, "gp51_attend", "law-officer", vars("outcome", "FINAL_ORDER"));
            complete(ctx, p, "gp51_comply", "department", null);
            ctx.check("two hearings attended, one escalation, case closed",
                    countVisits(p, "gp51_attend") == 2 && countVisits(p, "gp51_escalateBrief") == 1 && ended(p));
        });
    }

    // ================================================================== GP-52
    @Test
    void gp52() {
        scenario("GP-52", "Record lifecycle after closure: retention, reopen, review, weed", ctx -> {
            ctx.feature("long timer after closure", "reopen by message during retention", "review loop",
                    "history lookup after end (business key)", "history deletion (weeding)");
            deploy("gp52-retention.bpmn");
            Instant t0 = now;
            String p = adapter.startProcess(null, "gp52-retention", "FILE-52", Map.of());
            complete(ctx, p, "gp52_work", "section", null);
            complete(ctx, p, "gp52_close", "section-officer", vars("retentionUntil", date(t0.plus(Duration.ofDays(3 * 365)))));
            ctx.check("retention timer +3 years", dueIs(timerAt(p, "gp52_retentionOver"), t0.plus(Duration.ofDays(3 * 365))));
            advance(Duration.ofDays(365));
            adapter.correlateMessage(null, "fileReopened", "FILE-52", null);
            ctx.check("reopened file back in work", tasks(p, "gp52_work").size() == 1 && nat.timers(adapter, p).isEmpty());
            complete(ctx, p, "gp52_work", "section", null);
            Instant ret2 = now.plus(Duration.ofDays(3 * 365));
            complete(ctx, p, "gp52_close", "section-officer", vars("retentionUntil", date(ret2)));
            clock(ret2.plus(DAY));
            adapter.executeDueJobs();
            complete(ctx, p, "gp52_review", "record-officer", vars("reviewDecision", "RETAIN", "retentionUntil", date(now.plus(Duration.ofDays(5 * 365)))));
            ctx.check("retained: retention re-armed +5 years", dueIs(timerAt(p, "gp52_retentionOver"), now.plus(Duration.ofDays(5 * 365))));
            advance(Duration.ofDays(5 * 365 + 1));
            adapter.executeDueJobs();
            complete(ctx, p, "gp52_review", "record-officer", vars("reviewDecision", "WEED"));
            ctx.check("weeded: instance ended", ended(p) && visited(p, "gp52_end_weeded"));
            ctx.check("reopen after closure: no waiting instance (refused)",
                    throwsOn(() -> adapter.correlateMessage(null, "fileReopened", "FILE-52", null)));
            ctx.check("history still finds the file by its number", nat.historicInstancesByBusinessKey(adapter, "gp52-retention", "FILE-52").size() == 1);
            nat.deleteHistoricInstance(adapter, p);
            ctx.check("after weeding the engine record is gone", nat.historicInstancesByBusinessKey(adapter, "gp52-retention", "FILE-52").isEmpty()
                    && adapter.getInstance(p) == null);
            ctx.detail("note", "retention runs as a live wait state in the instance (one timer job per closed file);"
                    + " engine history TTL / cleanup was not used, the weed step deletes history explicitly");
        });
    }

    // ================================================================== GP-53
    @Test
    void gp53() {
        scenario("GP-53", "Physical artefact (measurement book) custody alongside the digital flow", ctx -> {
            ctx.feature("gateway on a custody flag", "interrupting message boundary on a sub-process (loss)", "history of custody changes");
            deploy("gp53-custody.bpmn");
            String p = adapter.startProcess(null, "gp53-custody", "MB-53", vars("mbCustodian", "JE", "mbNo", "MB-1047"));
            complete(ctx, p, "gp53_record", "je", vars("mbCustodian", "JE"));
            ctx.check("check blocked while the MB is with the JE (hand-over task instead)",
                    tasks(p, "gp53_check").isEmpty() && tasks(p, "gp53_transfer").size() == 1);
            adapter.correlateMessage(null, "mbLost", "MB-53", vars("lostOn", date(now)));
            ctx.check("loss interrupts the custody flow", tasks(p, "gp53_transfer").isEmpty() && tasks(p, "gp53_reportLoss").size() == 1);
            complete(ctx, p, "gp53_reportLoss", "ee", null);
            complete(ctx, p, "gp53_reconstruct", "je", vars("mbNo", "MB-1047-R", "mbCustodian", "JE"));
            complete(ctx, p, "gp53_record", "je", null);
            complete(ctx, p, "gp53_transfer", "je", vars("mbCustodian", "AE"));
            complete(ctx, p, "gp53_check", "ae", null);
            ctx.check("ended after check by AE", ended(p));
            List<Object> h = nat.variableHistory(adapter, p, "mbCustodian");
            ctx.detail("custodyHistory", h);
            ctx.check("custody trail recorded (ends with AE, at least 3 entries)", h.size() >= 3 && "AE".equals(h.get(h.size() - 1)));
        });
    }

    // ================================================================== GP-54
    @Test
    void gp54() {
        scenario("GP-54", "Case split / part / link: part files as children, live parent update, linked file", ctx -> {
            ctx.feature("non-interrupting message event sub-process", "call activity in/out mapping", "parent update from running child (native)",
                    "sub-instance query (native)", "linked sibling instance (native reference / variables)", "gate on open parts");
            deploy("gp54-main.bpmn", "gp54-part.bpmn");
            String main = adapter.startProcess(null, "gp54-main", "F-100", vars("openParts", 0, "fileNo", "F-100"));
            adapter.correlateMessage(null, "createPart", "F-100", vars("partSubject", "land"));
            adapter.correlateMessage(null, "createPart", "F-100", vars("partSubject", "finance"));
            List<String> parts = nat.subInstances(adapter, main);
            ctx.check("two part files running as children", parts.size() == 2 && num(var(main, "openParts")) == 2);
            ctx.require("2 parts", parts.size() == 2);
            String part1 = parts.get(0);
            String parent = nat.superInstance(adapter, part1);
            nat.setVariable(adapter, parent, "partStatus_" + var(part1, "partSubject"), "noting started");
            ctx.check("main file sees the part's live status", var(main, "partStatus_" + var(part1, "partSubject")) != null);
            complete(ctx, part1, "gp54p_noting", "section", null);
            complete(ctx, part1, "gp54p_decide", "so", vars("partDecision", "approved"));
            ctx.check("part 1 closed: openParts 1, decision mapped to main", num(var(main, "openParts")) == 1
                    && "approved".equals(var(main, "partDecision")));
            complete(ctx, main, "gp54_noting", "section", null);
            ctx.check("main disposal blocked while a part is open", tasks(main, "gp54_dispose").isEmpty() && tasks(main, "gp54_waitParts").size() == 1);
            String part2 = parts.get(1);
            complete(ctx, part2, "gp54p_noting", "section", null);
            complete(ctx, part2, "gp54p_decide", "so", vars("partDecision", "noted"));
            complete(ctx, main, "gp54_waitParts", "section", null);
            ctx.check("all parts closed -> disposal", tasks(main, "gp54_dispose").size() == 1);
            String linked = nat.startLinked(adapter, "gp54-main", "F-200", vars("openParts", 0, "fileNo", "F-200"), main, "linked-file");
            if ("operaton".equals(nat.engine())) {
                ctx.workaround("no reference field on a process instance: the link is two variables (linkRefId,"
                        + " linkRefType) queried with variableValueEquals", 4);
            }
            ctx.check("linked file found from the main file", nat.linkedInstances(adapter, main, "linked-file").equals(List.of(linked)));
            complete(ctx, main, "gp54_dispose", "so", null);
            ctx.check("main ended, linked file still running", ended(main) && !ended(linked));
            ctx.detail("merge", "not tested: merge of files has no sourced stage (GP-coverage-validation GP-54)");
        });
    }

    // ================================================================== GP-55
    @Test
    void gp55() {
        scenario("GP-55", "Periodic verification campaign (GFR 213) that opens discrepancy cases", ctx -> {
            ctx.feature("timer start event (yearly cycle)", "parallel multi-instance verification", "local variables per branch (native)",
                    "conditional case creation by external work", "cases outlive the campaign");
            deploy("gp55-verification.bpmn", "gp55-discrepancy.bpmn");
            List<Native.Timer> st = nat.startTimers(adapter, "gp55-verification");
            ctx.check("campaign timer armed for 2027-03-31", st.size() == 1 && dueIs(st.get(0), Instant.parse("2027-03-31T04:30:00Z")));
            clock(Instant.parse("2027-03-31T04:31:00Z"));
            adapter.executeDueJobs();
            List<String> camp = nat.runningInstances(adapter, "gp55-verification");
            ctx.require("one campaign started", camp.size() == 1);
            String c = camp.get(0);
            int[] book = {10, 4, 7, 2, 30};
            int[] physical = {10, 3, 7, 0, 30};
            work(ctx, c, "stock-register", vars("itemCount", book.length));
            List<TaskView> vt = tasks(c, "gp55_verify");
            ctx.check("5 verification tasks", vt.size() == 5);
            ctx.workaround("per-branch result: the product writes physicalQty/bookQty as LOCAL variables of the branch"
                    + " (RuntimeService.setVariablesLocal on the task's execution) before completing, so concurrent"
                    + " completions never overwrite each other", 4);
            for (TaskView t : vt) {
                String ex = nat.taskExecutionId(adapter, t.id());
                int i = ((Number) nat.getVariable(adapter, ex, "loopCounter")).intValue();
                nat.setVariablesLocal(adapter, ex, vars("bookQty", book[i], "physicalQty", physical[i], "itemNo", i));
                adapter.claimTask(t.id(), "board");
                adapter.completeTask(t.id(), Map.of());
            }
            if ("flowable".equals(nat.engine())) {
                ctx.workaround("external-worker jobs are exclusive by default; the multi-instance 'open case' task carries"
                        + " flowable:exclusive=\"false\" so several branches' jobs can be fetched together", 1);
            }
            int opened = 0;
            for (int k = 0; k < 4; k++) {
                List<ExternalWork> got = adapter.fetchAndLock("w-open", "open-discrepancy", 10, Duration.ofMinutes(5));
                for (ExternalWork w : got) {
                    Object item = w.variables().get("itemNo");
                    adapter.startProcess(null, "gp55-discrepancy", "DISC-2027-" + item,
                            vars("itemNo", item, "shortBy", ((Number) w.variables().get("bookQty")).intValue()
                                    - ((Number) w.variables().get("physicalQty")).intValue()));
                    adapter.completeExternal(w.id(), "w-open", w.lockVersion(), Map.of());
                    opened++;
                }
            }
            List<String> cases = nat.runningInstances(adapter, "gp55-discrepancy");
            ctx.check("2 discrepancy cases opened (items 1 and 3)", opened == 2 && cases.size() == 2
                    && !nat.historicInstancesByBusinessKey(adapter, "gp55-discrepancy", "DISC-2027-1").isEmpty()
                    && !nat.historicInstancesByBusinessKey(adapter, "gp55-discrepancy", "DISC-2027-3").isEmpty());
            complete(ctx, c, "gp55_report", "hod", null);
            ctx.check("campaign ended while the cases continue", ended(c) && nat.runningInstances(adapter, "gp55-discrepancy").size() == 2);
            List<Native.Timer> st2 = nat.startTimers(adapter, "gp55-verification");
            ctx.check("next campaign armed for 2028-03-31", st2.size() == 1 && dueIs(st2.get(0), Instant.parse("2028-03-31T04:30:00Z")));
        });
    }


}
