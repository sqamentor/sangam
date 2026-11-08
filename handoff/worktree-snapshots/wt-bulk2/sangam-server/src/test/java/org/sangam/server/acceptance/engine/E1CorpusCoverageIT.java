// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.sangam.core.api.EngineAdapter.TaskView;

/**
 * E1 corpus coverage (spike E1CorpusCoverageTest, SANGAM.md XI.4) on the real server: WF-1, WF-2, WF-3 and the CMMN
 * case through Sangam's {@code SangamEngineAdapter}. Same scenarios and checks as Stage B; the spike's two
 * informational divergence cases (no amount / null amount) are not scored there and are not ported.
 *
 * <p>Corpus deviation: {@code wf1-bill} declares {@code operaton:decisionRefBinding="deployment"}, required by Sangam
 * rule D-12 (a decision call must state its binding; the Stage B corpus relied on Operaton's implicit "latest").
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E1CorpusCoverageIT extends EngineScenarioSupport {

    private static final Instant BASE = Instant.parse("2026-11-02T04:30:00Z");

    @BeforeAll
    void deployCorpus() {
        deployCorpus("e1-wf1", "wf1-bill.bpmn", "wf1-delegation-of-powers.dmn");
        deployCorpus("e1-wf2", "wf2-file.bpmn");
        deployCorpus("e1-wf3", "wf3-licence.bpmn");
        deployCorpus("e1-wf3-case", "wf3-licence-case.cmmn");
    }

    // ================================================================ WF-1

    private String startBill(String bk, long amount, String head) {
        return adapter.startProcess(null, "wf1-bill", bk, vars("billId", bk, "beneficiary", "Synthetic Vendor Pvt Ltd",
                "budgetHead", head, "initiatorSeat", "SEAT-INITIATOR", "checkerGroup", "SEAT-CHECKER", "amount", amount));
    }

    private void approvePath(Ctx ctx, long amount, String head, String level) {
        clock(BASE);
        String bk = key("BILL");
        String pid = startBill(bk, amount, head);
        complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "forward"));
        Map<String, Object> v = adapter.getInstance(pid).variables();
        Object max = v.get("maxAmount");
        ctx.detail("approverLevel", v.get("approverLevel"));
        ctx.check("DMN approverLevel == " + level, level.equals(v.get("approverLevel")));
        ctx.check("maxAmount >= amount (numeric compare)", max instanceof Number n && n.doubleValue() >= amount);
        complete(ctx, pid, "wf1_approve", level, vars("approverDecision", "approve"));
        completeExternal(ctx, pid, "payment", vars("paymentRef", "UTR-" + bk, "paymentStatus", "SUCCESS"), true);
        ctx.check("instance ended", ended(pid));
        ctx.check("ended at wf1_paidEnd", historicActivityIds(pid).contains("wf1_paidEnd"));
    }

    @Test void wf1Approve100000() { scenario("WF1-APPROVE-100000", c -> approvePath(c, 100_000, "2059-MAINT", "SECTION_OFFICER")); }
    @Test void wf1Approve100001() { scenario("WF1-APPROVE-100001", c -> approvePath(c, 100_001, "2059-MAINT", "DDO")); }
    @Test void wf1Approve1000000() { scenario("WF1-APPROVE-1000000", c -> approvePath(c, 1_000_000, "2059-MAINT", "DDO")); }
    @Test void wf1Approve1000001() { scenario("WF1-APPROVE-1000001", c -> approvePath(c, 1_000_001, "2059-MAINT", "HOD")); }
    @Test void wf1Approve10000000() { scenario("WF1-APPROVE-10000000", c -> approvePath(c, 10_000_000, "2059-MAINT", "HOD")); }
    @Test void wf1Approve10000001() { scenario("WF1-APPROVE-10000001", c -> approvePath(c, 10_000_001, "2059-MAINT", "SECRETARY")); }
    @Test void wf1ApproveCapitalR1() { scenario("WF1-APPROVE-CAPITAL-R1", c -> approvePath(c, 6_000_000, "4059-CAPITAL", "SECRETARY")); }

    @Test
    void wf1ReturnRevise() {
        scenario("WF1-RETURN-REVISE", ctx -> {
            String bk = key("BILL");
            String pid = startBill(bk, 500_000L, "2059-MAINT");
            complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "return"));
            complete(ctx, pid, "wf1_reviseBill", "SEAT-INITIATOR", vars("revisionNote", "fixed GST"));
            complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "forward"));
            complete(ctx, pid, "wf1_approve", "DDO", vars("approverDecision", "return"));
            complete(ctx, pid, "wf1_reviseBill", "SEAT-INITIATOR", vars("revisionNote", "attached invoice"));
            complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "forward"));
            complete(ctx, pid, "wf1_approve", "DDO", vars("approverDecision", "approve"));
            completeExternal(ctx, pid, "payment", vars("paymentRef", "UTR-" + bk), false);
            List<String> hist = historicActivityIds(pid);
            ctx.check("revise visited twice", hist.stream().filter("wf1_reviseBill"::equals).count() == 2);
            ctx.check("ended at wf1_paidEnd", ended(pid) && hist.contains("wf1_paidEnd"));
        });
    }

    @Test
    void wf1RejectChecker() {
        scenario("WF1-REJECT-CHECKER", ctx -> {
            String pid = startBill(key("BILL"), 50_000L, "2059-MAINT");
            complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "reject"));
            ctx.check("ended at wf1_rejectedEnd", ended(pid) && historicActivityIds(pid).contains("wf1_rejectedEnd"));
        });
    }

    @Test
    void wf1RejectApprover() {
        scenario("WF1-REJECT-APPROVER", ctx -> {
            String pid = startBill(key("BILL"), 2_000_000L, "2059-MAINT");
            complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "forward"));
            complete(ctx, pid, "wf1_approve", "HOD", vars("approverDecision", "reject"));
            ctx.check("ended at wf1_rejectedEnd", ended(pid) && historicActivityIds(pid).contains("wf1_rejectedEnd"));
        });
    }

    // ================================================================ WF-2

    private Map<String, Object> wf2Vars(String bk) {
        return vars("fileId", bk, "subject", "Synthetic file", "initiatorSeat", "SEAT-INITIATOR", "l1Group", "SEAT-L1",
                "l2Group", "SEAT-L2", "l3Group", "SEAT-L3", "sectionAGroup", "SEC-A", "sectionBGroup", "SEC-B",
                "disposalGroup", "SEAT-DISPOSAL");
    }

    private void l(Ctx ctx, String pid, int level, String decision) {
        complete(ctx, pid, "wf2_l" + level + "Review", "SEAT-L" + level, vars("l" + level + "Decision", decision));
    }

    private String startFile() {
        String bk = key("FILE");
        return adapter.startProcess(null, "wf2-file", bk, wf2Vars(bk));
    }

    @Test
    void wf2Forward3Return2() {
        scenario("WF2-AC04-FORWARD3-RETURN2", ctx -> {
            String pid = startFile();
            l(ctx, pid, 1, "forward");
            l(ctx, pid, 2, "forward");
            l(ctx, pid, 3, "return");
            l(ctx, pid, 2, "return");
            l(ctx, pid, 1, "forward");
            l(ctx, pid, 2, "forward");
            l(ctx, pid, 3, "forward");
            ctx.check("both comment branches open", tasks(pid, "wf2_commentsSectionA").size() == 1
                    && tasks(pid, "wf2_commentsSectionB").size() == 1);
            complete(ctx, pid, "wf2_commentsSectionA", "SEC-A", vars("commentA", "ok"));
            ctx.check("join waits after one branch (no disposal yet)", tasks(pid, "wf2_finalDisposal").isEmpty());
            complete(ctx, pid, "wf2_commentsSectionB", "SEC-B", vars("commentB", "ok"));
            complete(ctx, pid, "wf2_finalDisposal", "SEAT-DISPOSAL", vars("disposal", "filed"));
            List<String> hist = historicActivityIds(pid);
            ctx.check("ended at wf2_disposedEnd", ended(pid) && hist.contains("wf2_disposedEnd"));
            ctx.check("L2 visited 3 times, L3 twice", hist.stream().filter("wf2_l2Review"::equals).count() == 3
                    && hist.stream().filter("wf2_l3Review"::equals).count() == 2);
        });
    }

    @Test
    void wf2ReturnToInitiator() {
        scenario("WF2-RETURN-TO-INITIATOR", ctx -> {
            String pid = startFile();
            l(ctx, pid, 1, "return");
            complete(ctx, pid, "wf2_reviseNote", "SEAT-INITIATOR", vars());
            l(ctx, pid, 1, "forward");
            l(ctx, pid, 2, "forward");
            l(ctx, pid, 3, "forward");
            complete(ctx, pid, "wf2_commentsSectionB", "SEC-B", vars());
            complete(ctx, pid, "wf2_commentsSectionA", "SEC-A", vars());
            complete(ctx, pid, "wf2_finalDisposal", "SEAT-DISPOSAL", vars());
            ctx.check("ended at wf2_disposedEnd", ended(pid) && historicActivityIds(pid).contains("wf2_disposedEnd"));
        });
    }

    @Test
    void wf2ParallelJoinConcurrent() {
        scenario("WF2-PARALLEL-JOIN-CONCURRENT", ctx -> {
            String pid = startFile();
            l(ctx, pid, 1, "forward");
            l(ctx, pid, 2, "forward");
            l(ctx, pid, 3, "forward");
            TaskView a = single(ctx, pid, "wf2_commentsSectionA");
            TaskView b = single(ctx, pid, "wf2_commentsSectionB");
            adapter.claimTask(a.id(), "user-a");
            adapter.claimTask(b.id(), "user-b");
            List<String> conflicts = new ArrayList<>();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<?>> fs = new ArrayList<>();
                for (TaskView t : List.of(a, b)) {
                    fs.add(pool.submit(() -> {
                        go.await();
                        for (int attempt = 0; attempt < 5; attempt++) {
                            try {
                                adapter.completeTask(t.id(), Map.of());
                                return null;
                            } catch (RuntimeException ex) {
                                synchronized (conflicts) {
                                    conflicts.add(ex.getClass().getSimpleName());
                                }
                                if (tasks(pid, t.taskDefinitionKey()).isEmpty()) {
                                    return null;
                                }
                            }
                        }
                        throw new IllegalStateException("could not complete " + t.taskDefinitionKey());
                    }));
                }
                go.countDown();
                for (Future<?> f : fs) {
                    f.get();
                }
            } finally {
                pool.shutdownNow();
            }
            ctx.detail("conflicts", conflicts);
            ctx.check("exactly one wf2_finalDisposal task after the join", tasks(pid, "wf2_finalDisposal").size() == 1);
            complete(ctx, pid, "wf2_finalDisposal", "SEAT-DISPOSAL", vars());
            ctx.check("ended", ended(pid));
        });
    }

    // ================================================================ WF-3

    @Test
    void wf3FullSlaClarifyIssue() {
        scenario("WF3-FULL-SLA-CLARIFY-ISSUE", ctx -> {
            clock(BASE);
            String bk = key("LIC");
            String pid = adapter.startProcess(null, "wf3-licence", bk,
                    vars("applicationId", bk, "applicantId", "APPLICANT-1", "licenceType", "TRADE"));
            single(ctx, pid, "wf3_scrutiny");
            ctx.check("scrutiny offered to licence-scrutiny", countForGroup(pid, "wf3_scrutiny", "licence-scrutiny") == 1);
            advance(Duration.ofHours(71));
            adapter.executeDueJobs();
            ctx.check("no escalation at 71 h", tasks(pid, "wf3_slaEscalation").isEmpty());
            advance(Duration.ofHours(2));
            adapter.executeDueJobs();
            ctx.check("one escalation at 73 h", tasks(pid, "wf3_slaEscalation").size() == 1);
            ctx.check("scrutiny still open (non-interrupting)", tasks(pid, "wf3_scrutiny").size() == 1);
            ctx.check("escalation offered to licence-escalation",
                    countForGroup(pid, "wf3_slaEscalation", "licence-escalation") == 1);
            advance(Duration.ofHours(100));
            adapter.executeDueJobs();
            ctx.check("still one escalation 100 h later (duration timer fires once)",
                    tasks(pid, "wf3_slaEscalation").size() == 1);
            complete(ctx, pid, "wf3_slaEscalation", "licence-escalation", vars("escalationNote", "chased"));

            complete(ctx, pid, "wf3_scrutiny", "licence-scrutiny", vars("scrutinyDecision", "clarify"));
            ctx.check("waiting for applicant message", active(pid).contains("wf3_awaitApplicantResponse"));
            String corr = adapter.correlateMessage(null, "applicantResponse", bk, vars("applicantReply", "documents attached"));
            ctx.check("message correlated to this instance", pid.equals(corr));
            single(ctx, pid, "wf3_scrutiny");
            advance(Duration.ofHours(73));
            adapter.executeDueJobs();
            ctx.check("one escalation for the second scrutiny entry", tasks(pid, "wf3_slaEscalation").size() == 1);
            complete(ctx, pid, "wf3_slaEscalation", "licence-escalation", vars());

            complete(ctx, pid, "wf3_scrutiny", "licence-scrutiny", vars("scrutinyDecision", "accept"));
            complete(ctx, pid, "wf3_inspection", "licence-inspector", vars("inspectionResult", "compliant"));
            complete(ctx, pid, "wf3_approval", "licence-approver", vars("approvalDecision", "approve"));
            completeExternal(ctx, pid, "esign", vars("esignTxn", "ESP-" + bk, "signedDocHash", "sha256:synthetic"), true);
            completeExternal(ctx, pid, "digilocker-push", vars("digilockerUri", "in.gov.synthetic-" + bk), false);
            ctx.check("ended at wf3_issuedEnd", ended(pid) && historicActivityIds(pid).contains("wf3_issuedEnd"));
        });
    }

    @Test
    void wf3RejectAtScrutiny() {
        scenario("WF3-REJECT-AT-SCRUTINY", ctx -> {
            clock(BASE);
            String bk = key("LIC");
            String pid = adapter.startProcess(null, "wf3-licence", bk, vars("applicationId", bk));
            complete(ctx, pid, "wf3_scrutiny", "licence-scrutiny", vars("scrutinyDecision", "reject"));
            ctx.check("ended at wf3_rejectedEnd", ended(pid) && historicActivityIds(pid).contains("wf3_rejectedEnd"));
            advance(Duration.ofHours(80));
            adapter.executeDueJobs();
            ctx.check("no escalation after the instance ended", tasks(pid, "wf3_slaEscalation").isEmpty());
        });
    }

    @Test
    void wf3RejectAtApproval() {
        scenario("WF3-REJECT-AT-APPROVAL", ctx -> {
            String bk = key("LIC");
            String pid = adapter.startProcess(null, "wf3-licence", bk, vars("applicationId", bk));
            complete(ctx, pid, "wf3_scrutiny", "licence-scrutiny", vars("scrutinyDecision", "accept"));
            complete(ctx, pid, "wf3_inspection", "licence-inspector", vars());
            complete(ctx, pid, "wf3_approval", "licence-approver", vars("approvalDecision", "reject"));
            ctx.check("ended at wf3_rejectedEnd", ended(pid) && historicActivityIds(pid).contains("wf3_rejectedEnd"));
        });
    }

    // ================================================================ CMMN

    @Test
    void wf3CaseCmmn() {
        scenario("WF3-CASE-CMMN", ctx -> {
            clock(BASE);
            String bk = key("CASE");
            String cid = adapter.startCase(null, "wf3-licence-case", bk, vars("applicationId", bk));
            List<TaskView> t1 = tasks(cid, null);
            ctx.require("one case task after start (found " + t1.size() + ")", t1.size() == 1);
            ctx.check("first task is Scrutiny", "Scrutiny".equals(t1.get(0).name()));
            advance(Duration.ofMinutes(1));
            adapter.claimTask(t1.get(0).id(), "case-worker");
            adapter.completeTask(t1.get(0).id(), vars("scrutinyOk", true));
            List<TaskView> t2 = tasks(cid, null);
            ctx.require("one case task after scrutiny (found " + t2.size() + ")", t2.size() == 1);
            ctx.check("second task is Inspection", "Inspection".equals(t2.get(0).name()));
            advance(Duration.ofMinutes(1));
            adapter.completeTask(t2.get(0).id(), vars());
            adapter.executeDueJobs();
            ctx.check("case ended", ended(cid));
            List<String> history = caseHistory(cid);
            ctx.detail("caseHistory", history);
            List<String> kinds = new ArrayList<>();
            for (String h : history) {
                if (!h.endsWith(":completed")) {
                    continue;
                }
                String id = h.substring(0, h.indexOf(':'));
                if (id.contains("scrutiny")) {
                    kinds.add("Scrutiny");
                } else if (id.contains("inspection")) {
                    kinds.add("Inspection");
                } else if (id.contains("approved")) {
                    kinds.add("Approved");
                }
            }
            ctx.check("history order Scrutiny -> Inspection -> Approved, each recorded COMPLETED (got " + kinds + ")",
                    kinds.equals(List.of("Scrutiny", "Inspection", "Approved")));
        });
    }
}
