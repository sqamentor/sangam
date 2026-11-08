// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.rollup;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;

/**
 * Cross-cell business messages (state to central and back, SCALE-ARCHITECTURE.md section 8.3). A cell writes an
 * outbox event to destination {@code xcell:<targetTenant>} in the same transaction as its own state change; this
 * publisher posts it through the gateway (which routes by tenant) to the target cell's message endpoint with
 * {@code Idempotency-Key = operationId}. The target cell's inbox (R38, PK tenant+source+message_id) and R41 idempotency
 * make the receive exactly-once; the relay's at-least-once publish plus that dedup gives effectively-once end to end.
 * There is never a cross-cell transaction.
 *
 * <p>Payload contract: {@code {"operationId": "...", "messageName": "...", "businessKey": "...", "variables": {...}}}.
 */
public final class CrossCellPublisher implements Publisher {

    public static final String PREFIX = "xcell:";

    private final URI gatewayBase;
    private final String serviceToken;
    private final String sourceCell;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public CrossCellPublisher(URI gatewayBase, String serviceToken, String sourceCell) {
        this.gatewayBase = gatewayBase;
        this.serviceToken = serviceToken;
        this.sourceCell = sourceCell;
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        if (!destination.startsWith(PREFIX)) {
            throw new RollupPublisher.PoisonEventException("not a cross-cell destination: " + destination);
        }
        String targetTenant = destination.substring(PREFIX.length());
        Map<String, Object> p = MiniJson.readObject(event.payload());
        Object op = p.get("operationId");
        if (op == null || op.toString().isBlank()) {
            throw new RollupPublisher.PoisonEventException("cross-cell message without operationId: " + event.eventId());
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(gatewayBase.resolve("/api/v1/messages"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", op.toString())
                .header("X-Sangam-Tenant", targetTenant)
                .header("X-Sangam-Source-Cell", sourceCell)
                .header("X-Sangam-Source-Tenant", URLEncoder.encode(event.tenantId(), StandardCharsets.UTF_8))
                .header("X-Request-Id", event.eventId().toString())
                .POST(HttpRequest.BodyPublishers.ofString(event.payload()));
        if (serviceToken != null && !serviceToken.isBlank()) {
            b.header("Authorization", "Bearer " + serviceToken);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        int sc = r.statusCode();
        if (sc / 100 == 2 || sc == 409) {
            // 409 = duplicate operationId already processed (idempotent replay) -> done
            return;
        }
        if (sc >= 400 && sc < 500 && sc != 408 && sc != 429) {
            throw new RollupPublisher.PoisonEventException("target rejected cross-cell message " + event.eventId()
                    + ": HTTP " + sc);
        }
        throw new IOException("target cell unavailable for " + targetTenant + ": HTTP " + sc);
    }
}
