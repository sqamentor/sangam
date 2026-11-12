// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import org.sangam.rest.web.ProblemHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problems for the server's own controllers ({@code OnboardingController}, {@code PluginAdminController},
 * {@code SecurityDefaultsController}, ...). {@link ProblemHandler} is scoped to {@code org.sangam.rest.web}; without
 * this advice an {@code ApiProblem} thrown here (a 409 stale hash, a 422 maker-is-checker) left the controller
 * unhandled, the container forwarded to {@code /error}, and that second dispatch was answered 401 UNAUTHENTICATED
 * instead of the real problem (PlugAndPlayIT go-live, gap register).
 */
@RestControllerAdvice(basePackages = "org.sangam.server.web")
public class ServerWebProblemHandler extends ProblemHandler {
}
