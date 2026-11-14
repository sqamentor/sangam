// SPDX-License-Identifier: Apache-2.0
package org.sangam.operator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The validated {@code spec} of a {@code SangamCell} (crds/sangamcells.yaml): turns the {@code SangamInstallation} it
 * names into a SCALE cell. The keys are the ones the Helm chart renders for {@code scale.cell}
 * (deploy/helm/sangam/templates/server-configmap.yaml), with no product default for the jurisdiction (SL-104). The cell
 * id itself is validated by the server at start-up ({@code org.sangam.cell.id.CellId}); the operator only keeps it
 * immutable once bound ({@code status.cellId}), because cell ids are never changed or reused.
 */
public record CellSpec(String namespace, String name, String uid, long generation, String installationRef, String cellId,
                       String region, String jurisdiction, String residency, String publicUrl, String adminUrl,
                       long capacityStartsPerSec, long capacityTenants, String controlPlaneUrl, String rollupIngestUrl,
                       String gatewayUrl, String existingSecret) {

    /** Validates the custom resource; the jurisdiction, URLs and token Secret are required. */
    public static CellSpec parse(JsonNode cr) {
        JsonNode meta = cr.path("metadata");
        JsonNode s = cr.path("spec");
        String jurisdiction = required(s, "jurisdiction");
        String publicUrl = url(s, "publicUrl", true);
        JsonNode cap = s.path("capacity");
        long starts = cap.path("startsPerSec").asLong(500);
        long tenants = cap.path("tenants").asLong(2000);
        if (starts < 1 || tenants < 1) {
            throw new InstallationSpec.InvalidSpec("spec.capacity.startsPerSec and spec.capacity.tenants must be positive");
        }
        String residency = s.path("residency").asText("");
        String adminUrl = url(s, "adminUrl", false);
        return new CellSpec(meta.path("namespace").asText(""), meta.path("name").asText(""), meta.path("uid").asText(""),
                meta.path("generation").asLong(0), required(s, "installationRef"), required(s, "cellId"),
                s.path("region").asText(""), jurisdiction, residency.isBlank() ? jurisdiction : residency, publicUrl,
                adminUrl.isBlank() ? publicUrl : adminUrl, starts, tenants, url(s, "controlPlaneUrl", true),
                url(s, "rollupIngestUrl", true), url(s, "gatewayUrl", true), required(s, "existingSecret"));
    }

    /** The cell's environment, rendered into the installation's {@code <name>-cell} ConfigMap. */
    public Map<String, String> environment() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("SANGAM_CELL_ID", cellId);
        m.put("SANGAM_CELL_REGION", region);
        m.put("SANGAM_CELL_JURISDICTION", jurisdiction);
        m.put("SANGAM_CELL_RESIDENCY", residency);
        m.put("SANGAM_CELL_PUBLIC_URL", publicUrl);
        m.put("SANGAM_CELL_ADMIN_URL", adminUrl);
        m.put("SANGAM_CELL_CAPACITY_STARTS", Long.toString(capacityStartsPerSec));
        m.put("SANGAM_CELL_CAPACITY_TENANTS", Long.toString(capacityTenants));
        m.put("SANGAM_CONTROL_PLANE_URL", controlPlaneUrl);
        m.put("SANGAM_ROLLUP_INGEST_URL", rollupIngestUrl);
        m.put("SANGAM_GATEWAY_URL", gatewayUrl);
        return m;
    }

    private static String required(JsonNode s, String field) {
        String v = s.path(field).asText("");
        if (v.isBlank()) {
            throw new InstallationSpec.InvalidSpec("spec." + field + " is required");
        }
        return v.trim();
    }

    private static String url(JsonNode s, String field, boolean required) {
        String v = required ? required(s, field) : s.path(field).asText("").trim();
        if (!v.isEmpty() && !v.startsWith("https://") && !v.startsWith("http://")) {
            throw new InstallationSpec.InvalidSpec("spec." + field + " must be an http(s) URL");
        }
        return v;
    }
}
