// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiFunction;

import javax.sql.DataSource;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.pipeline.FeelEvaluator;
import org.sangam.integration.pipeline.PipelineDefinition;
import org.sangam.integration.pipeline.PipelineDelegate;
import org.sangam.integration.pipeline.PipelineMessages;
import org.sangam.integration.pipeline.PipelineResources;
import org.sangam.integration.pipeline.PipelineRunner;
import org.sangam.integration.pipeline.PipelineScheduler;
import org.sangam.integration.pipeline.PipelineStore;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.telemetry.AlarmEpisodeStore;
import org.sangam.integration.telemetry.AlarmEventEmitter;
import org.sangam.integration.telemetry.DeviceRegistry;
import org.sangam.integration.telemetry.OperatonRuleDecisionEvaluator;
import org.sangam.integration.telemetry.RuleSet;
import org.sangam.integration.telemetry.TelemetryRuleHandler;
import org.sangam.integration.telemetry.TelemetryWiring;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Data pipelines (N11) and telemetry (N12), off with {@code sangam.modules.pipelines.enabled=false} and
 * {@code sangam.modules.telemetry.enabled=false}. Definitions are read from {@code classpath:pipelines/<id>.json}.
 */
public class PipelineTelemetryWiring {

    private PipelineTelemetryWiring() {
    }

    private static Transactions engineTx(ObjectProvider<EngineAdapter> adapter, DataSource ds) {
        EngineAdapter a = adapter.getIfAvailable();
        return a != null ? Transactions.of(a) : Transactions.of(ds);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = {"sangam.modules.pipelines.enabled", "sangam.modules.integration.enabled"}, havingValue = "true", matchIfMissing = true)
    public static class Pipelines {

        @Bean(destroyMethod = "close")
        public PipelineScheduler sangamPipelineScheduler(ProcessEngine engine, InboxService inbox, OutboxWriter outbox,
                ObjectProvider<SecretResolver> secrets, ObjectProvider<EngineAdapter> adapter, DataSource ds, TxSupport tx,
                RestProperties rest, Environment env) {
            Dialect dialect = tx.dialect();
            Transactions txs = engineTx(adapter, ds);
            PipelineStore store = new PipelineStore(dialect, txs);
            // Air-gapped by default: no HTTP egress, and no data sources or file transports until a site adds them.
            PipelineResources resources = new PipelineResources(Map.of(), Map.of(),
                    new HttpTransport(EgressPolicy.denyAll(), null, Duration.ofSeconds(10), Duration.ofSeconds(30), null),
                    secrets.getIfAvailable(SecretResolver::fromEnvironment), outbox, PipelineMessages.decisions(engine),
                    Path.of(env.getProperty("sangam.pipelines.stage-root", "data/pipeline-stage")));
            PipelineRunner runner = new PipelineRunner(store, resources, txs, new FeelEvaluator(), rest.getNode());
            BiFunction<String, String, PipelineDefinition> definitions = PipelineTelemetryWiring::definition;
            PipelineMessages.wire(engine, inbox, runner);
            PipelineDelegate.install(store, definitions);
            LeaderElection leader = new LeaderElection(dialect, Transactions.of(ds), rest.getNode(), Duration.ofMinutes(3));
            PipelineScheduler scheduler = new PipelineScheduler(leader, txs, dialect, store, runner, definitions,
                    env.getProperty("sangam.pipelines.max-concurrent", Integer.class, 4));
            if (env.getProperty("sangam.pipelines.schedule.enabled", Boolean.class, true)) {
                scheduler.start(env.getProperty("sangam.pipelines.schedule.every", Duration.class, Duration.ofSeconds(30)));
            }
            return scheduler;
        }
    }

    private static PipelineDefinition definition(String tenant, String id) {
        if (!id.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid pipeline id " + id);
        }
        try (InputStream in = PipelineTelemetryWiring.class.getClassLoader().getResourceAsStream("pipelines/" + id + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("pipeline definition not found: " + id);
            }
            return PipelineDefinition.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read pipeline " + id, e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = {"sangam.modules.telemetry.enabled", "sangam.modules.integration.enabled", "sangam.modules.ports.enabled"}, havingValue = "true", matchIfMissing = true)
    public static class Telemetry {

        @Bean
        public TelemetryRuleHandler sangamTelemetryRuleHandler(ProcessEngine engine, InboxService inbox,
                InboundEventProcessor processor, ObjectProvider<EngineAdapter> adapter, DataSource ds, TxSupport tx) {
            Transactions txs = engineTx(adapter, ds);
            TelemetryRuleHandler handler = new TelemetryRuleHandler(tx.dialect(), new DeviceRegistry(tx.dialect(), txs),
                    new AlarmEpisodeStore(tx.dialect(), txs), new RuleSet.Registry(), new AlarmEventEmitter(processor),
                    new OperatonRuleDecisionEvaluator(engine.getDecisionService(), true));
            TelemetryWiring.wire(inbox, handler);
            return handler;
        }
    }
}
