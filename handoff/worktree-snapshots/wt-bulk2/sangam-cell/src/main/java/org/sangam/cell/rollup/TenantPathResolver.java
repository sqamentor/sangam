// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.rollup;

import java.util.Optional;

/**
 * Port: tenant code to hierarchy path ({@code IN/RJ/RJ-JPR/RJ-JPR-H0042}). Implementations: the cached control-plane
 * lookup {@code ControlPlaneClient::tenantPath}, or a static map.
 */
@FunctionalInterface
public interface TenantPathResolver {
    Optional<String> pathOf(String tenantCode);
}
