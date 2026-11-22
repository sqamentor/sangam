// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import jakarta.servlet.Filter;

import org.sangam.server.security.PrincipalAttributeFilter;

/**
 * Filters sangam-security adds to the server's single {@code SecurityFilterChain} ({@code SecurityConfig}). Held in
 * this plain object, not as {@code Filter} beans, so that Spring Boot does not also register them as servlet filters.
 *
 * @param apiTokenFilter  authenticates {@code sgm_} API tokens (service principals, R26); runs before bearer/JWT auth
 * @param identityFilter  resolves the caller against the organisation model and binds {@code TenantContext}
 * @param seatRefusal     register E-32(g): writes the {@code AUTH_DENIED} of a seat the server's
 *                        {@code PrincipalAttributeFilter} refuses by the token's seats claim (it runs before
 *                        {@code identityFilter}); null: not recorded
 */
public record SecurityChainContributions(Filter apiTokenFilter, Filter identityFilter,
                                         PrincipalAttributeFilter.SeatRefusal seatRefusal) {

    public SecurityChainContributions(Filter apiTokenFilter, Filter identityFilter) {
        this(apiTokenFilter, identityFilter, null);
    }
}
