// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.operaton.bpm.engine.MismatchingMessageCorrelationException;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.channels.ChannelsSchema;
import org.sangam.channels.bot.BotBridge;
import org.sangam.channels.conversation.ConversationRetention;
import org.sangam.channels.conversation.ConversationService;
import org.sangam.channels.inbound.PolledChannels;
import org.sangam.channels.mail.GraphMailSource;
import org.sangam.channels.mail.InboundMail;
import org.sangam.channels.messenger.ChannelRuntime;
import org.sangam.channels.messenger.InboundMediaQueue;
import org.sangam.channels.messenger.OptInRegistry;
import org.sangam.channels.messenger.ProviderTokens;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.DocumentStore;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.file.LocalDirectoryTransport;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.integration.relay.WakeupListener;
import org.sangam.integration.relay.WakeupNotifier;
import org.sangam.ports.content.FileSystemDocumentStore;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.rest.modules.channels.ChannelsLiveHub;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.BotFrameworkKeys;
import org.sangam.server.bridge.ChannelMediaWorker;
import org.sangam.server.bridge.ChannelRuntimeBinder;
import org.sangam.server.bridge.ChannelsScheduler;
import org.sangam.core.engine.DocumentSlotNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-channels, off unless {@code sangam.modules.channels.enabled=true}. Nothing leaves the server unless
 * configured: every transport is limited to {@code sangam.channels.egress.<tenant>} and denies everything with no rules.
 *
 * <p>Properties: {@code sangam.channels.documents-dir} (default {@code ./data/channels}),
 * {@code sangam.channels.allow-unscanned} (default false: with no scanner bean, attachments are refused),
 * {@code sangam.channels.bot-process.<CHANNEL|*>}, {@code sangam.channels.teams.jwks-uri}, {@code sangam.channels.poll-seconds},
 * {@code sangam.channels.webhooks.<tenant>.*} (see {@code ChannelHooksController}), and polled sources, each a named map:
 * {@code sangam.channels.poll.imap.<name>.{tenant,host,port,user,password-ref,folder}},
 * {@code sangam.channels.poll.file.<name>.{tenant,root,in-dir,done-dir,error-dir,pattern}},
 * {@code sangam.channels.poll.sql.<name>.{tenant,select-sql,initial-cursor}},
 * {@code sangam.channels.poll.service-bus.<name>.{tenant,namespace,entity-path,sas-key-name,sas-key-ref}},
 * {@code sangam.channels.poll.graph.<name>.{tenant,mailbox,folder,client-id,client-secret-ref,directory-tenant,graph-base}}
 * (Microsoft Graph mail with a client-credentials token, application permission {@code Mail.ReadWrite}; the token and
 * Graph hosts must be on the tenant egress list). Enterprise connectors: {@code sangam.channels.enterprise.<tenant>.<id>.*}
 * (see {@link ChannelRuntimeBinder}). Retention: per tenant, at most once per {@code sangam.channels.retention.period}
 * (default {@code P1D}) on the scheduler's leader, every conversation retention class whose {@code retention-classes}
 * registry entry has {@code retentionYears} is purged ({@code sangam.channels.retention.enabled}, default true).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.channels.enabled", havingValue = "true")
public class ChannelsWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelsWiring.class);
    private static final int BATCH = 50;
    /** A Microsoft Entra directory id (GUID) or a verified domain; it becomes a path segment of the token URL. */
    private static final Pattern DIRECTORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,252}");

    @Bean
    public InitializingBean sangamChannelsDevSchema(DataSource ds, TxSupport tx, Environment env) {
        return () -> {
            if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
                try (Connection c = ds.getConnection()) {
                    if (!tableExists(c, "CH_CONVERSATION")) {
                        ChannelsSchema.create(c, tx.dialect());
                    }
                }
            }
        };
    }

    static boolean tableExists(Connection c, String table) throws SQLException {
        var md = c.getMetaData();
        for (String name : List.of(table, table.toLowerCase(java.util.Locale.ROOT))) {
            try (var rs = md.getTables(null, null, name, null)) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The PostgreSQL notification channel of the live-stream hint (payload-free, like the relay wake-up). */
    static final String LIVE_CHANNEL = "sangam_channels";

    /**
     * M-040 fan-out on the M-005 mechanism: a {@code LISTEN} on its own physical connection (PostgreSQL) or in-JVM only
     * (other dialects, where a single node is complete and other nodes catch up on the heartbeat). Its own channel name,
     * so chat traffic does not wake the worker long-polls.
     */
    static final class LiveFanout implements AutoCloseable {
        final WakeupListener listener;
        final WakeupNotifier notifier;

        LiveFanout(WakeupListener listener, WakeupNotifier notifier) {
            this.listener = listener;
            this.notifier = notifier;
        }

        @Override
        public void close() {
            listener.close();
        }
    }

    @Bean(destroyMethod = "close")
    LiveFanout sangamChannelsLiveFanout(TxSupport tx, DataSource ds) {
        Dialect d = tx.dialect();
        WakeupListener listener = WakeupListener.forDialect(d, d == Dialect.POSTGRESQL ? IntegrationWiring.listenerConnections(ds) : null,
                LIVE_CHANNEL);
        WakeupNotifier notifier = listener.usesListen() ? WakeupNotifier.postgres(d, tx.newTransactions(), LIVE_CHANNEL)
                : listener.inJvmNotifier();
        listener.start();
        return new LiveFanout(listener, notifier);
    }

    /**
     * {@code sangam.channels.stream.heartbeat} (default {@code PT30S}): SSE comment and catch-up interval;
     * {@code sangam.channels.stream.catch-up-window} (default {@code PT2M}): how far a catch-up re-reads behind the newest
     * message time it has seen, longer than any send transaction.
     */
    @Bean(destroyMethod = "close")
    public ChannelsLiveHub sangamChannelsLiveHub(LiveFanout sangamChannelsLiveFanout, ObjectProvider<ConversationService> conversations,
            Environment env) {
        ChannelsLiveHub hub = new ChannelsLiveHub(ChannelsLiveHub.Replay.of(conversations::getObject),
                () -> sangamChannelsLiveFanout.notifier.cycleCompleted(1),
                Duration.parse(env.getProperty("sangam.channels.stream.heartbeat", "PT30S")),
                Duration.parse(env.getProperty("sangam.channels.stream.catch-up-window", "PT2M")));
        sangamChannelsLiveFanout.listener.subscribe(hub::wake);
        return hub;
    }

    @Bean
    public DocumentStore sangamChannelsDocuments(Environment env) throws IOException {
        return new FileSystemDocumentStore(Path.of(env.getProperty("sangam.channels.documents-dir", "./data/channels")));
    }

    @Bean
    public OptInRegistry sangamOptInRegistry(TxSupport tx) {
        return new OptInRegistry(tx.dialect(), tx.newTransactions(), new AuditChain(tx.dialect()));
    }

    /** With no scanner bean, attachments are refused unless {@code sangam.channels.allow-unscanned=true}. */
    @Bean
    public ConversationService sangamConversationService(TxSupport tx, DocumentStore sangamChannelsDocuments, ChannelsLiveHub live,
            ObjectProvider<ConversationService.MalwareScanner> scanner, Environment env) {
        Dialect d = tx.dialect();
        boolean allow = env.getProperty("sangam.channels.allow-unscanned", Boolean.class, false);
        return new ConversationService(d, tx.newTransactions(), new AuditChain(d), sangamChannelsDocuments, live,
                scanner.getIfAvailable(() -> (name, content) -> allow), ChannelsWiring::mayJoin);
    }

    /** R19: an id qualified with another tenant ({@code other:user}) cannot join; ids are otherwise tenant-local. */
    static boolean mayJoin(String tenant, String principal) {
        return principal != null && !principal.isBlank() && (!principal.contains(":") || principal.startsWith(tenant + ":"));
    }

    @Bean
    public BotBridge sangamBotBridge(ObjectProvider<ProcessEngine> engine, Environment env) {
        Map<String, String> processes = Binder.get(env).bind("sangam.channels.bot-process", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        return new BotBridge(new BotBridge.ProcessGateway() {
            @Override
            public boolean correlate(String tenantId, String messageName, String businessKey, Map<String, Object> variables) {
                DocumentSlotNames.refuse(variables == null ? null : variables.keySet()); // decision 385 (2)
                try {
                    engine.getObject().getRuntimeService().createMessageCorrelation(messageName).tenantId(tenantId)
                            .processInstanceBusinessKey(businessKey).setVariables(variables).correlateWithResult();
                    return true;
                } catch (MismatchingMessageCorrelationException e) {
                    return false;
                }
            }

            @Override
            public void start(String tenantId, String processKey, String businessKey, Map<String, Object> variables) {
                DocumentSlotNames.refuse(variables == null ? null : variables.keySet()); // decision 385 (2)
                engine.getObject().getRuntimeService().createProcessInstanceByKey(processKey).processDefinitionTenantId(tenantId)
                        .businessKey(businessKey).setVariables(variables).execute();
            }
        }, processes);
    }

    @Bean
    public ChannelRuntimeBinder sangamChannelRuntime(RegistryPort registry, ConnectorRegistry connectors, OptInRegistry optIns,
            ObjectProvider<SecretResolver> secrets, Environment env) {
        return new ChannelRuntimeBinder(registry, connectors, optIns, secrets.getIfAvailable(SecretResolver::fromEnvironment), env);
    }

    /**
     * Inbound media of the channel webhooks, durable on the inbox row ({@code CH_INBOUND_SEEN} media columns, written with
     * the row): claimed by any node, downloaded with the tenant's channel entry, M-082 scan (without a scanner bean only with
     * {@code sangam.channels.allow-unscanned=true}), stored, document key to the bot. Retries after 10 s, 1 min and 5 min;
     * then the row is parked and {@code INBOUND_MEDIA_FAILED} audited in the same transaction.
     * {@code sangam.channels.media.threads} (default 2), {@code sangam.channels.media.poll} (default {@code PT5S}),
     * {@code sangam.channels.media.lease} (default {@code PT10M}: a stopped node's claim returns after it).
     */
    @Bean(destroyMethod = "close")
    public ChannelMediaWorker sangamChannelMediaWorker(RegistryPort registry, ChannelRuntimeBinder runtime, ObjectProvider<SecretResolver> secretProvider,
            DocumentStore sangamChannelsDocuments, ObjectProvider<ConversationService.MalwareScanner> scanner, BotBridge bot, TxSupport tx,
            RestProperties rest, Environment env) {
        SecretResolver secrets = secretProvider.getIfAvailable(SecretResolver::fromEnvironment);
        boolean allow = env.getProperty("sangam.channels.allow-unscanned", Boolean.class, false);
        Dialect d = tx.dialect();
        AuditChain audit = new AuditChain(d);
        Transactions t = tx.newTransactions();
        InboundMediaQueue queue = new InboundMediaQueue(d, t, rest.getNode(),
                Duration.parse(env.getProperty("sangam.channels.media.lease", "PT10M")));
        return new ChannelMediaWorker(queue, t, tenant -> channelEntries(registry, tenant),
                (tenant, entry, ref) -> ChannelRuntime.media(tenant, entry, runtime.transport(tenant), secrets, runtime.tokens(), ref),
                sangamChannelsDocuments,
                (name, content) -> scanner.getIfAvailable() != null ? scanner.getObject().clean(name, content) : allow,
                bot, env.getProperty("sangam.channels.media.threads", Integer.class, 2),
                Duration.parse(env.getProperty("sangam.channels.media.poll", "PT5S")),
                List.of(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                (c, media, reason) -> audit.append(c, media.tenantId(), "channel:" + media.channel().toLowerCase(java.util.Locale.ROOT),
                        "INBOUND_MEDIA_FAILED", Map.of("messageId", String.valueOf(media.messageId()), "reason", String.valueOf(reason))))
                .start();
    }

    static List<Map<String, Object>> channelEntries(RegistryPort registry, String tenant) {
        RequestContext sys = new RequestContext(tenant, "system:channels", java.util.Set.of("sangam-system", "sangam-admin"), null, null,
                null, null, null, "SYSTEM");
        return registry.list(sys, "conversation-channels", null, Page.PageRequest.of(0, 500)).items();
    }

    @Bean
    public BiFunction<String, String, Optional<PublicKey>> sangamBotFrameworkKeys(ChannelRuntimeBinder runtime, Environment env) {
        return new BotFrameworkKeys(URI.create(env.getProperty("sangam.channels.teams.jwks-uri",
                "https://login.botframework.com/v1/.well-known/keys")), runtime::transport);
    }

    @Bean
    public InboundMail sangamInboundMail(TxSupport tx, DocumentStore sangamChannelsDocuments, InboundEventProcessor processor,
            ObjectProvider<ConversationService.MalwareScanner> scanner, Environment env) {
        Dialect d = tx.dialect();
        AuditChain audit = new AuditChain(d);
        Transactions t = tx.newTransactions();
        boolean allow = env.getProperty("sangam.channels.allow-unscanned", Boolean.class, false);
        return new InboundMail(d, t, audit, sangamChannelsDocuments,
                (name, content) -> scanner.getIfAvailable() != null ? scanner.getObject().clean(name, content) : allow,
                (tenant, channel, mail, attachments) -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("from", mail.from());
                    event.put("subject", mail.subject());
                    event.put("text", mail.text());
                    event.put("attachments", attachments);
                    processor.receive(tenant, channel, Json.write(event), Map.of(), mail.messageId());
                },
                (tenant, channel, messageId, reason) -> t.inTransaction(c -> audit.append(c, tenant, "mail:" + channel, "INBOUND_MAIL_POISON",
                        Map.of("messageId", messageId, "reason", String.valueOf(reason)))));
    }

    @Bean
    public ChannelsScheduler sangamChannelsScheduler(TxSupport tx, DataSource ds, RestProperties rest, InboundMail mail,
            InboundEventProcessor processor, ChannelRuntimeBinder runtime, ObjectProvider<SecretResolver> secretProvider,
            ObjectProvider<AdminPort> admin, Environment env,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters, ConversationService conversations,
            RegistryPort registry) {
        LeaderElection leader = new LeaderElection(tx.dialect(), Transactions.of(ds), rest.getNode(), Duration.ofMinutes(3));
        ScheduleTenantsMonitor monitor = ScheduleTenantsMonitor.of("channels", "no tenant's channel runtime is refreshed"
                + " (polled sources still run). Tenants come from the tenant directory plus sangam.compliance.tenants.", meters);
        SecretResolver secrets = secretProvider.getIfAvailable(SecretResolver::fromEnvironment);
        List<ChannelsScheduler.NamedTask> polled = polledTasks(tx, mail, PolledChannels.Receiver.of(processor), runtime, secrets, env);
        boolean retain = env.getProperty("sangam.channels.retention.enabled", Boolean.class, true);
        ConversationRetention retention = new ConversationRetention(conversations, tx.dialect(), tx.newTransactions(),
                tenant -> retentionPolicy(registry, tenant));
        ChannelsScheduler.Spaced daily = new ChannelsScheduler.Spaced(
                Duration.parse(env.getProperty("sangam.channels.retention.period", "P1D")), Duration.ofHours(1), Clock.systemUTC());
        return new ChannelsScheduler(leader, () -> {
            List<ChannelsScheduler.NamedTask> all = new ArrayList<>();
            for (String tenant : monitor.observe(ComplianceWiring.tenants(env, admin, monitor))) {
                all.add(new ChannelsScheduler.NamedTask("runtime:" + tenant, () -> runtime.refresh(tenant)));
                if (retain) {
                    all.add(new ChannelsScheduler.NamedTask("retention:" + tenant, daily.wrap("retention:" + tenant, () -> {
                        Map<String, Integer> removed = retention.runOnce(tenant);
                        if (removed.values().stream().anyMatch(n -> n > 0)) {
                            LOG.info("channels retention for tenant {}: {}", tenant, removed);
                        }
                    })));
                }
            }
            all.addAll(polled);
            return all;
        }, env.getProperty("sangam.channels.poll-seconds", Long.class, 60L));
    }

    private static List<ChannelsScheduler.NamedTask> polledTasks(TxSupport tx, InboundMail mail, PolledChannels.Receiver receiver,
            ChannelRuntimeBinder runtime, SecretResolver secrets, Environment env) {
        List<ChannelsScheduler.NamedTask> out = new ArrayList<>();
        sources(env, "imap").forEach((name, m) -> out.add(new ChannelsScheduler.NamedTask("imap:" + name, () -> {
            String tenant = m.get("tenant");
            try (InboundMail.Imap source = new InboundMail.Imap(new InboundMail.Imap.Config(tenant, m.get("host"),
                    Integer.parseInt(m.getOrDefault("port", "993")), m.get("user"), m.get("password-ref"), m.getOrDefault("folder", "INBOX")),
                    runtime.egress(tenant), secrets)) {
                mail.poll(tenant, name, source, BATCH);
            }
        })));
        sources(env, "file").forEach((name, m) -> {
            PolledChannels.FileChannel ch = new PolledChannels.FileChannel(m.get("tenant"), name, new LocalDirectoryTransport(Path.of(m.get("root"))),
                    m.getOrDefault("in-dir", "in"), m.getOrDefault("done-dir", "done"), m.getOrDefault("error-dir", "error"),
                    Pattern.compile(m.getOrDefault("pattern", ".*")), receiver);
            out.add(new ChannelsScheduler.NamedTask("file:" + name, () -> ch.pollOnce(BATCH)));
        });
        sources(env, "sql").forEach((name, m) -> {
            Transactions t = tx.newTransactions();
            PolledChannels.SqlChannel ch = new PolledChannels.SqlChannel(m.get("tenant"), name, t, t, tx.dialect(), m.get("select-sql"),
                    m.getOrDefault("initial-cursor", "0"), receiver);
            out.add(new ChannelsScheduler.NamedTask("sql:" + name, () -> ch.pollOnce(BATCH)));
        });
        sources(env, "service-bus").forEach((name, m) -> {
            String tenant = m.get("tenant");
            PolledChannels.ServiceBusChannel ch = new PolledChannels.ServiceBusChannel(tenant, name, URI.create(m.get("namespace")),
                    m.get("entity-path"), m.get("sas-key-name"), m.get("sas-key-ref"), runtime.transport(tenant), secrets,
                    now -> now.plusSeconds(300), receiver);
            out.add(new ChannelsScheduler.NamedTask("service-bus:" + name, () -> ch.pollOnce(BATCH)));
        });
        sources(env, "graph").forEach((name, m) -> out.add(new ChannelsScheduler.NamedTask("graph:" + name, () -> {
            String tenant = m.get("tenant");
            HttpTransport transport = runtime.transport(tenant);
            String directory = m.get("directory-tenant");
            if (directory == null || !DIRECTORY.matcher(directory).matches()) {
                throw new IllegalArgumentException("sangam.channels.poll.graph." + name + ".directory-tenant must be a directory id or domain");
            }
            ProviderTokens.TokenSource token = runtime.tokens().oauth2(
                    URI.create("https://login.microsoftonline.com/" + directory + "/oauth2/v2.0/token"), m.get("client-id"),
                    m.get("client-secret-ref"), ProviderTokens.GRAPH_SCOPE, secrets, transport);
            URI graphBase = URI.create(m.getOrDefault("graph-base", GraphMailSource.GRAPH_V1.toString()));
            try (GraphMailSource source = new GraphMailSource(new GraphMailSource.Config(tenant, graphBase, m.get("mailbox"),
                    m.get("folder")), transport, token)) {
                mail.poll(tenant, name, source, BATCH);
            }
        })));
        if (!out.isEmpty()) {
            LOG.info("channels: {} polled sources configured", out.size());
        }
        return out;
    }

    /** The tenant's {@code retention-classes} entries with {@code retentionYears}; an unreadable entry is skipped (kept). */
    static Map<String, Period> retentionPolicy(RegistryPort registry, String tenant) {
        RequestContext sys = new RequestContext(tenant, ConversationRetention.ACTOR, java.util.Set.of("sangam-system", "sangam-admin"),
                null, null, null, null, null, "SYSTEM");
        Map<String, Period> out = new LinkedHashMap<>();
        for (Map<String, Object> e : registry.list(sys, "retention-classes", null, Page.PageRequest.of(0, 500)).items()) {
            Object key = e.get("key");
            try {
                Period p = ConversationRetention.years(e.get("retentionYears"));
                if (key != null && p != null) {
                    out.put(key.toString(), p);
                }
            } catch (IllegalArgumentException ex) {
                LOG.warn("retention class {} of tenant {} is skipped: {}", key, tenant, ex.getMessage());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> sources(Environment env, String type) {
        ResolvableType inner = ResolvableType.forClassWithGenerics(Map.class, String.class, String.class);
        ResolvableType outer = ResolvableType.forClassWithGenerics(Map.class, ResolvableType.forClass(String.class), inner);
        return (Map<String, Map<String, String>>) Binder.get(env)
                .bind("sangam.channels.poll." + type, Bindable.of(outer)).orElse(Map.of());
    }
}
