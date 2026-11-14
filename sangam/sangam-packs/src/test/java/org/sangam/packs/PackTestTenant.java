// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

/**
 * Decision 145: pack work is tenant-owned and runs only under an engine tenant. Test engines therefore deploy and
 * start packs under this tenant, as {@code PacksTenantDeployer} deploys them per tenant in production; an untenanted
 * pack instance fails closed ({@code PackTenantSourceTest}).
 */
public final class PackTestTenant {

    /** The tenant of the pack test deployments. */
    public static final String ID = "t-pack-test";

    private PackTestTenant() {
    }
}
