// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.cloud;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.model.ChannelDefinition;

/**
 * The cloud event-gateway channel families (M-064), plugged into the flowable-ports {@link ChannelAdapters} through
 * its {@link ChannelAdapters.Provider} seam: AWS {@code sqs} (in/out), {@code sns} and {@code eventbridge} (out), Azure
 * {@code servicebus} (in/out) and {@code eventgrid} namespace topics (out; inbound is a push channel served by
 * {@link EventGridInboundEndpoint}, decision 189), Google {@code pubsub} (in/out). Each speaks
 * the provider's public HTTPS API with no vendor SDK (so no gRPC: REST first), through the connector {@link HttpTransport}
 * (egress allow-list with DNS pinning and the private-address block) and the product's one signer of each kind:
 * SigV4 ({@code AwsRequestSigner} over {@code AwsSigV4}), {@link AzureSas}, and {@code GcpTokens}.
 *
 * <p>Egress: the channel's hosts must be on the deployment allow-list ({@link ChannelAdapters.Environment#egressHosts()},
 * https only; for Pub/Sub also the token endpoint of the service-account key). Credentials are secret references
 * resolved through the environment on every call (a rotated key needs no redeploy). The time comes from the injected
 * {@link Clock}. A send throws on anything but a 2xx answer, so the relay retries (at-least-once; receivers dedupe by
 * message id).
 */
public final class CloudChannels implements ChannelAdapters.Provider {

    public static final Set<String> TYPES = Set.of("sqs", "sns", "eventbridge", "servicebus", "eventgrid", "pubsub");
    /** Largest answer read (an SQS batch is at most 10 x 256 KiB; Pub/Sub pulls are bounded by maxMessages). */
    static final long MAX_RESPONSE_BYTES = 16L * 1024 * 1024;

    private final Clock clock;
    private final BiFunction<ChannelDefinition, ChannelAdapters.Environment, HttpTransport> transports;

    /** Production: one egress-checked transport per channel, built from the deployment allow-list. */
    public CloudChannels(Clock clock) {
        this(clock, CloudChannels::transport);
    }

    /** Tests and simulators pass their own transport (for example a loopback allow-list). */
    public CloudChannels(Clock clock, BiFunction<ChannelDefinition, ChannelAdapters.Environment, HttpTransport> transports) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transports = Objects.requireNonNull(transports, "transports");
    }

    @Override
    public Set<String> types() {
        return TYPES;
    }

    @Override
    public ChannelAdapters.OutboundChannelAdapter outbound(ChannelDefinition ch, ChannelAdapters.Environment env) {
        Http http = new Http(transports.apply(ch, env), env, clock);
        return switch (ch.adapterType()) {
            case "sqs" -> new AwsChannels.SqsOutbound(ch, http);
            case "sns" -> new AwsChannels.SnsOutbound(ch, http);
            case "eventbridge" -> new AwsChannels.EventBridgeOutbound(ch, http);
            case "servicebus" -> new AzureChannels.ServiceBusOutbound(ch, http);
            case "eventgrid" -> new AzureChannels.EventGridOutbound(ch, http);
            case "pubsub" -> new PubSubChannels.Outbound(ch, http);
            default -> throw new IllegalArgumentException("no outbound adapter for " + ch.adapterType());
        };
    }

    @Override
    public ChannelAdapters.InboundChannelAdapter inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor,
                                                         ChannelAdapters.Environment env) {
        Http http = new Http(transports.apply(ch, env), env, clock);
        return switch (ch.adapterType()) {
            case "sqs" -> new AwsChannels.SqsInbound(ch, tenant, processor, http);
            case "servicebus" -> new AzureChannels.ServiceBusInbound(ch, tenant, processor, http);
            case "pubsub" -> new PubSubChannels.Inbound(ch, tenant, processor, http);
            default -> throw new IllegalArgumentException("no inbound adapter for " + ch.adapterType());
        };
    }

    /** The production transport: https to the allow-listed hosts only, public addresses only, no transport retries. */
    static HttpTransport transport(ChannelDefinition ch, ChannelAdapters.Environment env) {
        List<EgressPolicy.Rule> rules = new ArrayList<>();
        for (String h : env.egressHosts()) {
            if (h != null && !h.isBlank()) {
                rules.add(EgressPolicy.Rule.parse("*", "https://" + h.trim(), false));
            }
        }
        return new HttpTransport(EgressPolicy.of(rules, 0, MAX_RESPONSE_BYTES), null, Duration.ofSeconds(10),
                Duration.ofSeconds(60), RetryPolicy.none());
    }

    /** One channel's HTTP access: transport, secrets by reference, clock. */
    record Http(HttpTransport transport, ChannelAdapters.Environment env, Clock clock) {

        String secret(String ref) {
            if (ref == null || ref.isBlank()) {
                throw new IllegalStateException("a secret reference is missing");
            }
            return env.secret(ref).filter(v -> !v.isBlank())
                    .orElseThrow(() -> new IllegalStateException("secret " + ref + " cannot be resolved (the value is not shown)"));
        }

        /** The environment's secrets as the connector port, for the signers that read references themselves. */
        SecretResolver secrets() {
            return env::secret;
        }

        /** Executes the call; anything but a 2xx answer throws (the message stays with the relay or the broker). */
        HttpTransport.Outcome.Response ok(String tenant, String what, HttpTransport.Call call) {
            HttpTransport.Outcome o = transport.execute(tenant == null ? "system" : tenant, call, 0);
            if (o instanceof HttpTransport.Outcome.Response r && r.is2xx()) {
                return r;
            }
            throw new IllegalStateException(what + " failed: " + describe(o));
        }

        JsonNode okJson(String tenant, String what, HttpTransport.Call call) {
            HttpTransport.Outcome.Response r = ok(tenant, what, call);
            JsonNode n = r.body().length == 0 ? null : Json.tree(r.body());
            return n == null ? Json.MAPPER.createObjectNode() : n;
        }
    }

    static String describe(HttpTransport.Outcome o) {
        return switch (o) {
            case HttpTransport.Outcome.Response r -> "HTTP " + r.status() + " " + HttpTransport.printable(
                    abbreviate(new String(r.body(), StandardCharsets.UTF_8)));
            case HttpTransport.Outcome.NoEffect ne -> ne.reason();
            case HttpTransport.Outcome.Unknown u -> "outcome unknown: " + u.reason();
        };
    }

    private static String abbreviate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    static URI uri(String s) {
        return URI.create(s);
    }

    /** {@code properties.<name>}, or the default. */
    static String prop(ChannelDefinition ch, String name, String dflt) {
        String v = ch.properties().get(name);
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    static String required(ChannelDefinition ch, String name) {
        String v = prop(ch, name, null);
        if (v == null) {
            throw new IllegalArgumentException(ch.adapterType() + " channel " + ch.key() + " needs properties." + name);
        }
        return v;
    }

    static int intProp(ChannelDefinition ch, String name, int dflt, int min, int max) {
        int v = Integer.parseInt(prop(ch, name, Integer.toString(dflt)));
        return Math.max(min, Math.min(max, v));
    }

    /** Header-like metadata of one outgoing message: ids, and the CloudEvents binary attributes under {@code prefix}. */
    static Map<String, String> metadata(ChannelAdapters.Outgoing m, String prefix) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (m.binary()) {
            m.cloudEventsOrEmpty().forEach((k, v) -> out.put(prefix + k, v));   // id, type and tenant are among them
        } else {
            out.put("message-id", m.messageId());
            out.put("event-key", m.eventKey());
            if (m.tenantId() != null) {
                out.put("tenant-id", m.tenantId());
            }
        }
        out.put("content-type", m.contentType());
        return out;
    }
}
