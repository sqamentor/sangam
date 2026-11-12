// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * W3C Trace Context of the request that created a work item (sangam-api.yaml TraceContext; ExternalWork.traceContext,
 * M-062, R13): the engine's stored JOB_TRACE_CONTEXT row. {@code traceparent} is required in the schema,
 * {@code tracestate} optional. A worker links its own span to it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TraceContext(String traceparent, String tracestate) {
}
