// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.worker;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sangam.packs.core.Vars;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.PackRuntime;

/**
 * The external-task topics the packs declare, with their handlers. Every handler reaches the outside world through
 * {@link org.sangam.packs.port.ConnectorGateway}.
 *
 * <ul>
 *   <li>{@code sangam.docs.bulk}: bulk document generation (admit cards, sanction orders, PPOs; GP-27);</li>
 *   <li>{@code sangam.rpa}: RPA step against a legacy UI, bot pool by {@code botPool}, credentials by reference only
 *       (UP-20, R73); a screen failure becomes BPMN error {@code RPA_SCREEN_FAILURE};</li>
 *   <li>topics a pack declares in its manifest ({@code workers}: topic, pack connector, operation, class), registered
 *       when the pack is discovered ({@link #registerDeclared}; SL-104: domain topics are pack data, not product code);</li>
 *   <li>{@code sangam.ai.extract}: IDP / agent extraction with per-field confidence (UP-06, R75).</li>
 * </ul>
 */
public final class PackWorkers {

    private static final Map<String, PackWorker> WORKERS = new LinkedHashMap<>();
    /** Topics declared by packs, with the declaration that registered them. */
    private static final Map<String, org.sangam.packs.spi.PackDescriptor.WorkerUse> DECLARED = new HashMap<>();

    static {
        register(gatewayWorker("sangam.docs.bulk", "docs.render", "renderBatch", "a"));
        register(gatewayWorker("sangam.ai.extract", "ai.extract", "extract", "a"));
        register(new PackWorker() {
            @Override
            public String topic() {
                return "sangam.rpa";
            }

            @Override
            public Map<String, Object> handle(String tenantId, String businessKey, String operationId,
                                              Map<String, Object> variables) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("script", variables.get("rpaScript"));
                payload.put("scriptVersion", variables.get("rpaScriptVersion"));
                payload.put("botPool", variables.getOrDefault("botPool", "unattended"));
                payload.put("credentialRef", variables.get("credentialRef"));
                Object d = variables.get(Vars.simVar("rpa.bot"));
                if (d != null) {
                    payload.put(org.sangam.packs.sim.SimulatorConnectorGateway.DIRECTIVE, d);
                }
                ConnectorResult r = PackRuntime.gateway().submit(new ConnectorRequest(tenantId, "rpa.bot", "run",
                        operationId, businessKey, null, payload, Vars.hash(Vars.map("script",
                        String.valueOf(variables.get("rpaScript")), "op", operationId))));
                if (r.status() == OperationStatus.FAILED) {
                    throw new WorkerBpmnError("RPA_SCREEN_FAILURE", r.reasonCode());
                }
                Map<String, Object> out = new HashMap<>();
                out.put("rpaStatus", r.status().name());
                out.put("rpaEvidenceRef", r.externalRef());
                return out;
            }
        });
    }

    private PackWorkers() {
    }

    public static synchronized void register(PackWorker w) {
        WORKERS.put(w.topic(), w);
    }

    /**
     * Registers the gateway workers {@code pack} declares ({@code workers} in its manifest). Several packs may declare
     * the same topic with the same declaration; a different declaration of a topic, or a topic that a built-in worker
     * already serves, is refused (naming the pack), never silently overridden.
     */
    public static synchronized void registerDeclared(org.sangam.packs.spi.PackDescriptor pack) {
        for (org.sangam.packs.spi.PackDescriptor.WorkerUse w : pack.workers()) {
            org.sangam.packs.spi.PackDescriptor.WorkerUse prior = DECLARED.get(w.topic());
            if (prior != null) {
                if (!prior.equals(w)) {
                    throw new IllegalStateException("pack " + pack.coordinates() + " declares worker topic " + w.topic()
                            + " as " + w + ", already declared as " + prior);
                }
                continue;
            }
            if (WORKERS.containsKey(w.topic())) {
                throw new IllegalStateException("pack " + pack.coordinates() + " declares worker topic " + w.topic()
                        + ", which a built-in worker already serves");
            }
            DECLARED.put(w.topic(), w);
            register(gatewayWorker(w.topic(), w.connector(), w.operation(), w.capabilityClass()));
        }
    }

    public static synchronized Optional<PackWorker> forTopic(String topic) {
        return Optional.ofNullable(WORKERS.get(topic));
    }

    public static synchronized List<PackWorker> all() {
        return List.copyOf(WORKERS.values());
    }

    private static PackWorker gatewayWorker(String topic, String connector, String operation, String cls) {
        return new PackWorker() {
            @Override
            public String topic() {
                return topic;
            }

            @Override
            public String capabilityClass() {
                return cls;
            }

            @Override
            public Map<String, Object> handle(String tenantId, String businessKey, String operationId,
                                              Map<String, Object> variables) {
                String held = org.sangam.packs.core.MoneyOutApproval.refusal(connector, variables::get);
                if (held != null) {
                    // a failure (retries, then an incident with the reason), never a BPMN error
                    throw new IllegalStateException(org.sangam.packs.core.MoneyOutApproval.HELD + ": " + held);
                }
                Map<String, Object> payload = new HashMap<>();
                variables.forEach((k, v) -> {
                    if (!k.startsWith("sim_")) {
                        payload.put(k, Vars.canonical(v));
                    }
                });
                String hash = Vars.hash(payload);
                Object d = variables.get(Vars.simVar(connector));
                if (d != null) {
                    payload.put(org.sangam.packs.sim.SimulatorConnectorGateway.DIRECTIVE, d);
                }
                ConnectorResult r = PackRuntime.gateway().submit(new ConnectorRequest(tenantId, connector, operation,
                        operationId, businessKey, null, payload, hash));
                if (r.status() == OperationStatus.FAILED) {
                    throw new WorkerBpmnError(connector.toUpperCase().replace('.', '_') + "_FAILED", r.reasonCode());
                }
                Map<String, Object> out = new HashMap<>();
                out.put("workerStatus", r.status().name());
                out.put("workerRef", r.externalRef());
                r.data().forEach((k, v) -> out.put("w_" + k, Vars.engineValue(v)));
                return out;
            }
        };
    }
}
