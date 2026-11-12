// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.sangam.integration.inbox.PoisonInbound;

/**
 * SL-142 / owner decision 61: the replay transport of a parked inbound message. The message of a tenant that left this
 * cell is posted through the gateway ({@code sangam.cell.gateway-url}, which routes by {@code X-Sangam-Tenant} to the
 * tenant's current cell) to {@code POST /api/v1/messages/replay}, received there by {@link CellParkedReplayInboxFilter}
 * into the inbox under the ORIGINAL source, message id, event type and correlation key, so the target inbox
 * deduplicates a repeated replay and a later broker redelivery of the same message alike.
 *
 * <p>Authenticated with the cell's service token (role {@code XCELL}, as cross-cell messages). 2xx (stored or
 * duplicate) is success; anything else throws, so {@link PoisonInbound#replayParked} leaves the item open. A target
 * where the tenant is not ACTIVE answers 503/421 through its {@link CellTenantStateFilter}, which is a failure here.
 */
public final class CellParkedReplayTransport implements PoisonInbound.ParkedReplay {

    public static final String PATH = "/api/v1/messages/replay";

    private final URI gatewayBase;
    private final String serviceToken;
    private final String sourceCell;
    private final HttpClient http;

    public CellParkedReplayTransport(URI gatewayBase, String serviceToken, String sourceCell) {
        this(gatewayBase, serviceToken, sourceCell, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    CellParkedReplayTransport(URI gatewayBase, String serviceToken, String sourceCell, HttpClient http) {
        if (gatewayBase == null) {
            throw new IllegalArgumentException("sangam.cell.gateway-url is required for the parked-message replay");
        }
        this.gatewayBase = gatewayBase;
        this.serviceToken = serviceToken;
        this.sourceCell = sourceCell;
        this.http = http;
    }

    @Override
    public void replay(PoisonInbound.Parked p) throws Exception {
        HttpResponse<String> r = http.send(request(p), HttpResponse.BodyHandlers.ofString());
        int sc = r.statusCode();
        if (sc / 100 != 2) {
            throw new IOException("target cell refused the replay of item " + p.itemId() + " for tenant " + p.tenantId()
                    + ": HTTP " + sc);
        }
    }

    /** The request the target cell's {@link CellParkedReplayInboxFilter} reads. */
    HttpRequest request(PoisonInbound.Parked p) {
        if (p.messageId() == null || p.messageId().isBlank() || p.source() == null || p.source().isBlank()) {
            throw new IllegalStateException("parked item " + p.itemId() + " has no original source or message id");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(gatewayBase.resolve(PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Sangam-Tenant", p.tenantId())
                .header("X-Sangam-Source-Cell", sourceCell)
                .header(CellParkedReplayInboxFilter.SOURCE_HEADER, p.source())
                .header("Idempotency-Key", p.messageId())
                // an empty body is a message without payload (the receiver stores null, as the broker consumer did)
                .POST(p.payloadJson() == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(p.payloadJson()));
        if (p.eventType() != null && !p.eventType().isBlank()) {
            b.header(CellParkedReplayInboxFilter.EVENT_TYPE_HEADER, p.eventType());
        }
        if (p.correlationKey() != null && !p.correlationKey().isBlank()) {
            b.header(CellParkedReplayInboxFilter.CORRELATION_HEADER, p.correlationKey());
        }
        if (serviceToken != null && !serviceToken.isBlank()) {
            b.header("Authorization", "Bearer " + serviceToken);
        }
        return b.build();
    }
}
