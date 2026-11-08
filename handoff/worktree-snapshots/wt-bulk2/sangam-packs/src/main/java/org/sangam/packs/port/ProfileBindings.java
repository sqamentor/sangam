// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.util.Map;

/**
 * Configuration-profile binding of a case (ADR-D-20, GP-19 semantics): at start every case records the version of the
 * tenant's configuration chain in force ({@code profileVersion}, e.g.
 * {@code INTL@2026.11.0>REGIME-...>IN@2026.11.0>...>RJ.PWD@3#a1b2c3d4e5f6}), so a running case keeps the rules it started
 * with. The default asks the {@code config.profile} connector (operation {@code profile.binding}, read-only) through the
 * installed {@link ConnectorGateway}; the integrator may install a direct binder instead. A tenant without a profile, or a
 * connector that is not bound, yields {@code null} and the case simply has no profile binding.
 */
public final class ProfileBindings {

    /** Gives the binding string for a tenant, or null. */
    @FunctionalInterface
    public interface Binder {
        String bind(String tenantId, String packId, String businessKey);
    }

    private static volatile Binder binder = ProfileBindings::viaGateway;

    private ProfileBindings() {
    }

    public static void install(Binder b) {
        binder = b == null ? ProfileBindings::viaGateway : b;
    }

    public static String bind(String tenantId, String packId, String businessKey) {
        try {
            return binder.bind(tenantId, packId, businessKey);
        } catch (RuntimeException e) {
            return null; // never fail a case start because configuration is not wired
        }
    }

    private static String viaGateway(String tenantId, String packId, String businessKey) {
        if (tenantId == null) {
            return null;
        }
        ConnectorResult r = PackRuntime.gateway().query(new ConnectorRequest(tenantId, "config.profile", "profile.binding",
                null, businessKey, null, Map.of("packId", packId == null ? "" : packId), null));
        if (r == null || r.status() != OperationStatus.COMPLETED) {
            return null;
        }
        Object b = r.data().get("binding");
        return b == null ? null : b.toString();
    }
}
