// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.sangam.channels.messenger.ChannelRuntime;
import org.sangam.channels.messenger.OptInRegistry;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.modules.channels.ChannelStatusSource;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Builds a running connector per {@code conversation-channels} registry entry and registers it for the tenant. Calls go
 * through a transport limited to {@code sangam.channels.egress.<tenant>} (host specs); with no rules the policy denies
 * everything, so nothing leaves until an operator allows it. Matrix and Slack entries have no adapter: they are reported
 * and logged, not accepted silently.
 */
public final class ChannelRuntimeBinder implements ChannelStatusSource {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelRuntimeBinder.class);
    private static final Set<String> NO_ADAPTER = Set.of("MATRIX", "SLACK");
    private static final List<String> RECIPIENT_FIELDS = List.of("to", "recipientId", "touser", "openId", "conversationId");

    private final RegistryPort registry;
    private final ConnectorRegistry connectors;
    private final OptInRegistry optIns;
    private final SecretResolver secrets;
    private final Environment env;
    private final Map<String, List<ChannelStatus>> status = new ConcurrentHashMap<>();

    public ChannelRuntimeBinder(RegistryPort registry, ConnectorRegistry connectors, OptInRegistry optIns, SecretResolver secrets,
                                Environment env) {
        this.registry = registry;
        this.connectors = connectors;
        this.optIns = optIns;
        this.secrets = secrets;
        this.env = env;
    }

    public void refresh(String tenant) {
        RequestContext sys = new RequestContext(tenant, "system:channels", Set.of("sangam-system", "sangam-admin"), null, null,
                null, null, null, "SYSTEM");
        Page<Map<String, Object>> page = registry.list(sys, "conversation-channels", null, Page.PageRequest.of(0, 500));
        HttpTransport transport = transport(tenant);
        List<ChannelStatus> out = new ArrayList<>();
        for (Map<String, Object> doc : page.items()) {
            String key = String.valueOf(doc.get("key"));
            String channel = String.valueOf(doc.get("channel")).toUpperCase(Locale.ROOT);
            if (NO_ADAPTER.contains(channel)) {
                LOG.warn("conversation channel {} of tenant {} is {}: no adapter exists, nothing will be delivered", key, tenant, channel);
                out.add(new ChannelStatus(key, channel, "NO_ADAPTER", "CHANNEL_ADAPTER_NOT_AVAILABLE"));
                continue;
            }
            try {
                ConnectorProfile profile = "SIMULATOR".equalsIgnoreCase(String.valueOf(doc.get("profile")))
                        ? ConnectorProfile.SIMULATOR : ConnectorProfile.LIVE;
                connectors.register(tenant, ChannelRuntime.connector(doc, profile, transport, secrets, optedIn(tenant, channel)));
                out.add(new ChannelStatus(key, channel, "ACTIVE", null));
            } catch (RuntimeException e) {
                LOG.warn("conversation channel {} of tenant {} is invalid: {}", key, tenant, e.getMessage());
                out.add(new ChannelStatus(key, channel, "INVALID", e.getMessage()));
            }
        }
        status.put(tenant, List.copyOf(out));
    }

    @Override
    public List<ChannelStatus> status(String tenantId) {
        return status.getOrDefault(tenantId, List.of());
    }

    /** Tenant transport; deny-all with no {@code sangam.channels.egress.<tenant>} rules. */
    public HttpTransport transport(String tenant) {
        return new HttpTransport(egress(tenant), null, Duration.ofSeconds(10), Duration.ofSeconds(30), null);
    }

    public EgressPolicy egress(String tenant) {
        List<String> specs = Binder.get(env).bind("sangam.channels.egress." + tenant, Bindable.listOf(String.class)).orElse(List.of());
        List<EgressPolicy.Rule> rules = new ArrayList<>();
        for (String spec : specs) {
            rules.add(EgressPolicy.Rule.parse(tenant, spec, false));
        }
        return rules.isEmpty() ? EgressPolicy.denyAll() : EgressPolicy.of(rules, 3, 32L * 1024 * 1024);
    }

    private Function<ConnectorRequest, Boolean> optedIn(String tenant, String channel) {
        return r -> RECIPIENT_FIELDS.stream().map(r::str).filter(v -> v != null && !v.isBlank()).findFirst()
                .flatMap(id -> optIns.principalOf(tenant, channel, id)).isPresent();
    }
}
