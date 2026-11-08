// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.EngineAdapter.TaskView;

/**
 * Port of Stage B E17-c ({@code spike/experiments-gov-c/.../E17cDomainTest}): domain end-to-end models on the Sangam
 * server, same models, data and checks. See {@link GovCSupport} for the shared-context differences.
 */
class E17cDomainIT extends GovCSupport {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static Instant plusMonths(Instant t, int months) {
        return ZonedDateTime.ofInstant(t, IST).plusMonths(months).toInstant();
    }

    // ================================================================== D-BANK-GUARANTEE
    @Test
    void bankGuarantee() {
        scenario("D-BANK-GUARANTEE", ctx -> {
            deploy("d-bg-contract.bpmn", "gp41-bg.bpmn");
            Instant t0 = now;
            String con1 = b("CON-1");
            String p = adapter.startProcess(null, "d-bg-contract", con1, vars("bgAmount", 300_000L,
                    "bgExpiry", date(t0.plus(Duration.ofDays(400))), "reminderAt", date(t0.plus(Duration.ofDays(370)))));
            complete(ctx, p, "dbg_issueLoA", "contracts", null);
            List<String> kids = subInstances(p);
            ctx.require("BG child started", kids.size() == 1);
            String bg = kids.get(0);
            ctx.check("child carries the contract number as business key", con1.equals(adapter.getInstance(bg).businessKey()));
            ctx.check("bank confirmation reaches the child by business key", bg.equals(adapter.correlateMessage(null, "bgConfirmed", con1, vars("genuine", true))));
            clock(t0.plus(Duration.ofDays(200)));
            complete(ctx, p, "dbg_execute", "ee", vars("dlpEnd", date(t0.plus(Duration.ofDays(565)))));
            clock(t0.plus(Duration.ofDays(371)));
            adapter.executeDueJobs();
            complete(ctx, bg, "gp41_requestExtension", "contracts", null);
            adapter.correlateMessage(null, "bgExtended", con1,
                    vars("bgExpiry", date(t0.plus(Duration.ofDays(700))), "reminderAt", date(t0.plus(Duration.ofDays(670)))));
            ctx.check("extended BG watched until +670d", dueIs(timerAt(bg, "gp41_reminderDue"), t0.plus(Duration.ofDays(670))));
            clock(t0.plus(Duration.ofDays(566)));
            adapter.executeDueJobs();
            complete(ctx, p, "dbg_finalBill", "accounts", null);
            ctx.check("contract waits for the security branch", tasks(p, "dbg_close").isEmpty());
            adapter.correlateMessage(null, "contractClosed", con1, null);
            complete(ctx, bg, "gp41_release", "accounts", null);
            complete(ctx, p, "dbg_close", "contracts", null);
            ctx.check("contract 1 closed, BG released", ended(p) && visited(bg, "gp41_end_released"));

            Instant t1 = now;
            String con2 = b("CON-2");
            String q = adapter.startProcess(null, "d-bg-contract", con2, vars("bgAmount", 300_000L,
                    "bgExpiry", date(t1.plus(Duration.ofDays(300))), "reminderAt", date(t1.plus(Duration.ofDays(270)))));
            complete(ctx, q, "dbg_issueLoA", "contracts", null);
            String bg2 = subInstances(q).get(0);
            adapter.correlateMessage(null, "bgConfirmed", con2, vars("genuine", true));
            clock(t1.plus(Duration.ofDays(271)));
            adapter.executeDueJobs();
            complete(ctx, bg2, "gp41_requestExtension", "contracts", null);
            clock(t1.plus(Duration.ofDays(301)));
            adapter.executeDueJobs();
            complete(ctx, bg2, "gp41_encash", "accounts", null);
            ctx.check("BG encashed, contract still running", visited(bg2, "gp41_end_encashed") && !ended(q));
            complete(ctx, q, "dbg_execute", "ee", vars("dlpEnd", date(now.plus(Duration.ofDays(365)))));
            advance(Duration.ofDays(366));
            adapter.executeDueJobs();
            complete(ctx, q, "dbg_finalBill", "accounts", null);
            ctx.check("release request after encashment is refused (no BG instance waiting)",
                    throwsOn(() -> adapter.correlateMessage(null, "contractClosed", con2, null)));
            complete(ctx, q, "dbg_close", "contracts", null);
            ctx.check("contract 2 closed", ended(q));

            String r = adapter.startProcess(null, "d-bg-contract", b("CON-3"), vars("bgAmount", 1L,
                    "bgExpiry", date(now.plus(Duration.ofDays(300))), "reminderAt", date(now.plus(Duration.ofDays(270)))));
            complete(ctx, r, "dbg_issueLoA", "contracts", null);
            String bg3 = subInstances(r).get(0);
            adapter.cancelInstance(r, "tender cancelled");
            ctx.check("cancelled contract removed its BG child", ended(r) && (adapter.getInstance(bg3) == null || ended(bg3))
                    && runningInstances("gp41-bg").isEmpty());
        });
    }

    // ================================================================== D-CONTRACT-VARIATION
    @Test
    void contractVariation() {
        scenario("D-CONTRACT-VARIATION", ctx -> {
            deploy("d-works-contract.bpmn", "d-variation.bpmn");
            Instant t0 = now;
            Instant stipulated = t0.plus(Duration.ofDays(365));
            String wo = b("WO-2027-17");
            String p = adapter.startProcess(null, "d-works-contract", wo,
                    vars("completionDate", date(stipulated), "contractValue", 10_000_000L, "ldWaived", false));
            clock(t0.plus(Duration.ofDays(300)));
            Instant eot = stipulated.plus(Duration.ofDays(60));
            adapter.correlateMessage(null, "variationProposed", wo,
                    vars("varType", "EOT", "days", 60, "amount", 0L, "proposedCompletion", date(eot)));
            String v1 = subInstances(p).get(0);
            complete(ctx, v1, "dvar_examine", "ee", null);
            ctx.check("EoT of 60 days stays at SE level", tasks(v1, "dvar_ceApprove").isEmpty() && tasks(v1, "dvar_seRecommend").isEmpty());
            complete(ctx, v1, "dvar_seApprove", "se", vars("approved", true, "approvedCompletion", date(eot), "approvedAmount", 0L,
                    "approvalLevel", "SE"));
            ctx.check("completion date of the running contract moved", var(p, "completionDate") instanceof Date d && d.toInstant().equals(eot));
            ctx.check("LD timer still at the old date until the engine is told", dueIs(timerAt(p, "dcv_ldStart"), stipulated));
            recalculateTimerFromModel(timerAt(p, "dcv_ldStart").jobId());
            ctx.check("LD timer re-armed to the EoT date", dueIs(timerAt(p, "dcv_ldStart"), eot));
            adapter.correlateMessage(null, "variationProposed", wo,
                    vars("varType", "EXTRA_ITEM", "days", 0, "amount", 600_000L, "proposedCompletion", date(eot)));
            String v2 = subInstances(p).get(0);
            complete(ctx, v2, "dvar_examine", "ee", null);
            complete(ctx, v2, "dvar_seRecommend", "se", null);
            complete(ctx, v2, "dvar_ceApprove", "ce", vars("approved", true, "approvedCompletion", date(eot), "approvedAmount", 600_000L,
                    "approvalLevel", "CE"));
            ctx.check("6 % extra item went to the CE and raised the value to 10,600,000",
                    visited(v2, "dvar_ceApprove") && num(var(p, "contractValue")) == 10_600_000L);
            complete(ctx, p, "dcv_extraItem", "ee", null);
            clock(eot.plus(Duration.ofDays(1)));
            adapter.executeDueJobs();
            ctx.check("LD accrual notice the day after the EoT date", tasks(p, "dcv_ldAccrues").size() == 1);
            clock(eot.plus(Duration.ofDays(10)));
            long daysLate = Duration.between(((Date) var(p, "completionDate")).toInstant(), now).toDays();
            long cv = num(var(p, "contractValue"));
            long ld = Math.min(cv * 5 * daysLate / 10_000, cv / 10);
            ctx.detail("daysLate", daysLate);
            ctx.detail("ldComputed", ld);
            ctx.check("LD = 0.05 %/day x 10 days x 10,600,000 = 53,000", ld == 53_000L);
            complete(ctx, p, "dcv_ldAccrues", "ee", null);
            complete(ctx, p, "dcv_execute", "ee", vars("actualCompletion", date(now)));
            adapter.correlateMessage(null, "variationProposed", wo,
                    vars("varType", "LD_WAIVER", "days", 0, "amount", ld, "proposedCompletion", date(eot)));
            String v3 = subInstances(p).get(0);
            complete(ctx, v3, "dvar_examine", "ee", null);
            complete(ctx, v3, "dvar_seApprove", "se", vars("approved", true, "approvedCompletion", date(eot), "approvedAmount", 0L,
                    "approvalLevel", "SE"));
            boolean waived = Boolean.TRUE.equals(var(p, "ldWaived"));
            ctx.check("LD waived on the running contract", waived);
            complete(ctx, p, "dcv_finalBill", "accounts", vars("ldRecovered", waived ? 0L : ld));
            ctx.check("contract closed with LD 0 after waiver", ended(p) && num(var(p, "ldRecovered")) == 0);
            ctx.check("three variation children ran", countVisits(p, "dcv_variation") == 3);
            ctx.check("history keeps the stipulated and the EoT completion dates", variableHistory(p, "completionDate").size() >= 2);
            ctx.check("history keeps the value before and after the extra item", variableHistory(p, "contractValue").size() >= 2);
        });
    }

    // ================================================================== D-COURT-CASE
    @Test
    void courtCase() {
        scenario("D-COURT-CASE", ctx -> {
            deploy("d-court-case.bpmn", "d-impugned.bpmn");
            Instant t0 = now;
            String imp = adapter.startProcess(null, "d-impugned", b("REC-ORDER-9"), Map.of());
            String wp = b("WP-4411-2027");
            String p = adapter.startProcess(null, "d-court-case", wp, vars("impugnedInstance", imp));
            complete(ctx, p, "dcc_examine", "nodal-officer", null);
            complete(ctx, p, "dcc_lawVetting", "law-ministry", null);
            adapter.correlateMessage(null, "hearingListed", wp,
                    vars("nextHearing", date(t0.plus(Duration.ofDays(30))), "briefDue", date(t0.plus(Duration.ofDays(23)))));
            advance(Duration.ofDays(3));
            adapter.correlateMessage(null, "hearingRescheduled", wp,
                    vars("nextHearing", date(t0.plus(Duration.ofDays(40))), "briefDue", date(t0.plus(Duration.ofDays(33)))));
            recalculateTimerFromModel(timerAt(p, "dcc_briefDue").jobId());
            ctx.check("after reschedule: hearing +40d (model), counter due +33d (native)",
                    dueIs(timerAt(p, "dcc_hearingDay"), t0.plus(Duration.ofDays(40))) && dueIs(timerAt(p, "dcc_briefDue"), t0.plus(Duration.ofDays(33))));
            clock(t0.plus(Duration.ofDays(34)));
            adapter.executeDueJobs();
            ctx.check("counter overdue on +33d -> JS (Legal)", tasks(p, "dcc_escalate").size() == 1);
            complete(ctx, p, "dcc_escalate", "js-legal", null);
            complete(ctx, p, "dcc_counter", "law-officer", null);
            clock(t0.plus(Duration.ofDays(41)));
            adapter.executeDueJobs();
            complete(ctx, p, "dcc_hearing", "law-officer", vars("order", "STAY",
                    "nextHearing", date(t0.plus(Duration.ofDays(90))), "briefDue", date(t0.plus(Duration.ofDays(83)))));
            TaskView impTask = single(ctx, imp, "dimp_implement");
            complete(ctx, p, "dcc_stay", "department", null);
            suspendInstance(imp);
            ctx.check("stay: impugned action suspended, cannot be implemented",
                    isSuspended(imp) && throwsOn(() -> adapter.completeTask(impTask.id(), Map.of())));
            complete(ctx, p, "dcc_counter", "law-officer", null);
            clock(t0.plus(Duration.ofDays(91)));
            adapter.executeDueJobs();
            complete(ctx, p, "dcc_hearing", "law-officer", vars("order", "FINAL"));
            complete(ctx, p, "dcc_decideAppeal", "law-ministry", vars("decision", "APPEAL"));
            complete(ctx, p, "dcc_fileAppeal", "law-officer", vars("nextHearing", date(t0.plus(Duration.ofDays(150))),
                    "briefDue", date(t0.plus(Duration.ofDays(143)))));
            ctx.check("appeal: new hearing series armed at +150d", dueIs(timerAt(p, "dcc_hearingDay"), t0.plus(Duration.ofDays(150))));
            complete(ctx, p, "dcc_counter", "law-officer", null);
            clock(t0.plus(Duration.ofDays(151)));
            adapter.executeDueJobs();
            complete(ctx, p, "dcc_hearing", "law-officer", vars("order", "FINAL"));
            complete(ctx, p, "dcc_decideAppeal", "law-ministry", vars("decision", "COMPLY", "complianceDue", date(t0.plus(Duration.ofDays(181)))));
            activateInstance(imp);
            ctx.check("stay vacated: impugned action active again", !isSuspended(imp));
            clock(t0.plus(Duration.ofDays(182)));
            adapter.executeDueJobs();
            ctx.check("compliance overdue raises contempt risk", tasks(p, "dcc_contemptRisk").size() == 1);
            complete(ctx, p, "dcc_contemptRisk", "js-legal", null);
            complete(ctx, p, "dcc_comply", "department", null);
            complete(ctx, imp, "dimp_implement", "department", null);
            ctx.check("case closed after 3 hearings and 1 appeal", ended(p) && countVisits(p, "dcc_hearing") == 3
                    && countVisits(p, "dcc_fileAppeal") == 1 && ended(imp));
        });
    }

    // ================================================================== D-STORES-VERIFICATION
    @Test
    void storesVerification() {
        scenario("D-STORES-VERIFICATION", ctx -> {
            deploy("d-stores-verification.bpmn", "d-stores-discrepancy.bpmn");
            sql("drop table if exists govc_stock");
            sql("create table govc_stock(item int primary key, qty int)");
            int[] book = {50, 12, 8, 100, 3, 40};
            int[] physical = {50, 12, 5, 100, 3, 39};
            for (int i = 0; i < book.length; i++) {
                sql("insert into govc_stock values (" + i + ", " + book[i] + ")");
            }
            clock(Instant.parse("2027-03-31T04:31:00Z"));
            adapter.executeDueJobs();
            List<String> camp = runningInstances("d-stores-verification");
            ctx.require("campaign started on 31 March", camp.size() == 1);
            String c = camp.get(0);
            work(ctx, c, "stock-register", vars("itemCount", book.length));
            for (TaskView tv : tasks(c, "dsv_verify")) {
                String ex = taskExecutionId(tv.id());
                int i = ((Number) getVariable(ex, "loopCounter")).intValue();
                setVariablesLocal(ex, vars("bookQty", book[i], "physicalQty", physical[i], "itemNo", i));
                adapter.claimTask(tv.id(), "board");
                adapter.completeTask(tv.id(), Map.of());
            }
            int opened = 0;
            for (ExternalWork w : fetch("open-discrepancy", 10)) {
                int item = ((Number) w.variables().get("itemNo")).intValue();
                adapter.startProcess(null, "d-stores-discrepancy", b("DISC-2027-" + item), vars("itemNo", item,
                        "shortBy", ((Number) w.variables().get("bookQty")).intValue() - ((Number) w.variables().get("physicalQty")).intValue()));
                completeWork(w, Map.of());
                opened++;
            }
            ctx.check("2 discrepancy cases (items 2 and 5)", opened == 2);
            complete(ctx, c, "dsv_report", "hod", null);
            List<Timer> next = startTimers("d-stores-verification");
            ctx.detail("nextCampaignTimers", next);
            ctx.check("campaign closed, next one armed for 2028-03-31", ended(c) && next.size() == 1
                    && dueIs(next.get(0), Instant.parse("2028-03-31T04:30:00Z")));
            List<String> d2l = historicInstancesByBusinessKey("d-stores-discrepancy", b("DISC-2027-2"));
            List<String> d5l = historicInstancesByBusinessKey("d-stores-discrepancy", b("DISC-2027-5"));
            ctx.require("discrepancy cases for items 2 and 5 exist", d2l.size() == 1 && d5l.size() == 1);
            String d2 = d2l.get(0);
            String d5 = d5l.get(0);
            complete(ctx, d2, "dsd_investigate", "stores-officer", null);
            List<TaskView> board = tasks(d2, "dsd_board");
            ctx.check("survey board: 3 members must sign", board.size() == 3);
            for (int k = 0; k < board.size(); k++) {
                adapter.claimTask(board.get(k).id(), "member-" + k);
                adapter.completeTask(board.get(k).id(), vars("writeOff", true));
            }
            ExternalWork adj = fetchOne(ctx, d2, "stock-ledger-adjust");
            adapter.inEngineTransaction(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("update govc_stock set qty = qty - ? where item = ?")) {
                    ps.setInt(1, ((Number) adj.variables().get("shortBy")).intValue());
                    ps.setInt(2, ((Number) adj.variables().get("itemNo")).intValue());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
                completeWork(adj, Map.of());
                return null;
            });
            complete(ctx, d2, "dsd_responsibility", "hod", null);
            ctx.check("item 2 written off: ledger 8 -> 5, responsibility fixed", ended(d2) && count("select qty from govc_stock where item = 2") == 5);
            complete(ctx, d5, "dsd_investigate", "stores-officer", null);
            for (TaskView tv : tasks(d5, "dsd_board")) {
                adapter.claimTask(tv.id(), "m");
                adapter.completeTask(tv.id(), vars("writeOff", false));
            }
            complete(ctx, d5, "dsd_recover", "accounts", null);
            ctx.check("item 5 recovered, ledger unchanged (40)", ended(d5) && count("select qty from govc_stock where item = 5") == 40);
        });
    }

    // ================================================================== D-LAND-ACQUISITION
    @Test
    void landAcquisition() {
        scenario("D-LAND-ACQUISITION", ctx -> {
            deploy("d-land-acquisition.bpmn");
            String a = adapter.startProcess(null, "d-land-acquisition", b("LA-1"), vars("landholderCount", 4));
            complete(ctx, a, "dla_sia", "sia-unit", null);
            complete(ctx, a, "dla_appraisal", "expert-group", vars("siaLapse", date(plusMonths(now, 12))));
            ctx.check("s11 window: 12 calendar months from the appraisal", dueIs(timerAt(a, "dla_siaLapse"), plusMonths(now, 12)));
            advance(Duration.ofDays(120));
            Instant s11 = now;
            complete(ctx, a, "dla_s11", "collector", vars("objectionEnd", date(s11.plus(Duration.ofDays(60))),
                    "s19Lapse", date(plusMonths(s11, 12))));
            ctx.check("4 landholder windows open, 60 days", messageSubscriptions(a, "s15Objection").size() == 4
                    && dueIs(timerAt(a, "dla_windowClosed"), s11.plus(Duration.ofDays(60))));
            advance(Duration.ofDays(20));
            ctx.check("landholder 1 objects", deliverToBranch(a, "s15Objection", 1, vars("objection", "compensation too low")));
            ctx.check("landholder 3 objects", deliverToBranch(a, "s15Objection", 3, vars("objection", "not a public purpose")));
            advance(Duration.ofDays(41));
            adapter.executeDueJobs();
            ctx.check("window closed: 2 objected, 2 silent", countVisits(a, "dla_oEndObj") == 2 && countVisits(a, "dla_oEndSilent") == 2);
            complete(ctx, a, "dla_s15Hearing", "collector", null);
            ctx.check("s19 lapse date = s11 + 12 months", dueIs(timerAt(a, "dla_s19Lapse"), plusMonths(s11, 12)));
            advance(Duration.ofDays(150));
            Instant s19 = now;
            complete(ctx, a, "dla_s19", "government", vars("awardLapse", date(plusMonths(s19, 12))));
            ctx.check("award lapse date = s19 + 12 months", dueIs(timerAt(a, "dla_awardLapse"), plusMonths(s19, 12)));
            advance(Duration.ofDays(200));
            complete(ctx, a, "dla_s23", "collector", null);
            complete(ctx, a, "dla_s31", "collector", null);
            ctx.check("acquisition completed through s31", ended(a) && visited(a, "dla_end"));

            String bb = adapter.startProcess(null, "d-land-acquisition", b("LA-2"), vars("landholderCount", 1));
            complete(ctx, bb, "dla_sia", "sia-unit", null);
            complete(ctx, bb, "dla_appraisal", "expert-group", vars("siaLapse", date(plusMonths(now, 12))));
            Instant s11b = now;
            complete(ctx, bb, "dla_s11", "collector", vars("objectionEnd", date(s11b.plus(Duration.ofDays(60))),
                    "s19Lapse", date(plusMonths(s11b, 12))));
            advance(Duration.ofDays(61));
            adapter.executeDueJobs();
            complete(ctx, bb, "dla_s15Hearing", "collector", null);
            TaskView late = single(ctx, bb, "dla_s19");
            clock(plusMonths(s11b, 12).plus(Duration.ofMinutes(1)));
            adapter.executeDueJobs();
            ctx.check("s19 lapse after 12 months", ended(bb) && visited(bb, "dla_end_s19Lapsed"));
            ctx.check("late declaration blocked", throwsOn(() -> adapter.completeTask(late.id(), Map.of())));

            String c = adapter.startProcess(null, "d-land-acquisition", b("LA-3"), vars("landholderCount", 1));
            complete(ctx, c, "dla_sia", "sia-unit", null);
            complete(ctx, c, "dla_appraisal", "expert-group", vars("siaLapse", date(plusMonths(now, 12))));
            clock(plusMonths(now, 12).plus(Duration.ofMinutes(1)));
            adapter.executeDueJobs();
            ctx.check("SIA lapsed (s14)", ended(c) && visited(c, "dla_end_siaLapsed"));
        });
    }

    // ================================================================== D-AUDIT-PARA
    @Test
    void auditPara() {
        scenario("D-AUDIT-PARA", ctx -> {
            deploy("d-audit-para.bpmn");
            Instant t0 = now;
            String a = adapter.startProcess(null, "d-audit-para", b("PARA-3.2-2026"), vars("returnCount", 0));
            ctx.check("reminder due at +2 calendar months", dueIs(timerAt(a, "dap_reminder"), plusMonths(t0, 2)));
            ctx.check("ATN due at +3 calendar months", dueIs(timerAt(a, "dap_due"), plusMonths(t0, 3)));
            ctx.detail("reminderDue", String.valueOf(timerAt(a, "dap_reminder")));
            ctx.detail("atnDue", String.valueOf(timerAt(a, "dap_due")));
            advance(Duration.ofDays(20));
            complete(ctx, a, "dap_draftAtn", "department", null);
            complete(ctx, a, "dap_vet", "audit", vars("vetted", false, "auditRemark", "action not complete"));
            ctx.check("returned to the department on the same para", tasks(a, "dap_draftAtn").size() == 1 && num(var(a, "returnCount")) == 1);
            clock(plusMonths(t0, 2).plus(Duration.ofMinutes(1)));
            adapter.executeDueJobs();
            ctx.check("reminder raised", countForGroup(a, "dap_remind", "department-head") == 1);
            clock(plusMonths(t0, 3).plus(Duration.ofMinutes(1)));
            adapter.executeDueJobs();
            ctx.check("escalated to the Secretary once", countForGroup(a, "dap_escalate", "secretary") == 1);
            complete(ctx, a, "dap_remind", "department-head", null);
            complete(ctx, a, "dap_escalate", "secretary", null);
            complete(ctx, a, "dap_draftAtn", "department", null);
            complete(ctx, a, "dap_vet", "audit", vars("vetted", true));
            complete(ctx, a, "dap_pac", "pac-cell", null);
            ctx.check("para settled; exactly one reminder and one escalation", ended(a)
                    && countVisits(a, "dap_remind") == 1 && countVisits(a, "dap_escalate") == 1);

            String bb = adapter.startProcess(null, "d-audit-para", b("PARA-4.1-2026"), vars("returnCount", 0));
            advance(Duration.ofDays(25));
            complete(ctx, bb, "dap_draftAtn", "department", null);
            complete(ctx, bb, "dap_vet", "audit", vars("vetted", true));
            ctx.check("ATN vetted within a month: timers removed", timers(bb).isEmpty() && tasks(bb, "dap_pac").size() == 1);
            complete(ctx, bb, "dap_pac", "pac-cell", null);
            ctx.check("no reminder, no escalation", ended(bb) && countVisits(bb, "dap_remind") == 0 && countVisits(bb, "dap_escalate") == 0);
        });
    }
}
