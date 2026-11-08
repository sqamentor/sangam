// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import jakarta.servlet.Filter;

/**
 * Filters sangam-security adds to the server's single {@code SecurityFilterChain} ({@code SecurityConfig}). Held in
 * this plain object, not as {@code Filter} beans, so that Spring Boot does not also register them as servlet filters.
 *
 * @param apiTokenFilter  authenticates {@code sgm_} API tokens (service principals, R26); runs before bearer/JWT auth
 * @param identityFilter  resolves the caller against the organisation model and binds {@code TenantContext}
 */
public record SecurityChainContributions(Filter apiTokenFilter, Filter identityFilter) {
}
