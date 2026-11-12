# SPDX-License-Identifier: Apache-2.0
"""M-062: ExternalWork.traceContext (sangam-api.yaml TraceContext) reaches the handler as ctx.traceparent."""
from sangam_sdk import SangamClient
from sangam_sdk.log import SangamLogger
from sangam_sdk.models import ExternalWork
from sangam_sdk.worker import TaskContext

TP = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


def _ctx(work: dict) -> TaskContext:
    return TaskContext(ExternalWork.from_dict(work), "w-1", SangamClient("http://127.0.0.1:1"), SangamLogger())


def test_task_exposes_the_engine_trace_context() -> None:
    ctx = _ctx({"id": "wk-1", "lockVersion": 1, "traceContext": {"traceparent": TP, "tracestate": "congo=t61rcWkgMzE"}})
    assert ctx.traceparent == TP
    assert ctx.tracestate == "congo=t61rcWkgMzE"


def test_trace_context_absent_when_none_was_stored() -> None:
    ctx = _ctx({"id": "wk-1", "lockVersion": 1})
    assert ctx.traceparent is None
    assert ctx.tracestate is None
