# SPDX-License-Identifier: Apache-2.0
"""Robot Framework RPA worker example (M-104, owner decision 164) on the external-worker pattern; nothing new on the
server. A BPMN service task of type ``external`` on topic ``rpa.robot`` hands one Robot Framework suite run to this
worker, which runs it as a separate ``robot`` process and reports the outcome:

* every test passed: the task completes with ``robotPassed``, ``robotFailed``, ``robotSkipped`` and ``robotReturnCode``;
* one or more tests failed (``robot`` exit codes 1-250): the BPMN error ``ROBOT_TESTS_FAILED`` with the same
  variables, for the model to route (for example to a human task);
* invalid suite data or options (exit code 252) or a suite name outside the suite folder: ``TaskFailed`` with
  ``retries=0`` (an incident; a retry cannot help);
* anything else after the bot started (interrupted run, internal error, no result file, the process timed out):
  outcome-unknown, never a blind retry, because a bot may already have acted on an external system.

Process variables read: ``robotSuite`` (file name of a ``.robot`` suite inside ``ROBOT_SUITES_DIR``; no path
separators) and optional ``robotVariables`` (a JSON object of string values, passed as Robot ``--variable``
options through an argument file, so they never appear in the process list). The run gets the task's operationId
as ``${SANGAM_OPERATION_ID}`` so a suite can pass it on as an idempotency key to the systems it drives.

Orchestrator REST adapters (UiPath, Automation Anywhere, Power Automate) are out of 0.1.0 (decision 164).

Requirements on the worker host, not on the Sangam server: Python 3.10+ with Robot Framework installed by the
operator (Apache-2.0; for example ``pip install robotframework``) and whatever libraries the suites use.

Environment: ``SANGAM_URL`` (default http://localhost:8080), either ``SANGAM_TOKEN`` or
``SANGAM_OIDC_TOKEN_URL`` + ``SANGAM_CLIENT_ID`` + ``SANGAM_CLIENT_SECRET`` (+ ``SANGAM_SCOPE``), optional
``SANGAM_TOPIC`` (default ``rpa.robot``), ``SANGAM_WORKER_ID``, ``SANGAM_SEAT``, ``ROBOT_SUITES_DIR`` (default
``./suites``), ``ROBOT_OUTPUT_DIR`` (default a temporary folder per run) and ``ROBOT_TIMEOUT_SECONDS`` (default 1800).
"""
from __future__ import annotations

import json
import logging
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Mapping

from sangam_sdk import (BpmnError, ClientCredentialsTokenProvider, NoAuth, OutcomeUnknown, SangamClient,
                        StaticTokenProvider, TaskContext, TaskFailed, TokenProvider, Worker)

SUITES_DIR = Path(os.environ.get("ROBOT_SUITES_DIR", "suites")).resolve()
OUTPUT_DIR = os.environ.get("ROBOT_OUTPUT_DIR")
TIMEOUT_SECONDS = int(os.environ.get("ROBOT_TIMEOUT_SECONDS", "1800"))

# robot exit codes (Robot Framework User Guide, "Return codes"): 0 all passed, 1-249 that many failed, 250 250 or
# more failed, 251 help or version printed, 252 invalid test data or command line options, 253 stopped by the user,
# 255 unexpected internal error
RC_INVALID_INPUT = 252
SUITE_NAME = re.compile(r"^[A-Za-z0-9_.-]+\.robot$")
VARIABLE_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def token_provider() -> TokenProvider:
    url = os.environ.get("SANGAM_OIDC_TOKEN_URL")
    if url:
        return ClientCredentialsTokenProvider(url, os.environ["SANGAM_CLIENT_ID"], os.environ["SANGAM_CLIENT_SECRET"],
                                              scope=os.environ.get("SANGAM_SCOPE"))
    token = os.environ.get("SANGAM_TOKEN")
    return StaticTokenProvider(token) if token else NoAuth()


def suite_path(name: Any) -> Path:
    """The suite file inside SUITES_DIR; a name with a path, another extension or a missing file is refused."""
    if not isinstance(name, str) or not SUITE_NAME.match(name):
        raise TaskFailed(f"robotSuite must be the file name of a .robot suite, got {name!r}", retries=0)
    path = (SUITES_DIR / name).resolve()
    if path.parent != SUITES_DIR or not path.is_file():
        raise TaskFailed(f"suite {name} is not in {SUITES_DIR}", retries=0)
    return path


def argument_lines(variables: Any, operation_id: str) -> list[str]:
    """Robot argument-file lines: one ``--variable NAME:value`` per variable (values are never on the command line)."""
    if variables is None:
        variables = {}
    if not isinstance(variables, Mapping):
        raise TaskFailed("robotVariables must be a JSON object", retries=0)
    lines = ["--variable SANGAM_OPERATION_ID:" + operation_id]
    for k, v in variables.items():
        if not isinstance(k, str) or not VARIABLE_NAME.match(k) or k == "SANGAM_OPERATION_ID":
            raise TaskFailed(f"robotVariables has an invalid name {k!r}", retries=0)
        if not isinstance(v, str) or "\n" in v or "\r" in v:
            raise TaskFailed(f"robotVariables.{k} must be a one-line string", retries=0)
        lines.append(f"--variable {k}:{v}")
    return lines


def statistics(output_xml: Path) -> dict[str, int]:
    """Totals from output.xml (``statistics/total/stat``: pass, fail and skip attributes)."""
    root = ET.parse(output_xml).getroot()
    stat = root.find("./statistics/total/stat")
    if stat is None:
        raise ValueError("output.xml has no total statistics")
    return {"robotPassed": int(stat.get("pass", "0")), "robotFailed": int(stat.get("fail", "0")),
            "robotSkipped": int(stat.get("skip", "0"))}


def run_suite(ctx: TaskContext) -> dict[str, Any]:
    suite = suite_path(ctx.variable("robotSuite"))
    lines = argument_lines(ctx.variable("robotVariables"), ctx.operation_id)
    ctx.throw_if_cancelled()
    out_dir = Path(OUTPUT_DIR) / ctx.operation_id if OUTPUT_DIR else Path(tempfile.mkdtemp(prefix="robot-"))
    out_dir.mkdir(parents=True, exist_ok=True)
    args_file = out_dir / "arguments.txt"
    args_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    cmd = [sys.executable, "-m", "robot", "--argumentfile", str(args_file), "--outputdir", str(out_dir),
           "--output", "output.xml", "--report", "NONE", "--log", "log.html", "--consolecolors", "off", str(suite)]
    ctx.logger.info("rpa.robot.start", "running Robot Framework suite", {"suite": suite.name})
    try:
        proc = subprocess.run(cmd, cwd=str(SUITES_DIR), capture_output=True, text=True, timeout=TIMEOUT_SECONDS,
                              check=False)
    except subprocess.TimeoutExpired as e:
        raise OutcomeUnknown(f"suite {suite.name} ran past {TIMEOUT_SECONDS} s and was stopped; the bot may have "
                             "acted", f"output folder {out_dir}") from e
    finally:
        args_file.unlink(missing_ok=True)
    rc = proc.returncode
    if rc == RC_INVALID_INPUT:
        raise TaskFailed(f"Robot Framework refused suite {suite.name} (invalid test data or options)",
                         proc.stderr[-2000:], retries=0)
    try:
        result = statistics(out_dir / "output.xml")
    except (OSError, ValueError, ET.ParseError) as e:
        raise OutcomeUnknown(f"suite {suite.name} ended with exit code {rc} and no readable result",
                             proc.stderr[-2000:]) from e
    result["robotReturnCode"] = rc
    if rc == 0:
        return result
    if 1 <= rc <= 250:
        raise BpmnError("ROBOT_TESTS_FAILED", f"{result['robotFailed']} Robot Framework test(s) failed in {suite.name}",
                        result)
    raise OutcomeUnknown(f"suite {suite.name} stopped with exit code {rc}; the bot may have acted",
                         proc.stderr[-2000:], result)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    client = SangamClient(os.environ.get("SANGAM_URL", "http://localhost:8080"), token_provider=token_provider(),
                          seat=os.environ.get("SANGAM_SEAT"))
    worker = Worker(client, worker_id=os.environ.get("SANGAM_WORKER_ID"), max_concurrency=1)
    # a bot drives external systems: unexpected errors become outcome-unknown, never a blind retry; the lease is kept
    # alive by heartbeats while the suite runs
    worker.topic(os.environ.get("SANGAM_TOPIC", "rpa.robot"), lock_duration_ms=300_000, retries=0,
                 variables=["robotSuite", "robotVariables"], irreversible=True)(run_suite)
    worker.run()


if __name__ == "__main__":
    main()
