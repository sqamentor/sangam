// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.support.Json;

/**
 * Decision 189 deploy rules: inbound Event Grid is a push channel that cannot deploy without a shared secret, and
 * Service Bus sends CloudEvents binary mode only when the channel opts in. Both stay off unless configured.
 */
class ChannelAdaptersAzureTest {

    private static ChannelDefinition ch(String json) {
        return Json.read(json, ChannelDefinition.class);
    }

    private static String eventGridIn(String props) {
        return "{\"key\":\"eg\",\"channelType\":\"eventgrid\",\"type\":\"inbound\",\"deserializerType\":\"cloudevents\","
                + "\"properties\":{" + props + "}}";
    }

    private static String serviceBusOut(String extra) {
        return "{\"key\":\"sb\",\"channelType\":\"servicebus\",\"type\":\"outbound\",\"serializerType\":\"cloudevents\","
                + "\"properties\":{\"cloudEventsMode\":\"binary\",\"namespace\":\"n\",\"entityPath\":\"q\","
                + "\"sasKeyName\":\"k\",\"sasKeyRef\":\"r\"" + extra + "}}";
    }

    @Test
    void inboundEventGridNeedsASecretInExactlyOnePlace() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(eventGridIn(""))))
                .getMessage().contains("authSecretRef"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(eventGridIn(
                "\"authSecretRef\":\"secret://eg\"")))).getMessage().contains("exactly one"));
        assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(eventGridIn(
                "\"authSecretRef\":\"secret://eg\",\"authHeader\":\"X-Key\",\"authQueryParam\":\"code\""))));
        assertDoesNotThrow(() -> ChannelAdapters.validate(ch(eventGridIn(
                "\"authSecretRef\":\"secret://eg\",\"authHeader\":\"X-Key\""))));
        assertDoesNotThrow(() -> ChannelAdapters.validate(ch(eventGridIn(
                "\"authSecretRef\":\"secret://eg\",\"authQueryParam\":\"code\""))));
    }

    @Test
    void entraModeNeedsATenantGuidAndAnAudienceAndNoSharedSecret() {
        String tenant = "\"authMode\":\"entra\",\"entraTenantId\":\"00000000-0000-4000-8000-000000000001\"";
        assertDoesNotThrow(() -> ChannelAdapters.validate(ch(eventGridIn(tenant + ",\"entraAudience\":\"api://sangam-hook\""))));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(eventGridIn(tenant))))
                .getMessage().contains("entraAudience"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(eventGridIn(
                "\"authMode\":\"entra\",\"entraTenantId\":\"common\",\"entraAudience\":\"a\""))))
                .getMessage().contains("GUID"), "a multi-tenant alias is not a configured tenant");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(eventGridIn(tenant
                + ",\"entraAudience\":\"a\",\"authSecretRef\":\"secret://eg\",\"authHeader\":\"X-Key\""))))
                .getMessage().contains("not both"));
        assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(eventGridIn(tenant
                + ",\"entraAudience\":\"a\",\"entraAuthority\":\"http://login.example\""))), "https only");
        assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(eventGridIn(tenant
                + ",\"entraAudience\":\"a\",\"entraRequiredRole\":\" \""))), "the role check cannot be switched off");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(eventGridIn(
                "\"authMode\":\"none\",\"authSecretRef\":\"secret://eg\",\"authHeader\":\"X-Key\""))))
                .getMessage().contains("authMode"), "no unauthenticated mode");
        assertDoesNotThrow(() -> ChannelAdapters.validate(ch(eventGridIn(
                "\"authMode\":\"sharedSecret\",\"authSecretRef\":\"secret://eg\",\"authHeader\":\"X-Key\""))));
    }

    @Test
    void inboundEventGridIsCloudEventsOnly() {
        String plain = "{\"key\":\"eg\",\"channelType\":\"eventgrid\",\"type\":\"inbound\","
                + "\"properties\":{\"authSecretRef\":\"secret://eg\",\"authHeader\":\"X-Key\"}}";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(plain)))
                .getMessage().contains("CloudEvents only"));
    }

    @Test
    void inboundEventGridHasNoConsumer() {
        ChannelAdapters.Environment env = new ChannelAdapters.Environment() {
            @Override
            public Optional<String> secret(String ref) {
                return Optional.empty();
            }

            @Override
            public Set<String> egressHosts() {
                return Set.of();
            }

            @Override
            public List<ChannelAdapters.Provider> providers() {
                return List.of(new ChannelAdapters.Provider() {
                    @Override
                    public Set<String> types() {
                        return Set.of("eventgrid");
                    }

                    @Override
                    public ChannelAdapters.OutboundChannelAdapter outbound(ChannelDefinition c, ChannelAdapters.Environment e) {
                        throw new AssertionError("not used");
                    }

                    @Override
                    public ChannelAdapters.InboundChannelAdapter inbound(ChannelDefinition c, String t,
                                                                         InboundEventProcessor p, ChannelAdapters.Environment e) {
                        throw new AssertionError("a push channel has no consumer");
                    }
                });
            }

            @Override
            public boolean egressAllowed(URI uri) {
                return false;
            }
        };
        assertNull(ChannelAdapters.inbound(ch(eventGridIn("\"authSecretRef\":\"s\",\"authHeader\":\"X-Key\"")), "T1",
                null, env));
    }

    @Test
    void serviceBusBinaryNeedsTheOptIn() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(serviceBusOut(""))))
                .getMessage().contains("binary"));
        ChannelDefinition optedIn = ch(serviceBusOut(",\"binaryMode\":\"customProperties\""));
        assertDoesNotThrow(() -> ChannelAdapters.validate(optedIn));
        assertTrue(ChannelAdapters.serviceBusBinary(optedIn));
        assertFalse(ChannelAdapters.serviceBusBinary(ch(serviceBusOut(""))));
        assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(serviceBusOut(
                ",\"binaryMode\":\"headers\""))));
        assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(
                "{\"key\":\"k\",\"channelType\":\"kafka\",\"type\":\"outbound\",\"topics\":[\"t\"],"
                        + "\"properties\":{\"binaryMode\":\"customProperties\"}}")), "the opt-in is Service Bus only");
    }

    @Test
    void otherPushAndOutboundOnlyRulesAreUnchanged() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(
                "{\"key\":\"s\",\"channelType\":\"eventbridge\",\"type\":\"inbound\",\"properties\":{}}")))
                .getMessage().contains("outbound only"));
        assertTrue(ChannelAdapters.PUSH_INBOUND.contains("webhook"));
        assertFalse(ChannelAdapters.OUTBOUND_ONLY.contains("eventgrid"));
    }
}
