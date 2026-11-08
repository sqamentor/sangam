// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import org.sangam.rest.web.ProblemHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * problem+json for the Flowable worker compatibility endpoint (FWT-D11). sangam-rest's {@link ProblemHandler} is scoped
 * to {@code org.sangam.rest.web}, so before this advice every exception of {@link FlowableWorkerCompatController} (409
 * {@code FENCING_TOKEN_REQUIRED} / {@code STALE_LEASE}, 410, 422 {@code INVALID_INPUT} / {@code RESERVED_VARIABLE},
 * unreadable bodies, unexpected errors) escaped to the servlet {@code /error} dispatch, which the security chain
 * denied, so clients saw 403 {@code ACTION_NOT_PERMITTED}. The advice inherits every handler of {@link ProblemHandler},
 * so the endpoint answers exactly as the native worker API {@code /api/v1/work} does (same codes, same stale-lease
 * metric), with no second mapping to keep in step.
 */
@RestControllerAdvice(assignableTypes = FlowableWorkerCompatController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FlowableWorkerCompatProblemAdvice extends ProblemHandler {
}
