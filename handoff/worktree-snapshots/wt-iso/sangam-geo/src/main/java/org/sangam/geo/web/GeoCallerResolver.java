// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.web;

import jakarta.servlet.http.HttpServletRequest;
import org.sangam.geo.rest.GeoCaller;

/**
 * Resolves the authenticated caller of a request. The integrator binds it to sangam-rest's request context (OIDC
 * token: tenant claim, principal, groups). Tenant and principal are never taken from the request body.
 */
@FunctionalInterface
public interface GeoCallerResolver {

    GeoCaller resolve(HttpServletRequest request);
}
