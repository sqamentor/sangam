# SPDX-License-Identifier: Apache-2.0
"""Unit tests of the set-up of load_presentation_data.py (register E-26 Follow-up 2 (v) and (vi)), with the HTTP calls
replaced by a fake. Run: python -I -m unittest discover -s sangam/deploy/presentation -p "test_*.py"."""
import contextlib
import io
import os
import unittest

import load_presentation_data as loader

DATA_SET = {
    "dataSet": "unit", "fiscalYear": "2026-27",
    "payees": [{"employeeId": "E-1", "payee": "First Payee", "payeeAccount": "0001", "ifsc": "BANK0000001"}],
    "setup": {"budget": [{"ledger": "BUDGET", "headOfAccount": "2054-00-097-01-00-01",
                          "stages": ["APPROPRIATION", "ALLOCATION", "RELEASE"], "amount": 430000000,
                          "assumed": True, "why": "the release the cases draw on", "auditRef": "order 1"}]},
}
NO_VAULT = '{"detail":"no vault service is configured to hold clear identifier values (type BANK_ACCOUNT)"}'


class FakeServer:
    """Answers the set-up's calls: no party and no budget held yet; a party registration is refused when told so."""

    def __init__(self, refuse_party):
        self.refuse_party = refuse_party
        self.calls = []

    def __call__(self, method, url, token, body=None, seat=None, headers=None):
        path = url.split("?")[0]
        self.calls.append((method, path, (body or {}).get("operation")))
        if method == "GET":
            return {"items": []}
        if path.endswith("/registry/parties"):
            if self.refuse_party:
                raise loader.Refused(method, url, 409, NO_VAULT)
            return {"id": "PTY-1"}
        return {"applied": True}

    def ledger_operations(self):
        return [op for m, p, op in self.calls if m == "POST" and p.endswith("/ledger/by-ledger/operations")]


class SetUp(unittest.TestCase):
    def setUp(self):
        self.saved_http = loader.http
        self.saved_token = os.environ.get("SANGAM_ADMIN_TOKEN")
        os.environ["SANGAM_ADMIN_TOKEN"] = "unit-token"

    def tearDown(self):
        loader.http = self.saved_http
        if self.saved_token is None:
            os.environ.pop("SANGAM_ADMIN_TOKEN", None)
        else:
            os.environ["SANGAM_ADMIN_TOKEN"] = self.saved_token

    def run_setup(self, server):
        loader.http = server
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            wrong = loader.setup("http://server/api/v1", DATA_SET, False)
        return wrong, out.getvalue()

    def test_a_refused_payee_registration_does_not_stop_the_budget_release(self):
        server = FakeServer(refuse_party=True)
        wrong, out = self.run_setup(server)
        self.assertEqual(["ALLOCATE", "DISTRIBUTE", "DISTRIBUTE"], server.ledger_operations(), out)
        self.assertIn("Rs 4300000.00 through APPROPRIATION > ALLOCATION > RELEASE (ASSUMED: the release the cases draw on)"
                      ": released", out)
        self.assertEqual(1, len(wrong), wrong)
        self.assertIn("payees", wrong[0])
        self.assertIn("409", wrong[0])

    def test_with_the_payees_registered_the_set_up_reports_nothing_wrong(self):
        server = FakeServer(refuse_party=False)
        wrong, out = self.run_setup(server)
        self.assertEqual([], wrong, out)
        self.assertIn("set-up payee E-1: registered", out)
        self.assertEqual(["ALLOCATE", "DISTRIBUTE", "DISTRIBUTE"], server.ledger_operations(), out)

    def test_without_the_admin_token_nothing_is_set_up(self):
        os.environ.pop("SANGAM_ADMIN_TOKEN", None)
        server = FakeServer(refuse_party=False)
        wrong, out = self.run_setup(server)
        self.assertEqual([], server.calls)
        self.assertEqual(["set-up (SANGAM_ADMIN_TOKEN is not set)"], wrong)


if __name__ == "__main__":
    unittest.main()
