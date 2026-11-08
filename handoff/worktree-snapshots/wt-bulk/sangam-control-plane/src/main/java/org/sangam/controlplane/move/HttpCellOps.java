// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.move;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.cell.rollup.MiniJson;
import org.sangam.controlplane.cells.Cell;
import org.sangam.controlplane.cells.CellDirectory;
import org.sangam.controlplane.config.ControlPlaneProperties;
import org.springframework.stereotype.Component;

/**
 * {@link CellOps} over each cell's admin base URL: {@code <adminBaseUrl>/api/v1/admin/cell/tenants/{tenant}/...}
 * (contract: README IR-4). JDK HTTP client, bearer token {@code sangam.controlplane.cell-admin-token}.
 */
@Component
public class HttpCellOps implements CellOps {

    private final CellDirectory cells;
    private final ControlPlaneProperties props;
    private final HttpClient http = HttpClient.newHttpClient();

    public HttpCellOps(CellDirectory cells, ControlPlaneProperties props) {
        this.cells = cells;
        this.props = props;
    }

    @Override
    public void prepareImport(String targetCell, String tenant, String moveId) {
        call(targetCell, "POST", tenant, "/import/prepare", moveId, Map.of("moveId", moveId));
    }

    @Override
    public void freeze(String sourceCell, String tenant, String moveId) {
        call(sourceCell, "POST", tenant, "/freeze", moveId, Map.of("moveId", moveId));
    }

    @Override
    public DrainStatus drainStatus(String sourceCell, String tenant) {
        Map<String, Object> m = call(sourceCell, "GET", tenant, "/drain-status", null, null);
        return new DrainStatus(num(m, "inFlightCommands"), num(m, "pendingOutbox"), num(m, "lockedWork"), num(m, "dueTimers"));
    }

    @Override
    public ExportHandle export(String sourceCell, String tenant, String moveId) {
        Map<String, Object> m = call(sourceCell, "POST", tenant, "/export", moveId, Map.of("moveId", moveId));
        return new ExportHandle(str(m, "url"), str(m, "token"), num(m, "rows"), str(m, "watermark"), str(m, "auditHead"));
    }

    @Override
    public Map<String, Long> importFrom(String targetCell, String tenant, String moveId, ExportHandle h) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("moveId", moveId);
        body.put("url", h.url());
        body.put("token", h.token());
        body.put("watermark", h.watermark());
        Map<String, Object> m = call(targetCell, "POST", tenant, "/import", moveId, body);
        return longs(m.get("rowCounts"));
    }

    @Override
    public TenantChecksum checksum(String cell, String tenant) {
        Map<String, Object> m = call(cell, "GET", tenant, "/checksum", null, null);
        return new TenantChecksum(longs(m.get("rowCounts")), str(m, "contentHash"));
    }

    @Override
    public void activate(String targetCell, String tenant, String moveId, String sourceAuditHead) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("moveId", moveId);
        body.put("sourceAuditHead", sourceAuditHead);
        call(targetCell, "POST", tenant, "/activate", moveId, body);
    }

    @Override
    public void markMoved(String sourceCell, String tenant, String moveId, String targetCell) {
        call(sourceCell, "POST", tenant, "/moved", moveId, Map.of("moveId", moveId, "targetCell", targetCell));
    }

    @Override
    public void unfreeze(String sourceCell, String tenant, String moveId) {
        call(sourceCell, "POST", tenant, "/unfreeze", moveId, Map.of("moveId", moveId));
    }

    @Override
    public void dropImport(String targetCell, String tenant, String moveId) {
        call(targetCell, "POST", tenant, "/import/drop", moveId, Map.of("moveId", moveId));
    }

    @Override
    public void purge(String sourceCell, String tenant, String moveId) {
        call(sourceCell, "POST", tenant, "/purge", moveId, Map.of("moveId", moveId));
    }

    private Map<String, Object> call(String cellId, String method, String tenant, String suffix, String idempotencyKey,
                                     Map<String, Object> body) {
        Cell c = cells.get(cellId);
        String base = c.adminBaseUrl() != null && !c.adminBaseUrl().isBlank() ? c.adminBaseUrl() : c.apiBaseUrl();
        URI uri = URI.create(base.replaceAll("/+$", "") + "/api/v1/admin/cell/tenants/"
                + URLEncoder.encode(tenant, StandardCharsets.UTF_8) + suffix);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(props.cellRequestTimeout());
        if (props.cellAdminToken() != null && !props.cellAdminToken().isBlank()) {
            b.header("Authorization", "Bearer " + props.cellAdminToken());
        }
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", "tenant-move:" + idempotencyKey + ":" + suffix);
        }
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(MiniJson.write(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new CellOpException(cellId + " " + method + " " + suffix + " -> HTTP " + r.statusCode(),
                        r.statusCode() >= 500 || r.statusCode() == 429 || r.statusCode() == 503);
            }
            String s = r.body();
            return s == null || s.isBlank() ? Map.of() : MiniJson.readObject(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CellOpException("interrupted", true);
        }
    }

    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.longValue() : v == null ? 0L : Long.parseLong(v.toString());
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static Map<String, Long> longs(Object o) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v))));
        }
        return out;
    }

    /** A failed cell call; {@code retryable} decides job retry (true) versus BPMN error / rollback (false). */
    public static final class CellOpException extends RuntimeException {
        private final boolean retryable;

        public CellOpException(String m, boolean retryable) {
            super(m);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
