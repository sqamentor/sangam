// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.connectorshealth;

import java.util.function.Function;

/**
 * Inbound health-interoperability endpoints (sangam-connectors-health README "Integration notes"), implemented in
 * sangam-server over {@code FhirSubscriptionReceiver} (sink = the integration inbox) and {@code CdsHooksService}.
 */
public interface HealthHooksPort {

    /** Outcome of one FHIR subscription notification: HTTP status, inbox messages written, missed events, kind. */
    record HookResult(int status, int messages, long missedEvents, String kind) {
    }

    /** A CDS Hooks reply: HTTP status and the JSON body to send as is. */
    record CdsReply(int status, String json) {
    }

    /** A received 835: inbox messages written, or the poison item recorded when the file is unreadable. */
    record RemittanceResult(int messages, Long poisonItemId) {
    }

    /** Routes one 835 interchange for the caller's tenant. */
    RemittanceResult remittance835(String tenantId, String principal, String x12);

    /** {@code POST /api/v1/hooks/fhir/{channel}}; {@code header} reads request headers (the channel secret). */
    HookResult fhirNotification(String channel, Function<String, String> header, byte[] body);

    /** {@code GET /cds-services} discovery. */
    CdsReply cdsDiscovery();

    /** {@code POST /cds-services/{id}}; {@code authorizedClient} is the authenticated EHR client. */
    CdsReply cdsCall(String serviceId, byte[] body, String authorizedClient);
}
