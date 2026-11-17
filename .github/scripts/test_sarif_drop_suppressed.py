# SPDX-License-Identifier: Apache-2.0
import unittest

from sarif_drop_suppressed import drop_in_source


def result(rule, sup=None):
    r = {"ruleId": rule}
    if sup is not None:
        r["suppressions"] = sup
    return r


class DropSuppressedTest(unittest.TestCase):
    def test_only_accepted_in_source_suppressions_are_dropped(self):
        sarif = {"runs": [{"results": [
            result("a"),
            result("b", [{"kind": "inSource"}]),
            result("c", [{"kind": "external"}]),
            result("d", [{"kind": "inSource", "status": "underReview"}]),
            result("e", []),
            result("f", [{"kind": "inSource"}, {"kind": "external"}]),
        ]}]}
        out, dropped = drop_in_source(sarif)
        self.assertEqual(1, dropped)
        self.assertEqual(["a", "c", "d", "e", "f"], [r["ruleId"] for r in out["runs"][0]["results"]])

    def test_a_run_without_results_stays_as_it_is(self):
        out, dropped = drop_in_source({"runs": [{"tool": {}}]})
        self.assertEqual(0, dropped)
        self.assertEqual({"runs": [{"tool": {}}]}, out)


if __name__ == "__main__":
    unittest.main()
