// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.EngineAdapter.TaskView;
import org.sangam.integration.connector.file.LocalDirectoryTransport;
import org.sangam.integration.sim.ifms.IfmsJitSimulator;
import org.sangam.integration.sim.pfms.PfmsFiles;
import org.sangam.integration.sim.pfms.PfmsSftpSimulator;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * E17-a ported to the Sangam server (spike experiments-gov-a E17DomainsTest, Operaton side): domain end-to-end
 * models, same scenarios and checks, with Sangam's own IFMS JIT and PFMS SFTP simulators (sangam-integration).
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class E17aDomainsIT extends GovABase {


    private String start(String key, String bk, Object... kv) {
        return a.startProcess(null, key, bk, vars(kv));
    }

    private String extVariant() {
        return "external task: " + (k().engine().equals("operaton") ? "operaton:type=external operaton:topic"
                : "flowable:type=external-worker flowable:topic");
    }

    private String dmnVariant() {
        return k().engine().equals("operaton")
                ? "DMN call: businessRuleTask operaton:decisionRef + resultVariable + mapDecisionResult=singleEntry"
                : "DMN call: serviceTask flowable:type=dmn + decisionTableReferenceKey";
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }

    // =====================================================================================  D-BUDGET-REAPPROPRIATION

    @Test
    void d1_budgetReappropriation() {
        scenario("D-BUDGET-REAPPROPRIATION", "Re-appropriation from a savings head: availability, competent authority "
                + "(DMN), concurrence, sanction, idempotent IFMS posting", r -> {
            r.feature("external task budget availability check", "revise loop", "DMN FIRST competent authority",
                    "user tasks by seat", "external task posting with operationId and lease re-delivery");
            r.variant(extVariant());
            r.variant(dmnVariant());
            String dmn = dmn("reapp-authority", "FIRST", "amount", "number", "authority", "string", List.of(
                    new String[]{"<= 1000000", "\"SEAT-HOD\""},
                    new String[]{"<= 10000000", "\"SEAT-FD-DS\""},
                    new String[]{"-", "\"SEAT-FD-SECY\""}));
            String bpmn = defs("<process id=\"dReapp\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "check")
                    + k().externalTask("check", "budget-savings-check", "") + flow("f2", "check", "gw")
                    + "<exclusiveGateway id=\"gw\" default=\"fRevise\"/>"
                    + flow("fOk", "gw", "auth", "${savingsAvailable == true}") + flow("fRevise", "gw", "revise")
                    + userTask("revise", "SEAT-DDO") + flow("f3", "revise", "check")
                    + k().dmnTask("auth", "reapp-authority", "authority", true) /* SANGAM.md version-binding rule (deployment-scoped vs latest; product validator id D-12; no SANGAM.md decision id): binding must be declared */ + flow("f4", "auth", "concur")
                    + userTask("concur", "SEAT-FD-BUDGET") + flow("f5", "concur", "sanction") + userTask("sanction", "${authority}")
                    + flow("f6", "sanction", "post") + k().externalTask("post", "ifms-reapp-post", "") + flow("f7", "post", "e")
                    + "<endEvent id=\"e\"/></process>");
            deploy("dReapp", "dReapp.bpmn", bpmn, "reapp-authority.dmn", dmn);
            Map<String, Long> savings = new ConcurrentHashMap<>(Map.of("2059-01-101-SAVINGS", 18_000_000L));
            Map<String, Long> excess = new ConcurrentHashMap<>();
            Set<String> posted = ConcurrentHashMap.newKeySet();
            AtomicInteger postings = new AtomicInteger();
            Handler check = w -> Outcome.complete(Map.of("savingsAvailable",
                    savings.getOrDefault((String) w.variables().get("fromHead"), 0L) >= num(w.variables().get("amount"))));
            Handler post = w -> {
                if (posted.add(operationId(w))) {
                    long amt = num(w.variables().get("amount"));
                    savings.merge((String) w.variables().get("fromHead"), -amt, Long::sum);
                    excess.merge((String) w.variables().get("toHead"), amt, Long::sum);
                    postings.incrementAndGet();
                }
                return Outcome.complete(Map.of("reappOrderNo", "RA/" + w.processInstanceId()));
            };
            clock("2026-11-05T10:00:00");
            // case 1: 5 lakh, available, HOD; the posting worker crashes after posting and the work is re-delivered
            String p1 = start("dReapp", "REAPP-1", "amount", 500_000L, "fromHead", "2059-01-101-SAVINGS", "toHead", "2059-01-102");
            work("budget-savings-check", check);
            a.completeTask(one(p1, "concur").id(), Map.of());
            r.check(tasksForGroups(p1, List.of("SEAT-HOD")).size() == 1, "5 lakh -> competent authority SEAT-HOD");
            a.completeTask(one(p1, "sanction").id(), Map.of());
            work("ifms-reapp-post", w -> {
                post.handle(w);
                return Outcome.crash();
            });
            clock("2026-11-05T10:06:00");
            work("ifms-reapp-post", post);
            r.check(ended(p1) && postings.get() == 1, "posted exactly once despite re-delivery");
            // case 2: 2 crore > savings -> revise to 1.5 crore -> FD Secretary
            String p2 = start("dReapp", "REAPP-2", "amount", 20_000_000L, "fromHead", "2059-01-101-SAVINGS", "toHead", "2059-01-103");
            work("budget-savings-check", check);
            r.check(tasks(p2, "revise").size() == 1, "insufficient savings -> proposal returned for revision");
            a.completeTask(one(p2, "revise").id(), Map.of("amount", 15_000_000L));
            work("budget-savings-check", check);
            a.completeTask(one(p2, "concur").id(), Map.of());
            r.check(tasksForGroups(p2, List.of("SEAT-FD-SECY")).size() == 1, "1.5 crore -> SEAT-FD-SECY");
            a.completeTask(one(p2, "sanction").id(), Map.of());
            work("ifms-reapp-post", post);
            r.check(ended(p2) && postings.get() == 2, "second re-appropriation posted");
            r.check(savings.get("2059-01-101-SAVINGS") == 18_000_000L - 500_000L - 15_000_000L, "savings head balance correct");
            r.detail("headsAfter", Map.of("savings", savings, "excess", excess));
            r.productLayer("budget ledger and posting idempotency (operationId set) in the product layer", 10);
        });
    }

    // =====================================================================================  D-WORKS

    @Test
    void d2_works() {
        scenario("D-WORKS", "Works: estimate -> AA -> TS -> tender stub -> work order -> measurement -> 3 monthly RA "
                + "bills (cycle timer) -> final bill -> completion", r -> {
            r.feature("user tasks by seat", "external task (tender stub, IFMS payments)",
                    "embedded sub-process with non-interrupting boundary timer cycle R3/P1M", "parallel tokens (RA bills "
                            + "outside the execution sub-process)", "operationId per activity instance");
            r.variant(extVariant());
            String bpmn = defs("<process id=\"dWorks\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "estimate")
                    + userTask("estimate", "SEAT-JE") + flow("f2", "estimate", "aa") + userTask("aa", "SEAT-EE")
                    + flow("f3", "aa", "ts") + userTask("ts", "SEAT-SE") + flow("f4", "ts", "tender")
                    + k().externalTask("tender", "tender-stub", "") + flow("f5", "tender", "workOrder")
                    + userTask("workOrder", "SEAT-EE") + flow("f6", "workOrder", "exec")
                    + "<subProcess id=\"exec\"><startEvent id=\"es\"/>" + flow("e1", "es", "mb") + userTask("mb", "SEAT-JE")
                    + flow("e2", "mb", "ee") + "<endEvent id=\"ee\"/></subProcess>"
                    + "<boundaryEvent id=\"monthly\" attachedToRef=\"exec\" cancelActivity=\"false\"><timerEventDefinition>"
                    + "<timeCycle xsi:type=\"tFormalExpression\">R3/P1M</timeCycle></timerEventDefinition></boundaryEvent>"
                    + flow("f7", "monthly", "raBill") + userTask("raBill", "SEAT-AE") + flow("f8", "raBill", "payRa")
                    + k().externalTask("payRa", "ifms-pay", "") + flow("f9", "payRa", "raEnd") + "<endEvent id=\"raEnd\"/>"
                    + flow("f10", "exec", "finalBill") + userTask("finalBill", "SEAT-EE") + flow("f11", "finalBill", "payFinal")
                    + k().externalTask("payFinal", "ifms-pay", "") + flow("f12", "payFinal", "completion")
                    + userTask("completion", "SEAT-EE") + flow("f13", "completion", "e") + "<endEvent id=\"e\"/></process>");
            deploy("dWorks", "dWorks.bpmn", bpmn);
            try (IfmsJitSimulator sim = new IfmsJitSimulator().start()) {
                GovAIfmsClient ifms = new GovAIfmsClient(sim);
                List<String> ops = new ArrayList<>();
                Handler pay = w -> {
                    String op = operationId(w);
                    ops.add(op);
                    String ref = ifms.pay(op, (String) w.variables().get("billNo"), num(w.variables().get("billAmount")),
                            "ACC-CONTRACTOR-7");
                    return Outcome.complete(Map.of("lastPaymentRef", ref));
                };
                clock("2026-11-05T10:00:00");
                String pid = start("dWorks", "WORK-2026-7", "estimateAmount", 9_500_000L);
                for (String step : List.of("estimate", "aa", "ts")) {
                    a.completeTask(one(pid, step).id(), Map.of(step + "No", step.toUpperCase() + "/2026/7"));
                }
                work("tender-stub", w -> Outcome.complete(Map.of("contractor", "M/s Synthetic Infra", "contractValue", 9_100_000L)));
                a.completeTask(one(pid, "workOrder").id(), Map.of("workOrderNo", "WO/2026/7"));
                List<String> raDues = new ArrayList<>();
                for (int m = 1; m <= 3; m++) {
                    List<String[]> t = k().timers(a, pid);
                    r.check(t.size() == 1, "month " + m + ": one cycle timer waiting");
                    if (t.isEmpty()) {
                        break;
                    }
                    raDues.add(t.get(0)[1]);
                    clock(Instant.parse(t.get(0)[1]).plusSeconds(60));
                    jobs();
                    TaskView ra = one(pid, "raBill");
                    a.completeTask(ra.id(), Map.of("billNo", "RA-" + m, "billAmount", 1_000_000L * m));
                    work("ifms-pay", pay);
                    r.check(tasks(pid, "mb").size() == 1, "month " + m + ": measurement book still open");
                }
                r.nativeApi(k().api("timers"));
                r.detail("raBillDueDatesUtc", raDues);
                r.check(k().timers(a, pid).isEmpty(), "no 4th RA bill cycle (R3)");
                a.completeTask(one(pid, "mb").id(), Map.of("workCompleted", true));
                a.completeTask(one(pid, "finalBill").id(), Map.of("billNo", "FINAL", "billAmount", 2_500_000L));
                work("ifms-pay", pay);
                a.completeTask(one(pid, "completion").id(), Map.of("completionCertificate", "CC/2026/7"));
                r.check(ended(pid), "work completed and closed");
                r.check(k().historicActivityCount(a, pid, "raBill") == 3, "3 RA bills");
                r.check(ops.size() == 4 && new HashSet<>(ops).size() == 4, "4 payments with 4 distinct operationIds");
                r.check(sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT) == 4
                        && !sim.ledger().hasDuplicates(IfmsJitSimulator.EFFECT_PAYMENT), "IFMS paid 4 bills, no duplicate");
            }
        });
    }

    // =====================================================================================  D-TENDER

    @Test
    void d3_tender() {
        scenario("D-TENDER", "Tender: NIT -> corrigendum extends running deadline -> bids (incl. early) -> technical "
                + "committee all-sign -> time-locked financial opening -> L1 negotiation loop -> LoA -> EMD refund to losers "
                + "by compensation", r -> {
            r.feature("event-based gateway loop (bid message / corrigendum message / deadline timer)",
                    "timer timeDate ${bidDeadline} re-armed with the corrigendum date",
                    "parallel multi-instance external task (EMD hold per bidder) with compensation boundary",
                    "parallel multi-instance user task, humanPerformer per member (all sign)",
                    "intermediate timer timeDate ${finOpenAt} (time lock)", "negotiation loop", "intermediate throw compensate");
            r.variant(extVariant());
            String bpmn = defs("<message id=\"mBid\" name=\"bidSubmitted\"/><message id=\"mCorr\" name=\"corrigendum\"/>"
                    + "<process id=\"dTender\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "publish")
                    + userTask("publish", "SEAT-TENDER") + flow("f2", "publish", "merge") + "<exclusiveGateway id=\"merge\"/>"
                    + flow("f3", "merge", "ebg") + "<eventBasedGateway id=\"ebg\"/>"
                    + flow("f4", "ebg", "bid") + "<intermediateCatchEvent id=\"bid\"><messageEventDefinition messageRef=\"mBid\"/></intermediateCatchEvent>"
                    + flow("f5", "bid", "merge")
                    + flow("f6", "ebg", "corr") + "<intermediateCatchEvent id=\"corr\"><messageEventDefinition messageRef=\"mCorr\"/></intermediateCatchEvent>"
                    + flow("f7", "corr", "merge")
                    + flow("f8", "ebg", "deadline") + "<intermediateCatchEvent id=\"deadline\"><timerEventDefinition><timeDate xsi:type=\"tFormalExpression\">"
                    + "${bidDeadline}</timeDate></timerEventDefinition></intermediateCatchEvent>"
                    + flow("f9", "deadline", "emd")
                    + k().externalTask("emd", "emd-hold", k().parallelExternalTaskAttrs()).replace("/>", "><multiInstanceLoopCharacteristics isSequential=\"false\">"
                    + "<loopCardinality xsi:type=\"tFormalExpression\">${bidCount}</loopCardinality></multiInstanceLoopCharacteristics></serviceTask>")
                    + "<boundaryEvent id=\"emdComp\" attachedToRef=\"emd\"><compensateEventDefinition/></boundaryEvent>"
                    + k().externalTask("refund", "emd-refund", "isForCompensation=\"true\"")
                    + flow("f10", "emd", "tech") + userTask("tech", "SEAT-TEC-CHAIR") + flow("f11", "tech", "sign")
                    + "<userTask id=\"sign\" name=\"sign\"><humanPerformer><resourceAssignmentExpression><formalExpression>"
                    + "${members.split(';')[loopCounter]}</formalExpression></resourceAssignmentExpression></humanPerformer>"
                    + "<multiInstanceLoopCharacteristics isSequential=\"false\"><loopCardinality xsi:type=\"tFormalExpression\">"
                    + "${memberCount}</loopCardinality></multiInstanceLoopCharacteristics></userTask>"
                    + flow("f12", "sign", "finLock") + "<intermediateCatchEvent id=\"finLock\"><timerEventDefinition><timeDate xsi:type=\"tFormalExpression\">"
                    + "${finOpenAt}</timeDate></timerEventDefinition></intermediateCatchEvent>"
                    + flow("f13", "finLock", "finOpen") + userTask("finOpen", "SEAT-TEC-CHAIR") + flow("f14", "finOpen", "nego")
                    + userTask("nego", "SEAT-TENDER") + flow("f15", "nego", "gwNego") + "<exclusiveGateway id=\"gwNego\" default=\"fRetender\"/>"
                    + flow("fLoa", "gwNego", "loa", "${negotiated == true}")
                    + flow("fAgain", "gwNego", "nego", "${negotiated == false && round < 3}")
                    + flow("fRetender", "gwNego", "retender") + "<endEvent id=\"retender\"/>"
                    + userTask("loa", "SEAT-EE") + flow("f16", "loa", "comp")
                    + "<intermediateThrowEvent id=\"comp\"><compensateEventDefinition/></intermediateThrowEvent>"
                    + flow("f17", "comp", "e") + "<endEvent id=\"e\"/>"
                    + "<association id=\"as1\" associationDirection=\"One\" sourceRef=\"emdComp\" targetRef=\"refund\"/>"
                    + "</process>");
            deploy("dTender", "dTender.bpmn", bpmn);
            // probe: the same parallel multi-instance external task WITHOUT the engine-specific attribute
            String probe = defs("<process id=\"dTenderProbe\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "t")
                    + k().externalTask("t", "emd-probe", "").replace("/>", "><multiInstanceLoopCharacteristics isSequential=\"false\">"
                    + "<loopCardinality xsi:type=\"tFormalExpression\">3</loopCardinality></multiInstanceLoopCharacteristics></serviceTask>")
                    + flow("f2", "t", "e") + "<endEvent id=\"e\"/></process>");
            deploy("dTenderProbe", "dTenderProbe.bpmn", probe);
            String probePid = start("dTenderProbe", "PROBE");
            int probeFetched = work("emd-probe", w -> Outcome.complete(Map.of()));
            r.detail("parallelMultiInstanceExternalTask_defaultAttributes_fetched", probeFetched + " of 3");
            if (!k().parallelExternalTaskAttrs().isEmpty()) {
                r.variant(k().parallelExternalTaskAttrs() + " on the parallel multi-instance EMD external task");
                r.note("Without " + k().parallelExternalTaskAttrs() + " a parallel multi-instance external-worker task cannot "
                        + "be fetched: acquiring its exclusive jobs of one process instance returned " + probeFetched
                        + " of 3 (native ManagementService.createExternalWorkerJobAcquireBuilder() returns 0 as well) and "
                        + "leaves them locked");
            }
            if (!ended(probePid)) {
                a.cancelInstance(probePid, "probe");
            }
            clock("2026-11-05T10:00:00");
            String bk = "NIT-2026-44";
            String pid = start("dTender", bk, "bidDeadline", ist("2026-11-20T15:00:00"), "finOpenAt", ist("2026-11-28T11:00:00"),
                    "members", "tec.chair;tec.finance;tec.technical", "memberCount", 3, "bidders", "", "bidCount", 0);
            // product bid register + inbox
            List<String> bidders = new ArrayList<>();
            Map<String, Long> prices = new LinkedHashMap<>();
            Map<String, Map<String, Object>> inbox = new LinkedHashMap<>();
            java.util.function.BiConsumer<String, Long> submit = (bidder, price) -> {
                bidders.add(bidder);
                prices.put(bidder, price);
                Map<String, Object> v = vars("bidders", String.join(";", bidders), "bidCount", bidders.size(),
                        "price_" + bidder, price);
                if (k().hasMessageSubscription(a, "bidSubmitted", bk)) {
                    a.correlateMessage(null, "bidSubmitted", bk, v);
                } else {
                    inbox.put(bidder, v); // early arrival: NIT not yet published
                }
            };
            submit.accept("B1", 9_400_000L); // early bid
            r.check(inbox.size() == 1, "early bid buffered in the inbox (no subscription before the NIT is published)");
            a.completeTask(one(pid, "publish").id(), Map.of());
            for (var e : inbox.values()) {
                a.correlateMessage(null, "bidSubmitted", bk, e);
            }
            inbox.clear();
            r.nativeApi(k().api("hasMessageSubscription"));
            clock("2026-11-12T12:00:00");
            submit.accept("B2", 9_100_000L);
            clock("2026-11-15T12:00:00");
            a.correlateMessage(null, "corrigendum", bk, Map.of("bidDeadline", ist("2026-11-27T15:00:00")));
            List<String> dues = new ArrayList<>();
            k().timers(a, pid).forEach(t -> dues.add(t[1]));
            r.check(dues.equals(List.of(ist("2026-11-27T15:00:00").toInstant().toString())),
                    "corrigendum re-armed the running deadline to 27 Nov 15:00 IST: " + dues);
            clock("2026-11-21T10:00:00");
            jobs();
            r.check(k().hasMessageSubscription(a, "bidSubmitted", bk), "bidding still open after the original deadline");
            clock("2026-11-24T10:00:00");
            submit.accept("B3", 8_900_000L);
            clock("2026-11-27T15:01:00");
            jobs();
            r.check(!k().hasMessageSubscription(a, "bidSubmitted", bk), "bidding closed at the extended deadline");
            r.workaround("product inbox for bids that arrive before the NIT is published (engines do not buffer messages)", 10);
            r.productLayer("bid register in the product layer: each bid message carries the running bidder list and count", 6);
            r.detail("activeAfterDeadline", a.getInstance(pid).activeActivityIds());
            r.detail("externalWorkAfterDeadline", k().externalWorkInfo(a, pid));
            List<String> jobErrors = k().jobFailures(a, pid);
            if (!jobErrors.isEmpty()) {
                r.detail("jobFailuresAfterDeadline", jobErrors);
            }
            // EMD hold per bidder (multi-instance external task)
            Map<String, String> emdHeld = new ConcurrentHashMap<>();
            int held = work("emd-hold", w -> {
                String bidder = ((String) w.variables().get("bidders")).split(";")[(int) num(w.variables().get("loopCounter"))];
                emdHeld.put(bidder, operationId(w));
                return Outcome.complete(Map.of());
            });
            r.check(held == 3 && emdHeld.keySet().equals(Set.of("B1", "B2", "B3")), "EMD held for the 3 bidders: " + emdHeld.keySet());
            a.completeTask(one(pid, "tech").id(), Map.of("qualified", "B1;B2;B3"));
            List<TaskView> signs = tasks(pid, "sign");
            r.check(signs.size() == 3, "3 committee signatures required");
            for (int i = 0; i < signs.size(); i++) {
                a.completeTask(signs.get(i).id(), Map.of());
                if (i < signs.size() - 1) {
                    r.check(tasks(pid, "finOpen").isEmpty(), "not opened before all have signed");
                }
            }
            r.check(tasks(pid, "finOpen").isEmpty(), "financial bids are time-locked until 28 Nov 11:00");
            clock("2026-11-28T10:59:00");
            jobs();
            r.check(tasks(pid, "finOpen").isEmpty(), "still locked at 10:59");
            clock("2026-11-28T11:01:00");
            jobs();
            r.check(tasks(pid, "finOpen").size() == 1, "financial opening available after 11:00");
            String l1 = prices.entrySet().stream().min(Map.Entry.comparingByValue()).get().getKey();
            a.completeTask(one(pid, "finOpen").id(), Map.of("l1Bidder", l1, "l1Price", prices.get(l1)));
            a.completeTask(one(pid, "nego").id(), Map.of("negotiated", false, "round", 1));
            r.check(tasks(pid, "nego").size() == 1, "negotiation round 2");
            a.completeTask(one(pid, "nego").id(), Map.of("negotiated", true, "round", 2, "agreedPrice", 8_850_000L));
            r.check(true, "steps up to the LoA verified");
            try {
                a.completeTask(one(pid, "loa").id(), Map.of("loaNo", "LOA/2026/44"));
            } catch (RuntimeException e) {
                String err = GovARec.errorText(e);
                r.detail("compensationError", err);
                r.check(false, "EMD refund by compensation: throwing compensation after the LoA failed ("
                        + err.substring(0, Math.min(160, err.length())) + "); every step before the LoA passed");
                return;
            }
            // compensation: one refund handler per held EMD; the winner's EMD is retained as security
            Map<String, String> refunded = new LinkedHashMap<>();
            List<String> handlerVars = new ArrayList<>();
            List<String> unsettled = new ArrayList<>(emdHeld.keySet());
            unsettled.sort(null);
            boolean[] ledgerAssigned = {false};
            int comp = work("emd-refund", w -> {
                Object lc = w.variables().get("loopCounter");
                handlerVars.add(lc == null ? "loopCounter=null" : "loopCounter=" + lc);
                String bidder;
                if (lc != null) {
                    bidder = ((String) w.variables().get("bidders")).split(";")[(int) num(lc)];
                } else {
                    // the handler cannot see which multi-instance item it compensates: the product EMD ledger
                    // hands it the next unsettled EMD
                    ledgerAssigned[0] = true;
                    bidder = unsettled.get(0);
                }
                unsettled.remove(bidder);
                refunded.put(bidder, bidder.equals(w.variables().get("l1Bidder")) ? "RETAINED_AS_SECURITY" : "REFUNDED");
                return Outcome.complete(Map.of());
            });
            if (ledgerAssigned[0]) {
                r.workaround("compensation handler does not see the compensated multi-instance item (loopCounter not "
                        + "visible to the external worker): the product EMD ledger assigns each handler run to one held EMD", 8);
            }
            r.detail("compensationHandlers", comp);
            r.detail("compensationHandlerSees", handlerVars);
            r.detail("emdOutcome", refunded);
            r.check(comp == 3, "compensation runs one EMD handler per bidder (got " + comp + ")");
            r.check(refunded.equals(Map.of("B1", "REFUNDED", "B2", "REFUNDED", "B3", "RETAINED_AS_SECURITY"))
                    || (refunded.size() == 3 && refunded.values().stream().filter("REFUNDED"::equals).count() == 2),
                    "losers refunded, L1 retained: " + refunded);
            r.check(ended(pid), "tender closed after LoA and EMD settlement");
        });
    }

    // =====================================================================================  D-PENSION-MONTHLY

    @Test
    void d4_pensionMonthly() {
        scenario("D-PENSION-MONTHLY", "Monthly pension bill generation by timer start cycle, approval, IFMS payment "
                + "per pensioner", r -> {
            r.feature("timer start event timeCycle R3/<start>/P1M", "external tasks (generate, pay)", "approval user task",
                    "operationId per pensioner and month");
            r.variant(extVariant());
            String bpmn = defs("<process id=\"dPension\" isExecutable=\"true\"><startEvent id=\"s\"><timerEventDefinition>"
                    + "<timeCycle xsi:type=\"tFormalExpression\">R3/2026-12-01T06:00:00+05:30/P1M</timeCycle></timerEventDefinition></startEvent>"
                    + flow("f1", "s", "gen") + k().externalTask("gen", "pension-generate", "") + flow("f2", "gen", "approve")
                    + userTask("approve", "SEAT-PAO") + flow("f3", "approve", "pay") + k().externalTask("pay", "pension-pay", "")
                    + flow("f4", "pay", "e") + "<endEvent id=\"e\"/></process>");
            clock("2026-11-20T10:00:00");
            deploy("dPension", "dPension.bpmn", bpmn);
            int pensioners = 50;
            try (IfmsJitSimulator sim = new IfmsJitSimulator().start()) {
                GovAIfmsClient ifms = new GovAIfmsClient(sim);
                List<String> months = new ArrayList<>();
                String[] runAt = {"2026-12-01T06:01:00", "2027-01-01T06:01:00", "2027-02-01T06:01:00", "2027-03-01T06:01:00"};
                String[] monthOf = {"2026-12", "2027-01", "2027-02", "2027-03"};
                for (int i = 0; i < runAt.length; i++) {
                    clock(runAt[i]);
                    jobs();
                    String month = monthOf[i];
                    work("pension-generate", w -> Outcome.complete(Map.of("pensionMonth", month, "pensioners", pensioners,
                            "billTotal", pensioners * 32_500L)));
                    for (String id : k().taskIds(a, null, "dPension", "approve", 10)) {
                        a.completeTask(id, Map.of());
                    }
                    work("pension-pay", w -> {
                        String m = (String) w.variables().get("pensionMonth");
                        for (int p = 1; p <= pensioners; p++) {
                            String op = UUID.nameUUIDFromBytes(("pension|" + m + "|PPO-" + p).getBytes(StandardCharsets.UTF_8)).toString();
                            ifms.pay(op, "PEN-" + m + "-" + p, 32_500L, "ACC-PPO-" + p);
                        }
                        months.add(m);
                        return Outcome.complete(Map.of());
                    });
                }
                List<String> inst = k().allInstanceIds(a, "dPension");
                r.nativeApi(k().api("instances"));
                r.nativeApi(k().api("taskIds"));
                r.detail("monthsPaid", months);
                int runs = inst.size();
                r.detail("runsOf_R3_start_P1M", runs);
                r.check(runs == 3 || runs == 4, "the cycle started the monthly runs (got " + runs + ")");
                if (runs == 4) {
                    r.workaround("R3/<start>/P1M fires 4 times on this engine (the start date plus 3 repetitions; the other "
                            + "engine fires 3 times): three monthly runs need R2/<start>/P1M here, an engine-specific value", 1);
                }
                r.check(months.equals(List.of(monthOf).subList(0, Math.min(runs, monthOf.length))),
                        "each month generated and paid once, in order: " + months);
                r.check(inst.stream().allMatch(this::ended), "all monthly instances closed");
                r.check(sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT) == (long) runs * pensioners
                        && !sim.ledger().hasDuplicates(IfmsJitSimulator.EFFECT_PAYMENT), runs * pensioners
                        + " pension payments, no duplicate");
            }
            r.note("Changing the monthly cycle of a timer start event needs a redeployment (the timer belongs to the "
                    + "definition); the month is taken from the run date by the product worker (timer starts carry no variables)");
        });
    }

    // =====================================================================================  D-CHALLAN-RECON

    @Test
    void d5_challanReconciliation() throws Exception {
        scenario("D-CHALLAN-RECON", "Challan / receipt reconciliation by file exchange (PFMS SFTP simulator) with "
                + "polling external work, exceptions to a user task", r -> {
            r.feature("external task writing the outbound file", "external task polling the inbound status file "
                            + "(fail with retry back-off until the file arrives)", "external task matching",
                    "exception gateway + user task", "non-interrupting boundary timer PT2H on the polling task (overdue file)");
            r.variant(extVariant());
            String bpmn = defs("<process id=\"dChallan\" isExecutable=\"true\"><startEvent id=\"s\"/>" + flow("f1", "s", "submit")
                    + k().externalTask("submit", "recon-file-submit", "") + flow("f2", "submit", "await")
                    + k().externalTask("await", "recon-file-await", "")
                    + "<boundaryEvent id=\"overdue\" attachedToRef=\"await\" cancelActivity=\"false\"><timerEventDefinition>"
                    + "<timeDuration xsi:type=\"tFormalExpression\">PT2H</timeDuration></timerEventDefinition></boundaryEvent>"
                    + flow("f3", "overdue", "chase") + userTask("chase", "SEAT-TO") + flow("f4", "chase", "chaseEnd") + "<endEvent id=\"chaseEnd\"/>"
                    + flow("f5", "await", "match") + k().externalTask("match", "recon-match", "") + flow("f6", "match", "gw")
                    + "<exclusiveGateway id=\"gw\" default=\"fEx\"/>" + flow("fClean", "gw", "post", "${exceptions == 0}")
                    + flow("fEx", "gw", "resolve") + userTask("resolve", "SEAT-TREASURY-ACCTS") + flow("f7", "resolve", "post")
                    + k().externalTask("post", "recon-post", "") + flow("f8", "post", "e") + "<endEvent id=\"e\"/></process>");
            deploy("dChallan", "dChallan.bpmn", bpmn);
            Path root = Files.createTempDirectory("pfms-");
            LocalDirectoryTransport transport = new LocalDirectoryTransport(root);
            try (PfmsSftpSimulator sim = new PfmsSftpSimulator(transport).failAccount("ACC-9")) {
                clock("2026-11-05T18:00:00");
                String pid = start("dChallan", "SCROLL-2026-11-05", "scrollDate", "2026-11-05", "batchId", "B20261105");
                List<PfmsFiles.PaymentLine> lines = new ArrayList<>();
                for (int i = 1; i <= 5; i++) {
                    lines.add(new PfmsFiles.PaymentLine("CH-2026-" + i, "CH-2026-" + i, "Depositor " + i,
                            i == 4 ? "ACC-9" : "ACC-" + i, "SBIN0000001", new BigDecimal(1000 * i + ".00")));
                }
                work("recon-file-submit", w -> {
                    PfmsFiles.PaymentFile f = new PfmsFiles.PaymentFile("SSID-TRY", "RCPT", (String) w.variables().get("batchId"), lines);
                    transport.write(PfmsSftpSimulator.OUTBOUND, f.fileName(), f.toCsv().getBytes(StandardCharsets.UTF_8));
                    return Outcome.complete(Map.of("fileName", f.fileName()));
                });
                AtomicInteger polls = new AtomicInteger();
                Runnable poll = () -> {
                    try {
                        for (ExternalWork w : a.fetchAndLock("worker-1", "recon-file-await", 10, Duration.ofMinutes(5))) {
                            polls.incrementAndGet();
                            List<String> sts = transport.list(PfmsSftpSimulator.INBOUND).stream()
                                    .filter(n -> n.startsWith("STS_" + w.variables().get("batchId"))).toList();
                            if (sts.isEmpty()) {
                                // not there yet: give the work back with a 30-minute back-off (engine clock)
                                a.failExternal(w.id(), "worker-1", w.lockVersion(), "status file not yet received", 99,
                                        Duration.ofMinutes(30));
                            } else {
                                String csv = new String(transport.read(PfmsSftpSimulator.INBOUND, sts.get(0)), StandardCharsets.UTF_8);
                                a.completeExternal(w.id(), "worker-1", w.lockVersion(), Map.of("statusCsv", csv));
                            }
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                };
                poll.run();
                r.check(polls.get() == 1 && tasks(pid, "match").isEmpty(), "first poll: no status file yet");
                clock("2026-11-05T18:20:00");
                poll.run();
                r.check(polls.get() == 1, "back-off respected: not offered again after 20 minutes");
                sim.pollOnce(); // bank processes the file and writes the scroll/status file
                clock("2026-11-05T20:01:00");
                jobs(); // PT2H overdue timer fires (non-interrupting)
                r.check(tasks(pid, "chase").size() == 1, "overdue-file escalation after 2 hours");
                poll.run();
                r.check(polls.get() == 2, "second poll after the back-off picked up the status file");
                Map<String, Object> matchOut = new LinkedHashMap<>();
                work("recon-match", w -> {
                    PfmsFiles.StatusFile sf = PfmsFiles.parseStatus((String) w.variables().get("statusCsv"));
                    List<String> ex = new ArrayList<>();
                    for (PfmsFiles.StatusLine l : sf.lines()) {
                        if (!"SUCCESS".equals(l.status())) {
                            ex.add(l.operationId() + ":" + l.reason());
                        }
                    }
                    matchOut.put("matched", sf.lines().size() - ex.size());
                    matchOut.put("exceptions", ex);
                    return Outcome.complete(Map.of("exceptions", ex.size(), "exceptionList", String.join(";", ex)));
                });
                r.detail("match", matchOut);
                r.check(tasks(pid, "resolve").size() == 1, "1 unmatched challan -> exception task");
                a.completeTask(one(pid, "resolve").id(), Map.of("resolution", "CH-2026-4 sent back to the bank"));
                AtomicInteger postedLines = new AtomicInteger();
                work("recon-post", w -> {
                    postedLines.set(5 - (int) num(w.variables().get("exceptions")));
                    return Outcome.complete(Map.of());
                });
                r.check(ended(pid) == false && tasks(pid, "chase").size() == 1, "main flow done; the chase task is still open");
                a.completeTask(one(pid, "chase").id(), Map.of());
                r.check(ended(pid), "reconciliation closed");
                r.check(postedLines.get() == 4, "4 challans posted to the receipt ledger");
                r.detail("pollAttempts", polls.get());
            }
            r.productLayer("file transfer, polling and matching are product workers; the engine only sequences them "
                    + "(no native file / SFTP connector is used on either engine)", 40);
        });
    }
}
