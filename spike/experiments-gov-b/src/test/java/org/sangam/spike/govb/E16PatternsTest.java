package org.sangam.spike.govb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.EngineAdapter.TaskQuery;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.govb.support.Env;
import org.sangam.spike.govb.support.Gov;
import org.sangam.spike.govb.support.GovTestBase;
import org.sangam.spike.govb.support.Rec;
import org.sangam.spike.govb.support.Report;
import org.sangam.spike.layer.AuthorizationPolicy;
import org.sangam.spike.layer.Dialect;
import org.sangam.spike.layer.Schema;
import org.sangam.spike.layer.SqlPredicate;
import org.sangam.spike.sim.dlt.DltSmsSimulator;
import org.sangam.spike.sim.esign.EsignSimulator;

/**
 * E16-b: government process patterns GP-21 to GP-40 (spike/GOV-PATTERNS.md) on the engine selected by
 * {@code gov.engine}. Each test is one pattern; its record goes to results/E16-b.json.
 */
class E16PatternsTest extends GovTestBase {

    static final Report REPORT = new Report("E16-b", "Government process patterns GP-21..GP-40 on both engines");

    @Override
    protected Report report() {
        return REPORT;
    }

    @Override
    protected String dbName() {
        return "e16b";
    }

    /** Case task key: Operaton reports the plan item id, Flowable the humanTask id (adapter note, not normalised). */
    private String ck(String planItemId, String humanTaskId) {
        return g.operaton() ? planItemId : humanTaskId;
    }

    // ------------------------------------------------------------------------------------------------ GP-21

    @Test
    void gp21_adHocCase() {
        REPORT.run("GP-21", "Ad-hoc / case handling", r -> {
            r.feature("CMMN 1.1 casePlanModel autoComplete", "humanTask", "manualActivationRule (ad-hoc action)",
                    "repetitionRule", "requiredRule", "sentry (planItemOnPart complete)");
            g.deploy("audit", "gp21-audit-case.cmmn");
            String c = g.adapter().startCase("audit", "gp21AuditPara", "PARA-2026-17", m("para", "Excess payment"));
            assertEquals(1, g.tasks(null, c, ck("PI_examine", "gp21_examine")).size(), "examine active at once");
            assertEquals(1, g.nat().enabledCaseItems(c, "PI_seekReport").size(), "seek report offered (enabled)");
            assertEquals(1, g.nat().enabledCaseItems(c, "PI_hearing").size(), "hearing offered (enabled)");
            assertEquals(1, g.tasksOf(c).size(), "enabled ad-hoc actions are not tasks until started");
            g.complete(c, ck("PI_examine", "gp21_examine"), m("examined", true));
            assertEquals(1, g.tasks(null, c, ck("PI_close", "gp21_close")).size(), "required Close active");

            // ad-hoc: seek report twice (repetition), personal hearing once
            for (int i = 1; i <= 2; i++) {
                List<String> enabled = g.nat().enabledCaseItems(c, "PI_seekReport");
                assertEquals(1, enabled.size(), "seek report enabled (round " + i + ")");
                g.nat().startCaseItem(enabled.get(0));
                g.complete(c, ck("PI_seekReport", "gp21_seekReport"), m("report" + i, "received"));
            }
            r.sub("repetitionRule: a completed ad-hoc action is offered again", "PASS");
            g.nat().startCaseItem(g.nat().enabledCaseItems(c, "PI_hearing").get(0));
            g.complete(c, ck("PI_hearing", "gp21_hearing"), m("heard", true));
            assertFalse(g.ended(c), "case open while Close is pending");
            g.complete(c, ck("PI_close", "gp21_close"), m("outcome", "SETTLED"));
            assertTrue(g.ended(c), "case auto-completes after the required task, enabled ad-hoc items do not block");
            r.sub("autoComplete with enabled (unstarted) ad-hoc items left", "PASS: case completed");

            // BPMN form: adHocSubProcess
            try {
                g.deploy("audit", "gp21-adhoc.bpmn");
                String p = g.start("audit", "gp21AdHoc", "COURT-1", m());
                List<String> enabled = g.nat().adhocEnabled(p);
                assertEquals(Set.of("gp21b_counterAffidavit", "gp21b_seekLegalOpinion"), new HashSet<>(enabled));
                g.nat().adhocStart(p, "gp21b_seekLegalOpinion");
                g.complete(p, "gp21b_seekLegalOpinion", m("caseDone", true));
                assertTrue(g.ended(p), "ad-hoc sub-process completes on its completionCondition");
                r.sub("BPMN adHocSubProcess", "PASS (native ad-hoc API)");
                r.feature("BPMN adHocSubProcess + completionCondition");
            } catch (RuntimeException e) {
                String detail = root(e);
                if (e instanceof org.operaton.bpm.engine.ParseException pe) {
                    detail += pe.getResourceReports().stream().flatMap(rr -> rr.getErrors().stream())
                            .map(pr -> pr.getMessage()).reduce((a, b) -> a + "; " + b).orElse("");
                }
                r.sub("BPMN adHocSubProcess", "UNSUPPORTED: " + detail);
            }

            // CMMN timer event listener (escalation inside a case)
            try {
                g.deploy("audit", "cmmn-timer-probe.cmmn");
                String p = g.adapter().startCase("audit", "cmmnTimerProbe", "PROBE-1", m());
                g.clock(days(22));
                g.jobs();
                boolean escalated = g.tasks(null, p, ck("PI_escalate", "ctp_escalate")).size() == 1;
                r.sub("CMMN timerEventListener (P21D) -> escalation task",
                        escalated ? "PASS" : "NOT EXECUTED: deployed, but the listener never occurred (no timer job)");
            } catch (RuntimeException e) {
                r.sub("CMMN timerEventListener", "UNSUPPORTED: " + root(e));
            }
            r.note("Ad-hoc actions are started through the engine's own case API (not in EngineAdapter).");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-22

    @Test
    void gp22_appealReopen() {
        REPORT.run("GP-22", "Appeal / reopen after closure", r -> {
            r.feature("linked new instance (business key chain)", "history lookup of the closed instance",
                    "exclusive gateway (remand)");
            g.deploy("grv22", "gp22-decision.bpmn");
            String p = g.start("grv22", "gp22Decision", "ORD-2026-0042", m("applicant", "Ramesh Kumar"));
            g.complete(p, "gp22_decide", m("decision", "REJECTED", "reasons", "Document missing"));
            assertTrue(g.ended(p));

            long t = System.nanoTime();
            List<String> found = g.nat().historicByBusinessKey("gp22Decision", "ORD-2026-0042");
            var closed = g.instance(found.get(0));
            r.time("historyLookupMs", Gov.ms(t));
            assertTrue(closed.ended());
            assertEquals("REJECTED", closed.variables().get("decision"), "decision readable from history");

            String a = g.start("grv22", "gp22Appeal", "ORD-2026-0042/APPEAL-1",
                    m("originalInstanceId", found.get(0), "originalDecision", closed.variables().get("decision")));
            g.complete(a, "gp22a_hear", m("appealOutcome", "REMAND"));
            g.complete(a, "gp22a_redecide", m("decision", "APPROVED"));
            assertTrue(g.ended(a));

            String re = g.start("grv22", "gp22Decision", "ORD-2026-0042/REOPEN-1", m("reopenOf", found.get(0)));
            g.complete(re, "gp22_decide", m("decision", "APPROVED"));
            assertEquals(found.get(0), g.var(re, "reopenOf"));
            assertEquals(1, g.nat().historicByBusinessKey("gp22Appeal", "ORD-2026-0042/APPEAL-1").size());
            r.note("The engines have no 'reopen a completed process instance'; appeal and reopen are new instances "
                    + "linked by business key and an originalInstanceId variable, with the closed record read from "
                    + "history (adapter getInstance reads history after the end).");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-23

    @Test
    void gp23_twoEnvelope() {
        REPORT.run("GP-23", "Two-envelope opening, gated by time and committee", r -> {
            r.feature("timer intermediate catch (timeDate from variable, +05:30)", "parallel multi-instance user task",
                    "loopCardinality", "potentialOwner user(${...[loopCounter]})", "exclusive gateway");
            g.deploy("tender", "gp23-two-envelope.bpmn");
            String p = g.start("tender", "gp23TwoEnvelope", "NIT-2026-77", m("techOpenAt", iso(days(2)),
                    "finOpenAt", iso(days(5)), "committee", "member-a;member-b;member-c", "committeeSize", 3));
            g.jobs();
            assertEquals(0, g.tasksOf(p).size(), "nothing before the technical opening date");
            g.clock(days(2).minusSeconds(60));
            g.jobs();
            assertEquals(0, g.tasksOf(p).size(), "still locked one minute before");
            g.clock(days(2).plusSeconds(60));
            g.jobs();
            assertEquals(3, g.tasks(null, p, "gp23_techEval").size(), "one task per member");
            for (String member : List.of("member-a", "member-b", "member-c")) {
                List<TaskView> mine = g.forUser("tender", member);
                assertEquals(1, mine.size(), member + " sees only own evaluation task");
                assertTrue(g.tasks(null, p, "gp23_techResult").isEmpty(), "chair waits for all members");
                g.adapter().claimTask(mine.get(0).id(), member);
                g.adapter().completeTask(mine.get(0).id(), m());
            }
            g.complete(p, "gp23_techResult", m("techQualified", true));
            g.jobs();
            assertTrue(g.tasks(null, p, "gp23_finEval").isEmpty(), "financial envelope locked until finOpenAt");
            g.clock(days(5).plusSeconds(60));
            g.jobs();
            List<TaskView> fin = g.tasks(null, p, "gp23_finEval");
            assertEquals(3, fin.size());
            for (TaskView tv : fin) {
                g.adapter().completeTask(tv.id(), m());
            }
            assertTrue(g.ended(p));
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-24

    @Test
    void gp24_timeLock() {
        REPORT.run("GP-24", "Time-locked action (not allowed before a date)", r -> {
            r.feature("timer intermediate catch, timeDate = ISO-8601 string variable with +05:30 offset");
            g.deploy("exam", "gp24-time-lock.bpmn");
            Instant resultAt = days(3).plus(Duration.ofHours(1));
            String p = g.start("exam", "gp24TimeLock", "RESULT-2026-CGL", m("resultDate", iso(resultAt)));
            Instant due = g.nat().earliestTimerDue(p);
            assertEquals(resultAt, due, "timer due exactly at the declared date");
            g.clock(resultAt.minusSeconds(1));
            g.jobs();
            assertEquals(0, g.count("exam", "gp24_declare"), "no declaration task one second early");
            g.clock(resultAt.plusSeconds(1));
            g.jobs();
            g.complete(p, "gp24_declare", m("declared", true));
            assertTrue(g.ended(p));
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-25

    @Test
    void gp25_negotiationLoop() {
        REPORT.run("GP-25", "Negotiation loop, retender if failed", r -> {
            r.feature("exclusive gateway loops (back to same task and to an earlier stage)", "history activity count");
            g.deploy("proc", "gp25-negotiation.bpmn");
            String p = g.start("proc", "gp25Negotiation", "TENDER-1", m("retenderCount", 0));
            g.complete(p, "gp25_publishNit", m());
            g.complete(p, "gp25_evaluate", m("l1", "Firm A"));
            g.complete(p, "gp25_negotiate", m("negotiation", "COUNTER"));
            g.complete(p, "gp25_negotiate", m("negotiation", "COUNTER"));
            g.complete(p, "gp25_negotiate", m("negotiation", "FAILED"));
            g.complete(p, "gp25_publishNit", m("retenderCount", 1));
            g.complete(p, "gp25_evaluate", m("l1", "Firm B"));
            g.complete(p, "gp25_negotiate", m("negotiation", "AGREED"));
            g.complete(p, "gp25_loa", m());
            assertTrue(g.ended(p));
            assertEquals(4, g.nat().historicActivityCount(p, "gp25_negotiate"));
            assertEquals(2, g.nat().historicActivityCount(p, "gp25_publishNit"));

            String q = g.start("proc", "gp25Negotiation", "TENDER-2", m("retenderCount", 2));
            g.complete(q, "gp25_publishNit", m());
            g.complete(q, "gp25_evaluate", m());
            g.complete(q, "gp25_negotiate", m("negotiation", "FAILED"));
            assertTrue(g.ended(q), "dropped after two retenders");
            assertEquals(1, g.nat().finishedByEndActivity("gp25Negotiation", "gp25_dropped"));
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-26

    record Cand(String id, String category, boolean pwd, boolean esm, int score) {
    }

    @Test
    void gp26_meritReservation() {
        REPORT.run("GP-26", "Merit list with reservation/roster rules", r -> {
            r.feature("DMN 1.3 decision table hit policy COLLECT", "DMN called from BPMN", "product merit-list code");
            g.deploy("rec26", "gp26-merit.bpmn", "reservation-pools.dmn");
            List<Cand> cands = List.of(new Cand("C1", "UR", false, false, 91), new Cand("C2", "OBC", false, false, 89),
                    new Cand("C3", "SC", false, false, 70), new Cand("C4", "OBC", true, false, 85),
                    new Cand("C5", "UR", false, true, 80), new Cand("C6", "ST", false, false, 60),
                    new Cand("C7", "EWS", false, false, 88), new Cand("C8", "OBC", false, false, 75));
            Map<String, List<String>> pools = new LinkedHashMap<>();
            for (Cand c : cands) {
                String p = g.start("rec26", "gp26Merit", c.id(), m("category", c.category(), "pwd", c.pwd(),
                        "exServiceman", c.esm(), "score", c.score()));
                pools.put(c.id(), collected(g.var(p, "reservationPools"), "pool"));
            }
            r.scale("rawResultType", g.var(g.nat().historicByBusinessKey("gp26Merit", "C4").get(0), "reservationPools")
                    .getClass().getName());
            assertEquals(List.of("UR", "OBC", "PWD"), pools.get("C4"));
            assertEquals(List.of("UR", "ESM"), pools.get("C5"));
            assertEquals(List.of("UR"), pools.get("C1"));
            assertEquals(List.of("UR", "SC"), pools.get("C3"));
            r.note("COLLECT result shape differs: Operaton collectEntries gives List<String>; Flowable gives a JSON array"
                    + " of {\"pool\": ..} objects in a variable named after the decision key. Each is directly usable on"
                    + " its own engine; a product spanning both needs an 18-line normaliser (GovTestBase.collected).");

            // product merit list: UR 3, OBC 1, SC 1, EWS 1; horizontal PwD 1 (adjusted within the selected set)
            Map<String, Integer> seats = new LinkedHashMap<>(Map.of("UR", 3, "OBC", 1, "SC", 1, "EWS", 1));
            List<Cand> byScore = new ArrayList<>(cands);
            byScore.sort(Comparator.comparingInt(Cand::score).reversed());
            Map<String, String> selected = new LinkedHashMap<>();
            for (Cand c : byScore) {          // open merit first
                if (selected.size() < seats.get("UR") && pools.get(c.id()).contains("UR")) {
                    selected.put(c.id(), "UR");
                }
            }
            for (String pool : List.of("OBC", "SC", "EWS")) {
                int left = seats.get(pool);
                for (Cand c : byScore) {
                    if (left > 0 && !selected.containsKey(c.id()) && pools.get(c.id()).contains(pool)) {
                        selected.put(c.id(), pool);
                        left--;
                    }
                }
            }
            boolean pwdIn = selected.keySet().stream().anyMatch(id -> pools.get(id).contains("PWD"));
            if (!pwdIn) {
                Cand bestPwd = byScore.stream().filter(c -> pools.get(c.id()).contains("PWD")).findFirst().orElseThrow();
                String drop = selected.keySet().stream().filter(id -> selected.get(id).equals("UR"))
                        .reduce((a, b) -> b).orElseThrow();
                selected.remove(drop);
                selected.put(bestPwd.id(), "UR(PwD)");
            }
            assertEquals(Set.of("C1", "C2", "C7", "C4", "C3"), new HashSet<>(selected.keySet()));
            r.scale("selected", selected.toString());

            int n = Env.scale(1000, 300);
            long t = System.nanoTime();
            for (int i = 0; i < n; i++) {
                Cand c = cands.get(i % cands.size());
                g.nat().evaluateDecision("reservationPools", "rec26", m("category", c.category(), "pwd", c.pwd(),
                        "exServiceman", c.esm()));
            }
            long ms = Gov.ms(t);
            r.time("nativeDecisionEvaluations" + n + "Ms", ms).scale("decisionEvaluationsPerSecond",
                    round(n * 1000.0 / Math.max(1, ms)));
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-27

    @Test
    void gp27_bulkDocuments() {
        REPORT.run("GP-27", "Bulk document generation (admit cards)", r -> {
            r.feature("external task / external worker job in bulk", "fetchAndLock batches", "parallel workers");
            g.deploy("exam27", "gp27-admit-card.bpmn");
            int n = Env.scale(1000, 300);
            long t = System.nanoTime();
            g.parallel(n, i -> g.start("exam27", "gp27AdmitCard", String.format("ROLL-%06d", i),
                    m("rollNo", String.format("ROLL-%06d", i), "centre", "C" + (i % 40))));
            r.time("startInstancesMs", Gov.ms(t));
            AtomicInteger done = new AtomicInteger();
            AtomicInteger fetchCalls = new AtomicInteger();
            ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
            t = System.nanoTime();
            ExecutorService pool = Executors.newFixedThreadPool(Env.THREADS);
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < Env.THREADS; w++) {
                String worker = "docgen-" + w;
                fs.add(pool.submit(() -> {
                    int empty = 0;
                    while (done.get() < n && empty < 20) {
                        List<ExternalWork> batch = g.adapter().fetchAndLock(worker, "admit-card", 50, Duration.ofMinutes(5));
                        fetchCalls.incrementAndGet();
                        if (batch.isEmpty()) {
                            empty++;
                            continue;
                        }
                        empty = 0;
                        for (ExternalWork x : batch) {
                            try {
                                g.adapter().completeExternal(x.id(), worker, x.lockVersion(),
                                        m("admitCardNo", "AC-" + x.variables().get("rollNo"),
                                                "docHash", sha256(String.valueOf(x.variables().get("rollNo")))));
                                done.incrementAndGet();
                            } catch (RuntimeException e) {
                                errors.add(first(e));
                            }
                        }
                    }
                }));
            }
            for (Future<?> f : fs) {
                f.get();
            }
            pool.shutdown();
            long workerMs = Gov.ms(t);
            t = System.nanoTime();
            int jobs = g.jobs();
            long jobsMs = Gov.ms(t);
            assertEquals(n, done.get(), "every admit card generated once; errors " + errors);
            assertEquals(0, g.nat().runningInstances("gp27AdmitCard"), "all instances ended");
            r.time("workersMs", workerMs).time("continuationJobsMs", jobsMs)
                    .scale("instances", n).scale("workers", Env.THREADS).scale("fetchBatch", 50)
                    .scale("fetchCalls", fetchCalls.get()).scale("completeErrors", errors.size())
                    .scale("continuationJobs", jobs)
                    .scale("docsPerSecond", round(n * 1000.0 / Math.max(1, workerMs + jobsMs)));
            if (jobs > 0) {
                r.note("Completion of an external worker job continues the process in an async job (" + jobs
                        + " jobs run by executeDueJobs).");
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-28

    @Test
    void gp28_waitlistPromotion() {
        REPORT.run("GP-28", "Waitlist promotion when a candidate declines", r -> {
            r.feature("signal intermediate throw", "signal START event (one new instance per vacancy)",
                    "message catch correlated by business key", "external task (promotion worker)");
            g.deploy("dv", "gp28-waitlist.bpmn");
            Map<String, String> pid = new LinkedHashMap<>();
            for (String c : List.of("SEL-1", "SEL-2", "SEL-3")) {
                pid.put(c, g.start("dv", "gp28Candidate", c, m("status", "SELECTED")));
            }
            ConcurrentLinkedQueue<String> waitlist = new ConcurrentLinkedQueue<>(List.of("WL-1", "WL-2"));
            for (String c : waitlist) {
                pid.put(c, g.start("dv", "gp28Candidate", c, m("status", "WAITLIST")));
            }
            g.complete(pid.get("SEL-1"), "gp28_offer", m("accepted", true));
            g.complete(pid.get("SEL-2"), "gp28_offer", m("accepted", false));       // vacancy 1
            g.jobs();
            assertEquals(1, g.nat().runningInstances("gp28Promote"), "one promotion instance per vacancy");
            // two more vacancies at once: SEL-3 declines, then (after promotion) WL-1 declines
            g.complete(pid.get("SEL-3"), "gp28_offer", m("accepted", false));       // vacancy 2
            g.jobs();
            assertEquals(2, g.nat().runningInstances("gp28Promote"), "no vacancy lost while another is pending");
            int promoted = promote(waitlist, "dv");
            assertEquals(2, promoted);
            assertTrue(waitlist.isEmpty());
            g.complete(pid.get("WL-1"), "gp28_offer", m("accepted", false));       // vacancy 3, waitlist exhausted
            g.jobs();
            int third = promote(waitlist, "dv");
            assertEquals(0, third, "waitlist exhausted: third vacancy recorded unfilled");
            g.complete(pid.get("WL-2"), "gp28_offer", m("accepted", true));
            assertEquals(3, g.nat().finishedInstances("gp28Promote"));
            assertEquals(2, g.nat().finishedByEndActivity("gp28Candidate", "gp28_endJoined"));
            assertEquals(3, g.nat().finishedByEndActivity("gp28Candidate", "gp28_endDeclined"));
        });
    }

    /** Promotion worker: for each vacancy, promote the next waitlisted candidate (product queue). */
    private int promote(ConcurrentLinkedQueue<String> waitlist, String tenant) {
        int promoted = 0;
        for (ExternalWork w : fetchAll("promoter", "waitlist-promote", 10)) {
            String next = waitlist.poll();
            if (next != null) {
                g.correlate(tenant, "promoted", next, m("promotedFor", w.processInstanceId()));
                promoted++;
            }
            g.adapter().completeExternal(w.id(), "promoter", w.lockVersion(),
                    m("promotedCandidate", next == null ? "NONE" : next));
        }
        g.jobs();
        return promoted;
    }

    // ------------------------------------------------------------------------------------------------ GP-29

    @Test
    void gp29_autoApproveThreshold() {
        REPORT.run("GP-29", "Auto-approve below threshold, else route", r -> {
            r.feature("DMN FIRST called from BPMN", "exclusive gateway on DMN output", "candidate group from DMN output");
            g.deploy("hr29", "leave.bpmn", "leave-routing.dmn");
            String auto = g.start("hr29", "leaveApplication", "LV-1", m("leaveType", "CL", "days", 2, "balance", 8));
            assertTrue(g.ended(auto), "2 days CL auto-approved");
            assertEquals("AUTO", g.var(auto, "route"));
            String co = g.start("hr29", "leaveApplication", "LV-2", m("leaveType", "EL", "days", 10, "balance", 30));
            assertEquals(1, g.forGroups("hr29", "CONTROLLING_OFFICER").size());
            String hod = g.start("hr29", "leaveApplication", "LV-3", m("leaveType", "EL", "days", 20, "balance", 30));
            String eol = g.start("hr29", "leaveApplication", "LV-4", m("leaveType", "EOL", "days", 2, "balance", 30));
            assertEquals(2, g.forGroups("hr29", "HOD").size());
            String noBal = g.start("hr29", "leaveApplication", "LV-5", m("leaveType", "EL", "days", 40, "balance", 30));
            assertTrue(g.ended(noBal));
            g.complete(co, "lv_sanction", m("sanctioned", true));
            g.complete(hod, "lv_sanction", m("sanctioned", false));
            g.complete(eol, "lv_sanction", m("sanctioned", true));
            assertEquals(1, g.nat().finishedByEndActivity("leaveApplication", "lv_endAuto"));
            assertEquals(2, g.nat().finishedByEndActivity("leaveApplication", "lv_endApproved"));
            assertEquals(1, g.nat().finishedByEndActivity("leaveApplication", "lv_endRejected"));
            assertEquals(1, g.nat().finishedByEndActivity("leaveApplication", "lv_endNoBalance"));
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-30

    @Test
    void gp30_periodicGeneration() {
        REPORT.run("GP-30", "Periodic generation (monthly pension/salary bills)", r -> {
            r.feature("timer start event timeCycle ISO-8601 R12/<start +05:30>/P1M", "timer start event cron");
            g.deploy("pension", "gp30-monthly.bpmn");
            g.jobs();
            assertEquals(0, g.count("pension", "gp30i_prepare"));
            g.clock(Instant.parse("2026-12-01T06:01:00Z"));   // 11:31 IST: both the IST and the UTC reading are due
            g.jobs();
            long iso1 = g.count("pension", "gp30i_prepare");
            long cron1 = g.count("pension", "gp30c_prepare");
            assertEquals(1, iso1, "December ISO bill");
            assertEquals(1, cron1, "December cron bill");
            g.clock(Instant.parse("2027-01-01T06:01:00Z"));
            g.jobs();
            assertEquals(2, g.count("pension", "gp30i_prepare"), "January ISO bill");
            // engine down over February: what happens to the missed cycle?
            g.clock(Instant.parse("2027-03-01T06:01:00Z"));
            g.jobs();
            long isoAfterGap = g.count("pension", "gp30i_prepare");
            long cronAfterGap = g.count("pension", "gp30c_prepare");
            r.scale("isoBillsAfterMissedFebruary", isoAfterGap).scale("cronBillsAfterMissedFebruary", cronAfterGap);
            r.sub("missed cycle (Feb) while the engine was down", "ISO: " + (isoAfterGap - 2) + " instance(s) created on"
                    + " 1 Mar for Feb+Mar; cron: " + (cronAfterGap - 2));
            assertTrue(isoAfterGap >= 3);
            r.note("Cron (timeCycle '0 0 6 1 * ?') is read in the JVM default time zone ("
                    + java.util.TimeZone.getDefault().getID() + "); the ISO cycle carries its own +05:30 offset.");
            if (isoAfterGap == 3) {
                r.note("A missed cycle is NOT replayed: the engine creates one instance on catch-up and schedules the next"
                        + " future date; the product must detect the missing month (pension rolls).");
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-31

    @Test
    void gp31_fileReconciliation() throws Exception {
        REPORT.run("GP-31", "Bank / treasury file reconciliation (challan matching)", r -> {
            r.feature("event-based gateway (message vs timer)", "message correlation by business key in bulk",
                    "external task reading a bank scroll file", "timer T+3 days");
            g.deploy("treasury", "gp31-challan.bpmn");
            int n = Env.scale(1000, 200);
            Set<String> register = java.util.concurrent.ConcurrentHashMap.newKeySet();   // product challan register
            long t = System.nanoTime();
            g.parallel(n, i -> {
                String chl = String.format("CHL-%06d", i);
                g.start("treasury", "gp31Challan", chl, m("amount", 100L + i, "head", "0030-STAMPS"));
                register.add(chl);
            });
            r.time("startChallansMs", Gov.ms(t));
            Path scroll = Files.createTempFile("bank-scroll-", ".csv");
            StringBuilder csv = new StringBuilder("challanNo,amount,bankRef\n");
            int credited = 0;
            for (int i = 0; i < n; i++) {
                if (i % 20 != 7) {          // 95 % credited
                    csv.append(String.format("CHL-%06d,%d,SBI%08d%n", i, 100 + i, i));
                    credited++;
                }
            }
            for (int u = 0; u < 10; u++) {
                csv.append(String.format("CHL-9%05d,%d,SBI-UNK%d%n", u, 500, u));
            }
            Files.writeString(scroll, csv, StandardCharsets.UTF_8);
            String recon = g.start("treasury", "gp31Recon", "SCROLL-20261102", m("scrollPath", scroll.toString()));

            ExternalWork w = fetchOne("recon-worker", "bank-scroll");
            List<String[]> lines = Files.readAllLines(Path.of((String) w.variables().get("scrollPath"))).stream()
                    .skip(1).map(l -> l.split(",")).toList();
            AtomicInteger matched = new AtomicInteger();
            AtomicInteger unknown = new AtomicInteger();
            t = System.nanoTime();
            long retries = g.parallel(lines.size(), i -> {
                String[] l = lines.get(i);
                if (!register.contains(l[0])) {
                    unknown.incrementAndGet();
                    return;
                }
                g.correlate("treasury", "challanCredited", l[0], m("bankRef", l[2], "creditedAmount", Long.parseLong(l[1])));
                matched.incrementAndGet();
            });
            long corrMs = Gov.ms(t);
            g.adapter().completeExternal(w.id(), "recon-worker", w.lockVersion(),
                    m("matched", matched.get(), "unknownCredits", unknown.get()));
            g.jobs();
            assertEquals(credited, matched.get());
            assertEquals(10, unknown.get());
            g.complete(recon, "gp31r_exceptions", m("reviewed", true));
            assertTrue(g.ended(recon));
            assertEquals(n - credited, g.nat().runningInstances("gp31Challan"));
            g.clock(days(3).plusSeconds(60));
            g.jobs();
            assertEquals(n - credited, g.count("treasury", "gp31_investigate"), "unmatched challans go to the officer");
            r.time("correlateMs", corrMs).scale("challans", n).scale("matched", matched.get())
                    .scale("unknownCredits", unknown.get()).scale("unmatchedAfterT+3", n - credited)
                    .scale("correlationsPerSecond", round(matched.get() * 1000.0 / Math.max(1, corrMs)))
                    .scale("conflictRetries", retries);
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-32

    @Test
    void gp32_interDepartmentCall() {
        REPORT.run("GP-32", "Inter-department referral (call activity across tenants)", r -> {
            r.feature("call activity to a definition of ANOTHER tenant", "call activity in/out variable mapping",
                    "tenant-scoped task queries");
            g.deploy("dept-fin", "concurrence.bpmn");
            g.deploy("dept-works", "gp32-works.bpmn");
            Map<String, Object> vars = m("fileNo", "WORKS/2026/311", "amount", 4_500_000L);
            if (!g.operaton()) {
                vars.put("finConcurrenceDefId", g.nat().processDefinitionId("concurrence", "dept-fin"));
                r.workaround("Flowable call activity resolves the callee by key only in the caller's tenant (or the "
                        + "default tenant): calledElementType=id + the callee definition id looked up by the product "
                        + "and passed as a start variable", 3);
            }
            // what the engine does on its own
            String probe = g.start("dept-works", "gp32WorksProposal", "PROBE/1", new LinkedHashMap<>(vars));
            g.complete(probe, "gp32_prepare", m());
            String probeChild = g.nat().subProcessInstances(probe).get(0);
            String nativeTenant = g.nat().instanceTenant(probeChild);
            r.sub("tenant of the called instance (no hook)", nativeTenant);
            g.cancelQuietly(probe);
            int hookLines = g.nat().calledProcessesRunInCalleeTenant();
            if (hookLines > 0) {
                r.workaround("called instance inherits the CALLER's tenant (task lands in the Works tenant, invisible to"
                        + " Finance): engine StartProcessInstanceInterceptor re-tenants the sub-process instance and"
                        + " its first execution (impl ExecutionEntity.setTenantId)", hookLines);
            }
            String p = g.start("dept-works", "gp32WorksProposal", "WORKS/2026/311", vars);
            g.complete(p, "gp32_prepare", m());
            List<String> children = g.nat().subProcessInstances(p);
            assertEquals(1, children.size());
            assertEquals("dept-fin", g.nat().instanceTenant(children.get(0)), "callee runs in Finance's tenant");
            assertEquals(1, g.forGroups("dept-fin", "concurrence-officer").size(), "visible in Finance inbox");
            assertEquals(0, g.forGroups("dept-works", "concurrence-officer").size(), "not in Works inbox");
            TaskView ft = g.forGroups("dept-fin", "concurrence-officer").get(0);
            assertEquals("WORKS/2026/311", ft.variables().get("fileNo"), "in-mapping");
            assertEquals("dept-fin", ft.tenantId());
            g.adapter().completeTask(ft.id(), m("concurred", false, "remarks", "Rates not as per SOR 2025"));
            assertEquals("Rates not as per SOR 2025", g.var(p, "finRemarks"), "out-mapping");
            g.complete(p, "gp32_prepare", m());
            TaskView ft2 = g.forGroups("dept-fin", "concurrence-officer").get(0);
            g.adapter().completeTask(ft2.id(), m("concurred", true, "remarks", "Concurred"));
            g.complete(p, "gp32_sanction", m());
            assertTrue(g.ended(p));
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-33

    @Test
    void gp33_dltNotifications() {
        REPORT.run("GP-33", "Notifications at each step (SMS, DLT)", r -> {
            r.feature("external task per notification step", "worker picks template by activity id",
                    "failure with retries 0 -> incident / dead letter", "DLT simulator (template, header, URL whitelist)");
            try (DltSmsSimulator dlt = new DltSmsSimulator()) {
                dlt.registerTemplate("1107000000000000001", "GOVSVC",
                        "आवेदन {#var#} प्राप्त हुआ। स्थिति: {#var#} - GOVSVC")
                        .registerTemplate("1107000000000000002", "GOVSVC", "आवेदन {#var#} पर निर्णय: {#var#} - GOVSVC")
                        .whitelistUrl("https://seva.gov.in/track/");
                dlt.start();
                g.deploy("svc", "gp33-notify.bpmn");
                String ok = g.start("svc", "gp33CitizenService", "APP/2026/000123", m("mobile", "9876543210",
                        "appNo", "APP/2026/000123", "trackUrl", "https://seva.gov.in/track/123"));
                String bad = g.start("svc", "gp33CitizenService", "APP/2026/000124", m("mobile", "9876543211",
                        "appNo", "APP/2026/000124", "trackUrl", "https://bit.ly/x0124"));
                sendSms(dlt);
                assertEquals(1, g.tasksOf(ok).size(), "after the receipt SMS the application is in scrutiny");
                assertEquals(1, g.nat().failedWork(bad), "DLT rejection (URL not whitelisted) -> incident");
                g.complete(ok, "gp33_scrutiny", m("decision", "स्वीकृत"));
                g.jobs();
                sendSms(dlt);
                assertTrue(g.ended(ok));
                assertEquals(2, dlt.ledger().count(DltSmsSimulator.EFFECT_SMS));
                r.scale("smsAccepted", dlt.ledger().count(DltSmsSimulator.EFFECT_SMS)).scale("smsRejected", 1);
            }
        });
    }

    private void sendSms(DltSmsSimulator dlt) {
        for (ExternalWork w : fetchAll("sms-worker", "sms", 10)) {
            Map<String, Object> v = w.variables();
            boolean receipt = "gp33_smsReceived".equals(w.activityId());
            String msg = receipt
                    ? "आवेदन " + v.get("appNo") + " प्राप्त हुआ। स्थिति: " + v.get("trackUrl") + " - GOVSVC"
                    : "आवेदन " + v.get("appNo") + " पर निर्णय: " + v.get("decision") + " - GOVSVC";
            String body;
            try {
                body = JSON.writeValueAsString(m("header", "GOVSVC-G", "templateId",
                        receipt ? "1107000000000000001" : "1107000000000000002", "mobile", v.get("mobile"), "message", msg));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            var resp = post(dlt.uri(DltSmsSimulator.SMS_PATH), "application/json", body);
            if (resp.statusCode() == 200) {
                g.adapter().completeExternal(w.id(), "sms-worker", w.lockVersion(), m("smsStatus", "ACCEPTED"));
            } else {
                g.adapter().failExternal(w.id(), "sms-worker", w.lockVersion(), "DLT " + resp.body(), 0, Duration.ZERO);
            }
        }
        g.jobs();
    }

    // ------------------------------------------------------------------------------------------------ GP-34

    @Test
    void gp34_esignBoundToApproval() {
        REPORT.run("GP-34", "Digital signature bound to the approval", r -> {
            r.feature("external task (eSign v2.1 simulator)", "payload hash in the approval",
                    "BPMN error from the worker -> error boundary event -> re-approval");
            try (EsignSimulator esp = new EsignSimulator()) {
                esp.start();
                g.deploy("orders", "gp34-esign.bpmn");
                String a = g.start("orders", "gp34SignedOrder", "SO-1", m("orderNo", "SO-1", "amount", 250_000L,
                        "beneficiary", "Gram Panchayat Rampur"));
                String b = g.start("orders", "gp34SignedOrder", "SO-2", m("orderNo", "SO-2", "amount", 90_000L,
                        "beneficiary", "Nagar Palika Sitapur"));
                for (String p : List.of(a, b)) {
                    var v = g.instance(p).variables();
                    g.complete(p, "gp34_approve", m("approvedHash", payloadHash(v)));
                }
                g.nat().setVariable(b, "amount", 900_000L);   // amount changed after approval
                int signed = signOrders(esp);
                assertEquals(1, signed);
                assertEquals(1, g.tasks(null, a, "gp34_dispatch").size(), "SO-1 signed");
                assertEquals(1, g.tasks(null, b, "gp34_approve").size(), "SO-2 back to approval");
                String sig = (String) g.var(a, "signature");
                assertTrue(esp.verify((String) g.var(a, "approvedHash"), sig), "signature over the approved hash");
                g.complete(b, "gp34_approve", m("approvedHash", payloadHash(g.instance(b).variables())));
                assertEquals(1, signOrders(esp));
                g.complete(a, "gp34_dispatch", m());
                g.complete(b, "gp34_dispatch", m());
                assertTrue(g.ended(a) && g.ended(b));
                r.scale("esignCalls", esp.ledger().count(EsignSimulator.EFFECT_SIGN));
            }
        });
    }

    private static String payloadHash(Map<String, Object> v) {
        return sha256(v.get("orderNo") + "|" + v.get("amount") + "|" + v.get("beneficiary"));
    }

    private static final Pattern DOC_SIG = Pattern.compile("<DocSignature[^>]*>([^<]+)</DocSignature>");

    private int signOrders(EsignSimulator esp) {
        int signed = 0;
        for (ExternalWork w : fetchAll("esign-worker", "esign", 10)) {
            Map<String, Object> v = w.variables();
            String now = payloadHash(v);
            if (!now.equals(v.get("approvedHash"))) {
                g.adapter().bpmnErrorExternal(w.id(), "esign-worker", w.lockVersion(), "PAYLOAD_CHANGED",
                        m("rejectReason", "payload hash differs from the approved hash"));
                continue;
            }
            String xml = "<Esign ver=\"2.1\" sc=\"Y\" ts=\"" + Instant.now() + "\" txn=\"ASP-" + v.get("orderNo") + "-"
                    + w.lockVersion() + "\" aspId=\"ASP001\" ekycIdType=\"A\" AuthMode=\"1\" responseSigType=\"pkcs7\""
                    + " responseUrl=\"https://asp.gov.in/cb\"><Docs><InputHash id=\"1\" hashAlgorithm=\"SHA256\""
                    + " docInfo=\"Sanction order\">" + now + "</InputHash></Docs><Signature>ASP</Signature></Esign>";
            var resp = post(esp.uri(EsignSimulator.SIGN_PATH), "application/xml", xml);
            Matcher mm = DOC_SIG.matcher(resp.body());
            assertTrue(mm.find(), "signature in eSign response: " + resp.body());
            g.adapter().completeExternal(w.id(), "esign-worker", w.lockVersion(), m("signature", mm.group(1),
                    "signedHash", now));
            signed++;
        }
        g.jobs();
        return signed;
    }

    // ------------------------------------------------------------------------------------------------ GP-35

    @Test
    void gp35_statutoryClocks() {
        REPORT.run("GP-35", "Statutory clocks (RTI 30 days / 48 hours) with appeal", r -> {
            r.feature("non-interrupting timer boundary, duration from a JUEL ternary over a variable",
                    "event-based gateway (appeal message vs 30-day window)", "linked appeal instance + history");
            g.deploy("pa-a", "rti.bpmn");
            String normal = g.start("pa-a", "rtiRequest", "RTI/2026/0001", m("lifeLiberty", false, "applicant", "A"));
            String urgent = g.start("pa-a", "rtiRequest", "RTI/2026/0002", m("lifeLiberty", true, "applicant", "B"));
            assertEquals(hours(48), g.nat().earliestTimerDue(urgent));
            assertEquals(days(30), g.nat().earliestTimerDue(normal));
            g.clock(hours(47));
            g.jobs();
            assertEquals(0, g.count("pa-a", "rti_deemed"));
            g.clock(hours(49));
            g.jobs();
            assertEquals(1, g.tasks(null, urgent, "rti_deemed").size(), "48-hour deemed refusal");
            assertEquals(0, g.tasks(null, normal, "rti_deemed").size());
            g.clock(days(30).plusSeconds(60));
            g.jobs();
            assertEquals(1, g.tasks(null, normal, "rti_deemed").size(), "30-day deemed refusal");
            // urgent: late reply, applicant appeals
            g.complete(urgent, "rti_pio", m("pioDecision", "SUPPLY"));
            g.correlate("pa-a", "firstAppealFiled", "RTI/2026/0002", m("appealDate", iso(days(31))));
            g.complete(urgent, "rti_deemed", m("noted", true));
            assertTrue(g.ended(urgent));
            assertEquals(1, g.nat().historicActivityCount(urgent, "rti_endAppealed"));
            var req = g.instance(urgent);
            String appeal = g.start("pa-a", "rtiFirstAppeal", "RTI/2026/0002/FA-1",
                    m("requestInstanceId", urgent, "lifeLiberty", req.variables().get("lifeLiberty")));
            g.clock(days(62));
            g.jobs();
            assertEquals(1, g.tasks(null, appeal, "rfa_recordReasons").size(), "FAA past 30 days: reasons");
            g.complete(appeal, "rfa_decide", m("faaOrder", "ALLOWED"));
            g.complete(appeal, "rfa_recordReasons", m("reasons", "Voluminous records"));
            assertTrue(g.ended(appeal));
            // normal: decision then no appeal within 30 days
            g.complete(normal, "rti_pio", m("pioDecision", "REJECT"));
            g.complete(normal, "rti_deemed", m("noted", true));
            g.clock(days(93));
            g.jobs();
            assertTrue(g.ended(normal));
            r.note("Calendar days, as the RTI Act counts them; a business-day calendar is not needed for RTI.");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-36

    @Test
    void gp36_misHistory() {
        REPORT.run("GP-36", "MIS / audit queries over history", r -> {
            r.feature("historic process / task / activity / variable queries", "durations", "completeness checks");
            g.deploy("mis", "leave.bpmn", "leave-routing.dmn");
            // the same key ran in other tenants in earlier patterns: work with deltas over a baseline
            List<String> ends = List.of("lv_endNoBalance", "lv_endAuto", "lv_endApproved", "lv_endRejected");
            long baseFinished = g.nat().finishedInstances("leaveApplication");
            Map<String, Long> baseEnd = new LinkedHashMap<>();
            ends.forEach(e -> baseEnd.put(e, g.nat().finishedByEndActivity("leaveApplication", e)));
            Map<String, Long> baseOfficer = g.nat().finishedTasksByAssignee("leaveApplication");
            long baseHod = g.nat().historicInstancesByVar("leaveApplication", "route", "HOD");
            int n = 120;
            List<String> routed = new ArrayList<>();
            Map<String, Integer> expectedEnd = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int kind = i % 12;
                int days = kind == 0 ? 40 : kind <= 4 ? 2 : kind <= 9 ? 10 : 20;
                String p = g.start("mis", "leaveApplication", "MIS-" + i, m("leaveType", "EL", "days", days,
                        "balance", 30, "employee", "E" + (i % 30)));
                String end = kind == 0 ? "lv_endNoBalance" : kind <= 4 ? "lv_endAuto" : null;
                if (end != null) {
                    expectedEnd.merge(end, 1, Integer::sum);
                } else {
                    routed.add(p);
                }
            }
            g.clock(days(2));
            int open = 0;
            for (int i = 0; i < routed.size(); i++) {
                String p = routed.get(i);
                if (i % 5 == 4) {
                    open++;
                    continue;
                }
                boolean ok = i % 7 != 0;
                g.completeAs(p, "lv_sanction", "officer-" + (i % 3), m("sanctioned", ok));
                expectedEnd.merge(ok ? "lv_endApproved" : "lv_endRejected", 1, Integer::sum);
            }
            Map<String, Object> q = new LinkedHashMap<>();
            long t = System.nanoTime();
            long finished = g.nat().finishedInstances("leaveApplication");
            q.put("finishedCountMs", Gov.ms(t));
            assertEquals(n - open, finished - baseFinished, "every closed application is in history");
            for (var e : expectedEnd.entrySet()) {
                t = System.nanoTime();
                assertEquals((long) e.getValue(), g.nat().finishedByEndActivity("leaveApplication", e.getKey())
                        - baseEnd.get(e.getKey()), "outcome " + e.getKey());
                q.put("byOutcome:" + e.getKey() + "Ms", Gov.ms(t));
            }
            t = System.nanoTime();
            Map<String, Long> byOfficer = g.nat().finishedTasksByAssignee("leaveApplication");
            q.put("tasksByAssigneeMs", Gov.ms(t));
            baseOfficer.forEach((k, v) -> byOfficer.merge(k, -v, Long::sum));
            byOfficer.values().removeIf(v -> v == 0);
            assertEquals(routed.size() - open, byOfficer.values().stream().mapToLong(Long::longValue).sum());
            assertFalse(byOfficer.containsKey("null"), "every completed task records who acted");
            t = System.nanoTime();
            long hodRouted = g.nat().historicInstancesByVar("leaveApplication", "route", "HOD") - baseHod;
            q.put("historicVariableQueryMs", Gov.ms(t));
            assertEquals(2 * n / 12, hodRouted);
            double avg = g.nat().avgDurationMs("leaveApplication");
            assertTrue(avg > 0, "durations recorded");
            r.scale("instances", n).scale("open", open).scale("byOfficer", byOfficer.toString())
                    .scale("avgDurationHours", round(avg / 3_600_000.0));
            r.timings(q);
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-37

    @Test
    void gp37_tenantRuleOverride() {
        REPORT.run("GP-37", "Same model, department-specific rule overrides (multi-tenant)", r -> {
            r.feature("tenant-scoped deployment (same BPMN in two tenants)", "same DMN key, different table per tenant",
                    "DMN resolved in the calling definition's tenant");
            g.deploy("state-a", "leave.bpmn", "leave-routing.dmn");
            g.deploy("state-b", "leave.bpmn", "leave-routing-state-b.dmn");
            String a2 = g.start("state-a", "leaveApplication", "A-2", m("leaveType", "CL", "days", 2, "balance", 10));
            String b2 = g.start("state-b", "leaveApplication", "B-2", m("leaveType", "CL", "days", 2, "balance", 10));
            String a10 = g.start("state-a", "leaveApplication", "A-10", m("leaveType", "EL", "days", 10, "balance", 30));
            String b10 = g.start("state-b", "leaveApplication", "B-10", m("leaveType", "EL", "days", 10, "balance", 30));
            assertEquals("AUTO", g.var(a2, "route"));
            assertEquals("CONTROLLING_OFFICER", g.var(b2, "route"));
            assertEquals("CONTROLLING_OFFICER", g.var(a10, "route"));
            assertEquals("HOD", g.var(b10, "route"));
            assertEquals(1, g.forGroups("state-a", "CONTROLLING_OFFICER").size());
            assertEquals(1, g.forGroups("state-b", "CONTROLLING_OFFICER").size());
            assertEquals(1, g.forGroups("state-b", "HOD").size());
            assertEquals(0, g.forGroups("state-a", "HOD").size());
            r.note("Shared (tenant-less) BPMN with per-tenant DMN was not used: on both engines it needs a tenant hint"
                    + " on the decision call (Operaton decisionRefTenantId expression / Flowable fallbackToDefaultTenant"
                    + " semantics), i.e. a variant. Per-tenant deployment of the same file needs none.");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-38

    @Test
    void gp38_hindiRoundTrip() {
        REPORT.run("GP-38", "Hindi / Unicode data round trip", r -> {
            r.feature("Devanagari in model names, business key, candidate group, variables, gateway literal",
                    "variable equals / like queries", "history", "DMN string literals");
            g.deploy("hindi", "gp38-hindi.bpmn", "hindi-status.dmn");
            String bk = "आरटीआई/२०२६/००१";
            String dept = "लोक निर्माण विभाग";
            String p = g.start("hindi", "gp38Bilingual", bk, m("department", dept, "section", "अनुभाग-क",
                    "विभाग", "राजस्व", "applicantName", "श्रीमती सीता देवी"));
            var inst = g.instance(p);
            assertEquals(bk, inst.businessKey());
            assertEquals("राजस्व", inst.variables().get("विभाग"), "Devanagari variable NAME");
            List<TaskView> t = g.forGroups("hindi", "अनुभाग-क");
            assertEquals(1, t.size(), "Devanagari candidate group");
            assertEquals("आवेदन की जाँच", t.get(0).name());
            assertEquals(1, g.nat().runtimeInstancesByVar("gp38Bilingual", "department", dept));
            assertEquals(1, g.nat().runtimeInstancesByVarLike("gp38Bilingual", "department", "%निर्माण%"));
            assertEquals(1, g.nat().tasksByProcessVar("applicantName", "श्रीमती सीता देवी"));
            g.adapter().completeTask(t.get(0).id(), m("status", "स्वीकृत"));
            assertTrue(g.ended(p));
            assertEquals(1, g.nat().finishedByEndActivity("gp38Bilingual", "gp38_endApproved"), "Hindi gateway literal");
            assertEquals(1, g.nat().historicInstancesByVar("gp38Bilingual", "status", "स्वीकृत"));
            assertEquals(dept, g.var(p, "department"), "history round trip");
            assertEquals(1, g.nat().historicByBusinessKey("gp38Bilingual", bk).size(), "business key query");
            var dec = g.nat().evaluateDecision("hindiStatus", "hindi", m("status", "स्वीकृत"));
            assertEquals("APPROVED / अनुमोदित", dec.get(0).get("result"));

            // NFC vs precomposed: U+0958 (QA) is a composition exclusion; NFC turns it into U+0915 U+093C
            String precomposed = "क़ानून";
            String nfc = Normalizer.normalize(precomposed, Normalizer.Form.NFC);
            String q = g.start("hindi", "gp38Bilingual", "NFC-1", m("section", "अनुभाग-ख", "subject", precomposed));
            long sameForm = g.nat().runtimeInstancesByVar("gp38Bilingual", "subject", precomposed);
            long nfcForm = g.nat().runtimeInstancesByVar("gp38Bilingual", "subject", nfc);
            r.sub("query with a different Unicode normal form", "exact form: " + sameForm + ", NFC form: " + nfcForm
                    + " (byte comparison; the product must normalise to NFC on input)");
            g.cancelQuietly(q);

            // Devanagari identifier in JUEL
            try {
                g.deploy("hindi", "gp38-hindi-identifier.bpmn");
                String h = g.start("hindi", "gp38HindiIdentifier", "ID-1", m("स्थिति", "स्वीकृत"));
                r.sub("Devanagari variable name as JUEL identifier in a gateway",
                        g.tasks(null, h, "gp38i_yes").size() == 1 ? "PASS" : "FAIL: wrong branch");
            } catch (RuntimeException e) {
                r.sub("Devanagari variable name as JUEL identifier in a gateway", "FAIL: " + root(e));
            }

            // long Devanagari text (> 4000 characters)
            String longText = "यह आवेदन जाँच के लिए प्रस्तुत किया गया है। ".repeat(130);   // ~5,500 chars
            r.scale("longTextChars", longText.length());
            try {
                String lp = g.start("hindi", "gp38Bilingual", "LONG-1", m("section", "अनुभाग-ग", "noting", longText));
                assertEquals(longText, g.var(lp, "noting"));
                r.sub("String variable of " + longText.length() + " Devanagari chars", "PASS (stored as String)");
                g.cancelQuietly(lp);
            } catch (RuntimeException e) {
                r.sub("String variable of " + longText.length() + " Devanagari chars", "FAIL as String: " + root(e));
                String lp = g.start("hindi", "gp38Bilingual", "LONG-2", m("section", "अनुभाग-ग",
                        "noting", longText.getBytes(StandardCharsets.UTF_8)));
                assertEquals(longText, new String((byte[]) g.var(lp, "noting"), StandardCharsets.UTF_8));
                r.workaround("long String (> 4000 chars) stored as byte[] UTF-8 variable and decoded by the product", 4);
                g.cancelQuietly(lp);
            }

            // Java collection variable (committee lists, pools)
            try {
                String lp = g.start("hindi", "gp38Bilingual", "LIST-1", m("section", "अनुभाग-घ",
                        "members", new ArrayList<>(List.of("सदस्य-१", "सदस्य-२"))));
                Object back = g.var(lp, "members");
                r.sub("java.util.ArrayList variable", "PASS (" + back.getClass().getSimpleName() + ")");
                g.cancelQuietly(lp);
            } catch (RuntimeException e) {
                r.sub("java.util.ArrayList variable", "REJECTED: " + root(e));
                r.note("Collections must be passed as delimited strings or JSON text (as GP-23 does) on this engine.");
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-39

    @Test
    void gp39_inboxUnderAuthorization() throws Exception {
        REPORT.run("GP-39", "Officer inbox with >= 10k tasks and filters under authorization (VI.4)", r -> {
            r.feature("task query by tenant + candidate groups (adapter)", "VI.4 SQL predicate AND-ed into a SQL "
                    + "list/count over ACT_RU_TASK / ACT_RU_IDENTITYLINK", "explicit deny wins");
            g.deploy("big-dept", "gp39-inbox.bpmn");
            g.deploy("other-dept", "gp39-inbox.bpmn");
            int n = Env.scale(10_000, 2_000);
            int seats = 300;
            long t = System.nanoTime();
            long retries = g.parallel(n, i -> g.start("big-dept", "gp39Dak", "DAK-" + i,
                    m("seat", String.format("seat-%03d", i % seats), "subject", "Dak item " + i)));
            retries += g.parallel(n / 10, i -> g.start("other-dept", "gp39Dak", "ODAK-" + i,
                    m("seat", String.format("seat-%03d", i % seats))));
            r.time("createTasksMs", Gov.ms(t)).scale("tasksInTenant", n).scale("tasksOtherTenant", n / 10)
                    .scale("seats", seats).scale("startRetries", retries);

            Dialect pred = Env.isPostgres() ? Dialect.POSTGRESQL : Dialect.ORACLE;   // H2: BITAND (dev only)
            AuthorizationPolicy policy = new AuthorizationPolicy(pred, "TASK",
                    new AuthorizationPolicy.ResourceColumns("TENANT_ID_", "ID_"));
            Set<String> groups20 = new LinkedHashSet<>();
            for (int s = 0; s < 20; s++) {
                groups20.add(String.format("seat-%03d", s));
            }
            Set<String> groups300 = new LinkedHashSet<>();
            for (int s = 0; s < seats; s++) {
                groups300.add(String.format("seat-%03d", s));
            }
            List<String> denied = new ArrayList<>();
            t = System.nanoTime();
            g.adapter().inEngineTransaction(c -> {
                try (Statement st = c.createStatement()) {
                    for (String ddl : Schema.ddl(Dialect.POSTGRESQL)) {
                        if (ddl.contains("AUTH_")) {
                            st.execute(ddl);
                        }
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id,"
                        + " resource_type, resource_id, deny_flag, perms, condition_id, policy_version) VALUES (?,?,?,?,?,?,NULL,1)")) {
                    for (String s : groups300) {   // role grant: every seat may read tasks of the department
                        bindGrant(ps, s, "*", 0, AuthorizationPolicy.Action.mask(AuthorizationPolicy.Action.READ_TASK,
                                AuthorizationPolicy.Action.CLAIM, AuthorizationPolicy.Action.COMPLETE));
                    }
                    // 50 sensitive tasks of the first 20 seats are denied to group seat-000 (vigilance files)
                    try (PreparedStatement q = c.prepareStatement("SELECT t.ID_ FROM ACT_RU_TASK t JOIN ACT_RU_IDENTITYLINK l"
                            + " ON l.TASK_ID_ = t.ID_ WHERE t.TENANT_ID_ = 'big-dept' AND l.TYPE_ = 'candidate'"
                            + " AND l.GROUP_ID_ IN (" + groups20.stream().map(s -> "'" + s + "'")
                            .collect(java.util.stream.Collectors.joining(",")) + ") ORDER BY t.ID_")) {
                        try (ResultSet rs = q.executeQuery()) {
                            while (rs.next() && denied.size() < 50) {
                                denied.add(rs.getString(1));
                            }
                        }
                    }
                    for (String id : denied) {
                        bindGrant(ps, "seat-000", id, 1, AuthorizationPolicy.Action.READ_TASK.bit());
                    }
                    ps.executeBatch();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
            r.time("grantSetupMs", Gov.ms(t));
            assertEquals(50, denied.size());

            long expected20 = 0;
            for (int i = 0; i < n; i++) {
                if (i % seats < 20) {
                    expected20++;
                }
            }
            expected20 -= 50;
            AuthorizationPolicy.Principal p20 = new AuthorizationPolicy.Principal("big-dept", "so-ramesh", groups20);
            AuthorizationPolicy.Principal p300 = new AuthorizationPolicy.Principal("big-dept", "js-admin", groups300);

            long c20 = vi4Count(policy, p20);
            assertEquals(expected20, c20, "VI.4 count = candidate tasks of 20 seats minus 50 denied");
            List<String> page = vi4List(policy, p20);
            assertEquals(50, page.size());
            assertTrue(Collections.disjoint(page, denied), "denied tasks never listed");
            long c300 = vi4Count(policy, p300);
            assertEquals(n - 50, c300);

            Map<String, Object> perf = new LinkedHashMap<>();
            perf.put("vi4List50_20groups", measure(() -> vi4List(policy, p20)));
            perf.put("vi4Count_20groups", measure(() -> vi4Count(policy, p20)));
            perf.put("vi4List50_300groups", measure(() -> vi4List(policy, p300)));
            perf.put("vi4Count_300groups", measure(() -> vi4Count(policy, p300)));
            TaskQuery tq20 = new TaskQuery("big-dept", null, new ArrayList<>(groups20), null, null, 0, 50);
            TaskQuery tq300 = new TaskQuery("big-dept", null, new ArrayList<>(groups300), null, null, 0, 50);
            perf.put("adapterList50_20groups", measure(() -> g.adapter().queryTasks(tq20)));
            perf.put("adapterCount_20groups", measure(() -> g.adapter().countTasks(tq20)));
            perf.put("adapterList50_300groups", measure(() -> g.adapter().queryTasks(tq300)));
            perf.put("adapterCount_300groups", measure(() -> g.adapter().countTasks(tq300)));
            assertEquals(expected20 + 50, g.adapter().countTasks(tq20), "engine query has no deny (VI.4 is product)");
            r.scale("visibleTasks20groups", c20).scale("visibleTasks300groups", c300).scale("perf", perf);
            Object p95 = ((Map<?, ?>) perf.get("vi4List50_300groups")).get("p95Ms");
            r.note("T38 target: p95 list < 200 ms with 10k tasks x 300 groups; measured VI.4 list p95 = " + p95
                    + " ms on " + Env.dbDescription() + ".");
            r.note("The VI.4 predicate is product SQL over the engine's task and identity-link tables (same table and"
                    + " column names on both engines); the adapter list projects variables per task as well.");
        });
    }

    private static void bindGrant(PreparedStatement ps, String principal, String resource, int deny, long perms)
            throws SQLException {
        ps.setString(1, "big-dept");
        ps.setString(2, principal);
        ps.setString(3, "TASK");
        ps.setString(4, resource);
        ps.setInt(5, deny);
        ps.setLong(6, perms);
        ps.addBatch();
    }

    private String inboxWhere(AuthorizationPolicy policy, AuthorizationPolicy.Principal p, List<Object> params) {
        SqlPredicate vis = policy.visible(p, AuthorizationPolicy.Action.READ_TASK, "t");
        params.addAll(vis.params());
        params.add(p.userId());
        params.addAll(p.groups());
        // H2 (development runs only) cannot type a bare parameter inside BITAND
        String visSql = Env.isPostgres() ? vis.sql() : vis.sql().replace(", ?)", ", CAST(? AS BIGINT))");
        return " FROM ACT_RU_TASK t WHERE " + visSql + " AND (t.ASSIGNEE_ = ? OR EXISTS (SELECT 1 FROM"
                + " ACT_RU_IDENTITYLINK l WHERE l.TASK_ID_ = t.ID_ AND l.TYPE_ = 'candidate' AND l.GROUP_ID_ IN ("
                + String.join(",", Collections.nCopies(p.groups().size(), "?")) + ")))";
    }

    private long vi4Count(AuthorizationPolicy policy, AuthorizationPolicy.Principal p) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*)" + inboxWhere(policy, p, params);
        return g.adapter().inEngineTransaction(c -> query(c, sql, params, rs -> {
            rs.next();
            return rs.getLong(1);
        }));
    }

    private List<String> vi4List(AuthorizationPolicy policy, AuthorizationPolicy.Principal p) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT t.ID_, t.NAME_, t.CREATE_TIME_" + inboxWhere(policy, p, params)
                + " ORDER BY t.CREATE_TIME_ DESC, t.ID_ DESC LIMIT 50";
        return g.adapter().inEngineTransaction(c -> query(c, sql, params, rs -> {
            List<String> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
            return ids;
        }));
    }

    interface RsFn<T> {
        T apply(ResultSet rs) throws SQLException;
    }

    private static <T> T query(Connection c, String sql, List<Object> params, RsFn<T> fn) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return fn.apply(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static Map<String, Object> measure(Runnable q) {
        for (int i = 0; i < 20; i++) {
            q.run();
        }
        List<Long> ns = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long t = System.nanoTime();
            q.run();
            ns.add(System.nanoTime() - t);
        }
        return percentiles(ns);
    }

    // ------------------------------------------------------------------------------------------------ GP-40

    @Test
    void gp40_suspendResume() {
        REPORT.run("GP-40", "Suspend / resume instance (court stay)", r -> {
            r.feature("suspend / activate process instance", "suspended: task completion, message correlation and timers "
                    + "blocked", "timer due date moved by the stay period");
            g.deploy("court", "gp40-stay.bpmn");
            String p = g.start("court", "gp40Proceeding", "SCN-2026-9", m("noticee", "M/s Ganga Traders"));
            assertEquals(days(30), g.nat().earliestTimerDue(p));
            g.clock(days(10));
            g.nat().suspend(p);
            assertTrue(g.nat().isSuspended(p));
            try {
                g.complete(p, "gp40_hearing", m());
                fail("completing a task of a stayed proceeding must fail");
            } catch (AssertionError e) {
                throw e;
            } catch (RuntimeException expected) {
                r.sub("complete task while suspended", "REJECTED: " + first(expected));
            }
            try {
                g.correlate("court", "replyReceived", "SCN-2026-9", m("reply", "x"));
                fail("message to a stayed proceeding must not be accepted");
            } catch (AssertionError e) {
                throw e;
            } catch (RuntimeException expected) {
                r.sub("correlate message while suspended", "REJECTED: " + first(expected));
            }
            g.clock(days(31));
            g.jobs();
            assertEquals(0, g.tasks(null, p, "gp40_breach").size(), "SLA timer does not fire during the stay");
            r.sub("timer while suspended", "not fired");
            g.clock(days(40));                                   // stay vacated after 30 days
            g.nat().activate(p);
            assertFalse(g.nat().isSuspended(p));
            Instant newDue = days(60);                           // original due + 30 days of stay
            g.nat().rescheduleTimers(p, newDue);
            assertEquals(newDue, g.nat().earliestTimerDue(p));
            g.jobs();
            assertEquals(0, g.tasks(null, p, "gp40_breach").size(), "no breach right after resume");
            g.clock(days(60).plusSeconds(60));
            g.jobs();
            assertEquals(1, g.tasks(null, p, "gp40_breach").size(), "breach at the extended date");
            g.complete(p, "gp40_hearing", m());
            g.correlate("court", "replyReceived", "SCN-2026-9", m("reply", "Denied"));
            g.complete(p, "gp40_breach", m());
            assertTrue(g.ended(p));
            r.note("Without the reschedule, the overdue timer fires at once on activation: the engines do not stop the"
                    + " statutory clock during a stay.");
        });
    }

}
