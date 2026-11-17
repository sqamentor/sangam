// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.time.LocalDate;

/**
 * The installed {@link VerifiedPayeeLookup} ([CONTRACT] SANGAM-PAYEE-VERIFIED v2). Delegates are instantiated by the
 * engine, so they find the port here (as {@link ProfileBindings}). The integrator installs the IFMS adapter
 * (mdm.payee). Until then the default is the simulator (ADR-D-13: the packs run without any external system) outside
 * production, and in production a lookup that answers PAYEE_NOT_VERIFIED for everyone, so nothing is ever paid to an
 * unverified beneficiary (fail closed).
 */
public final class VerifiedPayees {

    /** The simulator's answer: a deterministic verified version per key ({@code SIM-PV-<key>}). */
    public static final VerifiedPayeeLookup SIMULATOR = (tenant, employeeId, payeeId, asOf) -> {
        String key = employeeId != null && !employeeId.isBlank() ? employeeId : payeeId;
        if (key == null || key.isBlank()) {
            return new VerifiedPayeeLookup.NotVerified(VerifiedPayeeLookup.Reason.ONBOARDING_INCOMPLETE,
                    "no employeeId or payeeId");
        }
        return new VerifiedPayeeLookup.Verified("SIM-PV-" + key, "Simulated payee " + key, "000123456789",
                "SIMB0000001");
    };

    /** Production default without an adapter: nobody is verified. */
    public static final VerifiedPayeeLookup NONE = (tenant, employeeId, payeeId, asOf) ->
            new VerifiedPayeeLookup.NotVerified(VerifiedPayeeLookup.Reason.PAYEE_NOT_VERIFIED,
                    "no verified-payee adapter is installed");

    private static volatile VerifiedPayeeLookup installed;

    private VerifiedPayees() {
    }

    /** Installs the adapter; null restores the default. */
    public static void install(VerifiedPayeeLookup lookup) {
        installed = lookup;
    }

    /** The installed adapter, else the simulator outside production and {@link #NONE} in production. */
    public static VerifiedPayeeLookup lookup() {
        VerifiedPayeeLookup l = installed;
        if (l != null) {
            return l;
        }
        return PackRuntime.production() ? NONE : SIMULATOR;
    }

    /** Convenience: {@code lookup().resolve(...)}. */
    public static VerifiedPayeeLookup.Result resolve(String tenantId, String employeeId, String payeeId,
                                                     LocalDate asOf) {
        return lookup().resolve(tenantId, employeeId, payeeId, asOf);
    }
}
