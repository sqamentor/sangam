// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.ai.AiSchema;
import org.sangam.ai.a2a.A2a;
import org.sangam.ai.agent.AgentDefinition;
import org.sangam.ai.agent.AgentRuntime;
import org.sangam.ai.agent.AgentTool;
import org.sangam.ai.agent.AgentTools;
import org.sangam.ai.classify.DocumentClassifier;
import org.sangam.ai.draft.DraftService;
import org.sangam.ai.expr.ExpressionPlayground;
import org.sangam.ai.llm.Llm;
import org.sangam.ai.llm.LlmPort;
import org.sangam.ai.llm.OpenAiCompatibleLlm;
import org.sangam.ai.mcp.McpServer;
import org.sangam.ai.rag.KnowledgeBaseService;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.modules.ai.AiApi;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.support.TxSupport;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.SangamSecurity;
import org.sangam.security.policy.Permission;
import org.sangam.server.bridge.AiBindings;
import org.sangam.server.bridge.CaseFieldScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-ai. Off unless {@code sangam.modules.ai.enabled=true}. The LLM stays {@link LlmPort#disabled()}, with no
 * outbound call, unless {@code sangam.ai.llm.base-uri} and {@code sangam.ai.llm.model} are set; egress is limited to
 * that one origin. Agents ({@code sangam.ai.agents-json}), connector tools ({@code sangam.ai.tools-json}) and inbound
 * A2A agents ({@code sangam.ai.a2a-json}) are JSON arrays. MCP write tools are off unless a tenant is listed in
 * {@code sangam.ai.mcp.write-tenants}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.ai.enabled", havingValue = "true")
public class AiWiring {

    private static final Logger LOG = LoggerFactory.getLogger(AiWiring.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> DOCS = new TypeReference<>() {
    };

    @Bean
    public LlmPort sangamLlm(Environment env, ObjectProvider<SecretResolver> secrets) {
        String base = env.getProperty("sangam.ai.llm.base-uri");
        String model = env.getProperty("sangam.ai.llm.model");
        if (base == null || base.isBlank() || model == null || model.isBlank()) {
            LOG.info("sangam-ai: no LLM configured, AI calls are disabled");
            return LlmPort.disabled();
        }
        URI uri = URI.create(base);
        int port = uri.getPort() > 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
        boolean allowPrivate = env.getProperty("sangam.ai.llm.allow-private", Boolean.class, false);
        EgressPolicy egress = EgressPolicy.of(List.of(new EgressPolicy.Rule(null, uri.getScheme(), uri.getHost(), port,
                uri.getPath() == null ? "" : uri.getPath(), allowPrivate)), 0, 32L * 1024 * 1024);
        return new OpenAiCompatibleLlm(new OpenAiCompatibleLlm.Config(uri, model, env.getProperty("sangam.ai.llm.embedding-model"),
                env.getProperty("sangam.ai.llm.api-key-ref"), env.getProperty("sangam.ai.llm.timeout", Duration.class, Duration.ofSeconds(60))),
                egress, secrets.getIfAvailable(SecretResolver::fromEnvironment));
    }

    @Bean
    public AgentRuntime sangamAgentRuntime(TxSupport tx, DataSource ds, LlmPort llm, KnowledgeBaseService kb, Environment env,
            ObjectProvider<ConnectorInvoker> invoker, ObjectProvider<SangamSecurity> security) {
        Dialect d = tx.dialect();
        CaseWiring.devSchema(env, tx, "AI_AGENT_RUN", c -> {
            try {
                AiSchema.create(c, d);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("cannot create the sangam-ai schema", e);
            }
        });
        Transactions t = Transactions.of(ds);
        Map<String, AgentTool> tools = tools(env, invoker.getIfAvailable());
        Function<String, Optional<AgentTool>> lookup = name -> Optional.ofNullable(tools.get(name));
        return new AgentRuntime(d, t, new AuditChain(d), llm, lookup, kb, env.getProperty("sangam.ai.worker.lease", Duration.class,
                Duration.ofMinutes(2)), identityOf(security.getIfAvailable(), t));
    }

    /**
     * The run principal's full current identity (Sangam groups, roles, seats) for knowledge retrieval, so every deny applies.
     * No security module: null, and retrieval returns nothing (fail closed).
     */
    private static java.util.function.BiFunction<String, String, SecurityIdentity> identityOf(SangamSecurity sec, Transactions tx) {
        if (sec == null) {
            return null;
        }
        return (tenant, principal) -> tx.inTransaction(c -> sec.principals().enrich(c,
                new SecurityIdentity(tenant, principal, principal, Set.of(), Set.of(), Set.of(), Set.of(), "SYSTEM", null, null, false, Map.of()),
                java.time.Instant.now()));
    }

    @Bean
    public KnowledgeBaseService sangamKnowledgeBase(TxSupport tx, DataSource ds, LlmPort llm, Environment env, ObjectProvider<SangamSecurity> security) {
        // A-13: the sangam-security compiler (deny wins at every scope). Without the security module retrieval fails closed.
        SangamSecurity sec = security.getIfAvailable();
        KnowledgeBaseService.Visibility visibility = sec == null ? null
                : (c, who) -> sec.compiler().visible(c, who, "document", Permission.READ, "k");
        if (sec == null) {
            LOG.warn("sangam-ai: no security module, knowledge retrieval returns nothing");
        }
        return new KnowledgeBaseService(tx.dialect(), Transactions.of(ds), llm, env.getProperty("sangam.ai.kb.chunk-chars", Integer.class, 1200),
                env.getProperty("sangam.ai.kb.overlap-chars", Integer.class, 150), env.getProperty("sangam.ai.kb.max-candidates", Integer.class, 2000), visibility);
    }

    @Bean
    public AiApi sangamAiApi(AgentRuntime runtime, KnowledgeBaseService kb, LlmPort llm, TxSupport tx, DataSource ds, ProcessEngine engine, Environment env) {
        Dialect d = tx.dialect();
        Transactions t = Transactions.of(ds);
        AuditChain audit = new AuditChain(d);
        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        for (Map<String, Object> doc : docs(env, "sangam.ai.agents-json")) {
            AgentDefinition def = AgentDefinition.fromRegistryDocument(doc);
            agents.put(def.key(), def);
        }
        Set<String> writeTenants = Set.copyOf(CaseWiring.list(env, "sangam.ai.mcp.write-tenants"));
        String base = env.getProperty("sangam.ai.mcp.rest-base-url", "http://127.0.0.1:" + env.getProperty("server.port", "8080"));
        McpServer mcp = new McpServer(AiBindings.restGateway(base), writeTenants::contains);
        A2a.ProcessGateway gateway = AiBindings.processGateway(engine);
        Map<String, A2a.Server> a2a = new LinkedHashMap<>();
        for (Map<String, Object> m : docs(env, "sangam.ai.a2a-json")) {
            Set<String> callers = m.get("allowedCallers") instanceof List<?> l
                    ? l.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet()) : Set.of();
            String tenant = String.valueOf(m.get("tenantId"));
            String name = String.valueOf(m.get("agentName"));
            a2a.put(tenant + "/" + name, new A2a.Server(new A2a.InboundConfig(tenant, String.valueOf(m.get("servicePrincipal")),
                    String.valueOf(m.get("processKey")), name, String.valueOf(m.getOrDefault("description", "")),
                    String.valueOf(m.getOrDefault("url", "")), callers::contains), gateway));
        }
        return new AiApi(runtime, kb, new DraftService(d, t, audit, llm), new DocumentClassifier(d, t, audit, llm),
                new ExpressionPlayground(env.getProperty("sangam.ai.expression.limit", Duration.class, Duration.ofSeconds(2)), llm),
                key -> Optional.ofNullable(agents.get(key)),
                (tenant, principal, limit) -> AiBindings.runs(runtime, t, tenant, principal, limit), mcp,
                (tenant, name) -> Optional.ofNullable(a2a.get(tenant + "/" + name)));
    }

    @Bean
    public CaseFieldScheduler sangamAiWorker(AgentRuntime runtime, TxSupport tx, DataSource ds, RestProperties rest, Environment env,
            ObjectProvider<AdminPort> admin) {
        Transactions t = Transactions.of(ds);
        int batch = env.getProperty("sangam.ai.worker.batch", Integer.class, 20);
        Duration retry = env.getProperty("sangam.ai.worker.external-retry", Duration.class, Duration.ofMinutes(5));
        return new CaseFieldScheduler("sangam-ai-worker", new LeaderElection(tx.dialect(), t, rest.getNode(), Duration.ofMinutes(3)),
                () -> CaseWiring.tenants(env, admin, "sangam.ai.worker.tenants"),
                List.of(new CaseFieldScheduler.Task("agent-work", env.getProperty("sangam.ai.worker.every", Duration.class, Duration.ofSeconds(5)), true,
                                tenant -> AiBindings.work(runtime, tenant, rest.getNode(), batch)),
                        new CaseFieldScheduler.Task("agent-reconcile", Duration.ofMinutes(1), true,
                                tenant -> AiBindings.reconcile(runtime, t, tenant, retry, batch))),
                Duration.ofSeconds(5));
    }

    private static Map<String, AgentTool> tools(Environment env, ConnectorInvoker invoker) {
        Map<String, AgentTool> out = new LinkedHashMap<>();
        for (Map<String, Object> m : docs(env, "sangam.ai.tools-json")) {
            if (invoker == null) {
                throw new IllegalStateException("sangam.ai.tools-json needs sangam-integration's ConnectorInvoker");
            }
            String name = String.valueOf(m.get("name"));
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = m.get("schema") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of("type", "object");
            out.put(name, AgentTools.connector(new Llm.ToolSpec(name, String.valueOf(m.getOrDefault("description", "")), schema), invoker,
                    String.valueOf(m.get("connector")), String.valueOf(m.get("operation"))));
        }
        return out;
    }

    private static List<Map<String, Object>> docs(Environment env, String property) {
        String json = env.getProperty(property);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, DOCS);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(property + " is not a JSON array of objects", e);
        }
    }
}
