// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.DecisionDefinition;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Outbox;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.rules.DecisionSource;
import org.sangam.rest.adapter.operaton.OperatonWorkspaceAdapter;
import org.sangam.rest.port.WorkspacePort;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * sangam-resources port bindings (resources README "Entry point"): DMN tables from the engine repository, in-app
 * notifications, the display feed onto an event channel, audit on the core audit chain, metrics on Micrometer and
 * attendance through an integration connector. The security ports (authorization, seat directory, duty-seat link,
 * leave calendar) and the approval port are bound directly in {@code ResourcesWiring}.
 */
public final class ResourcesPortBridges {

    private static final Logger LOG = LoggerFactory.getLogger(ResourcesPortBridges.class);

    private ResourcesPortBridges() {
    }

    // ---------------------------------------------------------------- DMN tables from the engine repository

    /**
     * Decision tables by key from the engine's deployments: the tenant's latest version, else the shared (untenanted)
     * one. The module evaluates them itself (resources README "Rule contracts"); the hash identifies the version.
     */
    public static DecisionSource decisions(RepositoryService repository) {
        return (tenantId, key) -> {
            DecisionDefinition d = repository.createDecisionDefinitionQuery().decisionDefinitionKey(key)
                    .tenantIdIn(tenantId).latestVersion().singleResult();
            if (d == null) {
                d = repository.createDecisionDefinitionQuery().decisionDefinitionKey(key).withoutTenantId()
                        .latestVersion().singleResult();
            }
            if (d == null) {
                return Optional.empty();
            }
            try (InputStream in = repository.getDecisionModel(d.getId())) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                String xml = out.toString(StandardCharsets.UTF_8);
                return Optional.of(new DecisionSource.Decision(key, d.getKey(), xml,
                        Hashes.hex(Hashes.sha256(xml))));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read decision model " + d.getId(), e);
            }
        };
    }

    // ---------------------------------------------------------------- notifications → in-app (workspace)

    /**
     * Notices (bump, waitlist offer, token called, roster published ...) as in-app notifications of the recipient
     * ({@code /me/notifications}, REST_DOCUMENT), delivered after commit by the resources outbox publisher. E-mail /
     * SMS delivery of the same notices is configured per tenant through the notification connectors (gap: no
     * template mapping from resources template keys to DLT templates yet).
     */
    public static ResourcesPorts.NotificationPort notifications(WorkspacePort workspace) {
        return (c, tenantId, recipientId, templateKey, data) -> {
            if (workspace instanceof OperatonWorkspaceAdapter w) {
                Object link = data == null ? null : data.get("link");
                w.addNotification(tenantId, recipientId, new WorkspacePort.Notification(Hashes.uuidV7().toString(),
                        Instant.now(), templateKey, data == null ? null : CanonicalJson.write(jsonSafe(data)), false,
                        "SYSTEM", link == null ? null : String.valueOf(link)));
            } else {
                LOG.debug("resources notice {} for {} not delivered in-app: no workspace notification store", templateKey,
                        recipientId);
            }
        };
    }

    // ---------------------------------------------------------------- display feed → event channel outbox

    /**
     * Display boards receive the feed through the configured outbound event channel
     * ({@code channel:<sangam.resources.display-feed.channel>}, published by flowable-ports' ChannelPublisher),
     * ordered per service point. Without a configured channel boards poll the display snapshot and call events.
     */
    public static ResourcesPorts.DisplayFeedPort displayFeed(TxSupport tx, String channel) {
        if (channel == null || channel.isBlank()) {
            return (tenantId, event) -> LOG.trace("display feed event for {} (no channel configured)", event.servicePointId());
        }
        Outbox outbox = new Outbox(tx.dialect());
        return (tenantId, event) -> tx.inNewTx(() -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("servicePointId", event.servicePointId());
            p.put("kind", event.kind());
            p.put("tokenId", event.tokenId());
            p.put("displayNo", event.displayNo());
            p.put("counterId", event.counterId());
            return outbox.append(tx.connection(), tenantId, "res-board:" + event.servicePointId(), "RES_DISPLAY_FEED",
                    CanonicalJson.write(jsonSafe(p)), List.of(Outbox.Destination.ordered("channel:" + channel)));
        });
    }

    // ---------------------------------------------------------------- audit → core audit chain (same transaction)

    public static ResourcesPorts.AuditPort audit(AuditChain chain) {
        return (c, actor, eventType, subjectId, payload) -> {
            Map<String, Object> p = new LinkedHashMap<>(payload == null ? Map.of() : payload);
            p.put("actor", Map.of("principal_id", actor.personId(), "on_behalf_of",
                    actor.onBehalfOf() == null ? "" : actor.onBehalfOf()));
            chain.append(c, actor.tenantId(), subjectId, eventType, (Map<String, ?>) jsonSafe(p));
        };
    }

    // ---------------------------------------------------------------- metrics → Micrometer

    public static ResourcesPorts.MetricsPort metrics(MeterRegistry registry) {
        return (tenantId, metric, value, tags) -> {
            Tags t = Tags.empty();
            if (tags != null) {
                for (Map.Entry<String, String> e : tags.entrySet()) {
                    t = t.and(e.getKey(), e.getValue() == null ? "" : e.getValue());
                }
            }
            registry.summary(metric.startsWith("sangam.") ? metric : "sangam.resources." + metric, t).record(value);
        };
    }

    // ---------------------------------------------------------------- attendance → integration connector

    /**
     * Attendance records (punch in/out) from the configured connector ({@code sangam.resources.attendance.connector},
     * operation {@code attendance}, input personId/from/to; output {@code records: [{in, out, source}]}). A read-only
     * call made by the attendance reconciliation sweeper.
     */
    @SuppressWarnings("unchecked")
    public static ResourcesPorts.AttendancePort attendance(ConnectorRegistry registry, String connectorId) {
        return (c, tenantId, personId, from, to) -> {
            var conn = registry.require(tenantId, connectorId);
            String key = tenantId + ":" + personId + ":" + from + ":" + to;
            ConnectorResult r = conn.invoke(new ConnectorRequest(tenantId,
                    UUID.nameUUIDFromBytes(("attendance:" + key).getBytes(StandardCharsets.UTF_8)), null, 1, personId,
                    null, "attendance", Map.of("personId", personId, "from", from.toString(), "to", to.toString()),
                    Instant.now().plusSeconds(30)));
            if (!(r instanceof ConnectorResult.Success s)) {
                throw new IllegalStateException("attendance connector " + connectorId + " failed: " + r);
            }
            List<ResourcesPorts.AttendanceRecord> out = new ArrayList<>();
            Object recs = s.output().get("records");
            if (recs instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> mm = (Map<String, Object>) m;
                        out.add(new ResourcesPorts.AttendanceRecord(personId,
                                mm.get("in") == null ? null : Instant.parse(String.valueOf(mm.get("in"))),
                                mm.get("out") == null ? null : Instant.parse(String.valueOf(mm.get("out"))),
                                mm.get("source") == null ? connectorId : String.valueOf(mm.get("source"))));
                    }
                }
            }
            return out;
        };
    }

    static Object jsonSafe(Object v) {
        return RegistryPortBridges.jsonSafe(v);
    }
}
