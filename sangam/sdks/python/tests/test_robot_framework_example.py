# SPDX-License-Identifier: Apache-2.0
"""Decision 164: the Robot Framework worker example. Covers the parts that need no Robot Framework install: suite
confinement, argument-file lines, output.xml statistics and the exit-code mapping (subprocess replaced)."""
from __future__ import annotations

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from sangam_sdk import BpmnError, OutcomeUnknown, TaskFailed

EXAMPLE = Path(__file__).resolve().parents[1] / "examples" / "robot_framework_worker.py"
OUTPUT_XML = """<?xml version="1.0" encoding="UTF-8"?>
<robot generator="Robot 7.0" rpa="true" schemaversion="5">
<statistics><total><stat pass="{p}" fail="{f}" skip="{s}">All Tasks</stat></total></statistics>
</robot>
"""


def load(suites_dir: Path, output_dir: Path):
    with mock.patch.dict("os.environ", {"ROBOT_SUITES_DIR": str(suites_dir), "ROBOT_OUTPUT_DIR": str(output_dir)}):
        spec = importlib.util.spec_from_file_location("robot_framework_worker", EXAMPLE)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    return module


class Ctx:
    def __init__(self, variables):
        self._vars = variables
        self.operation_id = "op-123"
        self.logger = SimpleNamespace(info=lambda *a, **k: None)

    def variable(self, name, default=None):
        return self._vars.get(name, default)

    def throw_if_cancelled(self):
        pass


class RobotFrameworkExampleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.suites = base / "suites"
        self.suites.mkdir()
        (self.suites / "invoice.robot").write_text("*** Tasks ***\nT\n    No Operation\n", encoding="utf-8")
        self.out = base / "out"
        self.m = load(self.suites, self.out)

    def tearDown(self):
        self.tmp.cleanup()

    def run_with(self, rc, xml=None, variables=None):
        def fake_run(cmd, **kw):
            if xml is not None:
                (self.out / "op-123" / "output.xml").write_text(xml, encoding="utf-8")
            return subprocess.CompletedProcess(cmd, rc, "", "stderr text")
        ctx = Ctx(variables or {"robotSuite": "invoice.robot"})
        with mock.patch.object(self.m.subprocess, "run", side_effect=fake_run) as run:
            try:
                return self.m.run_suite(ctx), run
            finally:
                self.assertFalse((self.out / "op-123" / "arguments.txt").exists(), "argument file must be removed")

    def test_suite_outside_the_folder_is_refused_without_retry(self):
        for name in ("../secret.robot", "a/b.robot", "invoice.txt", "missing.robot", None, 5):
            with self.assertRaises(TaskFailed) as e:
                self.m.suite_path(name)
            self.assertEqual(0, e.exception.retries, name)

    def test_variables_go_to_the_argument_file_with_the_operation_id(self):
        lines = self.m.argument_lines({"CUSTOMER": "ACME Ltd", "AMOUNT": "125.50"}, "op-1")
        self.assertEqual(["--variable SANGAM_OPERATION_ID:op-1", "--variable CUSTOMER:ACME Ltd",
                          "--variable AMOUNT:125.50"], lines)

    def test_invalid_variables_are_refused(self):
        for bad in ([1], {"1x": "v"}, {"A": 5}, {"A": "two\nlines"}, {"SANGAM_OPERATION_ID": "x"}):
            with self.assertRaises(TaskFailed):
                self.m.argument_lines(bad, "op-1")

    def test_all_passed_completes_with_the_statistics(self):
        result, run = self.run_with(0, OUTPUT_XML.format(p=3, f=0, s=1))
        self.assertEqual({"robotPassed": 3, "robotFailed": 0, "robotSkipped": 1, "robotReturnCode": 0}, result)
        cmd = run.call_args.args[0]
        self.assertIn("--argumentfile", cmd)
        self.assertNotIn("ACME", " ".join(cmd))

    def test_failed_tests_are_a_bpmn_error_with_the_statistics(self):
        with self.assertRaises(BpmnError) as e:
            self.run_with(2, OUTPUT_XML.format(p=1, f=2, s=0))
        self.assertEqual("ROBOT_TESTS_FAILED", e.exception.error_code)
        self.assertEqual(2, e.exception.variables["robotFailed"])

    def test_invalid_suite_data_is_an_incident(self):
        with self.assertRaises(TaskFailed) as e:
            self.run_with(252)
        self.assertEqual(0, e.exception.retries)

    def test_interrupted_or_unreadable_runs_are_outcome_unknown(self):
        with self.assertRaises(OutcomeUnknown):
            self.run_with(253, OUTPUT_XML.format(p=0, f=0, s=0))
        with self.assertRaises(OutcomeUnknown):
            self.run_with(0)  # no output.xml

    def test_timeout_is_outcome_unknown(self):
        ctx = Ctx({"robotSuite": "invoice.robot"})
        with mock.patch.object(self.m.subprocess, "run", side_effect=subprocess.TimeoutExpired("robot", 1)):
            with self.assertRaises(OutcomeUnknown):
                self.m.run_suite(ctx)


if __name__ == "__main__":
    unittest.main()
