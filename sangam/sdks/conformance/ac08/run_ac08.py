# SPDX-License-Identifier: Apache-2.0
"""AC-08 cross-language harness (SANGAM.md IX.8 AC-08, R18; M-023): workers in Java, .NET, PHP, Python and Node complete
tasks with the same lease, idempotency and error semantics against one real Sangam server.

For every language and scenario the harness deploys one process (external task on topic ac08.<lang>.<scenario>, then a
user task that holds the instance so its variables stay readable), starts instances with the T18 values, runs that
language's AC-08 worker program (CLI contract below), and checks:
  complete         the worker reports "completed"; out_* variables are back on the instance with the identical
                   business value (Decimal with scale, Long beyond 2^53, Date instant, LocalDate, null)
  bpmn-error       "bpmn-error"; the boundary error path holds the instance; no out_* variables
  fail-retry       "failed-then-completed" with the same operationId on both attempts and a higher lockVersion
  outcome-unknown  "outcome-unknown"; a reconciliation item for that operationId exists
  stale-lease      "stale-lease": the late complete got 409 STALE_LEASE and changed nothing
and finally that every language produced identical results for every scenario.

Worker CLI contract (each SDK ships one: sdks/java .../conformance/Ac08Worker.java, sdks/dotnet/examples/Ac08Worker,
sdks/python/examples/ac08_worker.py, sdks/typescript/examples/ac08-worker.ts, sdks/php/examples/ac08-worker.php):
  env SANGAM_BASE_URL, SANGAM_TOKEN, AC08_TOPIC, AC08_WORKER_ID, AC08_SCENARIO, AC08_TASKS, AC08_TIMEOUT_S, AC08_LOCK_MS;
  prints one JSON line per task {"lang","workId","operationId","lockVersion","attemptId","scenario","result","detail"}.

The command per language comes from AC08_CMD_<LANG> (a shell command line run in the sdks/ directory), e.g.
  AC08_CMD_PYTHON="python python/examples/ac08_worker.py"
Languages without a command are skipped with a notice (so the job can run while a toolchain is still missing), unless
--require-all is given (CI).

  python sdks/conformance/ac08/run_ac08.py --base-url http://localhost:8080 --token "$SANGAM_DEV_TOKEN" [--require-all]

Standard library only. Exit 0 when every check passed.
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime
from decimal import Decimal

LANGS = ["java", "dotnet", "python", "typescript", "php"]
SCENARIOS = ["complete", "bpmn-error", "fail-retry", "outcome-unknown", "stale-lease"]
EXPECTED = {"complete": "completed", "bpmn-error": "bpmn-error", "fail-retry": "failed-then-completed",
            "outcome-unknown": "outcome-unknown", "stale-lease": "stale-lease"}
SDKS = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
# the T18 values every instance starts with (sdks/testing/t18-vectors.json: money, long-beyond-2^53, date-offset,
# local-date, null)
INPUT = {
    "amount": {"type": "Decimal", "value": "1234.50"},
    "count": {"type": "Long", "value": "9007199254740993"},
    "when": {"type": "Date", "value": "2026-04-01T00:00:00+05:30"},
    "day": {"type": "LocalDate", "value": "2026-03-31"},
    "note": None,
}


class Api:
    def __init__(self, base: str, token: str | None) -> None:
        self.base = base.rstrip("/").removesuffix("/api/v1") + "/api/v1"
        self.token = token

    def call(self, method: str, path: str, body: bytes | None = None, ctype: str = "application/json",
             expect=(200, 201, 202, 204)):
        req = urllib.request.Request(self.base + path, data=body, method=method)
        req.add_header("Accept", "application/json, application/problem+json")
        if body is not None:
            req.add_header("Content-Type", ctype)
        if method != "GET":
            req.add_header("Idempotency-Key", str(uuid.uuid4()))
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read()
                status = r.status
        except urllib.error.HTTPError as e:
            raw, status = e.read(), e.code
        if status not in expect:
            raise RuntimeError(f"{method} {path} -> {status}: {raw[:500]!r}")
        return json.loads(raw, parse_float=Decimal) if raw else None

    def json(self, method: str, path: str, body=None):
        return self.call(method, path, None if body is None else json.dumps(body).encode())


def bpmn(lang: str, scenario: str) -> tuple[str, str]:
    key = f"ac08_{lang}_{scenario}".replace("-", "_")
    topic = f"ac08.{lang}.{scenario}"
    xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
             id="defs_{key}" targetNamespace="http://sangam.org/conformance">
  <error id="err_ac08" name="AC08_ERROR" errorCode="AC08_ERROR"/>
  <process id="{key}" name="AC-08 {lang} {scenario}" isExecutable="true" operaton:historyTimeToLive="1">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="work"/>
    <serviceTask id="work" name="AC-08 work" operaton:type="external" operaton:topic="{topic}"/>
    <boundaryEvent id="onError" attachedToRef="work"><errorEventDefinition errorRef="err_ac08"/></boundaryEvent>
    <sequenceFlow id="f2" sourceRef="work" targetRef="hold"/>
    <userTask id="hold" name="Hold for inspection"/>
    <sequenceFlow id="f3" sourceRef="hold" targetRef="end"/>
    <endEvent id="end"/>
    <sequenceFlow id="f4" sourceRef="onError" targetRef="errorHold"/>
    <userTask id="errorHold" name="Error path"/>
    <sequenceFlow id="f5" sourceRef="errorHold" targetRef="errorEnd"/>
    <endEvent id="errorEnd"/>
  </process>
</definitions>
"""
    return key, xml


def deploy(api: Api, files: dict[str, str]) -> None:
    boundary = "ac08-" + uuid.uuid4().hex
    out = bytearray()
    for name, xml in files.items():
        out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{name}\"\r\n"
                "Content-Type: application/xml\r\n\r\n").encode() + xml.encode() + b"\r\n"
    out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nac08\r\n"
            f"--{boundary}--\r\n").encode()
    api.call("POST", "/deployments", bytes(out), "multipart/form-data; boundary=" + boundary)


def norm(v):
    """Business value of a VariableMap entry: TypedValue or plain JSON -> comparable form."""
    if isinstance(v, dict) and set(v) >= {"type", "value"}:
        t, val = v["type"], v["value"]
        if t == "Decimal":
            return ("decimal", str(Decimal(str(val))))  # keeps the scale: 1234.50 stays 1234.50
        if t in ("Long", "BigInteger", "Integer"):
            return ("integer", str(int(str(val))))
        if t == "Date":
            return ("instant", datetime.fromisoformat(str(val).replace("Z", "+00:00")).timestamp())
        if t == "LocalDate":
            return ("localdate", str(val))
        return (t.lower(), val)
    return ("plain", v)


def wait_ready(api: Api, timeout: float) -> None:
    end = time.time() + timeout
    while True:
        try:
            api.json("GET", "/me")
            return
        except Exception as e:  # noqa: BLE001 - keep polling until the deadline
            if time.time() > end:
                raise RuntimeError(f"server not ready: {e}") from e
            time.sleep(3)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base-url", default=os.environ.get("SANGAM_BASE_URL", "http://localhost:8080"))
    ap.add_argument("--token", default=os.environ.get("SANGAM_TOKEN"))
    ap.add_argument("--langs", default=",".join(LANGS))
    ap.add_argument("--scenarios", default=",".join(SCENARIOS))
    ap.add_argument("--tasks", type=int, default=2)
    ap.add_argument("--ready-timeout", type=float, default=300)
    ap.add_argument("--require-all", action="store_true")
    a = ap.parse_args(argv)
    api = Api(a.base_url, a.token)
    wait_ready(api, a.ready_timeout)
    langs = [x for x in a.langs.split(",") if x]
    scenarios = [x for x in a.scenarios.split(",") if x]
    cmds = {l: os.environ.get("AC08_CMD_" + l.upper()) for l in langs}
    missing = [l for l in langs if not cmds[l]]
    if missing and a.require_all:
        print("AC-08: no worker command for", missing, "(set AC08_CMD_<LANG>)")
        return 2
    for l in missing:
        print(f"::notice::AC-08 skips {l}: AC08_CMD_{l.upper()} not set")
    langs = [l for l in langs if cmds[l]]

    files = {}
    for l in langs:
        for s in scenarios:
            key, xml = bpmn(l, s)
            files[key + ".bpmn"] = xml
    deploy(api, files)

    failures: list[str] = []
    outcomes: dict[tuple[str, str], list] = {}
    for l in langs:
        for s in scenarios:
            key, _ = bpmn(l, s)
            instances = [api.json("POST", "/process-instances",
                                  {"key": key, "businessKey": f"AC08-{l}-{s}-{i}", "variables": INPUT})["id"]
                         for i in range(a.tasks)]
            env = dict(os.environ, SANGAM_BASE_URL=a.base_url, AC08_TOPIC=f"ac08.{l}.{s}", AC08_SCENARIO=s,
                       AC08_WORKER_ID=f"ac08-{l}-{s}", AC08_TASKS=str(a.tasks), AC08_TIMEOUT_S="120",
                       AC08_LOCK_MS="10000" if s == "stale-lease" else "30000")
            if a.token:
                env["SANGAM_TOKEN"] = a.token
            p = subprocess.run(shlex.split(cmds[l]), cwd=SDKS, env=env, capture_output=True, text=True, timeout=600)
            lines = []
            for line in p.stdout.splitlines():
                try:
                    lines.append(json.loads(line))
                except ValueError:
                    continue
            lines = [x for x in lines if isinstance(x, dict) and "result" in x]
            if p.returncode != 0 or len(lines) < a.tasks:
                failures.append(f"{l}/{s}: exit {p.returncode}, {len(lines)} result line(s); stderr: {p.stderr[-800:]}")
                continue
            results = sorted(x["result"] for x in lines)
            if results != [EXPECTED[s]] * len(results):
                failures.append(f"{l}/{s}: results {results}, expected {EXPECTED[s]}")
            observed = []
            for pid in instances:
                vars_ = api.json("GET", f"/process-instances/{pid}/variables") or {}
                outs = {k: norm(v) for k, v in vars_.items() if k.startswith("out_")}
                observed.append(outs)
                if s in ("complete", "fail-retry"):
                    want = {"out_" + k: norm(v) for k, v in INPUT.items()}
                    if outs != want:
                        failures.append(f"{l}/{s}: instance {pid} out_* {outs} != {want} (T18)")
                elif outs:
                    failures.append(f"{l}/{s}: instance {pid} has out_* variables after {s}: {outs}")
            if s == "outcome-unknown":
                items = api.json("GET", "/ops/reconciliation?size=500") or []
                items = items if isinstance(items, list) else items.get("items", [])
                known = {str(i.get("operationId")) for i in items if isinstance(i, dict)}
                for x in lines:
                    if str(x.get("operationId")) not in known:
                        failures.append(f"{l}/{s}: no reconciliation item for operationId {x.get('operationId')}")
            outcomes[(l, s)] = sorted(json.dumps(o, sort_keys=True, default=str) for o in observed)

    for s in scenarios:
        seen = {l: outcomes.get((l, s)) for l in langs if (l, s) in outcomes}
        if len({json.dumps(v) for v in seen.values()}) > 1:
            failures.append(f"cross-language difference in {s}: {seen}")

    report = {"langs": langs, "skipped": missing, "scenarios": scenarios, "failures": failures}
    print(json.dumps(report, indent=1))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
