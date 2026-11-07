// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import org.sangam.integration.inbox.InboxService;

/**
 * Connects telemetry to the inbox: rows of source {@code telemetry:*} go to the rule handler. Alarm events leave through
 * the ports {@code InboundEventProcessor}, whose event definitions decide the process start or message.
 */
public final class TelemetryWiring {

    private TelemetryWiring() {
    }

    public static void wire(InboxService inbox, TelemetryRuleHandler handler) {
        inbox.registerSourceHandler(TelemetryRuleHandler.SOURCE_PREFIX, handler);
    }
}
