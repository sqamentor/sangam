// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.EngineAdapter.TaskView;

/**
 * Port of Stage B E16-c ({@code spike/experiments-gov-c/.../E16cPatternTest}): government patterns GP-41..GP-55 on the
 * Sangam server, same models, data and checks. See {@link GovCSupport} for the shared-context differences.
 */
class E16cPatternIT extends GovCSupport {

    static final Duration DAY = Duration.ofDays(1);

    // ================================================================== GP-41
    @Test
    void gp41() {
        scenario("GP-41", ctx -> {
            deploy("gp41-bg.bpmn");
            Instant t0 = now;
            String bg1 = b("BG-1");
            String a = adapter.startProcess(null, "gp41-bg", bg1,
                    vars("bgExpiry", date(t0.plus(Duration.ofDays(180))), "reminderAt", date(t0.plus(Duration.ofDays(150)))));
            adapter.correlateMessage(null, "bgConfirmed", bg1, vars("genuine", true));
            ctx.check("after bank confirmation: reminder timer due at reminderAt (+150d)",
                    dueIs(timerAt(a, "gp41_reminderDue"), t0.plus(Duration.ofDays(150))));

            setVariable(a, "reminderAt", date(t0.plus(Duration.ofDays(120))));
            recalculateTimerFromModel(timerAt(a, "gp41_reminderDue").jobId());
            ctx.check("native recalculation moved the reminder to the new reminderAt (+120d)",
                    dueIs(timerAt(a, "gp41_reminderDue"), t0.plus(Duration.ofDays(120))));
            setTimerDue(timerAt(a, "gp41_reminderDue").jobId(), t0.plus(Duration.ofDays(125)));
            ctx.check("native set-due-date moved the reminder to +125d", dueIs(timerAt(a, "gp41_reminderDue"), t0.plus(Duration.ofDays(125))));
            ctx.check("still exactly one timer", timers(a).size() == 1);

            adapter.correlateMessage(null, "bgExtended", bg1,
                    vars("bgExpiry", date(t0.plus(Duration.ofDays(365))), "reminderAt", date(t0.plus(Duration.ofDays(335)))));
            ctx.check("model re-arm: one timer, due at the new reminderAt (+335d)",
                    timers(a).size() == 1 && dueIs(timerAt(a, "gp41_reminderDue"), t0.plus(Duration.ofDays(335))));

            advance(Duration.ofDays(336));
            adapter.executeDueJobs();
            complete(ctx, a, "gp41_requestExtension", "contracts", null);
            ctx.check("expiry timer armed at bgExpiry (+365d)", dueIs(timerAt(a, "gp41_expiryDue"), t0.plus(Duration.ofDays(365))));

            advance(Duration.ofDays(30)); // day 366
            adapter.correlateMessage(null, "bgExtended", bg1,
                    vars("bgExpiry", date(t0.plus(Duration.ofDays(500))), "reminderAt", date(t0.plus(Duration.ofDays(470)))));
            adapter.executeDueJobs();
            ctx.check("race: extension correlated before the overdue expiry job ran -> no encashment",
                    tasks(a, "gp41_encash").isEmpty());
            ctx.check("race: watch re-armed at +470d", dueIs(timerAt(a, "gp41_reminderDue"), t0.plus(Duration.ofDays(470))));

            advance(Duration.ofDays(105)); // 471
            adapter.executeDueJobs();
            complete(ctx, a, "gp41_requestExtension", "contracts", null);
            advance(Duration.ofDays(30)); // 501
            adapter.executeDueJobs();
            ctx.check("expiry fired -> encash task", tasks(a, "gp41_encash").size() == 1);
            ctx.check("late extension after expiry is refused (no waiting subscription)",
                    throwsOn(() -> adapter.correlateMessage(null, "bgExtended", bg1, vars("bgExpiry", date(now)))));
            complete(ctx, a, "gp41_encash", "accounts", null);
            ctx.check("instance ended at gp41_end_encashed", ended(a) && visited(a, "gp41_end_encashed"));

            String bg2 = b("BG-2");
            String bb = adapter.startProcess(null, "gp41-bg", bg2,
                    vars("bgExpiry", date(now.plus(Duration.ofDays(180))), "reminderAt", date(now.plus(Duration.ofDays(150)))));
            adapter.correlateMessage(null, "bgConfirmed", bg2, vars("genuine", true));
            adapter.correlateMessage(null, "contractClosed", bg2, null);
            complete(ctx, bb, "gp41_release", "accounts", null);
            ctx.check("release path ended", ended(bb) && visited(bb, "gp41_end_released"));
            String bg3 = b("BG-3");
            String c = adapter.startProcess(null, "gp41-bg", bg3,
                    vars("bgExpiry", date(now.plus(Duration.ofDays(180))), "reminderAt", date(now.plus(Duration.ofDays(150)))));
            adapter.correlateMessage(null, "bgConfirmed", bg3, vars("genuine", false));
            ctx.check("non-genuine BG goes to vigilance", countForGroup(c, "gp41_reportFake", "vigilance") == 1);
        });
    }

    // ================================================================== GP-42
    @Test
    void gp42() {
        scenario("GP-42", ctx -> {
            deploy("gp42-cumulative.bpmn", "gp42-revised-sanction.bpmn");
            String p = adapter.startProcess(null, "gp42-cumulative", b("WORK-42"),
                    vars("sanctioned", 1_000_000L, "cumulative", 0L, "revisionPending", false, "excessNoted", false, "done", false));
            for (long amt : new long[]{400_000L, 450_000L}) {
                complete(ctx, p, "gp42_raBill", "ee", vars("billAmount", amt, "done", false));
            }
            complete(ctx, p, "gp42_raBill", "ee", vars("billAmount", 200_000L, "done", false));
            ctx.check("cumulative 1,050,000 within 10 %: excess noted by SE", countForGroup(p, "gp42_excessApproval", "se") == 1);
            complete(ctx, p, "gp42_excessApproval", "se", vars("excessNoted", true));
            ctx.check("no revision yet", subInstances(p).isEmpty());
            complete(ctx, p, "gp42_raBill", "ee", vars("billAmount", 100_000L, "done", false));
            // Operaton evaluates conditional events on every variable change in scope (spike: no manual evaluation)
            List<String> subs = subInstances(p);
            ctx.detail("conditionalEvaluatedByEngineOnVariableChange", true);
            ctx.check("cumulative 1,150,000 > 110 %: revised-sanction child started", subs.size() == 1);
            ctx.check("billing continues meanwhile (RA bill task open)", tasks(p, "gp42_raBill").size() == 1);
            ctx.require("child exists", !subs.isEmpty());
            complete(ctx, subs.get(0), "gp42r_approve", "aa-authority", vars("revisedSanction", 1_300_000L));
            ctx.check("parent sanction updated from the child (out-mapping): 1,300,000",
                    Long.valueOf(1_300_000L).equals(var(p, "sanctioned")));
            complete(ctx, p, "gp42_raBill", "ee", vars("billAmount", 50_000L, "done", true));
            ctx.check("revision ran exactly once", countVisits(p, "gp42_revise") == 1);
            ctx.check("instance ended", ended(p));
            ctx.check("cumulative = 1,200,000", Long.valueOf(1_200_000L).equals(var(p, "cumulative")));
            List<Object> sanc = variableHistory(p, "sanctioned");
            ctx.detail("sanctionedHistory", sanc);
            ctx.check("history keeps old and new sanction [1000000, 1300000]",
                    sanc.size() >= 2 && Long.valueOf(1_000_000L).equals(sanc.get(0)) && Long.valueOf(1_300_000L).equals(sanc.get(sanc.size() - 1)));
        });
    }

    // ================================================================== GP-43
    @Test
    void gp43() {
        scenario("GP-43", ctx -> {
            deploy("gp43-contract.bpmn", "gp43-amendment.bpmn");
            Instant t0 = now;
            Instant stipulated = t0.plus(Duration.ofDays(365));
            String wo = b("WO-43");
            String p = adapter.startProcess(null, "gp43-contract", wo,
                    vars("completionDate", date(stipulated), "contractValue", 10_000_000L));
            TaskView workTask = single(ctx, p, "gp43_execution");
            ctx.check("LD timer at the stipulated date", dueIs(timerAt(p, "gp43_ldStart"), stipulated));

            advance(Duration.ofDays(200));
            Instant extended = stipulated.plus(Duration.ofDays(90));
            adapter.correlateMessage(null, "amendmentRequested", wo,
                    vars("amendType", "EOT", "proposedCompletion", date(extended), "extraAmount", 0L));
            List<String> subs = subInstances(p);
            ctx.require("amendment child started", subs.size() == 1);
            String child = subs.get(0);
            String parentOfChild = superInstance(child);
            ctx.check("child finds its parent", p.equals(parentOfChild));
            setVariable(parentOfChild, "hindranceDays", 90);
            ctx.check("parent sees the child's update while the child is still running",
                    Integer.valueOf(90).equals(var(p, "hindranceDays")) && !ended(child));
            complete(ctx, child, "gp43a_examine", "ee", null);
            complete(ctx, child, "gp43a_approve", "se", vars("approved", true, "approvedCompletion", date(extended), "approvedExtra", 0L));
            ctx.check("parent completionDate updated from the child result",
                    var(p, "completionDate") instanceof Date d && d.toInstant().equals(extended));
            ctx.detail("ldTimerFollowedVariableByItself", dueIs(timerAt(p, "gp43_ldStart"), extended));
            ctx.check("without engine action the LD timer still points at the OLD date (timers are not re-evaluated)",
                    dueIs(timerAt(p, "gp43_ldStart"), stipulated));
            recalculateTimerFromModel(timerAt(p, "gp43_ldStart").jobId());
            ctx.check("LD timer re-armed to the extended date", dueIs(timerAt(p, "gp43_ldStart"), extended));

            startAdditionalActivity(p, "gp43_extraItem");
            ctx.check("extra item task created in the running instance", tasks(p, "gp43_extraItem").size() == 1);
            List<TaskView> work = tasks(p, "gp43_execution");
            ctx.check("main work task still exactly one", work.size() == 1);
            ctx.detail("mainWorkTaskKeptItsId", work.size() == 1 && work.get(0).id().equals(workTask.id()));
            ctx.check("LD timer still at the extended date after the modification", dueIs(timerAt(p, "gp43_ldStart"), extended));
            adapter.correlateMessage(null, "amendmentRequested", wo,
                    vars("amendType", "EXTRA_ITEM", "proposedCompletion", date(extended), "extraAmount", 450_000L));
            String child2 = subInstances(p).get(0);
            complete(ctx, child2, "gp43a_examine", "ee", null);
            complete(ctx, child2, "gp43a_approve", "se", vars("approved", true, "approvedCompletion", date(extended), "approvedExtra", 450_000L));
            ctx.check("contract value increased to 10,450,000", Long.valueOf(10_450_000L).equals(var(p, "contractValue")));
            complete(ctx, p, "gp43_extraItem", "ee", null);

            clock(stipulated.plus(DAY));
            adapter.executeDueJobs();
            ctx.check("no LD notice the day after the OLD date", tasks(p, "gp43_ldNotice").isEmpty());
            clock(extended.plus(DAY));
            adapter.executeDueJobs();
            ctx.check("LD notice the day after the EXTENDED date", tasks(p, "gp43_ldNotice").size() == 1);
            List<Object> hist = variableHistory(p, "completionDate");
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
        scenario("GP-44", ctx -> {
            deploy("gp44-deemed.bpmn");
            Instant t0 = now;
            String r1 = b("RTI-1");
            String a = adapter.startProcess(null, "gp44-deemed", r1, Map.of());
            ctx.check("deadline at +30 days", dueIs(timerAt(a, "gp44_deadline"), t0.plus(Duration.ofDays(30))));
            TaskView stale = single(ctx, a, "gp44_pioDecide");
            advance(Duration.ofDays(30).plusMinutes(1));
            adapter.executeDueJobs();
            ctx.check("deemed refusal reached", visited(a, "gp44_deemedRefusal"));
            ctx.check("PIO task withdrawn", tasks(a, "gp44_pioDecide").isEmpty());
            ctx.check("late decision by the PIO is blocked (task no longer exists)",
                    throwsOn(() -> adapter.completeTask(stale.id(), Map.of())));
            adapter.correlateMessage(null, "firstAppealFiled", r1, null);
            ctx.check("first appeal goes to the FAA", countForGroup(a, "gp44_faaHearing", "faa") == 1);

            String bb = adapter.startProcess(null, "gp44-deemed", b("RTI-2"), Map.of());
            complete(ctx, bb, "gp44_pioDecide", "pio", null);
            ctx.check("decided in time ends normally", ended(bb) && visited(bb, "gp44_end_decided"));

            String c = adapter.startProcess(null, "gp44-deemed", b("RTI-3"), Map.of());
            advance(Duration.ofDays(31));
            adapter.executeDueJobs();
            advance(Duration.ofDays(31));
            adapter.executeDueJobs();
            ctx.check("no appeal within 30 days of the deemed refusal -> lapsed", ended(c) && visited(c, "gp44_end_lapsed"));
        });
    }

    // ================================================================== GP-45
    /**
     * Sangam replacement: the spike suspended the instance natively and tried three ways to re-date the suspended timer
     * (product-side remaining-time arithmetic). Sangam ships this as D-32 {@code pauseInstance}/{@code resumeInstance}
     * (re-dates every timer by the paused time while suspended, then activates), so that is what is tested here; the
     * spike's checks are unchanged.
     */
    @Test
    void gp45() {
        scenario("GP-45", ctx -> {
            deploy("gp45-pausable.bpmn");
            Instant t0 = now;
            String p = adapter.startProcess(null, "gp45-pausable", b("RTI-45"), vars("slaDue", date(t0.plus(Duration.ofDays(30)))));
            TaskView task = single(ctx, p, "gp45_process");
            advance(Duration.ofDays(10));
            Instant pausedAt = now;
            adapter.pauseInstance(p, "pio", "fee intimation sent");
            ctx.check("instance suspended", isSuspended(p));
            Timer sus = timerAt(p, "gp45_sla");
            ctx.check("SLA timer suspended with the instance", sus != null && sus.suspended());
            ctx.check("officer cannot complete the task while the clock is stopped",
                    throwsOn(() -> adapter.completeTask(task.id(), Map.of())));
            advance(Duration.ofDays(35)); // day 45, beyond the original due date
            adapter.executeDueJobs();
            ctx.check("suspended timer did not fire past its original due date", tasks(p, "gp45_escalate").isEmpty());
            Duration paused = Duration.between(pausedAt, now);
            adapter.resumeInstance(p, "pio");
            ctx.detail("resumePath", "SangamEngineAdapter.resumeInstance (D-32)");
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
        scenario("GP-46", ctx -> {
            deploy("gp46-objections.bpmn");
            Instant end = now.plus(Duration.ofDays(30));
            String p = adapter.startProcess(null, "gp46-objections", b("S11-1"), vars("partyCount", 3, "windowEnd", date(end)));
            complete(ctx, p, "gp46_notice", "pio", null);
            ctx.check("three party branches waiting", messageSubscriptions(p, "thirdPartyObjection").size() == 3);
            advance(Duration.ofDays(5));
            ctx.check("party 0 objects", deliverToBranch(p, "thirdPartyObjection", 0, vars("objection", "objection by party 0")));
            advance(Duration.ofDays(5));
            ctx.check("party 2 objects", deliverToBranch(p, "thirdPartyObjection", 2, vars("objection", "objection by party 2")));
            ctx.check("decision waits for the silent party", tasks(p, "gp46_decide").isEmpty());
            advance(Duration.ofDays(21));
            adapter.executeDueJobs();
            ctx.check("window closed -> decision", tasks(p, "gp46_decide").size() == 1);
            ctx.check("2 objections, 1 silent", countVisits(p, "gp46_endObjected") == 2 && countVisits(p, "gp46_endSilent") == 1);
            ctx.check("late objection finds no waiting branch", messageSubscriptions(p, "thirdPartyObjection").isEmpty());

            String q = adapter.startProcess(null, "gp46-objections", b("S11-2"), vars("partyCount", 3, "windowEnd", date(now.plus(Duration.ofDays(30)))));
            complete(ctx, q, "gp46_notice", "pio", null);
            for (int i = 0; i < 3; i++) {
                deliverToBranch(q, "thirdPartyObjection", i, vars("objection", "objection " + i));
            }
            ctx.check("all parties answered early -> decision at once", tasks(q, "gp46_decide").size() == 1);
        });
    }

    // ================================================================== GP-47
    /**
     * Sangam decision GP-09 (SANGAM.md X.0d pending amendment 4: "one instance per item; no large multi-instance"):
     * the engine refuses a multi-instance above {@code sangam.engine.multi-instance-max-cardinality} (default 50). The
     * spike ran the bulk recompute with 300 candidates; here the refusal of 300 is checked first, then the unchanged
     * scenario runs with the largest cardinality Sangam allows (50).
     */
    @Test
    void gp47() {
        scenario("GP-47", ctx -> {
            deploy("gp47-answer-key.bpmn", "gp47-challenge.bpmn", "probe-mi-external.bpmn");
            String probe = adapter.startProcess(null, "probe-mi-external", b("PROBE"), Map.of());
            List<ExternalWork> pf = fetch("probe-mi", 10);
            List<ExternalWork> pf1 = pf.isEmpty() ? fetch("probe-mi", 1) : List.of();
            ctx.detail("probeDefaultMiFetch10", pf.size());
            ctx.detail("probeDefaultMiFetch1", pf1.size());
            for (ExternalWork w : pf) {
                adapter.unlockExternal(w.id(), "w-" + w.topic(), w.lockVersion());
            }
            for (ExternalWork w : pf1) {
                adapter.unlockExternal(w.id(), "w-" + w.topic(), w.lockVersion());
            }
            adapter.cancelInstance(probe, "probe done");

            // GP-09 guard: 300 candidates in one multi-instance is refused
            String big = adapter.startProcess(null, "gp47-answer-key", b("EXAM-47-300"),
                    vars("windowEnd", date(now.plus(Duration.ofDays(7))), "candidateCount", 300));
            complete(ctx, big, "gp47_publishProvisional", "exam-cell", null);
            advance(Duration.ofDays(7).plusMinutes(1));
            adapter.executeDueJobs();
            String bigReview = tasks(big, "gp47_review").isEmpty() ? null : tasks(big, "gp47_review").get(0).id();
            boolean refused = false;
            ctx.detail("bigReviewTask", bigReview);
            if (bigReview != null) {
                adapter.claimTask(bigReview, "u");
                try {
                    adapter.completeTask(bigReview, vars("correctedQuestions", 2));
                } catch (RuntimeException e) {
                    refused = String.valueOf(e.getMessage()).contains("GP-09");
                    ctx.detail("gp09Refusal", e.getMessage());
                }
            }
            ctx.detail("bigActiveAfterReview", active(big).size());
            ctx.check("GP-09: a 300-item multi-instance is refused (one instance per item)", refused);
            adapter.cancelInstance(big, "GP-09 probe done");
            clock(START);

            int n = 50;
            sql("drop table if exists govc_result");
            sql("create table govc_result(candidate int, version int, score int, primary key(candidate, version))");
            adapter.inEngineTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement("insert into govc_result values (?, 1, ?)")) {
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
            String key = adapter.startProcess(null, "gp47-answer-key", b("EXAM-47"), vars("windowEnd", date(windowEnd), "candidateCount", n));
            complete(ctx, key, "gp47_publishProvisional", "exam-cell", null);
            List<String> ch = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                ch.add(adapter.startProcess(null, "gp47-challenge", b("CH-" + i), vars("windowEnd", date(windowEnd))));
            }
            for (int i = 0; i < 4; i++) {
                adapter.correlateMessage(null, "challengeFeePaid", b("CH-" + i), vars("question", 10 + i));
            }
            advance(Duration.ofDays(7).plusMinutes(1));
            adapter.executeDueJobs();
            ctx.check("late fee after the window is refused",
                    throwsOn(() -> adapter.correlateMessage(null, "challengeFeePaid", b("CH-5"), null)));
            long admitted = ch.stream().filter(id -> visited(id, "gp47c_admitted")).count();
            long lapsed = ch.stream().filter(id -> visited(id, "gp47c_lapsed")).count();
            ctx.check("4 paid challenges admitted, 2 lapsed", admitted == 4 && lapsed == 2);
            complete(ctx, key, "gp47_review", "expert-committee", vars("correctedQuestions", 2));
            long t = System.nanoTime();
            int done = 0;
            int missingIndex = 0;
            int[] fallback = {0};
            for (int round = 0; round < 20 && done < n; round++) {
                List<ExternalWork> got = fetch("recompute", 100);
                if (got.isEmpty()) {
                    break;
                }
                for (ExternalWork w : got) {
                    Object lc = w.variables().get("loopCounter");
                    if (!(lc instanceof Number)) {
                        missingIndex++;
                        lc = fallback[0]++;
                    }
                    int cand = ((Number) lc).intValue();
                    adapter.inEngineTransaction(c -> {
                        try (PreparedStatement ps = c.prepareStatement(
                                "insert into govc_result select candidate, 2, score + case when mod(candidate, 3) = 0 then 2 else 0 end"
                                        + " from govc_result where candidate = ? and version = 1")) {
                            ps.setInt(1, cand);
                            ps.executeUpdate();
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                        completeWork(w, Map.of());
                        return null;
                    });
                    done++;
                }
            }
            ctx.detail("candidates", n);
            ctx.detail("recomputeMs", (System.nanoTime() - t) / 1_000_000);
            ctx.detail("workItemsWithoutLoopCounterVariable", missingIndex);
            ctx.check("all " + n + " candidates recomputed", done == n && count("select count(*) from govc_result where version = 2") == n);
            ctx.check("recomputed scores correct (+2 where candidate % 3 == 0)",
                    count("select count(*) from govc_result a join govc_result b on a.candidate = b.candidate and b.version = 2"
                            + " where a.version = 1 and b.score = a.score + case when mod(a.candidate, 3) = 0 then 2 else 0 end") == n);
            complete(ctx, key, "gp47_publishFinal", "exam-cell", null);
            ctx.check("final published, instance ended", ended(key));
        });
    }

    // ================================================================== GP-48
    @Test
    void gp48() {
        scenario("GP-48", ctx -> {
            deploy("gp48-leave.bpmn", "gp48-yearend.bpmn");
            sql("drop table if exists govc_leave");
            sql("drop table if exists govc_leave_applied");
            sql("create table govc_leave(emp varchar(20), yr int, balance int, primary key(emp, yr))");
            sql("create table govc_leave_applied(request_id varchar(60) primary key)");
            sql("insert into govc_leave values ('E1', 2027, 20)");
            sql("insert into govc_leave values ('E2', 2027, 25)");
            int requests = 30;
            List<String> pids = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                pids.add(adapter.startProcess(null, "gp48-leave", b("LV-" + i), vars("emp", "E1", "days", 1, "requestId", b("LV-" + i))));
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
            for (int w = 0; w < threads; w++) {
                String worker = "w-leave-" + w;
                ex.submit(() -> {
                    try {
                        go.await();
                        for (int idle = 0; idle < 3; ) {
                            List<ExternalWork> got = adapter.fetchAndLock(worker, t("leave-debit"), 2, Duration.ofMinutes(5));
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
                                            errors.add(String.valueOf(e));
                                            break;
                                        }
                                        retries.incrementAndGet();
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        errors.add(String.valueOf(e));
                    }
                });
            }
            go.countDown();
            ex.shutdown();
            ex.awaitTermination(5, TimeUnit.MINUTES);
            ctx.detail("retriesAfterConflict", retries.get());
            ctx.detail("errors", new ArrayList<>(errors));
            ctx.check("exactly 20 granted, 10 refused (balance 20, 30 requests of 1 day)", granted.get() == 20 && refused.get() == 10);
            ctx.check("ledger balance 0, never negative", count("select balance from govc_leave where emp = 'E1' and yr = 2027") == 0);
            long grantedEnds = pids.stream().filter(id -> visited(id, "gp48_end_granted")).count();
            long informTasks = pids.stream().filter(id -> tasks(id, "gp48_inform").size() == 1).count();
            ctx.check("20 instances granted, 10 routed to 'inform' by the BPMN error", grantedEnds == 20 && informTasks == 10);
            boolean replay = adapter.inEngineTransaction(c -> applyDebit(c, b("LV-0"), "E1", 1));
            ctx.check("replayed debit of an applied request is a no-op", replay && count("select balance from govc_leave where emp = 'E1' and yr = 2027") == 0);
            List<Timer> st = startTimers("gp48-yearend");
            ctx.check("year-end timer armed for 2027-12-31T18:30Z (1 Jan IST)",
                    st.size() == 1 && dueIs(st.get(0), Instant.parse("2027-12-31T18:30:00Z")));
            ctx.detail("yearEndTimers", st);
            clock(Instant.parse("2027-12-31T18:31:00Z"));
            adapter.executeDueJobs();
            List<String> ye = runningInstances("gp48-yearend");
            ctx.require("year-end instance started", ye.size() == 1);
            ExternalWork cf = fetchOne(ctx, ye.get(0), "leave-carry-forward");
            adapter.inEngineTransaction(c -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("insert into govc_leave select emp, 2028, case when balance > 15 then 15 else balance end + 30"
                            + " from govc_leave where yr = 2027");
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
                completeWork(cf, Map.of());
                return null;
            });
            ctx.check("2028 balances: E1 = 30 (0 + 30), E2 = 45 (cap 15 + 30)",
                    count("select balance from govc_leave where emp = 'E1' and yr = 2028") == 30
                            && count("select balance from govc_leave where emp = 'E2' and yr = 2028") == 45);
            List<Timer> st2 = startTimers("gp48-yearend");
            ctx.detail("yearEndTimersAfterFire", st2);
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

    private static boolean applyDebit(Connection c, String requestId, String emp, int days) {
        try {
            try (PreparedStatement q = c.prepareStatement("select count(*) from govc_leave_applied where request_id = ?")) {
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
                    "update govc_leave set balance = balance - ? where emp = ? and yr = 2027 and balance >= ?")) {
                u.setInt(1, days);
                u.setString(2, emp);
                u.setInt(3, days);
                n = u.executeUpdate();
            }
            try (PreparedStatement i = c.prepareStatement("insert into govc_leave_applied values (?)")) {
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
        scenario("GP-49", ctx -> {
            deploy("gp49-dbt.bpmn");
            sql("drop table if exists govc_dbt_item");
            sql("drop table if exists govc_dbt_sent");
            sql("create table govc_dbt_item(item int primary key, status varchar(30))");
            sql("create table govc_dbt_sent(round int, slot int, item int, primary key(round, slot))");
            for (int i = 0; i < 10; i++) {
                sql("insert into govc_dbt_item values (" + i + ", 'NEW')");
            }
            String p = adapter.startProcess(null, "gp49-dbt", b("BATCH-49"), vars("itemCount", 10, "round", 1));
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
                    List<Integer> items = queryInts(c, "select item from govc_dbt_item where status <> 'PAID' order by item");
                    try (PreparedStatement ps = c.prepareStatement("insert into govc_dbt_sent values (?, ?, ?)")) {
                        for (int s = 0; s < items.size(); s++) {
                            ps.setInt(1, r);
                            ps.setInt(2, s);
                            ps.setInt(3, items.get(s));
                            ps.executeUpdate();
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                    completeWork(send, Map.of());
                    return items;
                });
                if (round == 2) {
                    sentRound2.addAll(sent);
                }
                List<String> waiting = messageSubscriptions(p, "itemResult");
                ctx.check("round " + round + ": one waiting branch per sent item (" + sent.size() + ")", waiting.size() == sent.size());
                List<Integer> slots = new ArrayList<>();
                for (String exId : waiting) {
                    slots.add(((Number) getVariable(exId, "loopCounter")).intValue());
                }
                for (int slot : slots) {
                    int item = sent.get(slot);
                    String status = round == 1 ? round1.get(item) : "PAID";
                    ctx.check("item " + item + " result delivered to its branch",
                            deliverToBranch(p, "itemResult", slot, vars("status", status, "itemId", item)));
                    sql("update govc_dbt_item set status = '" + status + "' where item = " + item);
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
                    int f = queryInts(c, "select item from govc_dbt_item where status <> 'PAID'").size();
                    completeWork(close, vars("itemCount", f, "round", r + 1));
                    return f;
                });
                if (failed == 0) {
                    break;
                }
            }
            ctx.detail("deliveries", deliveries);
            ctx.detail("sentInRound2", sentRound2);
            ctx.check("round 2 resent exactly the 3 failures [7, 8, 9]", sentRound2.equals(List.of(7, 8, 9)));
            ctx.check("13 per-item results correlated", deliveries == 13);
            ctx.check("all items paid, batch instance ended", ended(p) && count("select count(*) from govc_dbt_item where status = 'PAID'") == 10);
        });
    }

    // ================================================================== GP-50
    @Test
    void gp50() {
        scenario("GP-50", ctx -> {
            deploy("gp50-objection-loop.bpmn");
            Map<String, String> pid = new LinkedHashMap<>();
            for (int i = 1; i <= 5; i++) {
                String bk = b("BILL-" + i);
                pid.put(bk, adapter.startProcess(null, "gp50-objection-loop", bk, vars("objectionCount", 0)));
                complete(ctx, pid.get(bk), "gp50_prepare", "ddo", null);
                work(ctx, pid.get(bk), "treasury-submit", null);
            }
            for (int i = 1; i <= 5; i++) {
                if (i != 3) {
                    String got = adapter.correlateMessage(null, "treasuryVerdict", b("BILL-" + i), vars("verdict", "PASSED"));
                    ctx.check("BILL-" + i + " verdict reached its own instance", pid.get(b("BILL-" + i)).equals(got));
                }
            }
            String bk3 = b("BILL-3");
            String p3 = pid.get(bk3);
            for (int k = 0; k < 2; k++) {
                String got = adapter.correlateMessage(null, "treasuryVerdict", bk3, vars("verdict", "OBJECTED", "objection", "O-" + k));
                ctx.check("objection " + k + " correlated to the same instance", p3.equals(got));
                ctx.check("verdict for a bill not waiting is refused", throwsOn(() ->
                        adapter.correlateMessage(null, "treasuryVerdict", bk3, vars("verdict", "PASSED"))));
                complete(ctx, p3, "gp50_rectify", "ddo", null);
                work(ctx, p3, "treasury-submit", null);
            }
            adapter.correlateMessage(null, "treasuryVerdict", bk3, vars("verdict", "PASSED"));
            ctx.check("BILL-3 passed after 2 objections, same instance id and business key",
                    ended(p3) && num(var(p3, "objectionCount")) == 2 && bk3.equals(adapter.getInstance(p3).businessKey()));
            ctx.check("3 submissions on the one identity", countVisits(p3, "gp50_submit") == 3);
            ctx.check("all 5 bills ended", pid.values().stream().allMatch(this::ended));
        });
    }

    // ================================================================== GP-51
    @Test
    void gp51() {
        scenario("GP-51", ctx -> {
            deploy("gp51-hearings.bpmn");
            Instant t0 = now;
            String wp = b("WP-123-2027");
            String p = adapter.startProcess(null, "gp51-hearings", wp, Map.of());
            adapter.correlateMessage(null, "hearingListed", wp,
                    vars("nextHearing", date(t0.plus(Duration.ofDays(30))), "briefDue", date(t0.plus(Duration.ofDays(23)))));
            ctx.check("hearing timer +30d, brief deadline +23d",
                    dueIs(timerAt(p, "gp51_hearingDay"), t0.plus(Duration.ofDays(30))) && dueIs(timerAt(p, "gp51_briefDue"), t0.plus(Duration.ofDays(23))));
            advance(Duration.ofDays(5));
            adapter.correlateMessage(null, "hearingRescheduled", wp,
                    vars("nextHearing", date(t0.plus(Duration.ofDays(45))), "briefDue", date(t0.plus(Duration.ofDays(38)))));
            ctx.check("hearing timer re-armed by the model to +45d", dueIs(timerAt(p, "gp51_hearingDay"), t0.plus(Duration.ofDays(45))));
            ctx.check("brief deadline NOT moved by the model (still +23d)", dueIs(timerAt(p, "gp51_briefDue"), t0.plus(Duration.ofDays(23))));
            recalculateTimerFromModel(timerAt(p, "gp51_briefDue").jobId());
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
        scenario("GP-52", ctx -> {
            deploy("gp52-retention.bpmn");
            Instant t0 = now;
            String file = b("FILE-52");
            String p = adapter.startProcess(null, "gp52-retention", file, Map.of());
            complete(ctx, p, "gp52_work", "section", null);
            complete(ctx, p, "gp52_close", "section-officer", vars("retentionUntil", date(t0.plus(Duration.ofDays(3 * 365)))));
            ctx.check("retention timer +3 years", dueIs(timerAt(p, "gp52_retentionOver"), t0.plus(Duration.ofDays(3 * 365))));
            advance(Duration.ofDays(365));
            adapter.correlateMessage(null, "fileReopened", file, null);
            ctx.check("reopened file back in work", tasks(p, "gp52_work").size() == 1 && timers(p).isEmpty());
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
                    throwsOn(() -> adapter.correlateMessage(null, "fileReopened", file, null)));
            ctx.check("history still finds the file by its number", historicInstancesByBusinessKey("gp52-retention", file).size() == 1);
            deleteHistoricInstance(p);
            ctx.check("after weeding the engine record is gone", historicInstancesByBusinessKey("gp52-retention", file).isEmpty()
                    && adapter.getInstance(p) == null);
        });
    }

    // ================================================================== GP-53
    @Test
    void gp53() {
        scenario("GP-53", ctx -> {
            deploy("gp53-custody.bpmn");
            String mb = b("MB-53");
            String p = adapter.startProcess(null, "gp53-custody", mb, vars("mbCustodian", "JE", "mbNo", "MB-1047"));
            complete(ctx, p, "gp53_record", "je", vars("mbCustodian", "JE"));
            ctx.check("check blocked while the MB is with the JE (hand-over task instead)",
                    tasks(p, "gp53_check").isEmpty() && tasks(p, "gp53_transfer").size() == 1);
            adapter.correlateMessage(null, "mbLost", mb, vars("lostOn", date(now)));
            ctx.check("loss interrupts the custody flow", tasks(p, "gp53_transfer").isEmpty() && tasks(p, "gp53_reportLoss").size() == 1);
            complete(ctx, p, "gp53_reportLoss", "ee", null);
            complete(ctx, p, "gp53_reconstruct", "je", vars("mbNo", "MB-1047-R", "mbCustodian", "JE"));
            complete(ctx, p, "gp53_record", "je", null);
            complete(ctx, p, "gp53_transfer", "je", vars("mbCustodian", "AE"));
            complete(ctx, p, "gp53_check", "ae", null);
            ctx.check("ended after check by AE", ended(p));
            List<Object> h = variableHistory(p, "mbCustodian");
            ctx.detail("custodyHistory", h);
            ctx.check("custody trail recorded (ends with AE, at least 3 entries)", h.size() >= 3 && "AE".equals(h.get(h.size() - 1)));
        });
    }

    // ================================================================== GP-54
    @Test
    void gp54() {
        scenario("GP-54", ctx -> {
            deploy("gp54-main.bpmn", "gp54-part.bpmn");
            String f100 = b("F-100");
            String main = adapter.startProcess(null, "gp54-main", f100, vars("openParts", 0, "fileNo", f100));
            adapter.correlateMessage(null, "createPart", f100, vars("partSubject", "land"));
            adapter.correlateMessage(null, "createPart", f100, vars("partSubject", "finance"));
            List<String> parts = subInstances(main);
            ctx.check("two part files running as children", parts.size() == 2 && num(var(main, "openParts")) == 2);
            ctx.require("2 parts", parts.size() == 2);
            String part1 = parts.get(0);
            String parent = superInstance(part1);
            setVariable(parent, "partStatus_" + var(part1, "partSubject"), "noting started");
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
            // Operaton has no reference field on a process instance: the link is two variables (as in the spike)
            String linked = startLinked("gp54-main", b("F-200"), vars("openParts", 0, "fileNo", b("F-200")), main, "linked-file");
            ctx.check("linked file found from the main file", linkedInstances(main, "linked-file").equals(List.of(linked)));
            complete(ctx, main, "gp54_dispose", "so", null);
            ctx.check("main ended, linked file still running", ended(main) && !ended(linked));
        });
    }

    // ================================================================== GP-55
    @Test
    void gp55() {
        scenario("GP-55", ctx -> {
            deploy("gp55-verification.bpmn", "gp55-discrepancy.bpmn");
            List<Timer> st = startTimers("gp55-verification");
            ctx.check("campaign timer armed for 2027-03-31", st.size() == 1 && dueIs(st.get(0), Instant.parse("2027-03-31T04:30:00Z")));
            clock(Instant.parse("2027-03-31T04:31:00Z"));
            adapter.executeDueJobs();
            List<String> camp = runningInstances("gp55-verification");
            ctx.require("one campaign started", camp.size() == 1);
            String c = camp.get(0);
            int[] book = {10, 4, 7, 2, 30};
            int[] physical = {10, 3, 7, 0, 30};
            work(ctx, c, "stock-register", vars("itemCount", book.length));
            List<TaskView> vt = tasks(c, "gp55_verify");
            ctx.check("5 verification tasks", vt.size() == 5);
            for (TaskView tv : vt) {
                String ex = taskExecutionId(tv.id());
                int i = ((Number) getVariable(ex, "loopCounter")).intValue();
                setVariablesLocal(ex, vars("bookQty", book[i], "physicalQty", physical[i], "itemNo", i));
                adapter.claimTask(tv.id(), "board");
                adapter.completeTask(tv.id(), Map.of());
            }
            int opened = 0;
            for (int k = 0; k < 4; k++) {
                for (ExternalWork w : fetch("open-discrepancy", 10)) {
                    Object item = w.variables().get("itemNo");
                    adapter.startProcess(null, "gp55-discrepancy", b("DISC-2027-" + item),
                            vars("itemNo", item, "shortBy", ((Number) w.variables().get("bookQty")).intValue()
                                    - ((Number) w.variables().get("physicalQty")).intValue()));
                    completeWork(w, Map.of());
                    opened++;
                }
            }
            List<String> cases = runningInstances("gp55-discrepancy");
            ctx.check("2 discrepancy cases opened (items 1 and 3)", opened == 2 && cases.size() == 2
                    && !historicInstancesByBusinessKey("gp55-discrepancy", b("DISC-2027-1")).isEmpty()
                    && !historicInstancesByBusinessKey("gp55-discrepancy", b("DISC-2027-3")).isEmpty());
            complete(ctx, c, "gp55_report", "hod", null);
            ctx.check("campaign ended while the cases continue", ended(c) && runningInstances("gp55-discrepancy").size() == 2);
            List<Timer> st2 = startTimers("gp55-verification");
            ctx.detail("campaignTimersAfterFire", st2);
            ctx.detail("allJobsOfDefinition", engine.getManagementService().createJobQuery()
                    .processDefinitionKey("gp55-verification").list().stream()
                    .map(j -> j.getId() + " due=" + j.getDuedate() + " exc=" + j.getExceptionMessage()).toList());
            ctx.detail("timerGuardRows", count("select count(*) from SANGAM_TIMER_GUARD where process_definition_key = 'gp55-verification'"));
            ctx.check("next campaign armed for 2028-03-31", st2.size() == 1 && dueIs(st2.get(0), Instant.parse("2028-03-31T04:30:00Z")));
        });
    }
}
