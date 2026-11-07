// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.modeller.ModellerSchema;
import org.sangam.modeller.action.ActionModels;
import org.sangam.modeller.auth.TokenExchange;
import org.sangam.modeller.lease.EditLeases;
import org.sangam.modeller.mock.ServiceMocks;
import org.sangam.modeller.template.ElementTemplates;
import org.sangam.modeller.test.BulkModelTests;
import org.sangam.rest.modules.modeller.ModellerRequestScope;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PackageToolsPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.TokenExchangeConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Wires sangam-modeller, off unless {@code sangam.modules.modeller.enabled=true}; with it off, package saves and test runs
 * behave as before.
 * <ul>
 *   <li>Package save fence: {@code PackagePort.update} runs {@code EditLeases.checkFence} in the save transaction, with the
 *       fence from header {@value #FENCE_HEADER}. With no header, a save is refused while anyone holds a live lease.</li>
 *   <li>{@code PackageToolsPort.runTests} goes through {@code BulkModelTests}, which stores a report. The response stays
 *       the {@code TestRun} array. A case named {@code judge:*} is a judge verdict: {@code deterministic=false}.</li>
 *   <li>Service mocks ({@code sangam.modeller.mocks.file}, JSON array of rules): never on a profile containing
 *       {@code prod}, the same check as the debugger (R80).</li>
 *   <li>Token exchange: {@code sangam.modeller.token-exchange.{token-endpoint,client-id,client-secret-ref,audience,connectors,egress}};
 *       with no endpoint, nothing is wrapped and no call is made.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.modeller.enabled", havingValue = "true")
public class ModellerWiring {

    public static final String FENCE_HEADER = "X-Sangam-Edit-Fence";
    private static final Logger LOG = LoggerFactory.getLogger(ModellerWiring.class);

    @Bean
    public InitializingBean sangamModellerDevSchema(DataSource ds, TxSupport tx, Environment env) {
        return () -> {
            if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
                try (Connection c = ds.getConnection()) {
                    if (!ChannelsWiring.tableExists(c, "MOD_EDIT_LEASE")) {
                        ModellerSchema.create(c, tx.dialect());
                    }
                }
            }
        };
    }

    @Bean
    public EditLeases sangamEditLeases(TxSupport tx, Environment env) {
        Dialect d = tx.dialect();
        return new EditLeases(d, tx.newTransactions(), new AuditChain(d),
                Duration.ofSeconds(env.getProperty("sangam.modeller.lease-seconds", Long.class, 120L)));
    }

    @Bean
    public ElementTemplates sangamElementTemplates(TxSupport tx) {
        return new ElementTemplates(tx.dialect(), tx.newTransactions());
    }

    @Bean
    public BulkModelTests sangamBulkModelTests(TxSupport tx) {
        return new BulkModelTests(tx.dialect(), tx.newTransactions());
    }

    @Bean
    public ActionModels sangamActionModels(TxSupport tx, PermissionPort permissions, ObjectProvider<ProcessEngine> engine,
                                           ObjectProvider<ConnectorRegistry> connectors) {
        Dialect d = tx.dialect();
        return new ActionModels(d, tx.newTransactions(), new AuditChain(d), (tenant, user, permission, target) -> {
            RequestContext ctx = ModellerRequestScope.current();
            return ctx != null && tenant.equals(ctx.tenantId()) && user.equals(ctx.principalId()) && permissions.has(ctx, permission);
        }, (tenant, operationId, action, targetRef, input) -> dispatch(engine, connectors, tenant, operationId, action, targetRef, input));
    }

    /** BPMN: starts the process keyed by the operation id. REST: {@code connectorId:operation}. SCRIPT has no worker binding yet. */
    private static Map<String, Object> dispatch(ObjectProvider<ProcessEngine> engine, ObjectProvider<ConnectorRegistry> connectors,
            String tenant, UUID operationId, ActionModels.Action action, String targetRef, Map<String, Object> input) {
        switch (action.targetType()) {
            case BPMN -> {
                var pi = engine.getObject().getRuntimeService().createProcessInstanceByKey(action.target())
                        .processDefinitionTenantId(tenant).businessKey(operationId.toString()).setVariables(input).execute();
                return Map.of("processInstanceId", pi.getId());
            }
            case REST -> {
                String[] parts = action.target().split(":", 2);
                ConnectorRequest request = new ConnectorRequest(tenant, operationId, operationId.toString(), 1, null, targetRef,
                        parts.length > 1 ? parts[1] : "invoke", input, null);
                ConnectorResult result = connectors.getObject().require(tenant, parts[0]).invoke(request);
                if (result instanceof ConnectorResult.Success s) {
                    return s.output();
                }
                throw new IllegalStateException("ACTION_TARGET_FAILED: " + result);
            }
            default -> throw new IllegalArgumentException("ACTION_TARGET_UNSUPPORTED: " + action.targetType());
        }
    }

    @Bean
    public static BeanPostProcessor sangamModellerPortDecorator(ObjectProvider<EditLeases> leases, ObjectProvider<TxSupport> tx,
                                                                ObjectProvider<BulkModelTests> tests) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof PackagePort) {
                    return fenced((PackagePort) bean, leases, tx);
                }
                if (bean instanceof PackageToolsPort) {
                    return reported((PackageToolsPort) bean, tests);
                }
                return bean;
            }
        };
    }

    private static PackagePort fenced(PackagePort target, ObjectProvider<EditLeases> leases, ObjectProvider<TxSupport> tx) {
        return (PackagePort) Proxy.newProxyInstance(PackagePort.class.getClassLoader(), new Class<?>[]{PackagePort.class}, (proxy, m, args) -> {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (!m.getName().equals("update") || !(attrs instanceof ServletRequestAttributes sra)) {
                return call(target, m, args);
            }
            RequestContext ctx = (RequestContext) args[0];
            String file = (String) args[1];
            String header = sra.getRequest().getHeader(FENCE_HEADER);
            TxSupport support = tx.getObject();
            return support.inTx(() -> {
                checkFence(leases.getObject(), support, ctx, file, header);
                try {
                    return call(target, m, args);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            });
        });
    }

    private static void checkFence(EditLeases leases, TxSupport tx, RequestContext ctx, String file, String header) {
        if (header == null || header.isBlank()) {
            leases.current(ctx.tenantId(), file).filter(l -> l.expiresAt().isAfter(java.time.Instant.now())).ifPresent(l -> {
                throw ApiProblem.conflict("EDIT_LEASE_REQUIRED", "The model is under an edit lease; send " + FENCE_HEADER);
            });
            return;
        }
        try {
            leases.checkFence(tx.connection(), ctx.tenantId(), file, ctx.principalId(), Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            throw ApiProblem.badRequest("EDIT_FENCE_INVALID", FENCE_HEADER + " must be a number");
        } catch (EditLeases.StaleEditor e) {
            throw ApiProblem.conflict("EDIT_LEASE_LOST", "The edit lease is no longer held");
        }
    }

    private static PackageToolsPort reported(PackageToolsPort target, ObjectProvider<BulkModelTests> tests) {
        return (PackageToolsPort) Proxy.newProxyInstance(PackageToolsPort.class.getClassLoader(), new Class<?>[]{PackageToolsPort.class}, (proxy, m, args) -> {
            Object result = call(target, m, args);
            if (m.getName().equals("runTests")) {
                @SuppressWarnings("unchecked")
                List<PackageToolsPort.TestRun> runs = (List<PackageToolsPort.TestRun>) result;
                RequestContext ctx = (RequestContext) args[0];
                store(tests.getObject(), ctx, (String) args[1], runs);
            }
            return result;
        });
    }

    private static void store(BulkModelTests tests, RequestContext ctx, String packageId, List<PackageToolsPort.TestRun> runs) {
        Map<String, PackageToolsPort.TestRun> byName = new java.util.LinkedHashMap<>();
        List<BulkModelTests.TestCase> cases = new ArrayList<>();
        for (PackageToolsPort.TestRun r : runs) {
            byName.put(r.name(), r);
            cases.add(new BulkModelTests.TestCase(r.name(), packageId, Map.of()));
        }
        tests.run(ctx.tenantId(), packageId, cases, (tenant, tc) -> {
            PackageToolsPort.TestRun r = byName.get(tc.id());
            return new BulkModelTests.CaseResult(tc.id(), r.passed() ? BulkModelTests.Status.PASSED : BulkModelTests.Status.FAILED,
                    !tc.id().startsWith("judge:"), r.durationMs(), r.message());
        }, Duration.ofMinutes(5), ctx.principalId());
    }

    private static Object call(Object target, java.lang.reflect.Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Runs after all connectors are registered. */
    @Bean
    public SmartInitializingSingleton sangamModellerConnectorDecorators(ObjectProvider<ConnectorRegistry> connectors, TxSupport tx,
            ObjectProvider<SecretResolver> secrets, Environment env, ObjectProvider<ServiceMocks> mocks) {
        return () -> {
            ConnectorRegistry registry = connectors.getIfAvailable();
            if (registry != null) {
                applyMocks(registry, env, mocks.getIfAvailable());
                applyTokenExchange(registry, tx, secrets.getIfAvailable(SecretResolver::fromEnvironment), env);
            }
        };
    }

    private static void applyMocks(ConnectorRegistry registry, Environment env, ServiceMocks mocks) {
        if (mocks == null) {
            String ignored = env.getProperty("sangam.modeller.mocks.file");
            if (ignored != null) {
                LOG.warn("sangam.modeller.mocks.file is ignored on a production profile (R80)");
            }
            return;
        }
        java.util.Set<String> wrapped = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.function.Consumer<String> wrap = id -> {
            if (wrapped.add(id)) {
                registry.find(null, id).ifPresentOrElse(c -> registry.register(mocks.wrap(c)), () -> {
                    wrapped.remove(id);
                    LOG.warn("mock rule names unknown connector {}", id);
                });
            }
        };
        mocks.rules().stream().map(ServiceMocks.Rule::service).distinct().forEach(wrap);
        mocks.onNewService(wrap);
    }

    /** Null on a production profile, so the mock routes answer "not installed" there (R80). */
    @Bean
    public ServiceMocks sangamServiceMocks(Environment env, TxSupport tx, DataSource ds) {
        if (Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.contains("prod"))) {
            return null;
        }
        String file = env.getProperty("sangam.modeller.mocks.file");
        try {
            ServiceMocks.Rule[] rules = file == null ? new ServiceMocks.Rule[0] : Json.MAPPER.readValue(Files.readString(Path.of(file)), ServiceMocks.Rule[].class);
            // Stored rules (MOD_SERVICE_MOCK) of the default tenant, then file rules on top for this process.
            ServiceMocks mocks = new org.sangam.modeller.mock.ServiceMockStore(tx.dialect(), org.sangam.core.layer.Transactions.of(ds))
                    .load(GeoWiring.defaultTenant(env), false);
            for (ServiceMocks.Rule r : rules) {
                mocks.addRule(r);
            }
            return mocks;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("sangam.modeller.mocks.file could not be read: " + e.getMessage(), e);
        }
    }

    private static void applyTokenExchange(ConnectorRegistry registry, TxSupport tx, SecretResolver secrets, Environment env) {
        String p = "sangam.modeller.token-exchange.";
        String endpoint = env.getProperty(p + "token-endpoint");
        if (endpoint == null) {
            return;
        }
        List<String> specs = Binder.get(env).bind(p + "egress", Bindable.listOf(String.class)).orElse(List.of());
        List<EgressPolicy.Rule> rules = specs.stream().map(s -> EgressPolicy.Rule.parse("*", s, false)).toList();
        HttpTransport transport = new HttpTransport(rules.isEmpty() ? EgressPolicy.denyAll() : EgressPolicy.of(rules, 3, 1024 * 1024),
                null, Duration.ofSeconds(10), Duration.ofSeconds(30), null);
        TokenExchange exchange = new TokenExchange(new TokenExchange.Config(URI.create(endpoint), env.getRequiredProperty(p + "client-id"),
                env.getRequiredProperty(p + "client-secret-ref")), transport, secrets, Clock.systemUTC());
        AuditChain audit = new AuditChain(tx.dialect());
        String audience = env.getRequiredProperty(p + "audience");
        for (String id : Binder.get(env).bind(p + "connectors", Bindable.listOf(String.class)).orElse(List.of())) {
            SangamConnector real = registry.find(null, id).orElse(null);
            if (real == null) {
                LOG.warn("token exchange names unknown connector {}", id);
            } else {
                registry.register(new TokenExchangeConnector(real, exchange, audience, tx.newTransactions(), audit));
            }
        }
    }
}
