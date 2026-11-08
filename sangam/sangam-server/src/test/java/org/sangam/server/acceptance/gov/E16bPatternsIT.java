// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.EngineAdapter.TaskQuery;
import org.sangam.core.api.EngineAdapter.TaskView;
import org.sangam.core.layer.AuthorizationPolicy;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.integration.sim.dlt.DltSmsSimulator;
import org.sangam.integration.sim.esign.EsignSimulator;
import org.sangam.ports.adhoc.AdHocSubProcessService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Port of spike E16-b ({@code spike/experiments-gov-b/.../E16PatternsTest}): government process patterns GP-21 to
 * GP-40 against the running Sangam server on its database. Same scenarios and the same assertions as the spike
 * (PostgreSQL scale). Spike sub-checks that only recorded an outcome are kept as details, except where Sangam
 * replaced an Operaton gap on purpose (BPMN ad-hoc sub-process, CMMN timer event listener, product authorization),
 * where the replacement is asserted.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class E16bPatternsIT extends GovBSupport {

    @Autowired
    AdHocSubProcessService adHoc;

    // ------------------------------------------------------------------------------------------------ GP-21

    @Test
    void gp21_adHocCase() {
        scenario("GP-21", ctx -> {
            deploy("audit", "gp21-audit-case.cmmn");
            String c = startCase("audit", "gp21AuditPara", "PARA-2026-17", m("para", "Excess payment"));
            eq(ctx, 1, tasksIn(c, "PI_examine").size(), "examine active at once");
            eq(ctx, 1, enabledCaseItems(c, "PI_seekReport").size(), "seek report offered (enabled)");
            eq(ctx, 1, enabledCaseItems(c, "PI_hearing").size(), "hearing offered (enabled)");
            eq(ctx, 1, tasksOf(c).size(), "enabled ad-hoc actions are not tasks until started");
            completeTask(c, "PI_examine", m("examined", true));
            eq(ctx, 1, tasksIn(c, "PI_close").size(), "required Close active");
            for (int i = 1; i <= 2; i++) {
                List<String> enabled = enabledCaseItems(c, "PI_seekReport");
                eq(ctx, 1, enabled.size(), "seek report enabled (round " + i + ")");
                startCaseItem(enabled.get(0));
                completeTask(c, "PI_seekReport", m("report" + i, "received"));
            }
            startCaseItem(enabledCaseItems(c, "PI_hearing").get(0));
            completeTask(c, "PI_hearing", m("heard", true));
            yes(ctx, !ended(c), "case open while Close is pending");
            completeTask(c, "PI_close", m("outcome", "SETTLED"));
            yes(ctx, ended(c), "case auto-completes after the required task, enabled ad-hoc items do not block");

            // BPMN form: adHocSubProcess. Spike: UNSUPPORTED on Operaton 2.2.0 (recorded only). Sangam replaced it
            // with the ports' ad-hoc sub-process (CF-13, AdHocSubProcessService), so the replacement is asserted.
            deploy("audit", "gp21-adhoc.bpmn");
            String p = start("audit", "gp21AdHoc", "COURT-1", m());
            List<AdHocSubProcessService.EnabledActivity> enabled = adHoc.getEnabledActivities(p);
            eq(ctx, Set.of("gp21b_counterAffidavit", "gp21b_seekLegalOpinion"),
                    new HashSet<>(enabled.stream().map(AdHocSubProcessService.EnabledActivity::activityId).toList()),
                    "BPMN adHocSubProcess: enabled activities (Sangam AdHocSubProcessService)");
            adHoc.executeActivity(p, enabled.get(0).adHocActivityInstanceId(), "gp21b_seekLegalOpinion");
            completeTask(p, "gp21b_seekLegalOpinion", m("caseDone", true));
            yes(ctx, ended(p), "ad-hoc sub-process completes on its completionCondition");

            // CMMN timer event listener. Spike: deployed but never occurred on Operaton. Sangam replaced it (FC-55,
            // ports CMMN event listeners with CmmnTimerJobHandler), so the escalation is asserted.
            deploy("audit", "cmmn-timer-probe.cmmn");
            String probe = startCase("audit", "cmmnTimerProbe", "PROBE-1", m());
            clock(days(22));
            jobs();
            eq(ctx, 1, tasksIn(probe, "PI_escalate").size(), "CMMN timerEventListener (P21D) -> escalation task");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-22

    @Test
    void gp22_appealReopen() {
        scenario("GP-22", ctx -> {
            deploy("grv22", "gp22-decision.bpmn");
            String p = start("grv22", "gp22Decision", "ORD-2026-0042", m("applicant", "Ramesh Kumar"));
            completeTask(p, "gp22_decide", m("decision", "REJECTED", "reasons", "Document missing"));
            yes(ctx, ended(p), "decision instance ended");
            List<String> found = historicByBusinessKey("grv22", "gp22Decision", "ORD-2026-0042");
            var closed = instance(found.get(0));
            yes(ctx, closed.ended(), "closed instance read from history");
            eq(ctx, "REJECTED", closed.variables().get("decision"), "decision readable from history");
            String a = start("grv22", "gp22Appeal", "ORD-2026-0042/APPEAL-1",
                    m("originalInstanceId", found.get(0), "originalDecision", closed.variables().get("decision")));
            completeTask(a, "gp22a_hear", m("appealOutcome", "REMAND"));
            completeTask(a, "gp22a_redecide", m("decision", "APPROVED"));
            yes(ctx, ended(a), "appeal ended");
            String re = start("grv22", "gp22Decision", "ORD-2026-0042/REOPEN-1", m("reopenOf", found.get(0)));
            completeTask(re, "gp22_decide", m("decision", "APPROVED"));
            eq(ctx, found.get(0), var(re, "reopenOf"), "reopen linked to the closed instance");
            eq(ctx, 1, historicByBusinessKey("grv22", "gp22Appeal", "ORD-2026-0042/APPEAL-1").size(), "appeal in history");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-23

    @Test
    void gp23_twoEnvelope() {
        scenario("GP-23", ctx -> {
            deploy("tender", "gp23-two-envelope.bpmn");
            String p = start("tender", "gp23TwoEnvelope", "NIT-2026-77", m("techOpenAt", iso(days(2)),
                    "finOpenAt", iso(days(5)), "committee", "member-a;member-b;member-c", "committeeSize", 3));
            jobs();
            eq(ctx, 0, tasksOf(p).size(), "nothing before the technical opening date");
            clock(days(2).minusSeconds(60));
            jobs();
            eq(ctx, 0, tasksOf(p).size(), "still locked one minute before");
            clock(days(2).plusSeconds(60));
            jobs();
            eq(ctx, 3, tasksIn(p, "gp23_techEval").size(), "one task per member");
            for (String member : List.of("member-a", "member-b", "member-c")) {
                List<TaskView> mine = forUser("tender", member);
                eq(ctx, 1, mine.size(), member + " sees only own evaluation task");
                yes(ctx, tasksIn(p, "gp23_techResult").isEmpty(), "chair waits for all members");
                adapter.claimTask(mine.get(0).id(), member);
                adapter.completeTask(mine.get(0).id(), m());
            }
            completeTask(p, "gp23_techResult", m("techQualified", true));
            jobs();
            yes(ctx, tasksIn(p, "gp23_finEval").isEmpty(), "financial envelope locked until finOpenAt");
            clock(days(5).plusSeconds(60));
            jobs();
            List<TaskView> fin = tasksIn(p, "gp23_finEval");
            eq(ctx, 3, fin.size(), "financial evaluation tasks");
            for (TaskView tv : fin) {
                adapter.completeTask(tv.id(), m());
            }
            yes(ctx, ended(p), "instance ended");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-24

    @Test
    void gp24_timeLock() {
        scenario("GP-24", ctx -> {
            deploy("exam", "gp24-time-lock.bpmn");
            Instant resultAt = days(3).plus(Duration.ofHours(1));
            String p = start("exam", "gp24TimeLock", "RESULT-2026-CGL", m("resultDate", iso(resultAt)));
            eq(ctx, resultAt, earliestTimerDue(p), "timer due exactly at the declared date");
            clock(resultAt.minusSeconds(1));
            jobs();
            eq(ctx, 0, count("exam", "gp24_declare"), "no declaration task one second early");
            clock(resultAt.plusSeconds(1));
            jobs();
            completeTask(p, "gp24_declare", m("declared", true));
            yes(ctx, ended(p), "instance ended");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-25

    @Test
    void gp25_negotiationLoop() {
        scenario("GP-25", ctx -> {
            deploy("proc", "gp25-negotiation.bpmn");
            String p = start("proc", "gp25Negotiation", "TENDER-1", m("retenderCount", 0));
            completeTask(p, "gp25_publishNit", m());
            completeTask(p, "gp25_evaluate", m("l1", "Firm A"));
            completeTask(p, "gp25_negotiate", m("negotiation", "COUNTER"));
            completeTask(p, "gp25_negotiate", m("negotiation", "COUNTER"));
            completeTask(p, "gp25_negotiate", m("negotiation", "FAILED"));
            completeTask(p, "gp25_publishNit", m("retenderCount", 1));
            completeTask(p, "gp25_evaluate", m("l1", "Firm B"));
            completeTask(p, "gp25_negotiate", m("negotiation", "AGREED"));
            completeTask(p, "gp25_loa", m());
            yes(ctx, ended(p), "instance ended");
            eq(ctx, 4, historicActivityCount(p, "gp25_negotiate"), "negotiate visits");
            eq(ctx, 2, historicActivityCount(p, "gp25_publishNit"), "publish visits");
            String q = start("proc", "gp25Negotiation", "TENDER-2", m("retenderCount", 2));
            completeTask(q, "gp25_publishNit", m());
            completeTask(q, "gp25_evaluate", m());
            completeTask(q, "gp25_negotiate", m("negotiation", "FAILED"));
            yes(ctx, ended(q), "dropped after two retenders");
            eq(ctx, 1, finishedByEndActivity("proc", "gp25Negotiation", "gp25_dropped"), "dropped end");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-26

    record Cand(String id, String category, boolean pwd, boolean esm, int score) {
    }

    @Test
    void gp26_meritReservation() {
        scenario("GP-26", ctx -> {
            deploy("rec26", "gp26-merit.bpmn", "reservation-pools.dmn");
            List<Cand> cands = List.of(new Cand("C1", "UR", false, false, 91), new Cand("C2", "OBC", false, false, 89),
                    new Cand("C3", "SC", false, false, 70), new Cand("C4", "OBC", true, false, 85),
                    new Cand("C5", "UR", false, true, 80), new Cand("C6", "ST", false, false, 60),
                    new Cand("C7", "EWS", false, false, 88), new Cand("C8", "OBC", false, false, 75));
            Map<String, List<String>> pools = new LinkedHashMap<>();
            for (Cand c : cands) {
                String p = start("rec26", "gp26Merit", c.id(), m("category", c.category(), "pwd", c.pwd(),
                        "exServiceman", c.esm(), "score", c.score()));
                pools.put(c.id(), collected(var(p, "reservationPools")));
            }
            eq(ctx, List.of("UR", "OBC", "PWD"), pools.get("C4"), "C4 pools");
            eq(ctx, List.of("UR", "ESM"), pools.get("C5"), "C5 pools");
            eq(ctx, List.of("UR"), pools.get("C1"), "C1 pools");
            eq(ctx, List.of("UR", "SC"), pools.get("C3"), "C3 pools");
            Map<String, Integer> seats = new LinkedHashMap<>(Map.of("UR", 3, "OBC", 1, "SC", 1, "EWS", 1));
            List<Cand> byScore = new ArrayList<>(cands);
            byScore.sort(Comparator.comparingInt(Cand::score).reversed());
            Map<String, String> selected = new LinkedHashMap<>();
            for (Cand c : byScore) {
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
            eq(ctx, Set.of("C1", "C2", "C7", "C4", "C3"), new HashSet<>(selected.keySet()), "merit list");
            int n = 1000;
            long t = System.nanoTime();
            for (int i = 0; i < n; i++) {
                Cand c = cands.get(i % cands.size());
                evaluateDecision("reservationPools", "rec26", m("category", c.category(), "pwd", c.pwd(),
                        "exServiceman", c.esm()));
            }
            ctx.detail("decisionEvaluations1000Ms", ms(t));
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-27

    @Test
    void gp27_bulkDocuments() {
        scenario("GP-27", ctx -> {
            deploy("exam27", "gp27-admit-card.bpmn");
            int n = 1000;
            long t = System.nanoTime();
            parallel(n, i -> start("exam27", "gp27AdmitCard", String.format("ROLL-%06d", i),
                    m("rollNo", String.format("ROLL-%06d", i), "centre", "C" + (i % 40))));
            ctx.detail("startInstancesMs", ms(t));
            AtomicInteger done = new AtomicInteger();
            ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
            t = System.nanoTime();
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < THREADS; w++) {
                String worker = "docgen-" + w;
                fs.add(pool.submit(() -> {
                    int empty = 0;
                    while (done.get() < n && empty < 20) {
                        List<ExternalWork> batch = adapter.fetchAndLock(worker, "admit-card", 50, Duration.ofMinutes(5));
                        if (batch.isEmpty()) {
                            empty++;
                            continue;
                        }
                        empty = 0;
                        for (ExternalWork x : batch) {
                            try {
                                adapter.completeExternal(x.id(), worker, x.lockVersion(),
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
            ctx.detail("workersMs", ms(t));
            ctx.detail("continuationJobs", jobs());
            eq(ctx, n, done.get(), "every admit card generated once; errors " + errors);
            eq(ctx, 0, runningInstances("exam27", "gp27AdmitCard"), "all instances ended");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-28

    @Test
    void gp28_waitlistPromotion() {
        scenario("GP-28", ctx -> {
            deploy("dv", "gp28-waitlist.bpmn");
            Map<String, String> pid = new LinkedHashMap<>();
            for (String c : List.of("SEL-1", "SEL-2", "SEL-3")) {
                pid.put(c, start("dv", "gp28Candidate", c, m("status", "SELECTED")));
            }
            ConcurrentLinkedQueue<String> waitlist = new ConcurrentLinkedQueue<>(List.of("WL-1", "WL-2"));
            for (String c : waitlist) {
                pid.put(c, start("dv", "gp28Candidate", c, m("status", "WAITLIST")));
            }
            completeTask(pid.get("SEL-1"), "gp28_offer", m("accepted", true));
            completeTask(pid.get("SEL-2"), "gp28_offer", m("accepted", false));
            jobs();
            eq(ctx, 1, runningInstances("dv", "gp28Promote"), "one promotion instance per vacancy");
            completeTask(pid.get("SEL-3"), "gp28_offer", m("accepted", false));
            jobs();
            eq(ctx, 2, runningInstances("dv", "gp28Promote"), "no vacancy lost while another is pending");
            eq(ctx, 2, promote(waitlist, "dv"), "promoted");
            yes(ctx, waitlist.isEmpty(), "waitlist used up");
            completeTask(pid.get("WL-1"), "gp28_offer", m("accepted", false));
            jobs();
            eq(ctx, 0, promote(waitlist, "dv"), "waitlist exhausted: third vacancy recorded unfilled");
            completeTask(pid.get("WL-2"), "gp28_offer", m("accepted", true));
            eq(ctx, 3, finishedInstances("dv", "gp28Promote"), "promotion instances finished");
            eq(ctx, 2, finishedByEndActivity("dv", "gp28Candidate", "gp28_endJoined"), "joined");
            eq(ctx, 3, finishedByEndActivity("dv", "gp28Candidate", "gp28_endDeclined"), "declined");
        });
    }

    private int promote(ConcurrentLinkedQueue<String> waitlist, String tenant) {
        int promoted = 0;
        for (ExternalWork w : fetchAll("promoter", "waitlist-promote", 10)) {
            String next = waitlist.poll();
            if (next != null) {
                correlate(tenant, "promoted", next, m("promotedFor", w.processInstanceId()));
                promoted++;
            }
            adapter.completeExternal(w.id(), "promoter", w.lockVersion(), m("promotedCandidate", next == null ? "NONE" : next));
        }
        jobs();
        return promoted;
    }

    // ------------------------------------------------------------------------------------------------ GP-29

    @Test
    void gp29_autoApproveThreshold() {
        scenario("GP-29", ctx -> {
            deploy("hr29", "leave.bpmn", "leave-routing.dmn");
            String auto = start("hr29", "leaveApplication", "LV-1", m("leaveType", "CL", "days", 2, "balance", 8));
            yes(ctx, ended(auto), "2 days CL auto-approved");
            eq(ctx, "AUTO", var(auto, "route"), "route");
            String co = start("hr29", "leaveApplication", "LV-2", m("leaveType", "EL", "days", 10, "balance", 30));
            eq(ctx, 1, forGroups("hr29", "CONTROLLING_OFFICER").size(), "controlling officer inbox");
            String hod = start("hr29", "leaveApplication", "LV-3", m("leaveType", "EL", "days", 20, "balance", 30));
            String eol = start("hr29", "leaveApplication", "LV-4", m("leaveType", "EOL", "days", 2, "balance", 30));
            eq(ctx, 2, forGroups("hr29", "HOD").size(), "HOD inbox");
            String noBal = start("hr29", "leaveApplication", "LV-5", m("leaveType", "EL", "days", 40, "balance", 30));
            yes(ctx, ended(noBal), "no balance ends");
            completeTask(co, "lv_sanction", m("sanctioned", true));
            completeTask(hod, "lv_sanction", m("sanctioned", false));
            completeTask(eol, "lv_sanction", m("sanctioned", true));
            eq(ctx, 1, finishedByEndActivity("hr29", "leaveApplication", "lv_endAuto"), "auto");
            eq(ctx, 2, finishedByEndActivity("hr29", "leaveApplication", "lv_endApproved"), "approved");
            eq(ctx, 1, finishedByEndActivity("hr29", "leaveApplication", "lv_endRejected"), "rejected");
            eq(ctx, 1, finishedByEndActivity("hr29", "leaveApplication", "lv_endNoBalance"), "no balance");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-30

    @Test
    void gp30_periodicGeneration() {
        scenario("GP-30", ctx -> {
            deploy("pension", "gp30-monthly.bpmn");
            jobs();
            eq(ctx, 0, count("pension", "gp30i_prepare"), "no bill before the first cycle");
            clock(Instant.parse("2026-12-01T06:01:00Z"));
            jobs();
            eq(ctx, 1, count("pension", "gp30i_prepare"), "December ISO bill");
            eq(ctx, 1, count("pension", "gp30c_prepare"), "December cron bill");
            clock(Instant.parse("2027-01-01T06:01:00Z"));
            jobs();
            eq(ctx, 2, count("pension", "gp30i_prepare"), "January ISO bill");
            clock(Instant.parse("2027-03-01T06:01:00Z"));
            jobs();
            long isoAfterGap = count("pension", "gp30i_prepare");
            ctx.detail("isoBillsAfterMissedFebruary", isoAfterGap);
            ctx.detail("cronBillsAfterMissedFebruary", count("pension", "gp30c_prepare"));
            yes(ctx, isoAfterGap >= 3, "ISO cycle fires again after the missed February (got " + isoAfterGap + ")");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-31

    @Test
    void gp31_fileReconciliation() {
        scenario("GP-31", ctx -> {
            deploy("treasury", "gp31-challan.bpmn");
            int n = 1000;
            Set<String> register = java.util.concurrent.ConcurrentHashMap.newKeySet();
            parallel(n, i -> {
                String chl = String.format("CHL-%06d", i);
                start("treasury", "gp31Challan", chl, m("amount", 100L + i, "head", "0030-STAMPS"));
                register.add(chl);
            });
            Path scroll = Files.createTempFile("bank-scroll-", ".csv");
            StringBuilder csv = new StringBuilder("challanNo,amount,bankRef\n");
            int credited = 0;
            for (int i = 0; i < n; i++) {
                if (i % 20 != 7) {
                    csv.append(String.format("CHL-%06d,%d,SBI%08d%n", i, 100 + i, i));
                    credited++;
                }
            }
            for (int u = 0; u < 10; u++) {
                csv.append(String.format("CHL-9%05d,%d,SBI-UNK%d%n", u, 500, u));
            }
            Files.writeString(scroll, csv, StandardCharsets.UTF_8);
            String recon = start("treasury", "gp31Recon", "SCROLL-20261102", m("scrollPath", scroll.toString()));
            ExternalWork w = fetchOne("recon-worker", "bank-scroll");
            List<String[]> lines = Files.readAllLines(Path.of((String) w.variables().get("scrollPath"))).stream()
                    .skip(1).map(l -> l.split(",")).toList();
            AtomicInteger matched = new AtomicInteger();
            AtomicInteger unknown = new AtomicInteger();
            long t = System.nanoTime();
            long retries = parallel(lines.size(), i -> {
                String[] l = lines.get(i);
                if (!register.contains(l[0])) {
                    unknown.incrementAndGet();
                    return;
                }
                correlate("treasury", "challanCredited", l[0], m("bankRef", l[2], "creditedAmount", Long.parseLong(l[1])));
                matched.incrementAndGet();
            });
            ctx.detail("correlateMs", ms(t));
            ctx.detail("conflictRetries", retries);
            adapter.completeExternal(w.id(), "recon-worker", w.lockVersion(),
                    m("matched", matched.get(), "unknownCredits", unknown.get()));
            jobs();
            eq(ctx, credited, matched.get(), "matched");
            eq(ctx, 10, unknown.get(), "unknown credits");
            completeTask(recon, "gp31r_exceptions", m("reviewed", true));
            yes(ctx, ended(recon), "reconciliation ended");
            eq(ctx, n - credited, runningInstances("treasury", "gp31Challan"), "unmatched challans still waiting");
            clock(days(3).plusSeconds(60));
            jobs();
            eq(ctx, n - credited, count("treasury", "gp31_investigate"), "unmatched challans go to the officer");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-32

    @Test
    void gp32_interDepartmentCall() {
        scenario("GP-32", ctx -> {
            deploy("dept-fin", "concurrence.bpmn");
            deploy("dept-works", "gp32-works.bpmn");
            Map<String, Object> vars = m("fileNo", "WORKS/2026/311", "amount", 4_500_000L);
            String probe = start("dept-works", "gp32WorksProposal", "PROBE/1", new LinkedHashMap<>(vars));
            completeTask(probe, "gp32_prepare", m());
            ctx.detail("tenantOfCalledInstance", instanceTenant(subProcessInstances(probe).get(0)));
            cancelQuietly(probe);
            String p = start("dept-works", "gp32WorksProposal", "WORKS/2026/311", vars);
            completeTask(p, "gp32_prepare", m());
            List<String> children = subProcessInstances(p);
            eq(ctx, 1, children.size(), "one called instance");
            eq(ctx, tn("dept-fin"), instanceTenant(children.get(0)), "callee runs in Finance's tenant");
            eq(ctx, 1, forGroups("dept-fin", "concurrence-officer").size(), "visible in Finance inbox");
            eq(ctx, 0, forGroups("dept-works", "concurrence-officer").size(), "not in Works inbox");
            TaskView ft = forGroups("dept-fin", "concurrence-officer").get(0);
            eq(ctx, "WORKS/2026/311", ft.variables().get("fileNo"), "in-mapping");
            eq(ctx, tn("dept-fin"), ft.tenantId(), "task tenant");
            adapter.completeTask(ft.id(), m("concurred", false, "remarks", "Rates not as per SOR 2025"));
            eq(ctx, "Rates not as per SOR 2025", var(p, "finRemarks"), "out-mapping");
            completeTask(p, "gp32_prepare", m());
            TaskView ft2 = forGroups("dept-fin", "concurrence-officer").get(0);
            adapter.completeTask(ft2.id(), m("concurred", true, "remarks", "Concurred"));
            completeTask(p, "gp32_sanction", m());
            yes(ctx, ended(p), "proposal ended");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-33

    @Test
    void gp33_dltNotifications() {
        scenario("GP-33", ctx -> {
            // spike sim.dlt.DltSmsSimulator -> Sangam's own port (sangam-integration sim.dlt)
            try (DltSmsSimulator dlt = new DltSmsSimulator()) {
                dlt.registerTemplate("1107000000000000001", "GOVSVC", "आवेदन {#var#} प्राप्त हुआ। स्थिति: {#var#} - GOVSVC")
                        .registerTemplate("1107000000000000002", "GOVSVC", "आवेदन {#var#} पर निर्णय: {#var#} - GOVSVC")
                        .whitelistUrl("https://seva.gov.in/track/");
                dlt.start();
                deploy("svc", "gp33-notify.bpmn");
                String ok = start("svc", "gp33CitizenService", "APP/2026/000123", m("mobile", "9876543210",
                        "appNo", "APP/2026/000123", "trackUrl", "https://seva.gov.in/track/123"));
                String bad = start("svc", "gp33CitizenService", "APP/2026/000124", m("mobile", "9876543211",
                        "appNo", "APP/2026/000124", "trackUrl", "https://bit.ly/x0124"));
                sendSms(dlt);
                eq(ctx, 1, tasksOf(ok).size(), "after the receipt SMS the application is in scrutiny");
                eq(ctx, 1, failedWork(bad), "DLT rejection (URL not whitelisted) -> incident");
                completeTask(ok, "gp33_scrutiny", m("decision", "स्वीकृत"));
                jobs();
                sendSms(dlt);
                yes(ctx, ended(ok), "application ended");
                eq(ctx, 2, dlt.ledger().count(DltSmsSimulator.EFFECT_SMS), "SMS accepted");
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
            String body = "{\"header\":" + js("GOVSVC-G") + ",\"templateId\":"
                    + js(receipt ? "1107000000000000001" : "1107000000000000002") + ",\"mobile\":"
                    + js(String.valueOf(v.get("mobile"))) + ",\"message\":" + js(msg) + "}";
            var resp = post(dlt.uri(DltSmsSimulator.SMS_PATH), "application/json", body);
            if (resp.statusCode() == 200) {
                adapter.completeExternal(w.id(), "sms-worker", w.lockVersion(), m("smsStatus", "ACCEPTED"));
            } else {
                adapter.failExternal(w.id(), "sms-worker", w.lockVersion(), "DLT " + resp.body(), 0, Duration.ZERO);
            }
        }
        jobs();
    }

    private static String js(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ------------------------------------------------------------------------------------------------ GP-34

    private static final Pattern DOC_SIG = Pattern.compile("<DocSignature[^>]*>([^<]+)</DocSignature>");

    @Test
    void gp34_esignBoundToApproval() {
        scenario("GP-34", ctx -> {
            try (EsignSimulator esp = new EsignSimulator()) {
                esp.start();
                deploy("orders", "gp34-esign.bpmn");
                String a = start("orders", "gp34SignedOrder", "SO-1", m("orderNo", "SO-1", "amount", 250_000L,
                        "beneficiary", "Gram Panchayat Rampur"));
                String b = start("orders", "gp34SignedOrder", "SO-2", m("orderNo", "SO-2", "amount", 90_000L,
                        "beneficiary", "Nagar Palika Sitapur"));
                for (String p : List.of(a, b)) {
                    completeTask(p, "gp34_approve", m("approvedHash", payloadHash(instance(p).variables())));
                }
                setVariable(b, "amount", 900_000L);
                eq(ctx, 1, signOrders(ctx, esp), "signed in round 1");
                eq(ctx, 1, tasksIn(a, "gp34_dispatch").size(), "SO-1 signed");
                eq(ctx, 1, tasksIn(b, "gp34_approve").size(), "SO-2 back to approval");
                yes(ctx, esp.verify((String) var(a, "approvedHash"), (String) var(a, "signature")),
                        "signature over the approved hash");
                completeTask(b, "gp34_approve", m("approvedHash", payloadHash(instance(b).variables())));
                eq(ctx, 1, signOrders(ctx, esp), "signed in round 2");
                completeTask(a, "gp34_dispatch", m());
                completeTask(b, "gp34_dispatch", m());
                yes(ctx, ended(a) && ended(b), "both orders ended");
            }
        });
    }

    private static String payloadHash(Map<String, Object> v) {
        return sha256(v.get("orderNo") + "|" + v.get("amount") + "|" + v.get("beneficiary"));
    }

    private int signOrders(Ctx ctx, EsignSimulator esp) {
        int signed = 0;
        for (ExternalWork w : fetchAll("esign-worker", "esign", 10)) {
            Map<String, Object> v = w.variables();
            String h = payloadHash(v);
            if (!h.equals(v.get("approvedHash"))) {
                adapter.bpmnErrorExternal(w.id(), "esign-worker", w.lockVersion(), "PAYLOAD_CHANGED",
                        m("rejectReason", "payload hash differs from the approved hash"));
                continue;
            }
            String xml = "<Esign ver=\"2.1\" sc=\"Y\" ts=\"" + Instant.now() + "\" txn=\"ASP-" + v.get("orderNo") + "-"
                    + w.lockVersion() + "\" aspId=\"ASP001\" ekycIdType=\"A\" AuthMode=\"1\" responseSigType=\"pkcs7\""
                    + " responseUrl=\"https://asp.gov.in/cb\"><Docs><InputHash id=\"1\" hashAlgorithm=\"SHA256\""
                    + " docInfo=\"Sanction order\">" + h + "</InputHash></Docs><Signature>ASP</Signature></Esign>";
            var resp = post(esp.uri(EsignSimulator.SIGN_PATH), "application/xml", xml);
            Matcher mm = DOC_SIG.matcher(resp.body());
            ctx.require("signature in eSign response: " + resp.body(), mm.find());
            adapter.completeExternal(w.id(), "esign-worker", w.lockVersion(), m("signature", mm.group(1), "signedHash", h));
            signed++;
        }
        jobs();
        return signed;
    }

    // ------------------------------------------------------------------------------------------------ GP-35

    @Test
    void gp35_statutoryClocks() {
        scenario("GP-35", ctx -> {
            deploy("pa-a", "rti.bpmn");
            String normal = start("pa-a", "rtiRequest", "RTI/2026/0001", m("lifeLiberty", false, "applicant", "A"));
            String urgent = start("pa-a", "rtiRequest", "RTI/2026/0002", m("lifeLiberty", true, "applicant", "B"));
            eq(ctx, hours(48), earliestTimerDue(urgent), "48-hour clock");
            eq(ctx, days(30), earliestTimerDue(normal), "30-day clock");
            clock(hours(47));
            jobs();
            eq(ctx, 0, count("pa-a", "rti_deemed"), "nothing at 47 h");
            clock(hours(49));
            jobs();
            eq(ctx, 1, tasksIn(urgent, "rti_deemed").size(), "48-hour deemed refusal");
            eq(ctx, 0, tasksIn(normal, "rti_deemed").size(), "normal not yet");
            clock(days(30).plusSeconds(60));
            jobs();
            eq(ctx, 1, tasksIn(normal, "rti_deemed").size(), "30-day deemed refusal");
            completeTask(urgent, "rti_pio", m("pioDecision", "SUPPLY"));
            correlate("pa-a", "firstAppealFiled", "RTI/2026/0002", m("appealDate", iso(days(31))));
            completeTask(urgent, "rti_deemed", m("noted", true));
            yes(ctx, ended(urgent), "urgent request ended");
            eq(ctx, 1, historicActivityCount(urgent, "rti_endAppealed"), "ended as appealed");
            var req = instance(urgent);
            String appeal = start("pa-a", "rtiFirstAppeal", "RTI/2026/0002/FA-1",
                    m("requestInstanceId", urgent, "lifeLiberty", req.variables().get("lifeLiberty")));
            clock(days(62));
            jobs();
            eq(ctx, 1, tasksIn(appeal, "rfa_recordReasons").size(), "FAA past 30 days: reasons");
            completeTask(appeal, "rfa_decide", m("faaOrder", "ALLOWED"));
            completeTask(appeal, "rfa_recordReasons", m("reasons", "Voluminous records"));
            yes(ctx, ended(appeal), "appeal ended");
            completeTask(normal, "rti_pio", m("pioDecision", "REJECT"));
            completeTask(normal, "rti_deemed", m("noted", true));
            clock(days(93));
            jobs();
            yes(ctx, ended(normal), "normal closed after the appeal window");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-36

    @Test
    void gp36_misHistory() {
        scenario("GP-36", ctx -> {
            deploy("mis", "leave.bpmn", "leave-routing.dmn");
            String key = "leaveApplication";
            List<String> ends = List.of("lv_endNoBalance", "lv_endAuto", "lv_endApproved", "lv_endRejected");
            long baseFinished = finishedInstances("mis", key);
            Map<String, Long> baseEnd = new LinkedHashMap<>();
            ends.forEach(e -> baseEnd.put(e, finishedByEndActivity("mis", key, e)));
            Map<String, Long> baseOfficer = finishedTasksByAssignee("mis", key);
            long baseHod = historicInstancesByVar("mis", key, "route", "HOD");
            int n = 120;
            List<String> routed = new ArrayList<>();
            Map<String, Integer> expectedEnd = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int kind = i % 12;
                int days = kind == 0 ? 40 : kind <= 4 ? 2 : kind <= 9 ? 10 : 20;
                String p = start("mis", key, "MIS-" + i, m("leaveType", "EL", "days", days, "balance", 30,
                        "employee", "E" + (i % 30)));
                String end = kind == 0 ? "lv_endNoBalance" : kind <= 4 ? "lv_endAuto" : null;
                if (end != null) {
                    expectedEnd.merge(end, 1, Integer::sum);
                } else {
                    routed.add(p);
                }
            }
            clock(days(2));
            int open = 0;
            for (int i = 0; i < routed.size(); i++) {
                if (i % 5 == 4) {
                    open++;
                    continue;
                }
                boolean ok = i % 7 != 0;
                completeAs(routed.get(i), "lv_sanction", "officer-" + (i % 3), m("sanctioned", ok));
                expectedEnd.merge(ok ? "lv_endApproved" : "lv_endRejected", 1, Integer::sum);
            }
            eq(ctx, n - open, finishedInstances("mis", key) - baseFinished, "every closed application is in history");
            for (var e : expectedEnd.entrySet()) {
                eq(ctx, e.getValue(), finishedByEndActivity("mis", key, e.getKey()) - baseEnd.get(e.getKey()),
                        "outcome " + e.getKey());
            }
            Map<String, Long> byOfficer = finishedTasksByAssignee("mis", key);
            baseOfficer.forEach((k, v) -> byOfficer.merge(k, -v, Long::sum));
            byOfficer.values().removeIf(v -> v == 0);
            eq(ctx, routed.size() - open, byOfficer.values().stream().mapToLong(Long::longValue).sum(), "tasks by officer");
            yes(ctx, !byOfficer.containsKey("null"), "every completed task records who acted");
            eq(ctx, 2 * n / 12, historicInstancesByVar("mis", key, "route", "HOD") - baseHod, "HOD-routed");
            yes(ctx, avgDurationMs("mis", key) > 0, "durations recorded");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-37

    @Test
    void gp37_tenantRuleOverride() {
        scenario("GP-37", ctx -> {
            deploy("state-a", "leave.bpmn", "leave-routing.dmn");
            deploy("state-b", "leave.bpmn", "leave-routing-state-b.dmn");
            String a2 = start("state-a", "leaveApplication", "A-2", m("leaveType", "CL", "days", 2, "balance", 10));
            String b2 = start("state-b", "leaveApplication", "B-2", m("leaveType", "CL", "days", 2, "balance", 10));
            String a10 = start("state-a", "leaveApplication", "A-10", m("leaveType", "EL", "days", 10, "balance", 30));
            String b10 = start("state-b", "leaveApplication", "B-10", m("leaveType", "EL", "days", 10, "balance", 30));
            eq(ctx, "AUTO", var(a2, "route"), "state A 2-day CL");
            eq(ctx, "CONTROLLING_OFFICER", var(b2, "route"), "state B 2-day CL");
            eq(ctx, "CONTROLLING_OFFICER", var(a10, "route"), "state A 10-day EL");
            eq(ctx, "HOD", var(b10, "route"), "state B 10-day EL");
            eq(ctx, 1, forGroups("state-a", "CONTROLLING_OFFICER").size(), "A CO inbox");
            eq(ctx, 1, forGroups("state-b", "CONTROLLING_OFFICER").size(), "B CO inbox");
            eq(ctx, 1, forGroups("state-b", "HOD").size(), "B HOD inbox");
            eq(ctx, 0, forGroups("state-a", "HOD").size(), "A HOD inbox");
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-38

    @Test
    void gp38_hindiRoundTrip() {
        scenario("GP-38", ctx -> {
            deploy("hindi", "gp38-hindi.bpmn", "hindi-status.dmn");
            String k = "gp38Bilingual";
            String bk = "आरटीआई/२०२६/००१";
            String dept = "लोक निर्माण विभाग";
            String p = start("hindi", k, bk, m("department", dept, "section", "अनुभाग-क", "विभाग", "राजस्व",
                    "applicantName", "श्रीमती सीता देवी"));
            var inst = instance(p);
            eq(ctx, bk, inst.businessKey(), "Devanagari business key");
            eq(ctx, "राजस्व", inst.variables().get("विभाग"), "Devanagari variable NAME");
            List<TaskView> t = forGroups("hindi", "अनुभाग-क");
            eq(ctx, 1, t.size(), "Devanagari candidate group");
            eq(ctx, "आवेदन की जाँच", t.get(0).name(), "Devanagari task name");
            eq(ctx, 1, runtimeInstancesByVar("hindi", k, "department", dept), "variable equals");
            eq(ctx, 1, runtimeInstancesByVarLike("hindi", k, "department", "%निर्माण%"), "variable like");
            eq(ctx, 1, tasksByProcessVar("hindi", "applicantName", "श्रीमती सीता देवी"), "task by process variable");
            adapter.completeTask(t.get(0).id(), m("status", "स्वीकृत"));
            yes(ctx, ended(p), "instance ended");
            eq(ctx, 1, finishedByEndActivity("hindi", k, "gp38_endApproved"), "Hindi gateway literal");
            eq(ctx, 1, historicInstancesByVar("hindi", k, "status", "स्वीकृत"), "historic variable equals");
            eq(ctx, dept, var(p, "department"), "history round trip");
            eq(ctx, 1, historicByBusinessKey("hindi", k, bk).size(), "business key query");
            var dec = evaluateDecision("hindiStatus", "hindi", m("status", "स्वीकृत"));
            eq(ctx, "APPROVED / अनुमोदित", dec.get(0).get("result"), "DMN string literal");

            String precomposed = "क़ानून";
            String nfc = Normalizer.normalize(precomposed, Normalizer.Form.NFC);
            String q = start("hindi", k, "NFC-1", m("section", "अनुभाग-ख", "subject", precomposed));
            ctx.detail("unicodeNormalForm", "exact form: " + runtimeInstancesByVar("hindi", k, "subject", precomposed)
                    + ", NFC form: " + runtimeInstancesByVar("hindi", k, "subject", nfc));
            cancelQuietly(q);

            try {
                deploy("hindi", "gp38-hindi-identifier.bpmn");
                String h = start("hindi", "gp38HindiIdentifier", "ID-1", m("स्थिति", "स्वीकृत"));
                ctx.detail("devanagariJuelIdentifier", tasksIn(h, "gp38i_yes").size() == 1 ? "PASS" : "FAIL: wrong branch");
            } catch (RuntimeException e) {
                ctx.detail("devanagariJuelIdentifier", "FAIL: " + root(e));
            }

            String longText = "यह आवेदन जाँच के लिए प्रस्तुत किया गया है। ".repeat(130);
            try {
                String lp = start("hindi", k, "LONG-1", m("section", "अनुभाग-ग", "noting", longText));
                eq(ctx, longText, var(lp, "noting"), "long Devanagari String round trip");
                ctx.detail("longString" + longText.length(), "PASS (stored as String)");
                cancelQuietly(lp);
            } catch (RuntimeException e) {
                ctx.detail("longString" + longText.length(), "FAIL as String: " + root(e));
                String lp = start("hindi", k, "LONG-2", m("section", "अनुभाग-ग",
                        "noting", longText.getBytes(StandardCharsets.UTF_8)));
                eq(ctx, longText, new String((byte[]) var(lp, "noting"), StandardCharsets.UTF_8), "long text as byte[]");
                ctx.workaround("long String stored as byte[] UTF-8 variable");
                cancelQuietly(lp);
            }

            try {
                String lp = start("hindi", k, "LIST-1", m("section", "अनुभाग-घ",
                        "members", new ArrayList<>(List.of("सदस्य-१", "सदस्य-२"))));
                ctx.detail("arrayListVariable", "PASS (" + var(lp, "members").getClass().getSimpleName() + ")");
                cancelQuietly(lp);
            } catch (RuntimeException e) {
                ctx.detail("arrayListVariable", "REJECTED: " + root(e));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ GP-39

    /**
     * Spike: {@code spike.layer.AuthorizationPolicy} with its own DDL. Sangam's replacement is the same policy in
     * sangam-core ({@code org.sangam.core.layer.AuthorizationPolicy}) over the product {@code AUTH_EFFECTIVE} table
     * created by Liquibase; grants and denies go through its {@code grant}/{@code deny} calls.
     */
    @Test
    void gp39_inboxUnderAuthorization() {
        scenario("GP-39", ctx -> {
            String big = tn("big-dept");
            deploy("big-dept", "gp39-inbox.bpmn");
            deploy("other-dept", "gp39-inbox.bpmn");
            int n = 10_000;
            int seats = 300;
            long t = System.nanoTime();
            long retries = parallel(n, i -> start("big-dept", "gp39Dak", "DAK-" + i,
                    m("seat", String.format("seat-%03d", i % seats), "subject", "Dak item " + i)));
            retries += parallel(n / 10, i -> start("other-dept", "gp39Dak", "ODAK-" + i,
                    m("seat", String.format("seat-%03d", i % seats))));
            ctx.detail("createTasksMs", ms(t));
            ctx.detail("startRetries", retries);

            Dialect d = Dialect.POSTGRESQL;
            AuthorizationPolicy policy = new AuthorizationPolicy(d, "TASK",
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
            adapter.inEngineTransaction(c -> {
                long perms = AuthorizationPolicy.Action.mask(AuthorizationPolicy.Action.READ_TASK,
                        AuthorizationPolicy.Action.CLAIM, AuthorizationPolicy.Action.COMPLETE);
                for (String s : groups300) {
                    AuthorizationPolicy.grant(c, d, big, s, "TASK", AuthorizationPolicy.ANY, perms);
                }
                String in = groups20.stream().map(s -> "'" + s + "'").collect(java.util.stream.Collectors.joining(","));
                try (PreparedStatement q = c.prepareStatement("SELECT t.ID_ FROM ACT_RU_TASK t JOIN ACT_RU_IDENTITYLINK l"
                        + " ON l.TASK_ID_ = t.ID_ WHERE t.TENANT_ID_ = ? AND l.TYPE_ = 'candidate' AND l.GROUP_ID_ IN ("
                        + in + ") ORDER BY t.ID_")) {
                    q.setString(1, big);
                    try (ResultSet rs = q.executeQuery()) {
                        while (rs.next() && denied.size() < 50) {
                            denied.add(rs.getString(1));
                        }
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
                for (String id : denied) {
                    AuthorizationPolicy.deny(c, d, big, "seat-000", "TASK", id, AuthorizationPolicy.Action.READ_TASK.bit());
                }
                return null;
            });
            eq(ctx, 50, denied.size(), "denied tasks set up");
            long expected20 = 0;
            for (int i = 0; i < n; i++) {
                if (i % seats < 20) {
                    expected20++;
                }
            }
            expected20 -= 50;
            AuthorizationPolicy.Principal p20 = new AuthorizationPolicy.Principal(big, "so-ramesh", groups20);
            AuthorizationPolicy.Principal p300 = new AuthorizationPolicy.Principal(big, "js-admin", groups300);
            eq(ctx, expected20, vi4Count(policy, p20), "VI.4 count = candidate tasks of 20 seats minus 50 denied");
            List<String> page = vi4List(policy, p20);
            eq(ctx, 50, page.size(), "page size");
            yes(ctx, Collections.disjoint(page, denied), "denied tasks never listed");
            eq(ctx, n - 50, vi4Count(policy, p300), "VI.4 count with 300 groups");
            Map<String, Object> perf = new LinkedHashMap<>();
            perf.put("vi4List50_20groups", measure(() -> vi4List(policy, p20)));
            perf.put("vi4Count_20groups", measure(() -> vi4Count(policy, p20)));
            perf.put("vi4List50_300groups", measure(() -> vi4List(policy, p300)));
            perf.put("vi4Count_300groups", measure(() -> vi4Count(policy, p300)));
            TaskQuery tq20 = new TaskQuery(big, null, new ArrayList<>(groups20), null, null, 0, 50);
            perf.put("adapterList50_20groups", measure(() -> adapter.queryTasks(tq20)));
            perf.put("adapterCount_20groups", measure(() -> adapter.countTasks(tq20)));
            ctx.detail("perf", perf);
            eq(ctx, expected20 + 50, adapter.countTasks(tq20), "engine query has no deny (VI.4 is product)");
        });
    }

    private String inboxWhere(AuthorizationPolicy policy, AuthorizationPolicy.Principal p, List<Object> params) {
        SqlPredicate vis = policy.visible(p, AuthorizationPolicy.Action.READ_TASK, "t");
        params.addAll(vis.params());
        params.add(p.userId());
        params.addAll(p.groups());
        return " FROM ACT_RU_TASK t WHERE " + vis.sql() + " AND (t.ASSIGNEE_ = ? OR EXISTS (SELECT 1 FROM"
                + " ACT_RU_IDENTITYLINK l WHERE l.TASK_ID_ = t.ID_ AND l.TYPE_ = 'candidate' AND l.GROUP_ID_ IN ("
                + String.join(",", Collections.nCopies(p.groups().size(), "?")) + ")))";
    }

    private long vi4Count(AuthorizationPolicy policy, AuthorizationPolicy.Principal p) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*)" + inboxWhere(policy, p, params);
        return adapter.inEngineTransaction(c -> query(c, sql, params, rs -> {
            rs.next();
            return rs.getLong(1);
        }));
    }

    private List<String> vi4List(AuthorizationPolicy policy, AuthorizationPolicy.Principal p) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT t.ID_, t.NAME_, t.CREATE_TIME_" + inboxWhere(policy, p, params)
                + " ORDER BY t.CREATE_TIME_ DESC, t.ID_ DESC LIMIT 50";
        return adapter.inEngineTransaction(c -> query(c, sql, params, rs -> {
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
        long[] ns = new long[100];
        for (int i = 0; i < 100; i++) {
            long t = System.nanoTime();
            q.run();
            ns[i] = System.nanoTime() - t;
        }
        java.util.Arrays.sort(ns);
        return Map.of("p50Ms", ns[49] / 1e6, "p95Ms", ns[95] / 1e6, "maxMs", ns[99] / 1e6);
    }

    // ------------------------------------------------------------------------------------------------ GP-40

    @Test
    void gp40_suspendResume() {
        scenario("GP-40", ctx -> {
            deploy("court", "gp40-stay.bpmn");
            String p = start("court", "gp40Proceeding", "SCN-2026-9", m("noticee", "M/s Ganga Traders"));
            eq(ctx, days(30), earliestTimerDue(p), "SLA timer due");
            clock(days(10));
            suspend(p);
            yes(ctx, isSuspended(p), "suspended");
            boolean rejected;
            try {
                completeTask(p, "gp40_hearing", m());
                rejected = false;
            } catch (AssertionError e) {
                throw e;
            } catch (RuntimeException expected) {
                rejected = true;
                ctx.detail("completeWhileSuspended", "REJECTED: " + first(expected));
            }
            yes(ctx, rejected, "completing a task of a stayed proceeding must fail");
            try {
                correlate("court", "replyReceived", "SCN-2026-9", m("reply", "x"));
                rejected = false;
            } catch (RuntimeException expected) {
                rejected = true;
                ctx.detail("correlateWhileSuspended", "REJECTED: " + first(expected));
            }
            yes(ctx, rejected, "message to a stayed proceeding must not be accepted");
            clock(days(31));
            jobs();
            eq(ctx, 0, tasksIn(p, "gp40_breach").size(), "SLA timer does not fire during the stay");
            clock(days(40));
            activate(p);
            yes(ctx, !isSuspended(p), "activated");
            Instant newDue = days(60);
            rescheduleTimers(p, newDue);
            eq(ctx, newDue, earliestTimerDue(p), "timer re-dated");
            jobs();
            eq(ctx, 0, tasksIn(p, "gp40_breach").size(), "no breach right after resume");
            clock(days(60).plusSeconds(60));
            jobs();
            eq(ctx, 1, tasksIn(p, "gp40_breach").size(), "breach at the extended date");
            completeTask(p, "gp40_hearing", m());
            correlate("court", "replyReceived", "SCN-2026-9", m("reply", "Denied"));
            completeTask(p, "gp40_breach", m());
            yes(ctx, ended(p), "proceeding ended");
        });
    }
}
