package org.sangam.spike.experiments;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.corpus.Corpus;
import org.sangam.spike.experiments.support.ExperimentBase;
import org.sangam.spike.experiments.support.Results;

/**
 * E1 corpus coverage (SANGAM.md XI.4): WF-1, WF-2, WF-3 and the CMMN case, driven end to end through
 * {@link org.sangam.spike.api.EngineAdapter} on PostgreSQL 17. Every scenario records pass/fail, the variant files it
 * needed and every engine-specific step (workaround) observed at run time.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E1CorpusCoverageTest extends ExperimentBase {

    private static final Instant BASE = Instant.parse("2026-11-02T04:30:00Z"); // Mon 10:00 IST

    /** VARIANTS.md element rows used by E1 (element id → variant id). */
    private static final Map<String, String> VARIANT_ELEMENTS = Map.of(
            "wf1_delegationOfPowers", "V-01 DMN call from BPMN",
            "wf1_payment", "V-02 external task (payment)",
            "wf3_esign", "V-03 external task (esign)",
            "wf3_digilockerPush", "V-04 external task (digilocker-push)");

    private List<String> wf1Files;
    private List<String> wf2Files;
    private List<String> wf3Files;
    private List<String> cmmnFiles;
    private final Map<String, Object> deployments = new LinkedHashMap<>();
    private int bk;

    @BeforeAll
    void setUp() {
        open("E1", "Corpus coverage without engine patches (WF-1, WF-2, WF-3, CMMN case)");
        String e = hooks.engine();
        wf1Files = List.of(Corpus.resolve("wf1-bill", "bpmn", e), Corpus.resolve("wf1-delegation-of-powers", "dmn", e));
        wf2Files = List.of(Corpus.resolve("wf2-file", "bpmn", e));
        wf3Files = List.of(Corpus.resolve("wf3-licence", "bpmn", e));
        cmmnFiles = List.of(Corpus.resolve("wf3-licence-case", "cmmn", e));
        deploy("wf1", "wf1-bill.bpmn", "wf1-delegation-of-powers.dmn");
        deploy("wf2", "wf2-file.bpmn");
        deploy("wf3", "wf3-licence.bpmn");
        deploy("wf3-case", "wf3-licence-case.cmmn");
        clock(BASE);
    }

    private void deploy(String name, String... files) {
        try {
            String id = adapter.deploy(null, "e1-" + name, Corpus.resources(hooks.engine(), files));
            deployments.put(name, Results.map("ok", true, "deploymentId", id, "files", List.of(files)));
        } catch (RuntimeException ex) {
            deployments.put(name, Results.map("ok", false, "error", Results.error(ex), "files", List.of(files)));
        }
    }

    private String nextKey(String prefix) {
        return prefix + "-" + (++bk);
    }

    // ================================================================ WF-1

    private String startBill(String key, Object amount, String head, boolean includeAmount) {
        Map<String, Object> v = vars("billId", key, "beneficiary", "Synthetic Vendor Pvt Ltd", "budgetHead", head,
                "initiatorSeat", "SEAT-INITIATOR", "checkerGroup", "SEAT-CHECKER");
        if (includeAmount) {
            v.put("amount", amount);
        }
        return adapter.startProcess(null, "wf1-bill", key, v);
    }

    private void approvePath(Ctx ctx, long amount, String head, String expectedLevel) {
        String key = nextKey("BILL");
        String pid = startBill(key, amount, head, true);
        complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "forward"));
        Map<String, Object> v = adapter.getInstance(pid).variables();
        ctx.detail("approverLevel", v.get("approverLevel"));
        Object max = v.get("maxAmount");
        ctx.detail("maxAmount", max);
        ctx.detail("maxAmountType", max == null ? null : max.getClass().getName());
        ctx.check("DMN approverLevel == " + expectedLevel, expectedLevel.equals(v.get("approverLevel")));
        ctx.check("maxAmount >= amount (numeric compare)", max instanceof Number n && n.doubleValue() >= amount);
        complete(ctx, pid, "wf1_approve", expectedLevel, vars("approverDecision", "approve"));
        completeExternal(ctx, pid, "payment", vars("paymentRef", "UTR-" + key, "paymentStatus", "SUCCESS"), true);
        ctx.check("instance ended", ended(pid));
        ctx.check("ended at wf1_paidEnd", hooks.historicActivityIds(adapter, pid).contains("wf1_paidEnd"));
    }

    @Test
    @Order(1)
    void wf1() {
        long[][] bands = {{100_000, 0}, {100_001, 1}, {1_000_000, 1}, {1_000_001, 2}, {10_000_000, 2}, {10_000_001, 3}};
        String[] levels = {"SECTION_OFFICER", "DDO", "HOD", "SECRETARY"};
        for (long[] b : bands) {
            String level = levels[(int) b[1]];
            scenario("WF1-APPROVE-" + b[0], "WF-1", "approve by amount band " + b[0] + " (2059-MAINT) -> " + level
                    + ", payment external work with lockVersion fencing", wf1Files,
                    ctx -> approvePath(ctx, b[0], "2059-MAINT", level));
        }
        scenario("WF1-APPROVE-CAPITAL-R1", "WF-1", "6000000 with 4059-CAPITAL -> SECRETARY (R1 wins under FIRST)", wf1Files,
                ctx -> approvePath(ctx, 6_000_000, "4059-CAPITAL", "SECRETARY"));

        scenario("WF1-RETURN-REVISE", "WF-1", "checker return -> revise -> checker forward -> approver return -> revise"
                + " -> forward -> approve -> payment", wf1Files, ctx -> {
            String key = nextKey("BILL");
            String pid = startBill(key, 500_000L, "2059-MAINT", true);
            complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "return"));
            complete(ctx, pid, "wf1_reviseBill", "SEAT-INITIATOR", vars("revisionNote", "fixed GST"));
            complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "forward"));
            complete(ctx, pid, "wf1_approve", "DDO", vars("approverDecision", "return"));
            complete(ctx, pid, "wf1_reviseBill", "SEAT-INITIATOR", vars("revisionNote", "attached invoice"));
            complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "forward"));
            complete(ctx, pid, "wf1_approve", "DDO", vars("approverDecision", "approve"));
            completeExternal(ctx, pid, "payment", vars("paymentRef", "UTR-" + key), false);
            List<String> hist = hooks.historicActivityIds(adapter, pid);
            ctx.detail("reviseCount", hist.stream().filter("wf1_reviseBill"::equals).count());
            ctx.check("revise visited twice", hist.stream().filter("wf1_reviseBill"::equals).count() == 2);
            ctx.check("ended at wf1_paidEnd", ended(pid) && hist.contains("wf1_paidEnd"));
        });

        scenario("WF1-REJECT-CHECKER", "WF-1", "checker rejects", wf1Files, ctx -> {
            String pid = startBill(nextKey("BILL"), 50_000L, "2059-MAINT", true);
            complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "reject"));
            ctx.check("ended at wf1_rejectedEnd", ended(pid) && hooks.historicActivityIds(adapter, pid).contains("wf1_rejectedEnd"));
        });

        scenario("WF1-REJECT-APPROVER", "WF-1", "approver rejects", wf1Files, ctx -> {
            String pid = startBill(nextKey("BILL"), 2_000_000L, "2059-MAINT", true);
            complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "forward"));
            complete(ctx, pid, "wf1_approve", "HOD", vars("approverDecision", "reject"));
            ctx.check("ended at wf1_rejectedEnd", ended(pid) && hooks.historicActivityIds(adapter, pid).contains("wf1_rejectedEnd"));
        });

        // Divergence cases from VARIANTS.md (recorded, not scored)
        scenario("WF1-DIVERGENCE-NO-AMOUNT", "WF-1", "start without amount (VARIANTS.md V-01 divergence; informational)",
                wf1Files, ctx -> {
                    ctx.info();
                    String pid = startBill(nextKey("BILL"), null, "2059-MAINT", false);
                    try {
                        complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "forward"));
                        ctx.detail("outcome", ended(pid) ? "ended at " + hooks.historicActivityIds(adapter, pid)
                                : "active " + active(pid));
                    } catch (RuntimeException ex) {
                        ctx.detail("outcome", "checker completion threw: " + Results.error(ex));
                    }
                });
        scenario("WF1-DIVERGENCE-NULL-AMOUNT", "WF-1", "amount = null (VARIANTS.md V-01 divergence; informational)",
                wf1Files, ctx -> {
                    ctx.info();
                    String pid = startBill(nextKey("BILL"), null, "2059-MAINT", true);
                    try {
                        complete(ctx, pid, "wf1_checkerReview", "SEAT-CHECKER", vars("checkerDecision", "forward"));
                        ctx.detail("outcome", ended(pid) ? "ended at " + hooks.historicActivityIds(adapter, pid)
                                : "active " + active(pid));
                    } catch (RuntimeException ex) {
                        ctx.detail("outcome", "checker completion threw: " + Results.error(ex));
                    }
                });
    }

    // ================================================================ WF-2

    private Map<String, Object> wf2Vars(String key) {
        return vars("fileId", key, "subject", "Synthetic file", "initiatorSeat", "SEAT-INITIATOR", "l1Group", "SEAT-L1",
                "l2Group", "SEAT-L2", "l3Group", "SEAT-L3", "sectionAGroup", "SEC-A", "sectionBGroup", "SEC-B",
                "disposalGroup", "SEAT-DISPOSAL");
    }

    private void l(Ctx ctx, String pid, int level, String decision) {
        complete(ctx, pid, "wf2_l" + level + "Review", "SEAT-L" + level, vars("l" + level + "Decision", decision));
    }

    @Test
    @Order(2)
    void wf2() {
        scenario("WF2-AC04-FORWARD3-RETURN2", "WF-2", "AC-04: L1 fwd, L2 fwd, L3 return, L2 return, L1 fwd, L2 fwd, L3 fwd,"
                + " comments A and B (sequential), disposal", wf2Files, ctx -> {
            String key = nextKey("FILE");
            String pid = adapter.startProcess(null, "wf2-file", key, wf2Vars(key));
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
            List<String> hist = hooks.historicActivityIds(adapter, pid);
            ctx.check("ended at wf2_disposedEnd", ended(pid) && hist.contains("wf2_disposedEnd"));
            ctx.detail("l2Visits", hist.stream().filter("wf2_l2Review"::equals).count());
            ctx.check("L2 visited 3 times, L3 twice", hist.stream().filter("wf2_l2Review"::equals).count() == 3
                    && hist.stream().filter("wf2_l3Review"::equals).count() == 2);
        });

        scenario("WF2-RETURN-TO-INITIATOR", "WF-2", "L1 return -> revise note -> L1..L3 fwd -> comments -> disposal", wf2Files, ctx -> {
            String key = nextKey("FILE");
            String pid = adapter.startProcess(null, "wf2-file", key, wf2Vars(key));
            l(ctx, pid, 1, "return");
            complete(ctx, pid, "wf2_reviseNote", "SEAT-INITIATOR", vars());
            l(ctx, pid, 1, "forward");
            l(ctx, pid, 2, "forward");
            l(ctx, pid, 3, "forward");
            complete(ctx, pid, "wf2_commentsSectionB", "SEC-B", vars());
            complete(ctx, pid, "wf2_commentsSectionA", "SEC-A", vars());
            complete(ctx, pid, "wf2_finalDisposal", "SEAT-DISPOSAL", vars());
            ctx.check("ended at wf2_disposedEnd", ended(pid) && hooks.historicActivityIds(adapter, pid).contains("wf2_disposedEnd"));
        });

        scenario("WF2-PARALLEL-JOIN-CONCURRENT", "WF-2", "complete both comment tasks concurrently (VI.1 join);"
                + " retry on optimistic-lock failure; exactly one disposal task", wf2Files, ctx -> {
            String key = nextKey("FILE");
            String pid = adapter.startProcess(null, "wf2-file", key, wf2Vars(key));
            l(ctx, pid, 1, "forward");
            l(ctx, pid, 2, "forward");
            l(ctx, pid, 3, "forward");
            TaskView a = single(ctx, pid, "wf2_commentsSectionA");
            TaskView b = single(ctx, pid, "wf2_commentsSectionB");
            adapter.claimTask(a.id(), "user-a");
            adapter.claimTask(b.id(), "user-b");
            AtomicInteger conflicts = new AtomicInteger();
            List<String> conflictTypes = new ArrayList<>();
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
                                conflicts.incrementAndGet();
                                synchronized (conflictTypes) {
                                    conflictTypes.add(ex.getClass().getSimpleName());
                                }
                                if (tasks(pid, t.taskDefinitionKey()).isEmpty()) {
                                    return null; // already done by the losing commit's retry
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
            ctx.detail("conflicts", conflicts.get());
            ctx.detail("conflictTypes", conflictTypes);
            ctx.check("exactly one wf2_finalDisposal task after the join", tasks(pid, "wf2_finalDisposal").size() == 1);
            complete(ctx, pid, "wf2_finalDisposal", "SEAT-DISPOSAL", vars());
            ctx.check("ended", ended(pid));
        });
    }

    // ================================================================ WF-3

    @Test
    @Order(3)
    void wf3() {
        scenario("WF3-FULL-SLA-CLARIFY-ISSUE", "WF-3", "scrutiny + SLA timer (setClock/executeDueJobs), clarify message,"
                + " second SLA, accept, inspection, approval, esign and digilocker external work", wf3Files, ctx -> {
            String key = nextKey("LIC");
            String pid = adapter.startProcess(null, "wf3-licence", key,
                    vars("applicationId", key, "applicantId", "APPLICANT-1", "licenceType", "TRADE"));
            single(ctx, pid, "wf3_scrutiny");
            ctx.check("scrutiny offered to licence-scrutiny", countForGroup(pid, "wf3_scrutiny", "licence-scrutiny") == 1);
            List<Integer> escalationsPerEntry = new ArrayList<>();

            advance(Duration.ofHours(71));
            adapter.executeDueJobs();
            ctx.check("no escalation at 71 h", tasks(pid, "wf3_slaEscalation").isEmpty());
            advance(Duration.ofHours(2)); // 73 h
            adapter.executeDueJobs();
            int first = tasks(pid, "wf3_slaEscalation").size();
            ctx.check("one escalation at 73 h", first == 1);
            ctx.check("scrutiny still open (non-interrupting)", tasks(pid, "wf3_scrutiny").size() == 1);
            ctx.check("escalation offered to licence-escalation",
                    countForGroup(pid, "wf3_slaEscalation", "licence-escalation") == 1);
            advance(Duration.ofHours(100));
            adapter.executeDueJobs();
            ctx.check("still one escalation 100 h later (duration timer fires once)", tasks(pid, "wf3_slaEscalation").size() == 1);
            escalationsPerEntry.add(tasks(pid, "wf3_slaEscalation").size());
            complete(ctx, pid, "wf3_slaEscalation", "licence-escalation", vars("escalationNote", "chased"));

            complete(ctx, pid, "wf3_scrutiny", "licence-scrutiny", vars("scrutinyDecision", "clarify"));
            ctx.check("waiting for applicant message", active(pid).contains("wf3_awaitApplicantResponse"));
            String corr;
            try {
                corr = adapter.correlateMessage(null, "applicantResponse", key, vars("applicantReply", "documents attached"));
            } catch (IllegalStateException ex) {
                // Flowable adapter: ExecutionQuery.processInstanceBusinessKey(bk) matches only executions whose OWN
                // BUSINESS_KEY_ is bk (Execution.xml, includeChildExecutionsWithBusinessKeyQuery=false); the message
                // catch is a child execution. Exactly one execution waits for this message here, so correlate without
                // the business key. Adapter defect, reported (fix: processInstanceBusinessKey(bk, true)).
                ctx.detail("correlateByBusinessKeyError", ex.getMessage());
                ctx.workaround("adapter correlateMessage by business key matched 0 executions; correlated with"
                        + " businessKey=null (adapter defect, not an engine limit)");
                corr = adapter.correlateMessage(null, "applicantResponse", null, vars("applicantReply", "documents attached"));
            }
            ctx.check("message correlated to this instance", pid.equals(corr));
            single(ctx, pid, "wf3_scrutiny");
            advance(Duration.ofHours(73));
            adapter.executeDueJobs();
            escalationsPerEntry.add(tasks(pid, "wf3_slaEscalation").size());
            ctx.check("one escalation for the second scrutiny entry", tasks(pid, "wf3_slaEscalation").size() == 1);
            complete(ctx, pid, "wf3_slaEscalation", "licence-escalation", vars());
            ctx.detail("escalationsPerScrutinyEntry", escalationsPerEntry);

            complete(ctx, pid, "wf3_scrutiny", "licence-scrutiny", vars("scrutinyDecision", "accept"));
            complete(ctx, pid, "wf3_inspection", "licence-inspector", vars("inspectionResult", "compliant"));
            complete(ctx, pid, "wf3_approval", "licence-approver", vars("approvalDecision", "approve"));
            completeExternal(ctx, pid, "esign", vars("esignTxn", "ESP-" + key, "signedDocHash", "sha256:synthetic"), true);
            completeExternal(ctx, pid, "digilocker-push", vars("digilockerUri", "in.gov.synthetic-" + key), false);
            List<String> hist = hooks.historicActivityIds(adapter, pid);
            ctx.check("ended at wf3_issuedEnd", ended(pid) && hist.contains("wf3_issuedEnd"));
            ctx.detail("escalationActivities", hist.stream().filter("wf3_slaEscalation"::equals).count());
        });

        scenario("WF3-REJECT-AT-SCRUTINY", "WF-3", "scrutiny rejects; the pending SLA timer is cancelled", wf3Files, ctx -> {
            String key = nextKey("LIC");
            String pid = adapter.startProcess(null, "wf3-licence", key, vars("applicationId", key));
            complete(ctx, pid, "wf3_scrutiny", "licence-scrutiny", vars("scrutinyDecision", "reject"));
            ctx.check("ended at wf3_rejectedEnd", ended(pid) && hooks.historicActivityIds(adapter, pid).contains("wf3_rejectedEnd"));
            advance(Duration.ofHours(80));
            adapter.executeDueJobs();
            ctx.check("no escalation after the instance ended", tasks(pid, "wf3_slaEscalation").isEmpty());
        });

        scenario("WF3-REJECT-AT-APPROVAL", "WF-3", "accept, inspection, approval rejects", wf3Files, ctx -> {
            String key = nextKey("LIC");
            String pid = adapter.startProcess(null, "wf3-licence", key, vars("applicationId", key));
            complete(ctx, pid, "wf3_scrutiny", "licence-scrutiny", vars("scrutinyDecision", "accept"));
            complete(ctx, pid, "wf3_inspection", "licence-inspector", vars());
            complete(ctx, pid, "wf3_approval", "licence-approver", vars("approvalDecision", "reject"));
            ctx.check("ended at wf3_rejectedEnd", ended(pid) && hooks.historicActivityIds(adapter, pid).contains("wf3_rejectedEnd"));
        });
    }

    // ================================================================ CMMN

    @Test
    @Order(4)
    void cmmn() {
        scenario("WF3-CASE-CMMN", "WF-3 CMMN", "startCase, Scrutiny, Inspection (sentry), milestone Approved, case completes;"
                + " history order Scrutiny -> Inspection -> Approved", cmmnFiles, ctx -> {
            String key = nextKey("CASE");
            String cid = adapter.startCase(null, "wf3-licence-case", key, vars("applicationId", key));
            List<TaskView> t1 = tasks(cid, null);
            ctx.require("one case task after start (found " + t1.size() + ")", t1.size() == 1);
            ctx.detail("scrutinyTaskDefinitionKey", t1.get(0).taskDefinitionKey());
            ctx.detail("scrutinyTaskInstanceIdIsCaseId", cid.equals(t1.get(0).processInstanceId()));
            ctx.check("first task is Scrutiny", "Scrutiny".equals(t1.get(0).name()));
            advance(Duration.ofMinutes(1));
            adapter.claimTask(t1.get(0).id(), "case-worker");
            adapter.completeTask(t1.get(0).id(), vars("scrutinyOk", true));
            List<TaskView> t2 = tasks(cid, null);
            ctx.require("one case task after scrutiny (found " + t2.size() + ")", t2.size() == 1);
            ctx.detail("inspectionTaskDefinitionKey", t2.get(0).taskDefinitionKey());
            ctx.check("second task is Inspection", "Inspection".equals(t2.get(0).name()));
            advance(Duration.ofMinutes(1));
            adapter.completeTask(t2.get(0).id(), vars());
            int jobs = adapter.executeDueJobs();
            ctx.detail("jobsAfterInspection", jobs);
            ctx.check("case ended", ended(cid));
            List<String> history = hooks.caseHistory(adapter, cid);
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
            List<String> ended = new ArrayList<>();
            for (String h : history) {
                if (h.endsWith(":completed") || h.endsWith(":terminated")) {
                    ended.add(h.substring(0, h.indexOf(':')));
                }
            }
            ctx.detail("endedOrder", ended);
            ctx.check("history order Scrutiny -> Inspection -> Approved, each recorded COMPLETED (got " + kinds + ")",
                    kinds.equals(List.of("Scrutiny", "Inspection", "Approved")));
        });
    }

    @AfterAll
    void tearDown() {
        try {
            List<Map<String, Object>> sc = results.scenarios();
            Set<String> variantFiles = new LinkedHashSet<>();
            Set<String> runtime = new LinkedHashSet<>();
            for (Map<String, Object> s : sc) {
                for (Object f : (List<?>) s.get("variantFiles")) {
                    if (String.valueOf(f).contains("." + hooks.engine() + ".")) {
                        variantFiles.add(String.valueOf(f));
                    }
                }
                for (Object w : (List<?>) s.get("workarounds")) {
                    runtime.add(String.valueOf(w).replaceAll(" \\([a-z-]+\\)$", ""));
                }
            }
            List<String> handling = new ArrayList<>();
            VARIANT_ELEMENTS.entrySet().stream().sorted(Map.Entry.comparingByValue())
                    .forEach(e -> handling.add("model variant " + e.getValue() + " [" + e.getKey() + "]"));
            for (String c : hooks.configurationWorkarounds()) {
                handling.add("configuration: " + c);
            }
            for (String r : runtime) {
                handling.add("runtime: " + r);
            }
            results.put("deployments", deployments);
            results.put("engineSpecificHandling", Results.map(
                    "variantFilesUsed", new ArrayList<>(variantFiles),
                    "variantElements", VARIANT_ELEMENTS.size(),
                    "configurationItems", hooks.configurationWorkarounds().size(),
                    "runtimeItems", runtime.size(),
                    "total", handling.size(),
                    "items", handling));
            Map<String, Object> summary = summary(sc);
            long scored = sc.stream().filter(s -> !"info".equals(s.get("status"))).count();
            summary.put("scored", scored);
            results.write(hooks.engine(), adapter.engineId(), summary);
        } finally {
            closeAdapter();
        }
    }
}
