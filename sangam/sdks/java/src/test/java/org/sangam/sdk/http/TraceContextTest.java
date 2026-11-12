// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M-062: W3C trace context propagation of the SDK transport (off by default, never fails a call). */
class TraceContextTest {

    static final String TP = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private static HttpTransport transport(TraceContext trace) {
        return new HttpTransport(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1"), null, RetryPolicy.none(),
                null, null, null, null, trace);
    }

    @Test
    void validatesVersion00Traceparent() {
        assertTrue(TraceContext.validTraceparent(TP));
        assertFalse(TraceContext.validTraceparent(null));
        assertFalse(TraceContext.validTraceparent("00-00000000000000000000000000000000-00f067aa0ba902b7-01"), "zero trace-id");
        assertFalse(TraceContext.validTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"), "zero parent-id");
        assertFalse(TraceContext.validTraceparent("00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01"), "upper case");
        assertFalse(TraceContext.validTraceparent("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"), "version ff");
        assertFalse(TraceContext.validTraceparent(TP + "-extra"), "version 00 has exactly four fields");
    }

    @Test
    void validatesTracestate() {
        assertTrue(TraceContext.validTracestate("congo=t61rcWkgMzE"));
        assertFalse(TraceContext.validTracestate(""));
        assertFalse(TraceContext.validTracestate("a=é"));
        assertFalse(TraceContext.validTracestate("a=" + "x".repeat(511)));
    }

    @Test
    void noProviderSendsNoTraceHeaders() {
        assertEquals(Map.of(), transport(null).traceHeaders(ApiRequest.get("/me")));
    }

    @Test
    void providerContextIsSent() {
        TraceContext t = new TraceContext() {
            @Override public String traceparent() { return TP; }
            @Override public String tracestate() { return "congo=t61rcWkgMzE"; }
        };
        assertEquals(Map.of("traceparent", TP, "tracestate", "congo=t61rcWkgMzE"), transport(t).traceHeaders(ApiRequest.get("/me")));
    }

    @Test
    void invalidOrAbsentContextIsNotSent() {
        assertEquals(Map.of(), transport(() -> null).traceHeaders(ApiRequest.get("/me")));
        assertEquals(Map.of(), transport(() -> "not-a-traceparent").traceHeaders(ApiRequest.get("/me")));
    }

    @Test
    void failingProviderNeverFailsTheCall() {
        TraceContext broken = () -> { throw new IllegalStateException("exporter down"); };
        assertEquals(Map.of(), transport(broken).traceHeaders(ApiRequest.get("/me")));
    }

    @Test
    void requestLevelTraceparentWins() {
        ApiRequest req = ApiRequest.get("/me").header("TraceParent", "00-11111111111111111111111111111111-2222222222222222-00");
        assertEquals(Map.of(), transport(() -> TP).traceHeaders(req));
    }
}
