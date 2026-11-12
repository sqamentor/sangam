// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.http;

import java.util.regex.Pattern;

/**
 * Source of the caller's W3C Trace Context (R13, M-062). When the client is built with one, every request carries the
 * current {@code traceparent} (and {@code tracestate}), so the server's span (Spring Boot OpenTelemetry, W3C
 * propagation) joins the caller's trace. Off by default (R56): without a TraceContext no trace header is sent.
 *
 * <p>The SDK has no tracing dependency. Bridge your tracer in one line, for example with OpenTelemetry:
 * <pre>{@code
 * TraceContext otel = () -> {
 *     SpanContext s = Span.current().getSpanContext();
 *     return s.isValid() ? "00-" + s.getTraceId() + "-" + s.getSpanId() + "-" + s.getTraceFlags().asHex() : null;
 * };
 * }</pre>
 * A value that is not a valid version-00 {@code traceparent} is not sent; an exception from the provider is logged
 * and the request goes out without trace headers (tracing never fails a call). A request that already sets
 * {@code traceparent} keeps its own value.
 */
@FunctionalInterface
public interface TraceContext {

    /** W3C traceparent, version 00: {@code 00-<32 hex trace-id>-<16 hex parent-id>-<2 hex flags>}, lower case. */
    Pattern TRACEPARENT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    /** The current {@code traceparent}, or null when the caller is not inside a trace. */
    String traceparent();

    /** The current {@code tracestate}, or null. Sent only together with a valid traceparent. */
    default String tracestate() {
        return null;
    }

    /** True for a well-formed version-00 traceparent whose trace-id and parent-id are not all zero. */
    static boolean validTraceparent(String v) {
        return v != null && TRACEPARENT.matcher(v).matches()
                && !v.substring(3, 35).equals("0".repeat(32)) && !v.substring(36, 52).equals("0".repeat(16));
    }

    /** True for a tracestate of printable ASCII, at most 512 characters (W3C Trace Context). */
    static boolean validTracestate(String v) {
        if (v == null || v.isEmpty() || v.length() > 512) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }
}
