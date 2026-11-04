// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Map;
import java.util.function.Function;

import org.sangam.connectors.health.cdshooks.CdsHooksService;
import org.sangam.connectors.health.fhir.FhirSubscriptionReceiver;
import org.sangam.rest.modules.connectorshealth.HealthHooksPort;
import org.sangam.rest.port.ApiProblem;

/**
 * {@link HealthHooksPort} over the health module: FHIR subscription channels (one {@link FhirSubscriptionReceiver}
 * per configured channel, sink = the integration inbox) and the {@link CdsHooksService} registry.
 */
public final class HealthHooksBridge implements HealthHooksPort {

    private final Map<String, FhirSubscriptionReceiver> channels;
    private final CdsHooksService cds;

    public HealthHooksBridge(Map<String, FhirSubscriptionReceiver> channels, CdsHooksService cds) {
        this.channels = Map.copyOf(channels);
        this.cds = cds;
    }

    @Override
    public HookResult fhirNotification(String channel, Function<String, String> header, byte[] body) {
        FhirSubscriptionReceiver r = channels.get(channel);
        if (r == null) {
            throw ApiProblem.notFound("FHIR subscription channel", channel);
        }
        FhirSubscriptionReceiver.Result res = r.receive(header, body);
        return new HookResult(res.status(), res.messages() == null ? 0 : res.messages().size(), res.missedEvents(),
                res.kind());
    }

    @Override
    public CdsReply cdsDiscovery() {
        CdsHooksService.Reply r = cds.discovery();
        return new CdsReply(r.status(), r.json());
    }

    @Override
    public CdsReply cdsCall(String serviceId, byte[] body, String authorizedClient) {
        CdsHooksService.Reply r = cds.handle(serviceId, body, authorizedClient);
        return new CdsReply(r.status(), r.json());
    }
}
