// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.web;

import jakarta.servlet.http.HttpServletRequest;
import org.sangam.field.rest.FieldCaller;

/**
 * Resolves the authenticated caller of a request. The integrator binds it to sangam-rest's request context (OIDC
 * token: tenant claim, principal, groups, X-Sangam-Seat). It must never trust tenant or principal from the body.
 */
@FunctionalInterface
public interface CallerResolver {

    FieldCaller resolve(HttpServletRequest request);
}
