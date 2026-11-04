package org.sangam.spike.govb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.EngineAdapter.TaskQuery;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.govb.support.Env;
import org.sangam.spike.govb.support.Gov;
import org.sangam.spike.govb.support.GovTestBase;
import org.sangam.spike.govb.support.Report;
import org.sangam.spike.sim.Fault;
import org.sangam.spike.sim.pfms.LocalDirectoryTransport;
import org.sangam.spike.sim.pfms.PfmsFiles;
import org.sangam.spike.sim.pfms.PfmsSftpSimulator;

/** E17-b: domain end-to-end models on the engine selected by {@code gov.engine}; results go to results/E17-b.json. */
class E17DomainTest extends GovTestBase {

    static final Report REPORT = new Report("E17-b", "Government domain end-to-end models on both engines");

    @Override
    protected Report report() {
        return REPORT;
    }

    @Override
    protected String dbName() {
        return "e17b";
    }

    private String ck(String planItemId, String humanTaskId) {
        return g.operaton() ? planItemId : humanTaskId;
    }

    // ================================================================================================ D-RECRUIT

    record App(int i, String no, String category, boolean pwd, boolean esm, int score) {
    }

    @Test
    void d1_recruit() {
        REPORT.run("D-RECRUIT", "Recruitment: notification to appointment", r -> {
            r.feature("timer catch on a date variable (application window)", "event-based gateway (fee callback vs 7-day "
                    + "lapse)", "bulk message correlation", "bulk task completion", "DMN COLLECT per candidate",
                    "signal throw + signal start (one promotion instance per vacancy)", "external task (promotion)",
                    "message catch for waitlist promotion");
            g.deploy("psc", "recruit.bpmn", "reservation-pools.dmn");
            int n = Env.scale(2000, 300);
            String[] cats = {"UR", "UR", "OBC", "OBC", "SC", "ST", "EWS", "UR", "OBC", "SC"};
            List<App> apps = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                apps.add(new App(i, String.format("APP-%06d", i), cats[i % cats.length], i % 25 == 3, i % 40 == 5,
                        (int) ((i * 7919L) % 997)));
            }
            Map<String, String> pid = new ConcurrentHashMap<>();
            Map<String, App> byPid = new ConcurrentHashMap<>();
            Map<String, Object> phase = new LinkedHashMap<>();

            String notice = g.start("psc", "recruitNotice", "ADVT-2026-01",
                    m("applicationsCloseAt", iso(days(30)), "posts", 40));
            g.complete(notice, "rn_approve", m("published", true));

            long t = System.nanoTime();
            long retries = g.parallel(n, i -> {
                App a = apps.get(i);
                String p = g.start("psc", "recruitApplication", a.no(), m("name", "Candidate " + i,
                        "category", a.category(), "pwd", a.pwd(), "exServiceman", a.esm(), "advt", "ADVT-2026-01"));
                pid.put(a.no(), p);
                byPid.put(p, a);
            });
            phase.put("1_applicationsMs", Gov.ms(t));

            t = System.nanoTime();
            List<App> paying = apps.stream().filter(a -> a.i() % 20 != 13).toList();
            retries += g.parallel(paying.size(), k -> g.correlate("psc", "feePaid", paying.get(k).no(),
                    m("feeRef", "PG-" + paying.get(k).no(), "feeAmount", 100L)));
            phase.put("2_feeCallbacksMs", Gov.ms(t));
            g.clock(days(8));
            t = System.nanoTime();
            g.jobs();
            phase.put("3_feeLapseTimersMs", Gov.ms(t));
            assertEquals(n - paying.size(), g.nat().finishedByEndActivity("recruitApplication", "ra_endLapsed"));

            t = System.nanoTime();
            List<TaskView> scrutiny = g.adapter().queryTasks(new TaskQuery("psc", null, List.of("rec-scrutiny"), null,
                    "ra_scrutiny", 0, 0));
            phase.put("4a_scrutinyInboxQueryMs", Gov.ms(t));
            assertEquals(paying.size(), scrutiny.size());
            t = System.nanoTime();
            retries += g.parallel(scrutiny.size(), k -> {
                TaskView tv = scrutiny.get(k);
                g.adapter().completeTask(tv.id(), m("eligible", byPid.get(tv.processInstanceId()).i() % 30 != 17));
            });
            phase.put("4b_bulkScrutinyMs", Gov.ms(t));
            List<App> eligible = paying.stream().filter(a -> a.i() % 30 != 17).toList();

            g.clock(days(31));
            g.jobs();
            g.complete(notice, "rn_exam", m("examHeld", true));
            t = System.nanoTime();
            retries += g.parallel(eligible.size(), k -> g.correlate("psc", "examScore", eligible.get(k).no(),
                    m("score", eligible.get(k).score())));
            phase.put("5_examScoresWithDmnMs", Gov.ms(t));

            t = System.nanoTime();
            Map<String, List<String>> pools = new ConcurrentHashMap<>();
            g.parallel(eligible.size(), k -> pools.put(eligible.get(k).no(),
                    collected(g.var(pid.get(eligible.get(k).no()), "reservationPools"), "pool")));
            phase.put("6a_readPoolsMs", Gov.ms(t));
            t = System.nanoTime();
            Map<String, Integer> seats = new LinkedHashMap<>(Map.of("UR", 16, "OBC", 11, "SC", 6, "ST", 3, "EWS", 4));
            Merit merit = merit(eligible, pools, seats, 2, 10);
            phase.put("6b_meritListMs", Gov.ms(t));
            assertEquals(40, merit.selected().size(), "all 40 posts filled");
            long pwdSel = merit.selected().keySet().stream().filter(no -> pools.get(no).contains("PWD")).count();
            assertTrue(pwdSel >= 2, "PwD horizontal reservation met");
            g.complete(notice, "rn_result", m("meritApproved", true));
            assertTrue(g.ended(notice));

            t = System.nanoTime();
            retries += g.parallel(eligible.size(), k -> {
                String no = eligible.get(k).no();
                String outcome = merit.selected().containsKey(no) ? "SELECTED"
                        : merit.waitlist().contains(no) ? "WAITLIST" : "NOT_SELECTED";
                g.correlate("psc", "resultDeclared", no, m("outcome", outcome,
                        "pool", merit.selected().getOrDefault(no, "-")));
            });
            phase.put("7_resultDeclaredMs", Gov.ms(t));
            assertEquals(eligible.size() - 50, g.nat().finishedByEndActivity("recruitApplication", "ra_endNotSelected"));

            // DV and offers: 1 fails DV, 2 decline -> 3 vacancies -> 3 promotions from the waitlist
            t = System.nanoTime();
            ConcurrentLinkedQueue<String> waitlist = new ConcurrentLinkedQueue<>(merit.waitlist());
            List<String> sel = new ArrayList<>(merit.selected().keySet());
            for (int k = 0; k < sel.size(); k++) {
                String p = pid.get(sel.get(k));
                g.complete(p, "ra_dv", m("dvOk", k != 5));
                if (k != 5) {
                    g.complete(p, "ra_offer", m("accepted", k != 11 && k != 23));
                }
            }
            g.jobs();
            assertEquals(3, g.nat().runningInstances("recruitPromote"), "one promotion instance per vacancy");
            int promoted = 0;
            for (ExternalWork w : fetchAll("rec-promoter", "recruit-promote", 10)) {
                String next = waitlist.poll();
                g.correlate("psc", "waitlistPromoted", next, m("vacancyOf", w.processInstanceId()));
                g.adapter().completeExternal(w.id(), "rec-promoter", w.lockVersion(), m("promoted", next));
                String p = pid.get(next);
                g.complete(p, "ra_dv", m("dvOk", true));
                g.complete(p, "ra_offer", m("accepted", true));
                promoted++;
            }
            g.jobs();
            assertEquals(3, promoted);
            List<TaskView> appoint = g.adapter().queryTasks(new TaskQuery("psc", null, List.of("rec-appointing"), null,
                    "ra_appoint", 0, 0));
            assertEquals(40, appoint.size(), "40 appointment orders");
            for (TaskView tv : appoint) {
                g.adapter().completeTask(tv.id(), m("orderNo", "AO-" + tv.processInstanceId()));
            }
            phase.put("8_dvOffersPromotionAppointmentMs", Gov.ms(t));
            assertEquals(40, g.nat().finishedByEndActivity("recruitApplication", "ra_endAppointed"));
            assertEquals(3, g.nat().finishedByEndActivity("recruitApplication", "ra_endDeclined"));
            assertEquals(7, g.nat().runningInstances("recruitApplication"), "7 still on the waitlist");
            r.timings(phase);
            r.scale("applications", n).scale("feePaid", paying.size()).scale("eligible", eligible.size())
                    .scale("posts", 40).scale("waitlist", 10).scale("promotions", promoted)
                    .scale("conflictRetries", retries).scale("threads", Env.THREADS);
        });
    }

    record Merit(Map<String, String> selected, List<String> waitlist) {
    }

    /** Product merit list: open merit (UR) first, then reserved pools, then horizontal PwD; waitlist = next by score. */
    private static Merit merit(List<App> eligible, Map<String, List<String>> pools, Map<String, Integer> seats,
                               int pwdSeats, int waitlistSize) {
        List<App> byScore = new ArrayList<>(eligible);
        byScore.sort(Comparator.comparingInt(App::score).reversed().thenComparing(App::no));
        Map<String, String> selected = new LinkedHashMap<>();
        int ur = seats.get("UR");
        for (App a : byScore) {
            if (ur > 0 && pools.get(a.no()).contains("UR")) {
                selected.put(a.no(), "UR");
                ur--;
            }
        }
        for (String pool : List.of("OBC", "SC", "ST", "EWS")) {
            int left = seats.get(pool);
            for (App a : byScore) {
                if (left > 0 && !selected.containsKey(a.no()) && pools.get(a.no()).contains(pool)) {
                    selected.put(a.no(), pool);
                    left--;
                }
            }
        }
        long pwdIn = selected.keySet().stream().filter(no -> pools.get(no).contains("PWD")).count();
        for (App a : byScore) {
            if (pwdIn >= pwdSeats) {
                break;
            }
            if (!selected.containsKey(a.no()) && pools.get(a.no()).contains("PWD")) {
                // replace the lowest-scored non-PwD candidate of the same pool (horizontal adjustment)
                String pool = pools.get(a.no()).contains("UR") && !pools.get(a.no()).stream()
                        .anyMatch(p -> List.of("OBC", "SC", "ST", "EWS").contains(p)) ? "UR"
                        : pools.get(a.no()).stream().filter(p -> List.of("OBC", "SC", "ST", "EWS").contains(p))
                        .findFirst().orElse("UR");
                String drop = null;
                for (int k = byScore.size() - 1; k >= 0; k--) {
                    String no = byScore.get(k).no();
                    if (pool.equals(selected.get(no)) && !pools.get(no).contains("PWD")) {
                        drop = no;
                        break;
                    }
                }
                if (drop != null) {
                    selected.remove(drop);
                    selected.put(a.no(), pool);
                    pwdIn++;
                }
            }
        }
        List<String> waitlist = new ArrayList<>();
        for (App a : byScore) {
            if (waitlist.size() < waitlistSize && !selected.containsKey(a.no())) {
                waitlist.add(a.no());
            }
        }
        return new Merit(selected, waitlist);
    }

    // ================================================================================================ D-GRIEVANCE

    @Test
    void d2_grievance() {
        REPORT.run("D-GRIEVANCE", "Grievance case: ad-hoc actions, escalation, reopen", r -> {
            r.feature("CMMN case plan, autoComplete + requiredRule", "manual activation (ad-hoc), repetition",
                    "blocking processTask -> BPMN timer escalation", "exitCriterion on the process task", "milestone",
                    "reopen as a linked new case with history lookup");
            g.deploy("grv", "grievance-case.cmmn", "grievance-sla.bpmn");
            long slaBefore = g.nat().runningInstances("grievanceSla");

            // A: resolved within time, with ad-hoc actions
            String a = g.adapter().startCase("grv", "grievanceCase", "PMOPG/E/2026/0001",
                    m("complainant", "Sunita", "subject", "Pension not credited"));
            assertEquals(slaBefore + 1, g.nat().runningInstances("grievanceSla"), "SLA watch started with the case");
            g.complete(a, ck("PI_examine", "grv_examine"), m("examinedBy", "SO"));
            for (int k = 0; k < 2; k++) {
                g.nat().startCaseItem(g.nat().enabledCaseItems(a, "PI_seekReport").get(0));
                g.complete(a, ck("PI_seekReport", "grv_seekReport"), m("report" + k, "Field report " + k));
            }
            g.nat().startCaseItem(g.nat().enabledCaseItems(a, "PI_forward").get(0));
            g.complete(a, ck("PI_forward", "grv_forward"), m("forwardedTo", "District Treasury"));
            g.nat().startCaseItem(g.nat().enabledCaseItems(a, "PI_hearing").get(0));
            g.complete(a, ck("PI_hearing", "grv_hearing"), m("heard", true));
            g.complete(a, ck("PI_dispose", "grv_dispose"), m("reply", "Arrears credited on 20-11-2026"));
            assertTrue(g.ended(a), "case closed after disposal");
            List<String> slaOfA = g.nat().processInstancesOfCase(a);
            assertEquals(1, slaOfA.size());
            if (!g.ended(slaOfA.get(0))) {
                r.sub("exit criterion on a blocking processTask ends the called BPMN instance",
                        "NO: case closed, called SLA process still running (would escalate a disposed grievance)");
                g.adapter().cancelInstance(slaOfA.get(0), "grievance disposed");
                r.workaround("on case closure the product finds the case's called processes (native "
                        + "superCaseInstanceId query) and cancels those still running", 6);
            } else {
                r.sub("exit criterion on a blocking processTask ends the called BPMN instance", "YES");
            }
            assertTrue(g.ended(slaOfA.get(0)), "SLA process ended");
            assertEquals(slaBefore, g.nat().runningInstances("grievanceSla"));

            // B: not disposed in 21 days -> escalation to the senior officer
            String b = g.adapter().startCase("grv", "grievanceCase", "PMOPG/E/2026/0002", m("complainant", "Iqbal"));
            g.complete(b, ck("PI_examine", "grv_examine"), m());
            g.clock(days(20));
            g.jobs();
            assertEquals(0, g.forGroups("grv", "grv-senior").size());
            g.clock(days(22));
            g.jobs();
            List<TaskView> senior = g.forGroups("grv", "grv-senior");
            assertEquals(1, senior.size(), "escalated to the senior officer after 21 days");
            g.adapter().completeTask(senior.get(0).id(), m("direction", "Dispose within 7 days"));
            g.complete(b, ck("PI_dispose", "grv_dispose"), m("reply", "Disposed after escalation"));
            assertTrue(g.ended(b));

            // C: complainant not satisfied with A -> reopen (linked new case)
            var closed = g.instance(a);
            assertTrue(closed.ended());
            Object reply = closed.variables().get("reply");
            r.sub("closed case variables readable from history", reply == null ? "NO (reply missing)" : "YES");
            String c = g.adapter().startCase("grv", "grievanceCase", "PMOPG/E/2026/0001/R1",
                    m("reopenOf", a, "previousReply", String.valueOf(reply), "complainant", "Sunita"));
            g.complete(c, ck("PI_examine", "grv_examine"), m());
            g.complete(c, ck("PI_dispose", "grv_dispose"), m("reply", "Re-examined; balance arrears credited"));
            assertTrue(g.ended(c));
            for (String sla : g.nat().processInstancesOfCase(c)) {
                if (!g.ended(sla)) {
                    g.adapter().cancelInstance(sla, "grievance disposed");   // same workaround as case A
                }
            }
            assertEquals(slaBefore, g.nat().runningInstances("grievanceSla"), "no SLA watch left for closed cases");
            r.note("Escalation uses a BLOCKING CMMN processTask running a BPMN timer process (identical model on both "
                    + "engines) because Operaton 2.2.0 ignores CMMN timerEventListener (E16-b GP-21 sub-check).");
        });
    }

    // ================================================================================================ D-RTI

    @Test
    void d3_rti() {
        REPORT.run("D-RTI", "RTI request: 30-day / 48-hour clock, transfer, first appeal", r -> {
            r.feature("non-interrupting timer boundary (s.7(1) 30 days / 48 h from a ternary)", "deemed refusal branch",
                    "transfer u/s 6(3) = new instance in the other authority's tenant", "event-based gateway appeal window",
                    "first appeal as linked instance with history lookup", "FAA 30-day timer");
            g.deploy("pa-a", "rti.bpmn");
            g.deploy("pa-b", "rti.bpmn");
            // R1: normal, answered on day 10, no appeal -> closed after the 30-day window
            String r1 = g.start("pa-a", "rtiRequest", "RTI/A/1", m("lifeLiberty", false));
            g.clock(days(10));
            g.complete(r1, "rti_pio", m("pioDecision", "SUPPLY"));
            g.clock(days(41));
            g.jobs();
            assertTrue(g.ended(r1));
            assertEquals(1, g.nat().historicActivityCount(r1, "rti_endClosed"));
            assertEquals(0, g.nat().historicActivityCount(r1, "rti_deemed"));

            // R2: transferred on day 3 to authority B; B's clock starts on B's receipt
            g.clock(T0);
            String r2 = g.start("pa-a", "rtiRequest", "RTI/A/2", m("lifeLiberty", false));
            g.clock(days(3));
            g.complete(r2, "rti_pio", m("pioDecision", "TRANSFER", "transferTo", "pa-b"));
            assertTrue(g.ended(r2));
            String b2 = g.start("pa-b", "rtiRequest", "RTI/A/2", m("lifeLiberty", false, "transferredFrom", r2,
                    "transferredFromAuthority", "pa-a"));
            assertEquals(days(33), g.nat().earliestTimerDue(b2), "authority B: 30 days from its receipt");
            assertEquals(1, g.forGroups("pa-b", "pio").size());
            g.clock(days(20));
            g.complete(b2, "rti_pio", m("pioDecision", "REJECT", "section", "8(1)(j)"));
            g.correlate("pa-b", "firstAppealFiled", "RTI/A/2", m("appealDate", iso(days(25))));
            assertTrue(g.ended(b2));
            var transferredRecord = g.instance(r2);
            String fa = g.start("pa-b", "rtiFirstAppeal", "RTI/A/2/FA", m("requestInstanceId", b2,
                    "originalAuthorityInstanceId", transferredRecord.id()));
            g.clock(days(45));
            g.complete(fa, "rfa_decide", m("faaOrder", "REMANDED"));
            assertTrue(g.ended(fa), "FAA decided within 30 days: no extension needed");

            // R3: life or liberty: 48 hours
            g.clock(T0);
            String r3 = g.start("pa-a", "rtiRequest", "RTI/A/3", m("lifeLiberty", true));
            g.clock(hours(50));
            g.jobs();
            assertEquals(1, g.tasks(null, r3, "rti_deemed").size(), "48 hours passed: deemed refusal");
            g.complete(r3, "rti_pio", m("pioDecision", "SUPPLY"));
            g.complete(r3, "rti_deemed", m("noted", true));

            // R4: no reply in 30 days -> deemed refusal -> first appeal on deemed refusal, FAA overdue
            g.clock(T0);
            String r4 = g.start("pa-a", "rtiRequest", "RTI/A/4", m("lifeLiberty", false));
            g.clock(days(31));
            g.jobs();
            assertEquals(1, g.tasks(null, r4, "rti_deemed").size());
            String fa4 = g.start("pa-a", "rtiFirstAppeal", "RTI/A/4/FA", m("requestInstanceId", r4,
                    "ground", "deemed refusal s.7(2)"));
            g.clock(days(62));
            g.jobs();
            assertEquals(1, g.tasks(null, fa4, "rfa_recordReasons").size(), "FAA beyond 30 days must record reasons");
            g.complete(fa4, "rfa_decide", m("faaOrder", "DIRECTED_TO_SUPPLY"));
            g.complete(fa4, "rfa_recordReasons", m("reasons", "Third-party notice u/s 11"));
            g.complete(r4, "rti_deemed", m("noted", true));
            g.complete(r4, "rti_pio", m("pioDecision", "SUPPLY"));
            g.correlate("pa-a", "firstAppealFiled", "RTI/A/4", m());
            assertTrue(g.ended(r4) && g.ended(fa4));
            assertEquals(1, g.nat().historicByBusinessKey("rtiRequest", "RTI/A/2").stream()
                    .filter(id -> "pa-b".equals(g.nat().instanceTenant(id))).count(), "transfer chain visible in history");
            r.scale("requests", 5).scale("appeals", 2);
        });
    }

    // ================================================================================================ D-LEAVE

    @Test
    void d4_leave() {
        REPORT.run("D-LEAVE", "Leave: balance check, auto-approve below threshold, else route", r -> {
            r.feature("gateway on balance", "DMN FIRST routing", "candidate group from DMN output", "bulk officer inbox");
            g.deploy("hr", "leave.bpmn", "leave-routing.dmn");
            int n = Env.scale(500, 200);
            Map<String, Integer> balance = new ConcurrentHashMap<>();
            String[] types = {"CL", "EL", "EL", "HPL", "EOL"};
            int[] dayOpts = {1, 2, 3, 5, 8, 12, 16, 25, 40};
            Map<String, Integer> expect = new LinkedHashMap<>();
            Map<String, Object> phase = new LinkedHashMap<>();
            long t = System.nanoTime();
            List<String> pids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String emp = "EMP-" + (i % 60);
                int bal = balance.computeIfAbsent(emp, e -> 30);
                String type = types[i % types.length];
                int d = dayOpts[(i * 7) % dayOpts.length];
                String p = g.start("hr", "leaveApplication", "LV-" + i, m("employee", emp, "leaveType", type,
                        "days", d, "balance", bal));
                pids.add(p);
                String exp = d > bal ? "lv_endNoBalance" : "EOL".equals(type) ? "HOD" : d <= 3 ? "lv_endAuto"
                        : d <= 15 ? "CONTROLLING_OFFICER" : "HOD";
                expect.merge(exp, 1, Integer::sum);
                if ("lv_endAuto".equals(exp)) {
                    balance.put(emp, bal - d);
                }
            }
            phase.put("applyMs", Gov.ms(t));
            t = System.nanoTime();
            int approved = 0;
            int rejected = 0;
            for (String seat : List.of("CONTROLLING_OFFICER", "HOD")) {
                List<TaskView> inbox = g.forGroups("hr", seat);
                assertEquals((int) expect.getOrDefault(seat, 0), inbox.size(), seat + " inbox");
                for (int k = 0; k < inbox.size(); k++) {
                    boolean ok = k % 9 != 0;
                    g.adapter().claimTask(inbox.get(k).id(), seat.toLowerCase() + "-1");
                    g.adapter().completeTask(inbox.get(k).id(), m("sanctioned", ok));
                    if (ok) {
                        approved++;
                    } else {
                        rejected++;
                    }
                }
            }
            phase.put("officerDecisionsMs", Gov.ms(t));
            assertEquals((long) expect.getOrDefault("lv_endAuto", 0),
                    g.nat().finishedByEndActivity("leaveApplication", "lv_endAuto"));
            assertEquals((long) expect.getOrDefault("lv_endNoBalance", 0),
                    g.nat().finishedByEndActivity("leaveApplication", "lv_endNoBalance"));
            assertEquals(approved, g.nat().finishedByEndActivity("leaveApplication", "lv_endApproved"));
            assertEquals(rejected, g.nat().finishedByEndActivity("leaveApplication", "lv_endRejected"));
            assertEquals(0, g.nat().runningInstances("leaveApplication"));
            r.timings(phase);
            r.scale("applications", n).scale("outcomes", expect.toString()).scale("approvedByOfficers", approved)
                    .scale("rejectedByOfficers", rejected);
        });
    }

    // ================================================================================================ D-DBT-SCHEME

    @Test
    void d5_dbtScheme() throws Exception {
        REPORT.run("D-DBT-SCHEME", "Scheme: application to DBT payment with unknown outcome", r -> {
            r.feature("user tasks (verification, sanction)", "external task: PFMS payment file (simulator)",
                    "operationId = UUIDv3(tenant|instance|activityInstanceId)", "UNKNOWN outcome -> reconciliation worker",
                    "FAILED -> correction loop with a NEW operationId", "exclusive gateway loop");
            Path root = Files.createTempDirectory("pfms-sim-");
            try (PfmsSftpSimulator pfms = new PfmsSftpSimulator(new LocalDirectoryTransport(root))) {
                pfms.failAccount("00000000000");
                g.deploy("scheme", "dbt-scheme.bpmn");
                Map<String, String> pid = new LinkedHashMap<>();
                String[][] bens = {{"BEN-1", "Ram Prasad", "11111111111"}, {"BEN-2", "Kamla Devi", "00000000000"},
                        {"BEN-3", "Abdul Karim", "33333333333"}, {"BEN-4", "Lakshmi", "44444444444"}};
                for (String[] b : bens) {
                    String p = g.start("scheme", "dbtScheme", b[0], m("beneficiary", b[1], "accountNo", b[2],
                            "ifsc", "SBIN0001234"));
                    pid.put(b[0], p);
                    g.complete(p, "dbt_verify", m("eligible", !"BEN-4".equals(b[0])));
                    if (!"BEN-4".equals(b[0])) {
                        g.complete(p, "dbt_sanction", m("sanctionedAmount", 6000L));
                    }
                }
                assertTrue(g.ended(pid.get("BEN-4")), "ineligible closes without payment");
                AtomicInteger batch = new AtomicInteger();
                pay(pfms, batch, "BEN-3");                         // BEN-3's status file is lost
                assertEquals(1, g.tasks(null, pid.get("BEN-2"), "dbt_correct").size(), "account invalid -> correction");
                assertTrue(g.ended(pid.get("BEN-1")));
                assertFalse(g.ended(pid.get("BEN-3")), "unknown outcome waits for reconciliation, not a blind retry");
                reconcile(pfms, batch);
                assertTrue(g.ended(pid.get("BEN-3")), "reconciled as paid");
                g.complete(pid.get("BEN-2"), "dbt_correct", m("accountNo", "22222222222"));
                pay(pfms, batch, null);
                assertTrue(g.ended(pid.get("BEN-2")));
                assertEquals(3, pfms.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT), "three payments, none twice");
                assertFalse(pfms.ledger().hasDuplicates(PfmsSftpSimulator.EFFECT_PAYMENT));
                assertEquals(1, pfms.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT_FAILED));
                String failedOp = pfms.ledger().ofType(PfmsSftpSimulator.EFFECT_PAYMENT_FAILED).get(0).key();
                assertTrue(pfms.ledger().ofType(PfmsSftpSimulator.EFFECT_PAYMENT).stream()
                        .noneMatch(e -> e.key().equals(failedOp)), "corrected payment used a new operationId");
                r.scale("payments", pfms.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT))
                        .scale("failedPayments", pfms.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT_FAILED))
                        .scale("paymentFiles", batch.get());
                r.note("Reconciliation relies on the simulator's class (a) behaviour (idempotent by operationId: a "
                        + "re-sent operationId is reported DUPLICATE_OPERATION_ID, not paid again).");
            }
        });
    }

    private void pay(PfmsSftpSimulator pfms, AtomicInteger batch, String dropFor) throws Exception {
        for (ExternalWork w : fetchAll("pfms-worker", "dbt-payment", 10)) {
            Map<String, Object> v = w.variables();
            String bk = String.valueOf(g.instance(w.processInstanceId()).businessKey());
            String op = UUID.nameUUIDFromBytes(("scheme|" + w.processInstanceId() + "|" + w.activityInstanceId())
                    .getBytes(StandardCharsets.UTF_8)).toString();
            String batchId = "B" + batch.incrementAndGet();
            var file = new PfmsFiles.PaymentFile("SSID01", "PMKSY", batchId, List.of(new PfmsFiles.PaymentLine(op, bk,
                    String.valueOf(v.get("beneficiary")), String.valueOf(v.get("accountNo")),
                    String.valueOf(v.get("ifsc")), new BigDecimal(String.valueOf(v.get("sanctionedAmount"))).setScale(2))));
            if (bk.equals(dropFor)) {
                pfms.faults().once(Fault.dropResponse());
            }
            pfms.transport().write(PfmsSftpSimulator.OUTBOUND, file.fileName(), file.toCsv().getBytes(StandardCharsets.UTF_8));
            pfms.pollOnce();
            String status = "UNKNOWN";
            String ref = null;
            String sts = "STS_" + batchId + "_1.csv";
            if (pfms.transport().exists(PfmsSftpSimulator.INBOUND, sts)) {
                var s = PfmsFiles.parseStatus(new String(pfms.transport().read(PfmsSftpSimulator.INBOUND, sts),
                        StandardCharsets.UTF_8));
                status = s.lines().get(0).status();
                ref = s.lines().get(0).pfmsRef();
            }
            g.adapter().completeExternal(w.id(), "pfms-worker", w.lockVersion(), m("paymentStatus", status,
                    "operationId", op, "pfmsRef", ref == null ? "" : ref));
        }
        g.jobs();
    }

    private void reconcile(PfmsSftpSimulator pfms, AtomicInteger batch) throws Exception {
        for (ExternalWork w : fetchAll("recon-worker", "dbt-reconcile", 10)) {
            Map<String, Object> v = w.variables();
            String op = String.valueOf(v.get("operationId"));
            String batchId = "B" + batch.incrementAndGet();
            var file = new PfmsFiles.PaymentFile("SSID01", "PMKSY", batchId, List.of(new PfmsFiles.PaymentLine(op,
                    "RECON", String.valueOf(v.get("beneficiary")), String.valueOf(v.get("accountNo")),
                    String.valueOf(v.get("ifsc")), new BigDecimal(String.valueOf(v.get("sanctionedAmount"))).setScale(2))));
            pfms.transport().write(PfmsSftpSimulator.OUTBOUND, file.fileName(), file.toCsv().getBytes(StandardCharsets.UTF_8));
            pfms.pollOnce();
            var s = PfmsFiles.parseStatus(new String(pfms.transport().read(PfmsSftpSimulator.INBOUND,
                    "STS_" + batchId + "_1.csv"), StandardCharsets.UTF_8));
            String reason = s.lines().get(0).reason();
            String status = "DUPLICATE_OPERATION_ID".equals(reason) ? "SUCCESS" : s.lines().get(0).status();
            g.adapter().completeExternal(w.id(), "recon-worker", w.lockVersion(), m("paymentStatus", status,
                    "reconciledBy", "re-submission: " + reason));
        }
        g.jobs();
    }

    // ================================================================================================ D-REFERRAL

    @Test
    void d6_interDepartmentReferral() {
        REPORT.run("D-REFERRAL", "Inter-department referral (call activities to two other tenants)", r -> {
            r.feature("parallel call activities to definitions of two OTHER tenants", "in/out mapping with renaming",
                    "parallel join", "loop back on non-concurrence", "tenant-scoped inboxes");
            g.deploy("dept-fin", "concurrence.bpmn");
            g.deploy("dept-law", "concurrence.bpmn");
            g.deploy("dept-works", "referral.bpmn");
            Map<String, Object> vars = m("fileNo", "PWD/BLDG/2026/45", "subject", "New district court complex",
                    "amount", 182_000_000L);
            if (!g.operaton()) {
                vars.put("finConcurrenceDefId", g.nat().processDefinitionId("concurrence", "dept-fin"));
                vars.put("lawConcurrenceDefId", g.nat().processDefinitionId("concurrence", "dept-law"));
                r.workaround("callee by definition id (calledElementType=id) looked up per department and passed at start",
                        4);
            }
            int hook = g.nat().calledProcessesRunInCalleeTenant();
            if (hook > 0) {
                r.workaround("called instances re-tenanted to the callee definition's tenant (StartProcessInstanceInterceptor,"
                        + " see E16-b GP-32)", hook);
            }
            String p = g.start("dept-works", "interDeptReferral", "PWD/BLDG/2026/45", vars);
            long t = System.nanoTime();
            g.complete(p, "ref_prepare", m());
            List<String> children = g.nat().subProcessInstances(p);
            assertEquals(2, children.size());
            assertEquals(java.util.Set.of("dept-fin", "dept-law"),
                    new java.util.HashSet<>(children.stream().map(id -> g.nat().instanceTenant(id)).toList()));
            TaskView fin = g.forGroups("dept-fin", "concurrence-officer").get(0);
            TaskView law = g.forGroups("dept-law", "concurrence-officer").get(0);
            assertEquals(0, g.forGroups("dept-works", "concurrence-officer").size());
            assertEquals("New district court complex", law.variables().get("subject"));
            g.adapter().completeTask(fin.id(), m("concurred", true, "remarks", "Within budget head 4059"));
            g.adapter().completeTask(law.id(), m("concurred", false, "remarks", "Land title to be verified"));
            assertEquals("Land title to be verified", g.var(p, "lawRemarks"));
            assertEquals(1, g.tasks(null, p, "ref_prepare").size(), "returned for revision");
            g.complete(p, "ref_prepare", m("revision", 1));
            g.adapter().completeTask(g.forGroups("dept-fin", "concurrence-officer").get(0).id(),
                    m("concurred", true, "remarks", "Concurred"));
            g.adapter().completeTask(g.forGroups("dept-law", "concurrence-officer").get(0).id(),
                    m("concurred", true, "remarks", "Vetted"));
            g.complete(p, "ref_approve", m("approvedBy", "Secretary PWD"));
            assertTrue(g.ended(p));
            assertEquals(4, g.nat().subProcessInstances(p).size(), "two referral rounds x two departments");
            r.time("referralRoundTripsMs", Gov.ms(t));
        });
    }
}
