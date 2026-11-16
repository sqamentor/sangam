// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DecisionPort;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.IdempotencyPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;

/**
 * The cross-host equivalence scenarios (M-150). Each runs through the host's own ports with the fixed models of
 * {@link ConformanceModels}; its {@link BusinessResult} must equal the golden result ({@link GoldenResults}) on every
 * host. Generated values (instance ids, idempotency keys, business keys) are fresh per run and never part of a result,
 * so a scenario can run again on a database that already holds an earlier run.
 */
public final class EquivalenceScenarios {

    /** The command type the idempotency scenarios claim keys under. */
    static final String COMMAND = "conformance.charge";
    static final String SOURCE = "sangam-host-conformance";

    private EquivalenceScenarios() {
    }

    /** Every scenario, in run order. */
    public static List<EquivalenceScenario> all() {
        return List.of(
                new EquivalenceScenario("decision-fee", "money: rule version and minor units",
                        EquivalenceScenarios::decisionFee),
                new EquivalenceScenario("process-fee", "money and audit: a process step evaluates the rule",
                        EquivalenceScenarios::processFee),
                new EquivalenceScenario("cross-tenant", "authorization: another tenant's instance",
                        EquivalenceScenarios::crossTenant),
                new EquivalenceScenario("idempotent-replay", "duplicate prevention: Idempotency-Key replay and reuse",
                        EquivalenceScenarios::idempotentReplay),
                new EquivalenceScenario("inbox-dedupe", "duplicate prevention: an inbound message is accepted once",
                        PortScenarios::inboxDedupe),
                new EquivalenceScenario("audit-tamper", "audit: a row altered behind the ports breaks the chain",
                        PortScenarios::auditTamper));
    }

    /** The fee decision at its latest version for three amounts, plus a repeat that must give the same digest. */
    static BusinessResult decisionFee(HostHarness host) {
        RequestContext a = host.administrator(HostHarness.TENANT_A);
        host.port(DeploymentPort.class).deploy(a, new DeploymentPort.DeployRequest("conformance-fee", SOURCE, true,
                Map.of(ConformanceModels.DMN_RESOURCE, ConformanceModels.feeDecision())));
        DecisionPort decisions = host.port(DecisionPort.class);
        int version = latestVersion(decisions, a);
        BusinessResult.Builder r = BusinessResult.builder().put("decisionKey", ConformanceModels.FEE_DECISION);
        String firstDigest = null;
        for (long amount : new long[] {99_999L, 100_000L, 2_500_000L}) {
            DecisionPort.EvaluateResult e = decisions.evaluate(a, ConformanceModels.FEE_DECISION,
                    new DecisionPort.EvaluateRequest(Map.of("amountMinor", amount), version, "OTHER", null));
            r.put("fee." + amount, e.result());
            r.put("version." + amount + ".pinned", e.version() == version);
            if (firstDigest == null) {
                firstDigest = e.outputDigest();
            }
        }
        DecisionPort.EvaluateResult again = decisions.evaluate(a, ConformanceModels.FEE_DECISION,
                new DecisionPort.EvaluateRequest(Map.of("amountMinor", 99_999L), version, "OTHER", null));
        return r.put("digest.repeatable", firstDigest != null && firstDigest.equals(again.outputDigest())).build();
    }

    private static int latestVersion(DecisionPort decisions, RequestContext a) {
        Page<DecisionPort.DecisionDefinition> p = decisions.definitions(a, ConformanceModels.FEE_DECISION, true,
                new Page.PageRequest(0, 10));
        return p.items().stream().filter(d -> ConformanceModels.FEE_DECISION.equals(d.key()))
                .mapToInt(DecisionPort.DecisionDefinition::version).max()
                .orElseThrow(() -> new IllegalStateException("the fee decision was not deployed"));
    }

    /** Starts the fee process for one amount: the rule runs inside the process step; the audit chain verifies. */
    static BusinessResult processFee(HostHarness host) {
        RequestContext a = host.administrator(HostHarness.TENANT_A);
        ProcessPort.ProcessInstance pi = startFeeProcess(host, a, 250_000L);
        ProcessPort processes = host.port(ProcessPort.class);
        Map<String, Object> vars = processes.variables(a, pi.id());
        ProcessPort.ProcessInstance now = processes.get(a, pi.id());
        AuditPort.VerifyResult audit = host.port(AuditPort.class).verify(a, null, pi.businessKey());
        return BusinessResult.builder()
                .put("processDefinitionKey", now.processDefinitionKey())
                .put("feeMinor", vars.get("feeMinor"))
                .put("amountMinor", vars.get("amountMinor"))
                .put("activeActivityIds", now.activeActivityIds())
                .put("ended", now.ended())
                .put("tenant.matches", HostHarness.TENANT_A.equals(now.tenantId()))
                .put("audit.ok", audit.ok())
                .build();
    }

    /** Tenant B, even as its own administrator, can neither read nor change an instance of tenant A. */
    static BusinessResult crossTenant(HostHarness host) {
        RequestContext a = host.administrator(HostHarness.TENANT_A);
        RequestContext b = host.administrator(HostHarness.TENANT_B);
        ProcessPort.ProcessInstance pi = startFeeProcess(host, a, 50_000L);
        ProcessPort processes = host.port(ProcessPort.class);
        BusinessResult.Builder r = BusinessResult.builder()
                .put("read", refusal(() -> processes.get(b, pi.id())))
                .put("variables", refusal(() -> processes.variables(b, pi.id())))
                .put("setVariables", refusal(() -> processes.setVariables(b, pi.id(), Map.of("feeMinor", 0L))));
        // the refused write changed nothing
        return r.put("feeMinor.unchanged", processes.variables(a, pi.id()).get("feeMinor")).build();
    }

    /** One claim executes once; a replay returns the stored response; another body under the key is refused. */
    static BusinessResult idempotentReplay(HostHarness host) throws Exception {
        RequestContext a = host.administrator(HostHarness.TENANT_A);
        IdempotencyPort ip = host.port(IdempotencyPort.class);
        String key = "conformance-" + UUID.randomUUID();
        byte[] body = "charged 2500".getBytes(StandardCharsets.UTF_8);
        byte[] fingerprint = sha256("conformance.charge:2500");
        AtomicInteger runs = new AtomicInteger();
        BusinessResult.Builder r = BusinessResult.builder();

        IdempotencyPort.Begin first = ip.begin(a, COMMAND, key, fingerprint);
        r.put("first", kind(first));
        if (first instanceof IdempotencyPort.Claimed claimed) {
            IdempotencyPort.Outcome o = ip.execute(claimed, () -> {
                runs.incrementAndGet();
                return new IdempotencyPort.Outcome(201, body, "charge/1");
            });
            r.put("first.status", o.status());
        }
        IdempotencyPort.Begin replay = ip.begin(a, COMMAND, key, fingerprint);
        r.put("replay", kind(replay));
        if (replay instanceof IdempotencyPort.Replay rp) {
            r.put("replay.status", rp.status()).put("replay.sameBody", Arrays.equals(body, rp.body()))
                    .put("replay.resourceRef", rp.resourceRef());
        }
        IdempotencyPort.Begin reuse = ip.begin(a, COMMAND, key, sha256("conformance.charge:9999"));
        r.put("reuse", kind(reuse));
        if (reuse instanceof IdempotencyPort.Rejected rj) {
            r.put("reuse.status", rj.status()).put("reuse.code", rj.code());
        }
        IdempotencyPort.Begin invalid = ip.begin(a, COMMAND, "", fingerprint);
        r.put("invalidKey", kind(invalid));
        if (invalid instanceof IdempotencyPort.Rejected rj) {
            r.put("invalidKey.status", rj.status()).put("invalidKey.code", rj.code());
        }
        return r.put("executions", runs.get()).build();
    }

    static ProcessPort.ProcessInstance startFeeProcess(HostHarness host, RequestContext ctx, long amountMinor) {
        host.port(DeploymentPort.class).deploy(ctx, new DeploymentPort.DeployRequest("conformance-fee-process", SOURCE,
                true, Map.of(ConformanceModels.DMN_RESOURCE, ConformanceModels.feeDecision(),
                        ConformanceModels.BPMN_RESOURCE, ConformanceModels.feeProcess())));
        return host.port(ProcessPort.class).start(ctx, new ProcessPort.StartProcessRequest(ConformanceModels.FEE_PROCESS,
                "conformance-" + UUID.randomUUID(), Map.of("amountMinor", amountMinor), null, null, null, null));
    }

    /** {@code refused-<status>} for an {@link ApiProblem}; {@code allowed} when the call went through. */
    static String refusal(Runnable call) {
        try {
            call.run();
            return "allowed";
        } catch (ApiProblem p) {
            return "refused-" + p.status();
        }
    }

    static String kind(IdempotencyPort.Begin b) {
        return b == null ? "null" : b.getClass().getSimpleName();
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
