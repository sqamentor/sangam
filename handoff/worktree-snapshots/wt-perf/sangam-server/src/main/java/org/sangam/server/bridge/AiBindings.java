// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.impl.identity.Authentication;
import org.sangam.ai.a2a.A2a;
import org.sangam.ai.agent.AgentRuntime;
import org.sangam.ai.mcp.McpServer;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapters of sangam-ai to the server: worker, parked-run reconciliation, MCP's REST gateway, A2A's process gateway. */
public final class AiBindings {

    private static final Logger LOG = LoggerFactory.getLogger(AiBindings.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TERMINAL = "('COMPLETED','FAILED','CANCELLED')";

    private AiBindings() {
    }

    /** One worker pass. Runs are claimed by lease inside the runtime, so an overlapping worker cannot double-execute. */
    public static void work(AgentRuntime runtime, String tenant, String node, int batch) {
        for (String runId : runtime.runnable(tenant, batch)) {
            try {
                runtime.drive(runId, node + ":" + UUID.randomUUID());
            } catch (RuntimeException e) {
                LOG.warn("agent run {} of tenant {} did not advance: {}", runId, tenant, e.toString());
            }
        }
    }

    /**
     * Resumes WAITING_EXTERNAL runs with no live child when a child ended after the parent parked, or when the wait is
     * older than {@code retryAfter} (the tool is re-invoked with its stable operation id, so an unresolved UNKNOWN parks again).
     */
    public static int reconcile(AgentRuntime runtime, Transactions tx, String tenant, Duration retryAfter, int batch) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retryAfter));
        List<String> ids = tx.inTransaction(c -> Jdbc.query(c,
                "SELECT p.run_id FROM AI_AGENT_RUN p WHERE p.tenant_id=? AND p.state='WAITING_EXTERNAL'"
                        + " AND NOT EXISTS (SELECT 1 FROM AI_AGENT_RUN l WHERE l.parent_run_id=p.run_id AND l.state NOT IN " + TERMINAL + ")"
                        + " AND (p.updated_at < ? OR EXISTS (SELECT 1 FROM AI_AGENT_RUN d WHERE d.parent_run_id=p.run_id AND d.updated_at >= p.updated_at))"
                        + " ORDER BY p.updated_at", rs -> rs.getString(1), tenant, cutoff));
        List<String> due = ids.size() > batch ? ids.subList(0, batch) : ids;
        due.forEach(id -> runtime.resume(tenant, id));
        return due.size();
    }

    /** Lists a tenant's runs, newest first; {@code principalOrNull} narrows to one owner. */
    public static List<AgentRuntime.Run> runs(AgentRuntime runtime, Transactions tx, String tenant, String principalOrNull, int limit) {
        List<String> ids = tx.inTransaction(c -> principalOrNull == null
                ? Jdbc.query(c, "SELECT run_id FROM AI_AGENT_RUN WHERE tenant_id=? ORDER BY created_at DESC", rs -> rs.getString(1), tenant)
                : Jdbc.query(c, "SELECT run_id FROM AI_AGENT_RUN WHERE tenant_id=? AND principal_id=? ORDER BY created_at DESC",
                rs -> rs.getString(1), tenant, principalOrNull));
        List<AgentRuntime.Run> out = new ArrayList<>();
        for (String id : ids.size() > limit ? ids.subList(0, limit) : ids) {
            runtime.find(id).ifPresent(out::add);
        }
        return out;
    }

    /** Loops back into this server's REST API with the caller's own token: every R19 and permission check applies. */
    public static McpServer.RestGateway restGateway(String baseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return (method, path, query, body, token, reason) -> {
            try {
                String qs = query == null || query.isEmpty() ? "" : "?" + query.entrySet().stream()
                        .map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1" + path + qs)).timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json");
                if (token != null) {
                    b.header("Authorization", "Bearer " + token);
                }
                if ("GET".equals(method)) {
                    b.GET();
                } else {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    if (body instanceof Map<?, ?> m) {
                        m.forEach((k, v) -> payload.put(String.valueOf(k), v));
                    }
                    if (reason != null) {
                        payload.put("reason", reason);
                    }
                    b.header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                            .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
                }
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                return new McpServer.Reply(r.statusCode(), r.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new McpServer.Reply(503, "interrupted");
            } catch (java.io.IOException e) {
                return new McpServer.Reply(503, "REST API unreachable");
            }
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(String.valueOf(s), StandardCharsets.UTF_8);
    }

    /** Starts as the configured service principal, in its tenant, so engine authorization judges that principal. */
    public static A2a.ProcessGateway processGateway(ProcessEngine engine) {
        return new A2a.ProcessGateway() {
            @Override
            public String start(String tenant, String sp, String key, String businessKey, Map<String, Object> vars) {
                IdentityService identity = engine.getIdentityService();
                Authentication previous = identity.getCurrentAuthentication();
                List<String> groups = identity.createGroupQuery().groupMember(sp).list().stream().map(Group::getId).toList();
                identity.setAuthentication(sp, groups, List.of(tenant));
                try {
                    return engine.getRuntimeService().createProcessInstanceByKey(key).processDefinitionTenantId(tenant)
                            .businessKey(businessKey).setVariables(vars).execute().getId();
                } finally {
                    identity.setAuthentication(previous);
                }
            }

            @Override
            public String status(String tenant, String sp, String instanceId) {
                HistoricProcessInstance h = engine.getHistoryService().createHistoricProcessInstanceQuery()
                        .processInstanceId(instanceId).tenantIdIn(tenant).singleResult();
                if (h == null) {
                    return null;
                }
                if (h.getEndTime() == null) {
                    return "working";
                }
                return HistoricProcessInstance.STATE_COMPLETED.equals(h.getState()) ? "completed" : "failed";
            }
        };
    }
}
