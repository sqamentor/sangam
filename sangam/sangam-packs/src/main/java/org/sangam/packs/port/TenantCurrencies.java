// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.util.function.Function;

/**
 * Owner decision M1: the currency of a tenant's money, from its tenant / jurisdiction profile ({@code locale.currency}).
 * Delegates are instantiated by the engine, so they find the port here (as {@link VerifiedPayees}). The server installs
 * the profile resolver; without it every tenant answers null and the pack's declared {@code money.currency} applies.
 * An answer is an ISO 4217 code or null (the profile names none). Nothing defaults silently: a case whose tenant names
 * no currency and whose pack declares none is held.
 */
public final class TenantCurrencies {

    private static final Function<String, String> NONE = tenant -> null;

    private static volatile Function<String, String> installed = NONE;

    private TenantCurrencies() {
    }

    /** Installs the lookup; null restores the default (no tenant names a currency). */
    public static void install(Function<String, String> lookup) {
        installed = lookup == null ? NONE : lookup;
    }

    /** The currency the tenant's profile names, or null. */
    public static String of(String tenantId) {
        String c = installed.apply(tenantId);
        return c == null || c.isBlank() ? null : c.trim();
    }
}
