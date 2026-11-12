# SPDX-License-Identifier: Apache-2.0
"""M-062: W3C trace context propagation of the transport (off by default, never fails a call)."""
from sangam_sdk.http import ApiRequest, HttpTransport, valid_traceparent, valid_tracestate

TP = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


def _t(ctx=None) -> HttpTransport:
    return HttpTransport("http://127.0.0.1:1", trace_context=ctx)


def test_validates_version_00_traceparent() -> None:
    assert valid_traceparent(TP)
    assert not valid_traceparent(None)
    assert not valid_traceparent("00-" + "0" * 32 + "-00f067aa0ba902b7-01")
    assert not valid_traceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-" + "0" * 16 + "-01")
    assert not valid_traceparent(TP.upper())
    assert not valid_traceparent(TP + "-extra")
    assert valid_tracestate("congo=t61rcWkgMzE")
    assert not valid_tracestate("a=" + "x" * 511)


def test_no_context_no_headers() -> None:
    assert _t().trace_headers(ApiRequest("GET", "/me")) == {}


def test_context_is_sent() -> None:
    t = _t(lambda: (TP, "congo=t61rcWkgMzE"))
    assert t.trace_headers(ApiRequest("GET", "/me")) == {"traceparent": TP, "tracestate": "congo=t61rcWkgMzE"}


def test_invalid_or_absent_context_dropped() -> None:
    assert _t(lambda: None).trace_headers(ApiRequest("GET", "/me")) == {}
    assert _t(lambda: ("nope", None)).trace_headers(ApiRequest("GET", "/me")) == {}


def test_failing_provider_never_fails_the_call() -> None:
    def broken():
        raise RuntimeError("exporter down")

    assert _t(broken).trace_headers(ApiRequest("GET", "/me")) == {}


def test_request_level_traceparent_wins() -> None:
    req = ApiRequest("GET", "/me", headers={"TraceParent": "00-" + "1" * 32 + "-" + "2" * 16 + "-00"})
    assert _t(lambda: (TP, None)).trace_headers(req) == {}
