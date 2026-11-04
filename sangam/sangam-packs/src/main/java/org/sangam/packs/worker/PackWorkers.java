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
 *   <li>{@code sangam.pfms.paymentFile}: signed IFMIS/PFMS payment file with SSID and scheme code (G-PFMS) for
 *       departments that run the payment step in their own worker (Tier-1 SDKs, AC-08);</li>
 *   <li>{@code sangam.ai.extract}: IDP / agent extraction with per-field confidence (UP-06, R75).</li>
 * </ul>
 */
public final class PackWorkers {

    private static final Map<String, PackWorker> WORKERS = new LinkedHashMap<>();

    static {
        register(gatewayWorker("sangam.docs.bulk", "docs.render", "renderBatch", "a"));
        register(gatewayWorker("sangam.pfms.paymentFile", "pfms.payment", "submitPaymentFile", "c"));
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
