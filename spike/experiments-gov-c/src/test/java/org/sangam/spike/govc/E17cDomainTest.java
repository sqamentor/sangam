package org.sangam.spike.govc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.govc.support.GovBase;
import org.sangam.spike.govc.support.Native;

/**
 * E17-c: domain end-to-end models (GOV-PATTERNS.md additional domains) on both engines, same models and data.
 * Each domain runs on a fresh engine and database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E17cDomainTest extends GovBase {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @BeforeAll
    void setUp() {
        open("E17-c", "Domain end-to-end models: bank guarantee, contract variation, court case, stores verification,"
                + " land acquisition, audit para");
    }

    @AfterAll
    void tearDown() {
        write();
    }

    private static Instant plusMonths(Instant t, int months) {
        return ZonedDateTime.ofInstant(t, IST).plusMonths(months).toInstant();
    }

    private Instant days(int d) {
        return now.plus(Duration.ofDays(d));
    }

    // ================================================================== D-BANK-GUARANTEE
    @Test
    void bankGuarantee() {
        scenario("D-BANK-GUARANTEE", "Contract with performance security as a long-lived child: extension, encashment, release, cancel", ctx -> {
            ctx.feature("call activity (child BG, business key inherited)", "parallel execution + DLP timer", "BG timers re-armed by the model",
                    "message correlation to the child by business key", "parent cancel removes the child");
            deploy("d-bg-contract.bpmn", "gp41-bg.bpmn");
            Instant t0 = now;
            // contract 1: BG extended once, released after the DLP
            String p = adapter.startProcess(null, "d-bg-contract", "CON-1", vars("bgAmount", 300_000L,
                    "bgExpiry", date(t0.plus(Duration.ofDays(400))), "reminderAt", date(t0.plus(Duration.ofDays(370)))));
            complete(ctx, p, "dbg_issueLoA", "contracts", null);
            List<String> kids = nat.subInstances(adapter, p);
            ctx.require("BG child started", kids.size() == 1);
            String bg = kids.get(0);
            ctx.check("child carries the contract number as business key", "CON-1".equals(adapter.getInstance(bg).businessKey()));
            ctx.check("bank confirmation reaches the child by business key", bg.equals(adapter.correlateMessage(null, "bgConfirmed", "CON-1", vars("genuine", true))));
            clock(t0.plus(Duration.ofDays(200)));
            complete(ctx, p, "dbg_execute", "ee", vars("dlpEnd", date(t0.plus(Duration.ofDays(565)))));
            clock(t0.plus(Duration.ofDays(371)));
            adapter.executeDueJobs();
            complete(ctx, bg, "gp41_requestExtension", "contracts", null);
            adapter.correlateMessage(null, "bgExtended", "CON-1",
                    vars("bgExpiry", date(t0.plus(Duration.ofDays(700))), "reminderAt", date(t0.plus(Duration.ofDays(670)))));
            ctx.check("extended BG watched until +670d", dueIs(timerAt(bg, "gp41_reminderDue"), t0.plus(Duration.ofDays(670))));
            clock(t0.plus(Duration.ofDays(566)));
            adapter.executeDueJobs();
            complete(ctx, p, "dbg_finalBill", "accounts", null);
            ctx.check("contract waits for the security branch", tasks(p, "dbg_close").isEmpty());
            adapter.correlateMessage(null, "contractClosed", "CON-1", null);
            complete(ctx, bg, "gp41_release", "accounts", null);
            complete(ctx, p, "dbg_close", "contracts", null);
            ctx.check("contract 1 closed, BG released", ended(p) && visited(bg, "gp41_end_released"));

            // contract 2: no extension, BG encashed; contract carries on and closes
            Instant t1 = now;
            String q = adapter.startProcess(null, "d-bg-contract", "CON-2", vars("bgAmount", 300_000L,
                    "bgExpiry", date(t1.plus(Duration.ofDays(300))), "reminderAt", date(t1.plus(Duration.ofDays(270)))));
            complete(ctx, q, "dbg_issueLoA", "contracts", null);
            String bg2 = nat.subInstances(adapter, q).get(0);
            adapter.correlateMessage(null, "bgConfirmed", "CON-2", vars("genuine", true));
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
                    throwsOn(() -> adapter.correlateMessage(null, "contractClosed", "CON-2", null)));
            complete(ctx, q, "dbg_close", "contracts", null);
            ctx.check("contract 2 closed", ended(q));

            // contract 3: cancelled; the BG child goes with it
            String r = adapter.startProcess(null, "d-bg-contract", "CON-3", vars("bgAmount", 1L,
                    "bgExpiry", date(now.plus(Duration.ofDays(300))), "reminderAt", date(now.plus(Duration.ofDays(270)))));
            complete(ctx, r, "dbg_issueLoA", "contracts", null);
            String bg3 = nat.subInstances(adapter, r).get(0);
            adapter.cancelInstance(r, "tender cancelled");
            ctx.check("cancelled contract removed its BG child", ended(r) && (adapter.getInstance(bg3) == null || ended(bg3))
                    && nat.runningInstances(adapter, "gp41-bg").isEmpty());
        });
    }

    // ================================================================== D-CONTRACT-VARIATION
    @Test
    void contractVariation() {
        scenario("D-CONTRACT-VARIATION", "Running works contract: EoT (LD timer moves), extra item (CE level), LD levy and waiver", ctx -> {
            ctx.feature("non-interrupting message event sub-process", "approval chain as child with in/out mapping",
                    "gateway on the variation size (5 % / 90 days)", "LD boundary timer re-armed natively", "history of variable updates");
            deploy("d-works-contract.bpmn", "d-variation.bpmn");
            Instant t0 = now;
            Instant stipulated = t0.plus(Duration.ofDays(365));
            long value = 10_000_000L;
            String p = adapter.startProcess(null, "d-works-contract", "WO-2027-17",
                    vars("completionDate", date(stipulated), "contractValue", value, "ldWaived", false));
            // 1. EoT 60 days for hindrance -> SE level
            clock(t0.plus(Duration.ofDays(300)));
            Instant eot = stipulated.plus(Duration.ofDays(60));
            adapter.correlateMessage(null, "variationProposed", "WO-2027-17",
                    vars("varType", "EOT", "days", 60, "amount", 0L, "proposedCompletion", date(eot)));
            String v1 = nat.subInstances(adapter, p).get(0);
            complete(ctx, v1, "dvar_examine", "ee", null);
            ctx.check("EoT of 60 days stays at SE level", tasks(v1, "dvar_ceApprove").isEmpty() && tasks(v1, "dvar_seRecommend").isEmpty());
            complete(ctx, v1, "dvar_seApprove", "se", vars("approved", true, "approvedCompletion", date(eot), "approvedAmount", 0L,
                    "approvalLevel", "SE"));
            ctx.check("completion date of the running contract moved", var(p, "completionDate") instanceof Date d && d.toInstant().equals(eot));
            ctx.check("LD timer still at the old date until the engine is told", dueIs(timerAt(p, "dcv_ldStart"), stipulated));
            String note = nat.recalculateTimerFromModel(adapter, timerAt(p, "dcv_ldStart").jobId());
            if (note != null) {
                ctx.workaround(note, 18);
            }
            ctx.check("LD timer re-armed to the EoT date", dueIs(timerAt(p, "dcv_ldStart"), eot));
            // 2. extra item of 6 % -> CE level
            adapter.correlateMessage(null, "variationProposed", "WO-2027-17",
                    vars("varType", "EXTRA_ITEM", "days", 0, "amount", 600_000L, "proposedCompletion", date(eot)));
            String v2 = nat.subInstances(adapter, p).get(0);
            complete(ctx, v2, "dvar_examine", "ee", null);
            complete(ctx, v2, "dvar_seRecommend", "se", null);
            complete(ctx, v2, "dvar_ceApprove", "ce", vars("approved", true, "approvedCompletion", date(eot), "approvedAmount", 600_000L,
                    "approvalLevel", "CE"));
            ctx.check("6 % extra item went to the CE and raised the value to 10,600,000",
                    visited(v2, "dvar_ceApprove") && num(var(p, "contractValue")) == 10_600_000L);
            complete(ctx, p, "dcv_extraItem", "ee", null);
            // 3. work finishes 10 days after the EoT date -> LD accrues
            clock(eot.plus(Duration.ofDays(1)));
            adapter.executeDueJobs();
            ctx.check("LD accrual notice the day after the EoT date", tasks(p, "dcv_ldAccrues").size() == 1);
            clock(eot.plus(Duration.ofDays(10)));
            long daysLate = Duration.between(((Date) var(p, "completionDate")).toInstant(), now).toDays();
            long cv = num(var(p, "contractValue"));
            long ld = Math.min(cv * 5 * daysLate / 10_000, cv / 10); // 0.05 % per day, cap 10 %
            ctx.detail("daysLate", daysLate);
            ctx.detail("ldComputed", ld);
            ctx.check("LD = 0.05 %/day x 10 days x 10,600,000 = 53,000", ld == 53_000L);
            complete(ctx, p, "dcv_ldAccrues", "ee", null);
            complete(ctx, p, "dcv_execute", "ee", vars("actualCompletion", date(now)));
            // 4. LD waiver proposed while the final bill is pending
            adapter.correlateMessage(null, "variationProposed", "WO-2027-17",
                    vars("varType", "LD_WAIVER", "days", 0, "amount", ld, "proposedCompletion", date(eot)));
            String v3 = nat.subInstances(adapter, p).get(0);
            complete(ctx, v3, "dvar_examine", "ee", null);
            complete(ctx, v3, "dvar_seApprove", "se", vars("approved", true, "approvedCompletion", date(eot), "approvedAmount", 0L,
                    "approvalLevel", "SE"));
            boolean waived = Boolean.TRUE.equals(var(p, "ldWaived"));
            ctx.check("LD waived on the running contract", waived);
            complete(ctx, p, "dcv_finalBill", "accounts", vars("ldRecovered", waived ? 0L : ld));
            ctx.check("contract closed with LD 0 after waiver", ended(p) && num(var(p, "ldRecovered")) == 0);
            ctx.check("three variation children ran", countVisits(p, "dcv_variation") == 3);
            ctx.check("history keeps the stipulated and the EoT completion dates", nat.variableHistory(adapter, p, "completionDate").size() >= 2);
            ctx.check("history keeps the value before and after the extra item", nat.variableHistory(adapter, p, "contractValue").size() >= 2);
        });
    }

    // ================================================================== D-COURT-CASE
    @Test
    void courtCase() {
        scenario("D-COURT-CASE", "Litigation with court-set hearings, reschedule, stay (suspends the impugned action), appeal, compliance", ctx -> {
            ctx.feature("hearing dates from messages / recorded orders", "event-gateway re-arm of the hearing timer",
                    "brief deadline re-armed natively", "suspension of another instance (stay)", "appeal loop", "compliance deadline");
            deploy("d-court-case.bpmn", "d-impugned.bpmn");
            Instant t0 = now;
            String imp = adapter.startProcess(null, "d-impugned", "REC-ORDER-9", Map.of());
            String p = adapter.startProcess(null, "d-court-case", "WP-4411-2027", vars("impugnedInstance", imp));
            complete(ctx, p, "dcc_examine", "nodal-officer", null);
            complete(ctx, p, "dcc_lawVetting", "law-ministry", null);
            adapter.correlateMessage(null, "hearingListed", "WP-4411-2027",
                    vars("nextHearing", date(t0.plus(Duration.ofDays(30))), "briefDue", date(t0.plus(Duration.ofDays(23)))));
            advance(Duration.ofDays(3));
            adapter.correlateMessage(null, "hearingRescheduled", "WP-4411-2027",
                    vars("nextHearing", date(t0.plus(Duration.ofDays(40))), "briefDue", date(t0.plus(Duration.ofDays(33)))));
            String note = nat.recalculateTimerFromModel(adapter, timerAt(p, "dcc_briefDue").jobId());
            if (note != null) {
                ctx.workaround(note, 18);
            }
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
            nat.suspendInstance(adapter, imp);
            ctx.check("stay: impugned action suspended, cannot be implemented",
                    nat.isSuspended(adapter, imp) && throwsOn(() -> adapter.completeTask(impTask.id(), Map.of())));
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
            nat.activateInstance(adapter, imp); // stay vacated with the appellate order
            ctx.check("stay vacated: impugned action active again", !nat.isSuspended(adapter, imp));
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
        scenario("D-STORES-VERIFICATION", "Annual stock verification (GFR 213) -> discrepancy cases -> survey board -> write-off / recovery", ctx -> {
            ctx.feature("timer start event (yearly)", "parallel multi-instance verification with local results (native)",
                    "case started per discrepancy by external work", "committee multi-instance (all sign)", "stock ledger in the engine transaction");
            deploy("d-stores-verification.bpmn", "d-stores-discrepancy.bpmn");
            sql("create table gov_stock(item int primary key, qty int)");
            int[] book = {50, 12, 8, 100, 3, 40};
            int[] physical = {50, 12, 5, 100, 3, 39};
            for (int i = 0; i < book.length; i++) {
                sql("insert into gov_stock values (" + i + ", " + book[i] + ")");
            }
            clock(Instant.parse("2027-03-31T04:31:00Z"));
            adapter.executeDueJobs();
            List<String> camp = nat.runningInstances(adapter, "d-stores-verification");
            ctx.require("campaign started on 31 March", camp.size() == 1);
            String c = camp.get(0);
            work(ctx, c, "stock-register", vars("itemCount", book.length));
            ctx.workaround("per-branch verification result written as LOCAL variables of the branch (setVariablesLocal on the"
                    + " task's execution) before completion", 4);
            if ("flowable".equals(nat.engine())) {
                ctx.workaround("multi-instance external task needs flowable:exclusive=\"false\" (exclusive external-worker jobs"
                        + " of one instance cannot be fetched together)", 1);
            }
            for (TaskView t : tasks(c, "dsv_verify")) {
                String ex = nat.taskExecutionId(adapter, t.id());
                int i = ((Number) nat.getVariable(adapter, ex, "loopCounter")).intValue();
                nat.setVariablesLocal(adapter, ex, vars("bookQty", book[i], "physicalQty", physical[i], "itemNo", i));
                adapter.claimTask(t.id(), "board");
                adapter.completeTask(t.id(), Map.of());
            }
            int opened = 0;
            for (ExternalWork w : adapter.fetchAndLock("w-open-discrepancy", "open-discrepancy", 10, Duration.ofMinutes(5))) {
                int item = ((Number) w.variables().get("itemNo")).intValue();
                adapter.startProcess(null, "d-stores-discrepancy", "DISC-2027-" + item, vars("itemNo", item,
                        "shortBy", ((Number) w.variables().get("bookQty")).intValue() - ((Number) w.variables().get("physicalQty")).intValue()));
                completeWork(w, Map.of());
                opened++;
            }
            ctx.check("2 discrepancy cases (items 2 and 5)", opened == 2);
            complete(ctx, c, "dsv_report", "hod", null);
            ctx.check("campaign closed, next one armed for 2028-03-31", ended(c) && nat.startTimers(adapter, "d-stores-verification").size() == 1
                    && dueIs(nat.startTimers(adapter, "d-stores-verification").get(0), Instant.parse("2028-03-31T04:30:00Z")));
            String d2 = nat.historicInstancesByBusinessKey(adapter, "d-stores-discrepancy", "DISC-2027-2").get(0);
            String d5 = nat.historicInstancesByBusinessKey(adapter, "d-stores-discrepancy", "DISC-2027-5").get(0);
            // item 2: written off by the survey board
            complete(ctx, d2, "dsd_investigate", "stores-officer", null);
            List<TaskView> board = tasks(d2, "dsd_board");
            ctx.check("survey board: 3 members must sign", board.size() == 3);
            for (int k = 0; k < board.size(); k++) {
                adapter.claimTask(board.get(k).id(), "member-" + k);
                adapter.completeTask(board.get(k).id(), vars("writeOff", true));
            }
            ExternalWork adj = fetchOne(ctx, d2, "stock-ledger-adjust");
            adapter.inEngineTransaction(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("update gov_stock set qty = qty - ? where item = ?")) {
                    ps.setInt(1, ((Number) adj.variables().get("shortBy")).intValue());
                    ps.setInt(2, ((Number) adj.variables().get("itemNo")).intValue());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
                adapter.completeExternal(adj.id(), "w-stock-ledger-adjust", adj.lockVersion(), Map.of());
                return null;
            });
            complete(ctx, d2, "dsd_responsibility", "hod", null);
            ctx.check("item 2 written off: ledger 8 -> 5, responsibility fixed", ended(d2) && count("select qty from gov_stock where item = 2") == 5);
            // item 5: recovery from the custodian
            complete(ctx, d5, "dsd_investigate", "stores-officer", null);
            for (TaskView t : tasks(d5, "dsd_board")) {
                adapter.claimTask(t.id(), "m");
                adapter.completeTask(t.id(), vars("writeOff", false));
            }
            complete(ctx, d5, "dsd_recover", "accounts", null);
            ctx.check("item 5 recovered, ledger unchanged (40)", ended(d5) && count("select qty from gov_stock where item = 5") == 40);
        });
    }

    // ================================================================== D-LAND-ACQUISITION
    @Test
    void landAcquisition() {
        scenario("D-LAND-ACQUISITION", "RFCTLARR: s4 SIA -> s11 -> s15 objections -> s19 -> s23 award -> s31, with statutory lapses", ctx -> {
            ctx.feature("statutory windows as interrupting boundary timers (12 months, calendar)", "per-landholder objection window (MI + event gateway)",
                    "per-branch message delivery (native)", "lapse outcomes as end events");
            deploy("d-land-acquisition.bpmn");
            ctx.workaround("per-landholder objection: delivered to the landholder's branch found by loopCounter"
                    + " (RuntimeService.messageEventReceived on that execution)", 12);
            // happy path
            String a = adapter.startProcess(null, "d-land-acquisition", "LA-1", vars("landholderCount", 4));
            complete(ctx, a, "dla_sia", "sia-unit", null);
            complete(ctx, a, "dla_appraisal", "expert-group", vars("siaLapse", date(plusMonths(now, 12))));
            ctx.check("s11 window: 12 calendar months from the appraisal", dueIs(timerAt(a, "dla_siaLapse"), plusMonths(now, 12)));
            advance(Duration.ofDays(120));
            Instant s11 = now;
            complete(ctx, a, "dla_s11", "collector", vars("objectionEnd", date(s11.plus(Duration.ofDays(60)))
                    , "s19Lapse", date(plusMonths(s11, 12))));
            ctx.check("4 landholder windows open, 60 days", nat.messageSubscriptions(adapter, a, "s15Objection").size() == 4
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

            // s19 not published within 12 months of s11 -> lapse; late declaration blocked
            String b = adapter.startProcess(null, "d-land-acquisition", "LA-2", vars("landholderCount", 1));
            complete(ctx, b, "dla_sia", "sia-unit", null);
            complete(ctx, b, "dla_appraisal", "expert-group", vars("siaLapse", date(plusMonths(now, 12))));
            Instant s11b = now;
            complete(ctx, b, "dla_s11", "collector", vars("objectionEnd", date(s11b.plus(Duration.ofDays(60))),
                    "s19Lapse", date(plusMonths(s11b, 12))));
            advance(Duration.ofDays(61));
            adapter.executeDueJobs();
            complete(ctx, b, "dla_s15Hearing", "collector", null);
            TaskView late = single(ctx, b, "dla_s19");
            clock(plusMonths(s11b, 12).plus(Duration.ofMinutes(1)));
            adapter.executeDueJobs();
            ctx.check("s19 lapse after 12 months", ended(b) && visited(b, "dla_end_s19Lapsed"));
            ctx.check("late declaration blocked", throwsOn(() -> adapter.completeTask(late.id(), Map.of())));

            // s11 not issued within 12 months of the appraisal -> SIA lapses (s14)
            String c = adapter.startProcess(null, "d-land-acquisition", "LA-3", vars("landholderCount", 1));
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
        scenario("D-AUDIT-PARA", "ATN due in 3 months: reminder at 2, escalation at 3, return by audit on the same para", ctx -> {
            ctx.feature("non-interrupting boundary timers P2M / P3M (calendar months) on a sub-process", "return loop with counter",
                    "timers removed when the cycle completes");
            deploy("d-audit-para.bpmn");
            Instant t0 = now;
            String a = adapter.startProcess(null, "d-audit-para", "PARA-3.2-2026", vars("returnCount", 0));
            ctx.check("reminder due at +2 calendar months", dueIs(timerAt(a, "dap_reminder"), plusMonths(t0, 2)));
            ctx.check("ATN due at +3 calendar months", dueIs(timerAt(a, "dap_due"), plusMonths(t0, 3)));
            ctx.detail("reminderDue", String.valueOf(timerAt(a, "dap_reminder").due()));
            ctx.detail("atnDue", String.valueOf(timerAt(a, "dap_due").due()));
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

            String b = adapter.startProcess(null, "d-audit-para", "PARA-4.1-2026", vars("returnCount", 0));
            advance(Duration.ofDays(25));
            complete(ctx, b, "dap_draftAtn", "department", null);
            complete(ctx, b, "dap_vet", "audit", vars("vetted", true));
            ctx.check("ATN vetted within a month: timers removed", nat.timers(adapter, b).isEmpty() && tasks(b, "dap_pac").size() == 1);
            complete(ctx, b, "dap_pac", "pac-cell", null);
            ctx.check("no reminder, no escalation", ended(b) && countVisits(b, "dap_remind") == 0 && countVisits(b, "dap_escalate") == 0);
        });
    }

    @SuppressWarnings("unused")
    private static Native.Timer none() {
        return null;
    }
}
