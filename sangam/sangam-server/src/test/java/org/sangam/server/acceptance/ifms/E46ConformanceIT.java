// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.ifms;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.runtime.Incident;
import org.sangam.core.api.EngineAdapter.TaskView;
import org.sangam.server.acceptance.engine.EngineScenarioSupport;

/**
 * The frozen E-46 v0.8 workflow-conformance fixtures WF-1..WF-9 ({@code acceptance/e46}, byte-identical copies,
 * SHA-256 verified here) deployed through the product deploy path and executed on the Tier-1 database. Paths and
 * expected results are the kit's {@code T12Fixtures} re-expressed against Sangam; WF-5 (negative) must be refused at
 * deploy with every planted item named. Two Sangam rules are proven on top: strict XOR at the band edges
 * 99,999 / 1,00,000 / 1,00,001 with overlapping bands raising AMBIGUOUS_ROUTE (ADR-D-25), and a deemed/escalation
 * timer never approving. The fixtures carry no Sangam- or domain-specific Java; this class knows no department.
 */
class E46ConformanceIT extends EngineScenarioSupport {

    private static final Map<String, String> SHA256 = Map.of(
            "e46-wf1.bpmn", "167df15c261cac014df6692213422d34800ee391fd00d401d5b4d0533c730395",
            "e46-wf2.bpmn", "fd838300cc2fd07b699be4840b28e6885af401abdbc21bbea53ca5ae1637639b",
            "e46-wf3.bpmn", "f0faf33fc5d943f62272640030732bf7801970bbd9b15cec5b2d8f76fcedcd3c",
            "e46-wf4.bpmn", "30abead9e70dd73845b51a6d017a789235390405a2c51c8620807413560b8ab7",
            "e46-wf5.bpmn", "b4572c2df517f78ba4a5f8c97400ec0027e4ab4ec2f82801e78546ecc5e59589",
            "e46-wf6.bpmn", "ff7135baf9297dcb7347965696fd3364efbf29123302cc7ae6d570a6ed542f1d",
            "e46-wf7.bpmn", "46d45aea9d1f280de36e6aaa09a17396da2bde7a7ad6b23a1be9f58706b8aa48",
            "e46-wf8-child.bpmn", "3926b185e771e5e004327fc691e091d0d3ada0a0ade35d3c0afa0c4fa64cf6e7",
            "e46-wf8.bpmn", "729e0bb1cf77fb841ce47bfe8039940dd3a3a87f4394deeba453b6a03a05db83",
            "e46-wf9.bpmn", "088e9144394c42312b57b56dc4d4370c8e618e5df5d98278ccef12ef8330d24f");

    private static byte[] fixture(String name) {
        byte[] b = resource("e46/" + name + ".model");
        try {
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
            assertThat(sha).as("fixture " + name + " must be byte-identical to the frozen kit copy").isEqualTo(SHA256.get(name));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return b;
    }

    private void deployFixtures(String... names) {
        Map<String, byte[]> res = new LinkedHashMap<>();
        for (String n : names) {
            res.put(n, fixture(n));
        }
        adapter.deploy(null, "e46-" + names[names.length - 1] + "-" + RUN, res);
    }

    private void deployText(String name, String xml) {
        adapter.deploy(null, "e46-" + name + "-" + RUN + "-" + key("v"), Map.of(name, xml.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ path steps

    private String start(String processKey, String bk, Map<String, Object> vars) {
        return adapter.startProcess(null, processKey, bk, vars);
    }

    private List<String> open(String pid) {
        List<String> out = new ArrayList<>();
        for (TaskView t : tasks(pid, null)) {
            out.add(t.taskDefinitionKey());
        }
        out.sort(null);
        return out;
    }

    private void expectOpen(Ctx ctx, String pid, String... keys) {
        List<String> want = new ArrayList<>(List.of(keys));
        want.sort(null);
        List<String> got = open(pid);
        ctx.require("open tasks " + want + " (found " + got + ")", got.equals(want));
    }

    private void done(Ctx ctx, String pid, String taskKey, Map<String, Object> vars) {
        List<TaskView> l = tasks(pid, taskKey);
        ctx.require("task " + taskKey + " open (open: " + open(pid) + ")", !l.isEmpty());
        adapter.claimTask(l.get(0).id(), "e46-person-" + taskKey.toLowerCase());
        adapter.completeTask(l.get(0).id(), vars);
    }

    private void advanceAndRun(Duration d) {
        advance(d);
        for (int i = 0; i < 5 && adapter.executeDueJobs() > 0; i++) {
            // drain cascades (a timer that leads to another due timer)
        }
    }

    private void endedAt(Ctx ctx, String pid, String endId) {
        ctx.require("instance ended (active: " + active(pid) + ")", ended(pid));
        List<String> hist = historicActivityIds(pid);
        ctx.require("ended at " + endId + " (history " + hist + ")", hist.contains(endId));
    }

    private static Map<String, Object> o(String outcome) {
        return vars("outcome", outcome);
    }

    private Instant t0() {
        clock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        return now;
    }

    // ------------------------------------------------------------------ WF-1

    @Test
    void wf1BillMakerCheckerApprover() {
        deployFixtures("e46-wf1.bpmn");
        scenario("E46 WF-1 approve at every step", ctx -> {
            String pid = start("E46-WF1", key("WF1"), Map.of());
            expectOpen(ctx, pid, "PREPARE");
            done(ctx, pid, "PREPARE", Map.of());
            expectOpen(ctx, pid, "CHECK");
            done(ctx, pid, "CHECK", o("APPROVE"));
            expectOpen(ctx, pid, "APPROVE");
            done(ctx, pid, "APPROVE", o("APPROVE"));
            endedAt(ctx, pid, "END_APPROVED");
        });
        scenario("E46 WF-1 return to maker, then approve", ctx -> {
            String pid = start("E46-WF1", key("WF1"), Map.of());
            done(ctx, pid, "PREPARE", Map.of());
            done(ctx, pid, "CHECK", o("RETURN"));
            expectOpen(ctx, pid, "PREPARE");
            done(ctx, pid, "PREPARE", Map.of());
            done(ctx, pid, "CHECK", o("APPROVE"));
            done(ctx, pid, "APPROVE", o("RETURN"));
            expectOpen(ctx, pid, "CHECK");
            done(ctx, pid, "CHECK", o("APPROVE"));
            done(ctx, pid, "APPROVE", o("APPROVE"));
            endedAt(ctx, pid, "END_APPROVED");
        });
        scenario("E46 WF-1 reject at check", ctx -> {
            String pid = start("E46-WF1", key("WF1"), Map.of());
            done(ctx, pid, "PREPARE", Map.of());
            done(ctx, pid, "CHECK", o("REJECT"));
            endedAt(ctx, pid, "END_REJECTED");
        });
    }

    // ------------------------------------------------------------------ WF-2 (deemed timer never approves)

    @Test
    void wf2DeemedTimerNeverApproves() {
        deployFixtures("e46-wf2.bpmn");
        scenario("E46 WF-2 reminder fires, task stays; low band", ctx -> {
            Instant n = t0();
            String pid = start("E46-WF2", key("WF2"), vars("amount", 50_000L, "deemedDue", n.plus(Duration.ofDays(2)).toString()));
            expectOpen(ctx, pid, "AUDIT");
            advanceAndRun(Duration.ofHours(5));
            expectOpen(ctx, pid, "AUDIT");
            ctx.require("still running", !ended(pid));
            done(ctx, pid, "AUDIT", Map.of());
            done(ctx, pid, "CHECK", o("APPROVE"));
            expectOpen(ctx, pid, "APPROVE");
            done(ctx, pid, "APPROVE", Map.of());
            endedAt(ctx, pid, "END_APPROVED");
        });
        scenario("E46 WF-2 deemed timer routes to a human review (never approves); default band", ctx -> {
            Instant n = t0();
            String pid = start("E46-WF2", key("WF2"), vars("amount", 500_000L, "deemedDue", n.plus(Duration.ofDays(2)).toString()));
            advanceAndRun(Duration.ofDays(3));
            expectOpen(ctx, pid, "DEEMED_REVIEW");
            ctx.check("the timer approved nothing", !historicActivityIds(pid).contains("END_APPROVED")
                    && !historicActivityIds(pid).contains("APPROVE") && !historicActivityIds(pid).contains("APPROVE_SENIOR"));
            // a further deemed period passing changes nothing: no timer is left that could approve
            advanceAndRun(Duration.ofDays(30));
            expectOpen(ctx, pid, "DEEMED_REVIEW");
            done(ctx, pid, "DEEMED_REVIEW", Map.of());
            done(ctx, pid, "CHECK", o("APPROVE"));
            expectOpen(ctx, pid, "APPROVE_SENIOR");
            done(ctx, pid, "APPROVE_SENIOR", Map.of());
            endedAt(ctx, pid, "END_APPROVED");
        });
        scenario("E46 WF-2 objection", ctx -> {
            Instant n = t0();
            String pid = start("E46-WF2", key("WF2"), vars("amount", 1_000L, "deemedDue", n.plus(Duration.ofDays(2)).toString()));
            done(ctx, pid, "AUDIT", Map.of());
            done(ctx, pid, "CHECK", o("REJECT"));
            endedAt(ctx, pid, "END_REJECTED");
        });
    }

    // ------------------------------------------------------------------ WF-3 (escalation forwards, never approves)

    @Test
    void wf3EscalationForwardsToHuman() {
        deployFixtures("e46-wf3.bpmn");
        scenario("E46 WF-3 auto-forward to the next level, then human approval", ctx -> {
            Instant n = t0();
            String pid = start("E46-WF3", key("WF3"), vars("fwd1Due", n.plus(Duration.ofDays(1)).toString(),
                    "fwd2Due", n.plus(Duration.ofDays(30)).toString()));
            expectOpen(ctx, pid, "ZPO_AUDITOR");
            advanceAndRun(Duration.ofDays(2));
            expectOpen(ctx, pid, "AAO");
            ctx.check("escalation did not end the case", !ended(pid));
            done(ctx, pid, "AAO", Map.of());
            expectOpen(ctx, pid, "AD");
            done(ctx, pid, "AD", Map.of());
            endedAt(ctx, pid, "END_APPROVED");
        });
    }

    // ------------------------------------------------------------------ WF-4 (amount bands, strict XOR)

    private void band(long amount, String task) {
        scenario("E46 WF-4 amount " + amount + " -> " + task, ctx -> {
            String pid = start("E46-WF4", key("WF4"), vars("amount", amount));
            done(ctx, pid, "JEN", Map.of());
            expectOpen(ctx, pid, task);
            done(ctx, pid, task, Map.of());
            endedAt(ctx, pid, "END_APPROVED");
        });
    }

    @Test
    void wf4AmountBandsExactlyOneRoute() {
        deployFixtures("e46-wf4.bpmn");
        band(99_999L, "AEN");
        band(100_000L, "AEN");
        band(100_001L, "XEN");
        band(5_000_000L, "SEN");
        band(6_000_000L, "ACEN");
        // overlapping bands (a lower bound of the second band made inclusive): at 1,00,000 two flows are true
        String overlapping = new String(fixture("e46-wf4.bpmn"), StandardCharsets.UTF_8)
                .replace("id=\"E46-WF4\"", "id=\"E46-WF4-OVERLAP\"")
                .replace("${amount &gt; 100000 &amp;&amp; amount &lt;= 1000000}", "${amount &gt;= 100000 &amp;&amp; amount &lt;= 1000000}");
        assertThat(overlapping).contains("E46-WF4-OVERLAP").contains("amount &gt;= 100000");
        deployText("e46-wf4-overlap.bpmn", overlapping);
        scenario("E46 WF-4 overlapping bands -> AMBIGUOUS_ROUTE, no branch taken", ctx -> {
            for (long amount : new long[] {99_999L, 100_001L}) {
                String pid = start("E46-WF4-OVERLAP", key("WF4O"), vars("amount", amount));
                done(ctx, pid, "JEN", Map.of());
                ctx.check(amount + ": exactly one route (" + open(pid) + ")", open(pid).size() == 1);
            }
            String pid = start("E46-WF4-OVERLAP", key("WF4O"), vars("amount", 100_000L));
            boolean threw = false;
            try {
                done(ctx, pid, "JEN", Map.of());
            } catch (RuntimeException e) {
                threw = true;
                ctx.detail("completion error", e.getMessage());
            }
            List<Incident> inc = engine.getRuntimeService().createIncidentQuery().processInstanceId(pid).list();
            String msg = inc.isEmpty() ? null : inc.get(0).getIncidentMessage();
            ctx.detail("incident", msg);
            ctx.check("AMBIGUOUS_ROUTE incident at gwBand (threw=" + threw + ")", inc.size() == 1
                    && msg != null && msg.startsWith("AMBIGUOUS_ROUTE") && "gwBand".equals(inc.get(0).getActivityId()));
            ctx.check("no approver task opened (" + open(pid) + ")", open(pid).isEmpty());
            ctx.check("no band taken", historicActivityIds(pid).stream().noneMatch(a -> List.of("AEN", "XEN", "SEN", "ACEN").contains(a)));
        });
    }

    // ------------------------------------------------------------------ WF-5 (negative: refused, items named)

    @Test
    void wf5UnsupportedElementRefusedAndNamed() {
        byte[] wf5 = fixture("e46-wf5.bpmn");
        scenario("E46 WF-5 refused at deploy, every planted item named", ctx -> {
            String error = null;
            try {
                adapter.deploy(null, "e46-wf5-" + RUN, Map.of("e46-wf5.bpmn", wf5));
            } catch (RuntimeException e) {
                StringBuilder sb = new StringBuilder();
                for (Throwable t = e; t != null; t = t.getCause()) {
                    sb.append(t.getMessage()).append(" | ");
                }
                error = sb.toString();
            }
            ctx.detail("error", error);
            ctx.require("deploy refused", error != null);
            ctx.check("names complexGateway CX_PLANTED", error.contains("complexGateway") && error.contains("CX_PLANTED"));
            ctx.check("names {urn:acme:unknown}routingHint on CHECK", error.contains("urn:acme:unknown") && error.contains("routingHint"));
            ctx.check("names {urn:acme:unknown}unknownThing", error.contains("unknownThing"));
            ctx.check("nothing deployed", engine.getRepositoryService().createProcessDefinitionQuery()
                    .processDefinitionKey("E46-WF5").count() == 0);
        });
    }

    // ------------------------------------------------------------------ WF-6 (parallel finance / technical)

    @Test
    void wf6ParallelFinanceTechnicalReview() {
        deployFixtures("e46-wf6.bpmn");
        scenario("E46 WF-6 technical then finance", ctx -> {
            String pid = start("E46-WF6", key("WF6"), Map.of());
            done(ctx, pid, "CHECK", Map.of());
            expectOpen(ctx, pid, "FIN_REVIEW", "TECH_REVIEW");
            done(ctx, pid, "TECH_REVIEW", vars("techOutcome", "APPROVE"));
            expectOpen(ctx, pid, "FIN_REVIEW");
            done(ctx, pid, "FIN_REVIEW", vars("finOutcome", "APPROVE"));
            expectOpen(ctx, pid, "APPROVE");
            done(ctx, pid, "APPROVE", Map.of());
            endedAt(ctx, pid, "END_APPROVED");
        });
        scenario("E46 WF-6 finance then technical", ctx -> {
            String pid = start("E46-WF6", key("WF6"), Map.of());
            done(ctx, pid, "CHECK", Map.of());
            done(ctx, pid, "FIN_REVIEW", vars("finOutcome", "APPROVE"));
            expectOpen(ctx, pid, "TECH_REVIEW");
            done(ctx, pid, "TECH_REVIEW", vars("techOutcome", "APPROVE"));
            expectOpen(ctx, pid, "APPROVE");
            done(ctx, pid, "APPROVE", Map.of());
            endedAt(ctx, pid, "END_APPROVED");
        });
        scenario("E46 WF-6 finance rejects; technical task cancelled", ctx -> {
            String pid = start("E46-WF6", key("WF6"), Map.of());
            done(ctx, pid, "CHECK", Map.of());
            done(ctx, pid, "FIN_REVIEW", vars("finOutcome", "REJECT"));
            expectOpen(ctx, pid);
            endedAt(ctx, pid, "END_REJECTED");
        });
    }

    // ------------------------------------------------------------------ WF-7 / WF-9 (committee ALL / ANY / QUORUM)

    @Test
    void wf7CommitteeAllMembers() {
        deployFixtures("e46-wf7.bpmn");
        scenario("E46 WF-7 three members respond, then approver", ctx -> {
            String pid = start("E46-WF7", key("WF7"), vars("members", List.of("KIT-P-M1", "KIT-P-M2", "KIT-P-M3")));
            done(ctx, pid, "CHECK", Map.of());
            expectOpen(ctx, pid, "COMMITTEE", "COMMITTEE", "COMMITTEE");
            done(ctx, pid, "COMMITTEE", Map.of());
            done(ctx, pid, "COMMITTEE", Map.of());
            expectOpen(ctx, pid, "COMMITTEE");
            done(ctx, pid, "COMMITTEE", Map.of());
            expectOpen(ctx, pid, "APPROVE");
            done(ctx, pid, "APPROVE", Map.of());
            endedAt(ctx, pid, "END_APPROVED");
        });
    }

    @Test
    void wf9CommitteeAnyAndQuorum() {
        deployFixtures("e46-wf9.bpmn");
        scenario("E46 WF-9 ANY: first response completes the committee", ctx -> {
            String pid = start("E46-WF9", key("WF9"), vars("policy", "ANY", "members", List.of("KIT-P-M1", "KIT-P-M2", "KIT-P-M3")));
            expectOpen(ctx, pid, "ANY_ONE", "ANY_ONE", "ANY_ONE");
            done(ctx, pid, "ANY_ONE", Map.of());
            expectOpen(ctx, pid, "APPROVE");
            done(ctx, pid, "APPROVE", Map.of());
            endedAt(ctx, pid, "END_APPROVED");
        });
        scenario("E46 WF-9 QUORUM 2 of 4 (K from a variable)", ctx -> {
            String pid = start("E46-WF9", key("WF9"), vars("policy", "QUORUM", "quorumK", 2,
                    "members", List.of("KIT-P-M1", "KIT-P-M2", "KIT-P-M3", "KIT-P-M4")));
            done(ctx, pid, "QUORUM", Map.of());
            expectOpen(ctx, pid, "QUORUM", "QUORUM", "QUORUM");
            done(ctx, pid, "QUORUM", Map.of());
            expectOpen(ctx, pid, "APPROVE");
            done(ctx, pid, "APPROVE", Map.of());
            endedAt(ctx, pid, "END_APPROVED");
        });
    }

    // ------------------------------------------------------------------ WF-8 (inclusive, pinned sub-process, race)

    private boolean correlate(String bk) {
        try {
            adapter.correlateMessage(null, "TREASURY_ACK", bk, Map.of());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void wf8InclusiveSubProcessMessageTimerRaceCompensation() {
        // the child is deployed with the parent: a call activity without a binding binds to its own deployment
        // (D-12), which is the version pin of oneifms:calledElementVersion
        deployFixtures("e46-wf8-child.bpmn", "e46-wf8.bpmn");
        scenario("E46 WF-8 inclusive 10, sub-process, message wins, duplicate idempotent", ctx -> {
            Instant n = t0();
            String bk = key("WF8");
            String pid = start("E46-WF8", bk, vars("needsTech", true, "needsLegal", false,
                    "ackDue", n.plus(Duration.ofDays(5)).toString()));
            done(ctx, pid, "CHECK", Map.of());
            expectOpen(ctx, pid, "TECH");
            done(ctx, pid, "TECH", Map.of());
            String child = childOf(pid);
            ctx.require("called child instance exists", child != null);
            ctx.check("child is the version deployed with the parent", sameDeployment(pid, child));
            done(ctx, child, "VERIFY_SANCTION", Map.of());
            expectOpen(ctx, pid);
            ctx.require("waiting for acknowledgement", !ended(pid));
            ctx.require("acknowledgement correlated", correlate(bk));
            expectOpen(ctx, pid, "APPROVE");
            advanceAndRun(Duration.ofDays(6));
            expectOpen(ctx, pid, "APPROVE");
            ctx.check("duplicate acknowledgement has no effect", !correlate(bk) || open(pid).equals(List.of("APPROVE")));
            expectOpen(ctx, pid, "APPROVE");
            done(ctx, pid, "APPROVE", Map.of());
            endedAt(ctx, pid, "END_APPROVED");
            ctx.check("no compensation ran", !historicActivityIds(pid).contains("UNDO_SANCTION_CHECK"));
        });
        scenario("E46 WF-8 inclusive 01", ctx -> {
            Instant n = t0();
            String bk = key("WF8");
            String pid = start("E46-WF8", bk, vars("needsTech", false, "needsLegal", true,
                    "ackDue", n.plus(Duration.ofDays(5)).toString()));
            done(ctx, pid, "CHECK", Map.of());
            expectOpen(ctx, pid, "LEGAL");
            done(ctx, pid, "LEGAL", Map.of());
            done(ctx, childOf(pid), "VERIFY_SANCTION", Map.of());
            ctx.require("acknowledgement correlated", correlate(bk));
            done(ctx, pid, "APPROVE", Map.of());
            endedAt(ctx, pid, "END_APPROVED");
        });
        scenario("E46 WF-8 inclusive 11; timeout wins the race; compensation", ctx -> {
            Instant n = t0();
            String bk = key("WF8");
            String pid = start("E46-WF8", bk, vars("needsTech", true, "needsLegal", true,
                    "ackDue", n.plus(Duration.ofDays(5)).toString()));
            done(ctx, pid, "CHECK", Map.of());
            expectOpen(ctx, pid, "LEGAL", "TECH");
            done(ctx, pid, "LEGAL", Map.of());
            expectOpen(ctx, pid, "TECH");
            done(ctx, pid, "TECH", Map.of());
            done(ctx, childOf(pid), "VERIFY_SANCTION", Map.of());
            advanceAndRun(Duration.ofDays(6));
            expectOpen(ctx, pid, "UNDO_SANCTION_CHECK");
            ctx.check("late acknowledgement refused after the timeout won", !correlate(bk));
            done(ctx, pid, "UNDO_SANCTION_CHECK", Map.of());
            endedAt(ctx, pid, "END_RECALLED");
            ctx.check("never approved", !historicActivityIds(pid).contains("APPROVE"));
        });
        scenario("E46 WF-8 inclusive 00 takes the default; wrong correlation refused", ctx -> {
            Instant n = t0();
            String bk = key("WF8");
            String pid = start("E46-WF8", bk, vars("needsTech", false, "needsLegal", false,
                    "ackDue", n.plus(Duration.ofDays(5)).toString()));
            done(ctx, pid, "CHECK", Map.of());
            String child = childOf(pid);
            ctx.require("default flow reached the sub-process", child != null);
            done(ctx, child, "VERIFY_SANCTION", Map.of());
            ctx.check("wrong correlation refused", !correlate(bk + "-other"));
            expectOpen(ctx, pid);
            ctx.require("still waiting", !ended(pid));
            ctx.require("acknowledgement correlated", correlate(bk));
            expectOpen(ctx, pid, "APPROVE");
            done(ctx, pid, "APPROVE", Map.of());
            endedAt(ctx, pid, "END_APPROVED");
        });
    }

    private String childOf(String pid) {
        var l = engine.getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(pid).list();
        return l.isEmpty() ? null : l.get(0).getId();
    }

    private boolean sameDeployment(String parent, String child) {
        var rs = engine.getRuntimeService();
        var repo = engine.getRepositoryService();
        String p = rs.createProcessInstanceQuery().processInstanceId(parent).singleResult().getProcessDefinitionId();
        String c = rs.createProcessInstanceQuery().processInstanceId(child).singleResult().getProcessDefinitionId();
        return repo.getProcessDefinition(p).getDeploymentId().equals(repo.getProcessDefinition(c).getDeploymentId());
    }
}
