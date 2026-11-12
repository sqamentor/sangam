// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.EngineAdapter.TaskQuery;
import org.sangam.core.api.EngineAdapter.TaskView;
import org.sangam.integration.connector.file.LocalDirectoryTransport;
import org.sangam.integration.sim.Fault;
import org.sangam.integration.sim.pfms.PfmsFiles;
import org.sangam.integration.sim.pfms.PfmsSftpSimulator;

/**
 * Port of spike E17-b ({@code spike/experiments-gov-b/.../E17DomainTest}): government domain end-to-end models on the
 * running Sangam server, PostgreSQL scale, same scenarios and assertions. The PFMS simulator is Sangam's own port
 * ({@code sangam-integration sim.pfms}).
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class E17bDomainIT extends GovBSupport {

    // ================================================================================================ D-RECRUIT

    record App(int i, String no, String category, boolean pwd, boolean esm, int score) {
    }

    record Merit(Map<String, String> selected, List<String> waitlist) {
    }

    @Test
    void d1_recruit() {
        scenario("D-RECRUIT", ctx -> {
            deploy("psc", "recruit.bpmn", "reservation-pools.dmn");
            String psc = tn("psc");
            int n = 2000;
            String[] cats = {"UR", "UR", "OBC", "OBC", "SC", "ST", "EWS", "UR", "OBC", "SC"};
            List<App> apps = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                apps.add(new App(i, String.format("APP-%06d", i), cats[i % cats.length], i % 25 == 3, i % 40 == 5,
                        (int) ((i * 7919L) % 997)));
            }
            Map<String, String> pid = new ConcurrentHashMap<>();
            Map<String, App> byPid = new ConcurrentHashMap<>();
            String notice = start("psc", "recruitNotice", "ADVT-2026-01", m("applicationsCloseAt", iso(days(30)), "posts", 40));
            completeTask(notice, "rn_approve", m("published", true));
            parallel(n, i -> {
                App a = apps.get(i);
                String p = start("psc", "recruitApplication", a.no(), m("name", "Candidate " + i, "category", a.category(),
                        "pwd", a.pwd(), "exServiceman", a.esm(), "advt", "ADVT-2026-01"));
                pid.put(a.no(), p);
                byPid.put(p, a);
            });
            List<App> paying = apps.stream().filter(a -> a.i() % 20 != 13).toList();
            parallel(paying.size(), k -> correlate("psc", "feePaid", paying.get(k).no(),
                    m("feeRef", "PG-" + paying.get(k).no(), "feeAmount", 100L)));
            clock(days(8));
            jobs();
            eq(ctx, n - paying.size(), finishedByEndActivity("psc", "recruitApplication", "ra_endLapsed"), "fee lapsed");
            List<TaskView> scrutiny = adapter.queryTasks(new TaskQuery(psc, null, List.of("rec-scrutiny"), null,
                    "ra_scrutiny", 0, 0));
            eq(ctx, paying.size(), scrutiny.size(), "scrutiny inbox");
            parallel(scrutiny.size(), k -> {
                TaskView tv = scrutiny.get(k);
                adapter.completeTask(tv.id(), m("eligible", byPid.get(tv.processInstanceId()).i() % 30 != 17));
            });
            List<App> eligible = paying.stream().filter(a -> a.i() % 30 != 17).toList();
            clock(days(31));
            jobs();
            completeTask(notice, "rn_exam", m("examHeld", true));
            parallel(eligible.size(), k -> correlate("psc", "examScore", eligible.get(k).no(),
                    m("score", eligible.get(k).score())));
            Map<String, List<String>> pools = new ConcurrentHashMap<>();
            parallel(eligible.size(), k -> pools.put(eligible.get(k).no(),
                    collected(var(pid.get(eligible.get(k).no()), "reservationPools"))));
            Merit merit = merit(eligible, pools, new LinkedHashMap<>(Map.of("UR", 16, "OBC", 11, "SC", 6, "ST", 3, "EWS", 4)),
                    2, 10);
            eq(ctx, 40, merit.selected().size(), "all 40 posts filled");
            long pwdSel = merit.selected().keySet().stream().filter(no -> pools.get(no).contains("PWD")).count();
            yes(ctx, pwdSel >= 2, "PwD horizontal reservation met");
            completeTask(notice, "rn_result", m("meritApproved", true));
            yes(ctx, ended(notice), "notice ended");
            parallel(eligible.size(), k -> {
                String no = eligible.get(k).no();
                String outcome = merit.selected().containsKey(no) ? "SELECTED"
                        : merit.waitlist().contains(no) ? "WAITLIST" : "NOT_SELECTED";
                correlate("psc", "resultDeclared", no, m("outcome", outcome, "pool", merit.selected().getOrDefault(no, "-")));
            });
            eq(ctx, eligible.size() - 50, finishedByEndActivity("psc", "recruitApplication", "ra_endNotSelected"),
                    "not selected");
            ConcurrentLinkedQueue<String> waitlist = new ConcurrentLinkedQueue<>(merit.waitlist());
            List<String> sel = new ArrayList<>(merit.selected().keySet());
            for (int k = 0; k < sel.size(); k++) {
                String p = pid.get(sel.get(k));
                completeTask(p, "ra_dv", m("dvOk", k != 5));
                if (k != 5) {
                    completeTask(p, "ra_offer", m("accepted", k != 11 && k != 23));
                }
            }
            jobs();
            eq(ctx, 3, runningInstances("psc", "recruitPromote"), "one promotion instance per vacancy");
            int promoted = 0;
            for (ExternalWork w : fetchAll("rec-promoter", "recruit-promote", 10)) {
                String next = waitlist.poll();
                correlate("psc", "waitlistPromoted", next, m("vacancyOf", w.processInstanceId()));
                adapter.completeExternal(w.id(), "rec-promoter", w.lockVersion(), m("promoted", next));
                String p = pid.get(next);
                completeTask(p, "ra_dv", m("dvOk", true));
                completeTask(p, "ra_offer", m("accepted", true));
                promoted++;
            }
            jobs();
            eq(ctx, 3, promoted, "promotions");
            List<TaskView> appoint = adapter.queryTasks(new TaskQuery(psc, null, List.of("rec-appointing"), null,
                    "ra_appoint", 0, 0));
            eq(ctx, 40, appoint.size(), "40 appointment orders");
            for (TaskView tv : appoint) {
                adapter.completeTask(tv.id(), m("orderNo", "AO-" + tv.processInstanceId()));
            }
            eq(ctx, 40, finishedByEndActivity("psc", "recruitApplication", "ra_endAppointed"), "appointed");
            eq(ctx, 3, finishedByEndActivity("psc", "recruitApplication", "ra_endDeclined"), "declined");
            eq(ctx, 7, runningInstances("psc", "recruitApplication"), "7 still on the waitlist");
        });
    }

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
        List<String> reserved = List.of("OBC", "SC", "ST", "EWS");
        for (String pool : reserved) {
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
                String pool = pools.get(a.no()).contains("UR") && pools.get(a.no()).stream().noneMatch(reserved::contains)
                        ? "UR" : pools.get(a.no()).stream().filter(reserved::contains).findFirst().orElse("UR");
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
        scenario("D-GRIEVANCE", ctx -> {
            deploy("grv", "grievance-case.cmmn", "grievance-sla.bpmn");
            long slaBefore = runningInstances("grv", "grievanceSla");
            String a = startCase("grv", "grievanceCase", "PMOPG/E/2026/0001",
                    m("complainant", "Sunita", "subject", "Pension not credited"));
            eq(ctx, slaBefore + 1, runningInstances("grv", "grievanceSla"), "SLA watch started with the case");
            completeTask(a, "PI_examine", m("examinedBy", "SO"));
            for (int k = 0; k < 2; k++) {
                startCaseItem(enabledCaseItems(a, "PI_seekReport").get(0));
                completeTask(a, "PI_seekReport", m("report" + k, "Field report " + k));
            }
            startCaseItem(enabledCaseItems(a, "PI_forward").get(0));
            completeTask(a, "PI_forward", m("forwardedTo", "District Treasury"));
            startCaseItem(enabledCaseItems(a, "PI_hearing").get(0));
            completeTask(a, "PI_hearing", m("heard", true));
            completeTask(a, "PI_dispose", m("reply", "Arrears credited on 20-11-2026"));
            yes(ctx, ended(a), "case closed after disposal");
            List<String> slaOfA = processInstancesOfCase(a);
            eq(ctx, 1, slaOfA.size(), "one SLA process for the case");
            if (!ended(slaOfA.get(0))) {
                ctx.detail("exitCriterionEndsCalledProcess", "NO: case closed, called SLA process still running");
                adapter.cancelInstance(slaOfA.get(0), "grievance disposed");
                ctx.workaround("on case closure the product cancels the case's still-running called processes");
            } else {
                ctx.detail("exitCriterionEndsCalledProcess", "YES");
            }
            yes(ctx, ended(slaOfA.get(0)), "SLA process ended");
            eq(ctx, slaBefore, runningInstances("grv", "grievanceSla"), "no SLA watch left");

            String b = startCase("grv", "grievanceCase", "PMOPG/E/2026/0002", m("complainant", "Iqbal"));
            completeTask(b, "PI_examine", m());
            clock(days(20));
            jobs();
            eq(ctx, 0, forGroups("grv", "grv-senior").size(), "no escalation at 20 days");
            clock(days(22));
            jobs();
            List<TaskView> senior = forGroups("grv", "grv-senior");
            eq(ctx, 1, senior.size(), "escalated to the senior officer after 21 days");
            adapter.completeTask(senior.get(0).id(), m("direction", "Dispose within 7 days"));
            completeTask(b, "PI_dispose", m("reply", "Disposed after escalation"));
            yes(ctx, ended(b), "case B closed");

            var closed = instance(a);
            yes(ctx, closed.ended(), "case A read from history");
            Object reply = closed.variables().get("reply");
            ctx.detail("closedCaseVariablesFromHistory", reply == null ? "NO (reply missing)" : "YES");
            String c = startCase("grv", "grievanceCase", "PMOPG/E/2026/0001/R1",
                    m("reopenOf", a, "previousReply", String.valueOf(reply), "complainant", "Sunita"));
            completeTask(c, "PI_examine", m());
            completeTask(c, "PI_dispose", m("reply", "Re-examined; balance arrears credited"));
            yes(ctx, ended(c), "reopened case closed");
            for (String sla : processInstancesOfCase(c)) {
                if (!ended(sla)) {
                    adapter.cancelInstance(sla, "grievance disposed");
                }
            }
            eq(ctx, slaBefore, runningInstances("grv", "grievanceSla"), "no SLA watch left for closed cases");
        });
    }

    // ================================================================================================ D-RTI

    @Test
    void d3_rti() {
        scenario("D-RTI", ctx -> {
            deploy("pa-a", "rti.bpmn");
            deploy("pa-b", "rti.bpmn");
            String r1 = start("pa-a", "rtiRequest", "RTI/A/1", m("lifeLiberty", false));
            clock(days(10));
            completeTask(r1, "rti_pio", m("pioDecision", "SUPPLY"));
            clock(days(41));
            jobs();
            yes(ctx, ended(r1), "R1 closed");
            eq(ctx, 1, historicActivityCount(r1, "rti_endClosed"), "R1 closed end");
            eq(ctx, 0, historicActivityCount(r1, "rti_deemed"), "R1 no deemed refusal");

            clock(T0);
            String r2 = start("pa-a", "rtiRequest", "RTI/A/2", m("lifeLiberty", false));
            clock(days(3));
            completeTask(r2, "rti_pio", m("pioDecision", "TRANSFER", "transferTo", "pa-b"));
            yes(ctx, ended(r2), "R2 transferred");
            String b2 = start("pa-b", "rtiRequest", "RTI/A/2", m("lifeLiberty", false, "transferredFrom", r2,
                    "transferredFromAuthority", "pa-a"));
            eq(ctx, days(33), earliestTimerDue(b2), "authority B: 30 days from its receipt");
            eq(ctx, 1, forGroups("pa-b", "pio").size(), "B PIO inbox");
            clock(days(20));
            completeTask(b2, "rti_pio", m("pioDecision", "REJECT", "section", "8(1)(j)"));
            correlate("pa-b", "firstAppealFiled", "RTI/A/2", m("appealDate", iso(days(25))));
            yes(ctx, ended(b2), "B2 ended (appealed)");
            String fa = start("pa-b", "rtiFirstAppeal", "RTI/A/2/FA", m("requestInstanceId", b2,
                    "originalAuthorityInstanceId", instance(r2).id()));
            clock(days(45));
            completeTask(fa, "rfa_decide", m("faaOrder", "REMANDED"));
            yes(ctx, ended(fa), "FAA decided within 30 days: no extension needed");

            clock(T0);
            String r3 = start("pa-a", "rtiRequest", "RTI/A/3", m("lifeLiberty", true));
            clock(hours(50));
            jobs();
            eq(ctx, 1, tasksIn(r3, "rti_deemed").size(), "48 hours passed: deemed refusal");
            completeTask(r3, "rti_pio", m("pioDecision", "SUPPLY"));
            completeTask(r3, "rti_deemed", m("noted", true));

            clock(T0);
            String r4 = start("pa-a", "rtiRequest", "RTI/A/4", m("lifeLiberty", false));
            clock(days(31));
            jobs();
            eq(ctx, 1, tasksIn(r4, "rti_deemed").size(), "R4 deemed refusal");
            String fa4 = start("pa-a", "rtiFirstAppeal", "RTI/A/4/FA", m("requestInstanceId", r4,
                    "ground", "deemed refusal s.7(2)"));
            clock(days(62));
            jobs();
            eq(ctx, 1, tasksIn(fa4, "rfa_recordReasons").size(), "FAA beyond 30 days must record reasons");
            completeTask(fa4, "rfa_decide", m("faaOrder", "DIRECTED_TO_SUPPLY"));
            completeTask(fa4, "rfa_recordReasons", m("reasons", "Third-party notice u/s 11"));
            completeTask(r4, "rti_deemed", m("noted", true));
            completeTask(r4, "rti_pio", m("pioDecision", "SUPPLY"));
            correlate("pa-a", "firstAppealFiled", "RTI/A/4", m());
            yes(ctx, ended(r4) && ended(fa4), "R4 and its appeal ended");
            eq(ctx, 1, historicByBusinessKey("pa-b", "rtiRequest", "RTI/A/2").size(), "transfer chain visible in history");
        });
    }

    // ================================================================================================ D-LEAVE

    @Test
    void d4_leave() {
        scenario("D-LEAVE", ctx -> {
            deploy("hr", "leave.bpmn", "leave-routing.dmn");
            int n = 500;
            Map<String, Integer> balance = new ConcurrentHashMap<>();
            String[] types = {"CL", "EL", "EL", "HPL", "EOL"};
            int[] dayOpts = {1, 2, 3, 5, 8, 12, 16, 25, 40};
            Map<String, Integer> expect = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                String emp = "EMP-" + (i % 60);
                int bal = balance.computeIfAbsent(emp, e -> 30);
                String type = types[i % types.length];
                int d = dayOpts[(i * 7) % dayOpts.length];
                start("hr", "leaveApplication", "LV-" + i, m("employee", emp, "leaveType", type, "days", d, "balance", bal));
                String exp = d > bal ? "lv_endNoBalance" : "EOL".equals(type) ? "HOD" : d <= 3 ? "lv_endAuto"
                        : d <= 15 ? "CONTROLLING_OFFICER" : "HOD";
                expect.merge(exp, 1, Integer::sum);
                if ("lv_endAuto".equals(exp)) {
                    balance.put(emp, bal - d);
                }
            }
            int approved = 0;
            int rejected = 0;
            for (String seat : List.of("CONTROLLING_OFFICER", "HOD")) {
                List<TaskView> inbox = forGroups("hr", seat);
                eq(ctx, expect.getOrDefault(seat, 0), inbox.size(), seat + " inbox");
                for (int k = 0; k < inbox.size(); k++) {
                    boolean ok = k % 9 != 0;
                    adapter.claimTask(inbox.get(k).id(), seat.toLowerCase() + "-1");
                    adapter.completeTask(inbox.get(k).id(), m("sanctioned", ok));
                    if (ok) {
                        approved++;
                    } else {
                        rejected++;
                    }
                }
            }
            eq(ctx, expect.getOrDefault("lv_endAuto", 0), finishedByEndActivity("hr", "leaveApplication", "lv_endAuto"), "auto");
            eq(ctx, expect.getOrDefault("lv_endNoBalance", 0),
                    finishedByEndActivity("hr", "leaveApplication", "lv_endNoBalance"), "no balance");
            eq(ctx, approved, finishedByEndActivity("hr", "leaveApplication", "lv_endApproved"), "approved");
            eq(ctx, rejected, finishedByEndActivity("hr", "leaveApplication", "lv_endRejected"), "rejected");
            eq(ctx, 0, runningInstances("hr", "leaveApplication"), "nothing left running");
        });
    }

    // ================================================================================================ D-DBT-SCHEME

    @Test
    void d5_dbtScheme() {
        scenario("D-DBT-SCHEME", ctx -> {
            Path root = Files.createTempDirectory("pfms-sim-");
            try (PfmsSftpSimulator pfms = new PfmsSftpSimulator(new LocalDirectoryTransport(root))) {
                pfms.failAccount("00000000000");
                deploy("scheme", "dbt-scheme.bpmn");
                Map<String, String> pid = new LinkedHashMap<>();
                String[][] bens = {{"BEN-1", "Ram Prasad", "11111111111"}, {"BEN-2", "Kamla Devi", "00000000000"},
                        {"BEN-3", "Abdul Karim", "33333333333"}, {"BEN-4", "Lakshmi", "44444444444"}};
                for (String[] b : bens) {
                    String p = start("scheme", "dbtScheme", b[0], m("beneficiary", b[1], "accountNo", b[2],
                            "ifsc", "SBIN0001234"));
                    pid.put(b[0], p);
                    completeTask(p, "dbt_verify", m("eligible", !"BEN-4".equals(b[0])));
                    if (!"BEN-4".equals(b[0])) {
                        completeTask(p, "dbt_sanction", m("sanctionedAmount", 6000L));
                    }
                }
                yes(ctx, ended(pid.get("BEN-4")), "ineligible closes without payment");
                AtomicInteger batch = new AtomicInteger();
                pay(pfms, batch, "BEN-3");
                eq(ctx, 1, tasksIn(pid.get("BEN-2"), "dbt_correct").size(), "account invalid -> correction");
                yes(ctx, ended(pid.get("BEN-1")), "BEN-1 paid");
                yes(ctx, !ended(pid.get("BEN-3")), "unknown outcome waits for reconciliation, not a blind retry");
                reconcile(pfms, batch);
                yes(ctx, ended(pid.get("BEN-3")), "reconciled as paid");
                completeTask(pid.get("BEN-2"), "dbt_correct", m("accountNo", "22222222222"));
                pay(pfms, batch, null);
                yes(ctx, ended(pid.get("BEN-2")), "BEN-2 paid after correction");
                eq(ctx, 3, pfms.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT), "three payments, none twice");
                yes(ctx, !pfms.ledger().hasDuplicates(PfmsSftpSimulator.EFFECT_PAYMENT), "no duplicate payment");
                eq(ctx, 1, pfms.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT_FAILED), "one failed payment");
                String failedOp = pfms.ledger().ofType(PfmsSftpSimulator.EFFECT_PAYMENT_FAILED).get(0).key();
                yes(ctx, pfms.ledger().ofType(PfmsSftpSimulator.EFFECT_PAYMENT).stream()
                        .noneMatch(e -> e.key().equals(failedOp)), "corrected payment used a new operationId");
            }
        });
    }

    private void pay(PfmsSftpSimulator pfms, AtomicInteger batch, String dropFor) throws Exception {
        for (ExternalWork w : fetchAll("pfms-worker", "dbt-payment", 10)) {
            Map<String, Object> v = w.variables();
            String bk = String.valueOf(instance(w.processInstanceId()).businessKey());
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
            adapter.completeExternal(w.id(), "pfms-worker", w.lockVersion(), m("paymentStatus", status,
                    "operationId", op, "pfmsRef", ref == null ? "" : ref));
        }
        jobs();
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
            adapter.completeExternal(w.id(), "recon-worker", w.lockVersion(), m("paymentStatus", status,
                    "reconciledBy", "re-submission: " + reason));
        }
        jobs();
    }

    // ================================================================================================ D-REFERRAL

    @Test
    void d6_interDepartmentReferral() {
        scenario("D-REFERRAL", ctx -> {
            // SL-13: the callee is deployed in the caller's tenant; the same key in other tenants is never reached
            deploy("dept-fin", "concurrence.bpmn");
            deploy("dept-law", "concurrence.bpmn");
            deploy("dept-works", "concurrence.bpmn");
            deploy("dept-works", "referral.bpmn");
            String p = start("dept-works", "interDeptReferral", "PWD/BLDG/2026/45", m("fileNo", "PWD/BLDG/2026/45",
                    "subject", "New district court complex", "amount", 182_000_000L));
            completeTask(p, "ref_prepare", m());
            List<String> children = subProcessInstances(p);
            eq(ctx, 2, children.size(), "two called instances");
            eq(ctx, Set.of(tn("dept-works")), new HashSet<>(children.stream().map(this::instanceTenant).toList()),
                    "callees run in the caller's tenant (SL-13)");
            eq(ctx, 0, forGroups("dept-fin", "concurrence-officer").size() + forGroups("dept-law", "concurrence-officer").size(),
                    "nothing in another tenant (SL-13)");
            List<TaskView> referred = forGroups("dept-works", "concurrence-officer");
            eq(ctx, 2, referred.size(), "both referrals visible to the concurring group");
            // Finance receives the amount (in-mapping), Law does not
            TaskView fin = referred.stream().filter(t -> t.variables().containsKey("amount")).findFirst().orElseThrow();
            TaskView law = referred.stream().filter(t -> !t.variables().containsKey("amount")).findFirst().orElseThrow();
            eq(ctx, "New district court complex", law.variables().get("subject"), "in-mapping");
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
            eq(ctx, 0, forGroups("dept-fin").size() + forGroups("dept-law").size(), "no task ever in Finance/Law tenants");
        });
    }
}
