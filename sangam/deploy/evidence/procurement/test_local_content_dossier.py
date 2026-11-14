# SPDX-License-Identifier: Apache-2.0
"""Tests of local_content_dossier.py (M-089). Run: python -I -m unittest discover -s deploy/evidence/procurement -p "test_*.py"."""
import copy
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import local_content_dossier as lcd  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def comp(cid, value, origin="DOMESTIC", paid=False, resold=False):
    return {"id": cid, "description": cid, "value": value, "origin": origin, "paidOutsideCountry": paid,
            "importedItemBoughtLocally": resold, "evidence": "ref-" + cid}


def doc(components, total):
    return {"sku": "SKU-1", "product": "Sangam", "productVersion": "0.1.0", "currency": "XTS",
            "totalValueExcludingDomesticIndirectTaxes": total,
            "thresholds": {"classIPercent": "50", "classIIPercent": "20", "source": "test thresholds"},
            "components": components}


class ComputeTest(unittest.TestCase):

    def test_class_i_at_exactly_the_threshold(self):
        m = lcd.compute(doc([comp("eng", "500.00"), comp("lic", "500.00", "IMPORTED", paid=True)], "1000.00"))
        self.assertEqual("50.00", m["localContentPercent"])
        self.assertEqual("CLASS_I", m["calculatedClass"])
        self.assertEqual("500.00", m["importedContentValue"])
        self.assertFalse(m["declaration"]["signed"])
        self.assertEqual("DRAFT_NOT_A_DECLARATION", m["status"])

    def test_just_below_class_i_is_never_rounded_up(self):
        # 49.999 % must not be shown as 50.00 or classed as Class-I
        m = lcd.compute(doc([comp("eng", "49999"), comp("imp", "50001", "IMPORTED")], "100000"))
        self.assertEqual("49.99", m["localContentPercent"])
        self.assertEqual("CLASS_II", m["calculatedClass"])

    def test_below_class_ii(self):
        m = lcd.compute(doc([comp("eng", "19"), comp("imp", "81", "IMPORTED")], "100"))
        self.assertEqual("BELOW_CLASS_II", m["calculatedClass"])

    def test_domestic_origin_paid_abroad_and_local_resale_of_imports_count_as_imported(self):
        m = lcd.compute(doc([comp("eng", "60"), comp("royalty", "25", paid=True), comp("resale", "15", resold=True)], "100"))
        self.assertEqual("40", m["importedContentValue"])
        by_id = {c["id"]: c for c in m["components"]}
        self.assertTrue(by_id["royalty"]["imported"])
        self.assertFalse(by_id["eng"]["imported"])
        self.assertEqual(["paid outside the country"], by_id["royalty"]["reasons"])
        self.assertEqual(["imported item bought locally"], by_id["resale"]["reasons"])
        self.assertEqual("60.00", m["localContentPercent"])
        self.assertEqual("CLASS_I", m["calculatedClass"])

    def test_exact_decimal_arithmetic(self):
        m = lcd.compute(doc([comp("a", "0.10"), comp("b", "0.20"), comp("imp", "0.70", "IMPORTED")], "1.00"))
        self.assertEqual("0.30", m["localValue"])
        self.assertEqual("30.00", m["localContentPercent"])

    def test_components_must_cover_the_total_exactly(self):
        with self.assertRaises(lcd.InputError) as e:
            lcd.compute(doc([comp("eng", "90")], "100"))
        self.assertIn("sum to 90", e.exception.problems[0])

    def test_float_money_and_bad_fields_are_refused_with_every_problem(self):
        bad = doc([comp("eng", 100.0), {"id": "eng", "value": "-1", "origin": "ELSEWHERE", "paidOutsideCountry": "yes"}], 100)
        bad["sku"] = "bad sku!"
        bad["thresholds"] = {"classIPercent": "20", "classIIPercent": "50", "source": ""}
        with self.assertRaises(lcd.InputError) as e:
            lcd.compute(bad)
        text = "\n".join(e.exception.problems)
        for expected in ("sku:", "totalValueExcludingDomesticIndirectTaxes: money", "thresholds.source: required",
                         "0 < classIIPercent < classIPercent", "components[0].value: money", "components[1].id: duplicate",
                         "components[1].value: must not be negative", "components[1].origin", "components[1].paidOutsideCountry",
                         "components[1].evidence: required"):
            self.assertIn(expected, text)

    def test_the_shipped_template_is_refused_until_it_is_filled(self):
        with open(os.path.join(HERE, "local-content-input.template.json"), encoding="utf-8") as f:
            template = json.load(f)
        with self.assertRaises(lcd.InputError):
            lcd.compute(template)
        filled = copy.deepcopy(template)
        filled.update({"sku": "IMPL-01", "productVersion": "0.1.0", "currency": "XTS",
                       "totalValueExcludingDomesticIndirectTaxes": "1000"})
        for c, v in zip(filled["components"], ("700", "200", "0", "100")):
            c["value"] = v
            c["evidence"] = "cost sheet"
        m = lcd.compute(filled)
        self.assertEqual("CLASS_I", m["calculatedClass"])
        self.assertEqual("100.00", m["localContentPercent"])


class OutputTest(unittest.TestCase):

    def test_main_writes_a_draft_that_is_never_signed(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "in.json")
            with open(src, "w", encoding="utf-8") as f:
                json.dump(doc([comp("eng", "70"), comp("lic", "30", "IMPORTED")], "100"), f)
            self.assertEqual(0, lcd.main([src, "--out", tmp]))
            with open(os.path.join(tmp, "local-content-SKU-1.md"), encoding="utf-8") as f:
                md = f.read()
            self.assertIn("DRAFT FOR THE OWNER", md)
            self.assertIn("Not signed.", md)
            self.assertIn("| Local content (cut to 2 places, never rounded up) | 70.00 % |", md)
            with open(os.path.join(tmp, "local-content-SKU-1.json"), encoding="utf-8") as f:
                self.assertIsNone(json.load(f)["declaration"]["signedBy"])

    def test_main_exits_1_on_invalid_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "in.json")
            with open(src, "w", encoding="utf-8") as f:
                json.dump({"sku": ""}, f)
            self.assertEqual(1, lcd.main([src, "--out", tmp]))
            self.assertEqual(["in.json"], os.listdir(tmp))


if __name__ == "__main__":
    unittest.main()
