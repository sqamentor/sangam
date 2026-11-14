// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.util.Locale;

/**
 * How traffic between the server and a sidecar (PDF validator, OCR) is protected (owner decision 153): {@link #TLS}
 * (the default) means the sidecar serves HTTPS itself and the client accepts only {@code https} URLs; {@link #MESH}
 * means an authenticated service mesh carries the traffic and plain {@code http} inside the pod is allowed. MESH must be
 * chosen explicitly; there is no silent fallback to plain HTTP. The bearer token is required in both modes.
 */
public enum SidecarTransport {
    TLS, MESH;

    /** {@code null} or blank is {@link #TLS}; otherwise {@code tls} or {@code mesh}, any case. */
    public static SidecarTransport parse(String value) {
        if (value == null || value.isBlank()) {
            return TLS;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "tls" -> TLS;
            case "mesh" -> MESH;
            default -> throw new IllegalArgumentException("transport must be tls or mesh");
        };
    }
}
