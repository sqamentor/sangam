// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.TenantKey;

/**
 * Owner decisions 387 (4) (a) and 390 (4): the one-time bootstrap of the first environment of an installation
 * ({@value KeyEnvironmentSeparation#BOOTSTRAP}), persisted as a system audit event once the database is ready. The event
 * belongs to no tenant ({@link TenantKey#SHARED}) and sits on the chain {@value #CHAIN}; its payload names the
 * environment and the number of its own fingerprints, never a key. The event is appended first, then the chain is
 * counted in the same transaction: the append locks the chain head until commit, so of two starts at once the second
 * sees both events. A second bootstrap of the same environment (or an erased payload, which could be one) is refused
 * with {@link KeyEnvironmentSeparation#LIST_REQUIRED} and its event rolls back with the start.
 */
public final class KeyEnvironmentBootstrap {

    public static final String EVENT = "KEY_ENVIRONMENT_BOOTSTRAPPED";
    public static final String CHAIN = "keys:bootstrap";
    private static final ObjectMapper JSON = new ObjectMapper();

    private KeyEnvironmentBootstrap() {
    }

    /** Records this start's bootstrap in the caller's transaction, or refuses when the environment bootstrapped before. */
    public static void record(Connection c, AuditChain chain, String environment, int ownFingerprints) {
        if (environment == null || environment.isBlank()) {
            throw new IllegalStateException(KeyEnvironmentSeparation.LIST_REQUIRED + ": a bootstrap names its environment");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("environment", environment);
        payload.put("own_fingerprints", ownFingerprints);
        chain.append(c, TenantKey.SHARED, CHAIN, EVENT, payload);
        long n = bootstraps(c, environment);
        if (n > 1) {
            throw new IllegalStateException(KeyEnvironmentSeparation.LIST_REQUIRED + " (owner decisions 387 (4) and 390):"
                    + " environment " + environment + " already started once with " + KeyEnvironmentSeparation.BOOTSTRAP
                    + "; the bootstrap is one-time. Set " + KeyEnvironmentSeparation.FOREIGN + " or "
                    + KeyEnvironmentSeparation.ATTESTATION + " and remove " + KeyEnvironmentSeparation.BOOTSTRAP);
        }
    }

    /** The bootstrap events of {@code environment}; an erased or unreadable payload counts (fail closed). */
    static long bootstraps(Connection c, String environment) {
        long n = 0;
        for (String payload : AuditChain.payloads(c, TenantKey.SHARED, CHAIN, EVENT)) {
            if (payload == null) {
                n++;
                continue;
            }
            try {
                Object env = JSON.readValue(payload, Map.class).get("environment");
                if (environment.equals(env)) {
                    n++;
                }
            } catch (Exception e) {
                n++;
            }
        }
        return n;
    }
}
