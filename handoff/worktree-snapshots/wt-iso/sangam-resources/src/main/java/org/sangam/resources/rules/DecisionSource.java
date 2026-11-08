// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.rules;

import org.sangam.core.layer.Hashes;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where DMN tables come from. Decisions are domain data (entitlement, priority, tariff, cancellation-refund, queue
 * priority and routing, roster constraints), deployed by packs or tenant configuration. The integrator can back this by
 * the Operaton repository (deployed DMN resources) or by sangam-config profile documents.
 */
public interface DecisionSource {

    /** A versioned DMN table: XML, the decision id inside it and a content hash (cache key and audit evidence). */
    record Decision(String key, String decisionId, String dmnXml, String hash) {
    }

    Optional<Decision> find(String tenantId, String key);

    /** In-memory source; tenant-specific tables override the shared ({@code *}) ones. */
    final class InMemory implements DecisionSource {
        private final Map<String, Decision> byKey = new ConcurrentHashMap<>();

        public InMemory put(String tenantId, String key, String decisionId, String dmnXml) {
            String hash = hexHash(dmnXml);
            byKey.put(tenantId + "|" + key, new Decision(key, decisionId, dmnXml, hash));
            return this;
        }

        @Override
        public Optional<Decision> find(String tenantId, String key) {
            Decision d = byKey.get(tenantId + "|" + key);
            return d != null ? Optional.of(d) : Optional.ofNullable(byKey.get("*|" + key));
        }

        private static String hexHash(String s) {
            byte[] h = Hashes.sha256(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        }
    }
}
