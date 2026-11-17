// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.sangam.cell.rollup.CrossCellPublisher;
import org.sangam.cell.rollup.MiniJson;
import org.sangam.cell.rollup.RollupEnvelope;
import org.sangam.cell.rollup.RollupPublisher;
import org.sangam.core.layer.Outbox;
import org.sangam.integration.publish.CloudEvents;
import org.sangam.integration.publish.WebhookSignature;
import org.sangam.packs.core.CaseState;
import org.sangam.ports.events.OutboundEventService;
import org.sangam.ports.events.pipeline.OutboundEventSerializer;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.server.config.WebhooksWiring;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * M-050: {@code sangam-rest/api/sangam-events.asyncapi.yaml} (generated from {@code api/events/*.py}) cannot drift from
 * the classes that put events on the wire: the webhook CloudEvents headers, the roll-up envelope and its type
 * allow-list, the event-registry envelope, the destination prefixes, and the audit event names the code writes.
 */
class EventContractTest {

    private static final Path CONTRACT = Path.of("..", "sangam-rest", "api", "sangam-events.asyncapi.yaml");
    private static final Outbox.OutboxEvent EVENT = new Outbox.OutboxEvent(1L, UUID.fromString(
            "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"), "T1", "root-1", 7L, "sangam.history.process.start", "{\"a\":1}");

    @Test
    void webhookHeadersAreTheCloudEventsAttributesPlusIdempotencyAndSignature() throws IOException {
        Map<String, Object> headers = schema("WebhookHeaders");
        Set<String> ce = new TreeSet<>();
        for (String k : CloudEvents.attributes("urn:sangam:x", WebhooksWiring.PREFIX + "a", EVENT).keySet()) {
            ce.add("ce-" + k);
        }
        Set<String> contract = new TreeSet<>(properties(headers).keySet());
        Set<String> contractCe = new TreeSet<>(contract.stream().filter(h -> h.startsWith("ce-")).toList());
        assertEquals(ce, contractCe, "ce- headers (CloudEvents.attributes)");
        assertTrue(contract.contains(WebhookSignature.HEADER), "signature header " + WebhookSignature.HEADER);
        assertTrue(contract.contains("Idempotency-Key"));
        assertEquals("webhook:{name}", channel("webhook").get("address"));
        assertTrue(((String) channel("webhook").get("address")).startsWith(WebhooksWiring.PREFIX));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollupEnvelopeAndTypeAllowListMatch() throws IOException {
        RollupEnvelope env = RollupEnvelope.of("c1", "IN/RJ", EVENT, "2026-11-12T00:00:00Z", Map.of("eventType", "x"), "op-1");
        Map<String, Object> json = MiniJson.readObject(env.toJson());
        assertEquals(new TreeSet<>(json.keySet()), new TreeSet<>(properties(schema("RollupCloudEvent")).keySet()),
                "RollupEnvelope.toJson members");
        Map<String, Object> message = message("RollupEvent");
        assertEquals(new TreeSet<>(RollupPublisher.DEFAULT_EVENT_TYPES),
                new TreeSet<>((List<String>) message.get("x-sangam-event-types")), "RollupPublisher.DEFAULT_EVENT_TYPES");
        String data = (String) ((Map<String, Object>) properties(schema("RollupCloudEvent")).get("data")).get("description");
        for (String f : RollupPublisher.DEFAULT_FIELDS) {
            assertTrue(data.contains(f), "allow-listed data field " + f + " named in the contract");
        }
        assertEquals(RollupPublisher.DESTINATION, channel("rollupNational").get("address"));
    }

    @Test
    void channelEnvelopeAndPrefixesMatch() throws IOException {
        Map<String, Object> json = MiniJson.readObject(OutboundEventSerializer.JSON.serialize("k", "T1", "m1", Map.of("x", 1)));
        assertEquals(new TreeSet<>(json.keySet()), new TreeSet<>(properties(schema("ChannelEventEnvelope")).keySet()),
                "OutboundEventSerializer.JSON members");
        assertTrue(((String) channel("channel").get("address")).startsWith(OutboundEventService.DESTINATION_PREFIX));
        assertEquals("^" + OutboundEventService.EVENT_TYPE_PREFIX.replace(".", "\\.") + ".+$",
                message("ChannelEvent").get("x-sangam-event-type-pattern"));
        assertTrue(((String) channel("crossCell").get("address")).startsWith(CrossCellPublisher.PREFIX));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyAuditNameTheCodeWritesIsPublishedOrPending() throws IOException {
        Map<String, Object> names = schema("AuditEventName");
        Set<String> catalogue = new TreeSet<>((List<String>) names.get("enum"));
        Map<String, List<String>> pending = (Map<String, List<String>>) names.get("x-sangam-pending-change-control");

        Map<String, List<String>> written = new LinkedHashMap<>();
        List<String> security = new ArrayList<>();
        for (SecurityEventType t : SecurityEventType.values()) {
            security.add(t.name());
        }
        written.put("SecurityEventType", security);
        List<String> caseStates = new ArrayList<>();
        for (CaseState s : CaseState.values()) {
            caseStates.add(s.auditEvent());
        }
        written.put("CaseState", caseStates);
        // decision 380: the hosted catalogue's review events and the connector-template install event
        written.put("CatalogueHostController", org.sangam.rest.modules.modeller.CatalogueHostController.AUDIT_EVENTS);
        written.put("ExtensionCatalogueController",
                List.of(org.sangam.rest.modules.modeller.ExtensionCatalogueController.TEMPLATES_INSTALLED));

        for (var e : written.entrySet()) {
            Set<String> notPublished = new TreeSet<>(e.getValue());
            notPublished.removeAll(catalogue);
            assertEquals(notPublished, new TreeSet<>(pending.getOrDefault(e.getKey(), List.of())),
                    e.getKey() + ": names outside X.4.0.3 must be listed as pending change control, and only those");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> doc;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) throws IOException {
        return (Map<String, Object>) ((Map<String, Object>) components().get("schemas")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> message(String name) throws IOException {
        return (Map<String, Object>) ((Map<String, Object>) components().get("messages")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> channel(String name) throws IOException {
        return (Map<String, Object>) ((Map<String, Object>) read().get("channels")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> components() throws IOException {
        return (Map<String, Object>) read().get("components");
    }

    private static synchronized Map<String, Object> read() throws IOException {
        if (doc == null) {
            LoaderOptions o = new LoaderOptions();
            o.setCodePointLimit(16 * 1024 * 1024);
            try (InputStream in = Files.newInputStream(CONTRACT)) {
                doc = new Yaml(new SafeConstructor(o)).load(in);
            }
        }
        return doc;
    }

    @AfterAll
    static void releaseStatics() {
        // R9-statics: a test class stays loaded for the whole run; its statics must not pin what they held
        doc = null;
    }
}
