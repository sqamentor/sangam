# SPDX-License-Identifier: Apache-2.0
"""Owner decision 138 guard for the contract and the SDK surface: exact decimals are strings end to end, and no SDK
type ever turns money or a financial decimal into a float.

- MoneyAmount and DecimalString are string schemas whose pattern accepts plain decimal text only (no exponent, no
  sign other than a leading minus, no bare dot);
- every property named like money (amount, fee, price, tax, balance, max_amount, ...) resolves, through the same IR the
  SDK emitters use (contract_ir), to a string: never double, a JSON-number decimal or an integer (owner decision 211:
  money travels as decimal text in the API and every SDK, Task.amount included);
- the decision-141 and decision-142 shapes (UnverifiedPayment, PowerLimitDecision and its parts) carry MoneyAmount;
  PowerLimitDecision is the audit document sangam.power-decision/1 with its own snake_case member names;
- the TypedValue Decimal text is DecimalString.

Run with the other generator tests: python -m unittest discover -s sangam/sdks/codegen -p "test_*.py".
"""
import os
import re
import sys
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import contract_ir  # noqa: E402

CONTRACT = os.path.join(HERE, "..", "..", "sangam-rest", "api", "sangam-api.yaml")
MONEY_NAME = re.compile(r"(?i)^(amount|fee|fees|price|tax|balance)$|(Amount|Fee|Fees|Price|Tax|Balance)$")


def _load():
    with open(CONTRACT, encoding="utf-8") as f:
        return yaml.safe_load(f)


class DecimalContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = _load()
        cls.schemas = cls.doc["components"]["schemas"]
        cls.ir = contract_ir.build(cls.doc)

    def test_decimal_text_schemas_accept_plain_decimals_only(self):
        for name in ("MoneyAmount", "DecimalString"):
            s = self.schemas[name]
            self.assertEqual("string", s["type"], name)
            p = re.compile(s["pattern"])
            for ok in ("0", "12", "-1250.5", "100000.00", "0.075"):
                self.assertTrue(p.match(ok), f"{name} accepts {ok}")
            for bad in ("1e5", "1.5E-3", ".5", "5.", "+1", "1,000.00", "NaN", "Infinity", " 1", "0x10"):
                self.assertFalse(p.match(bad) and p.match(bad).group(0) == bad, f"{name} refuses {bad!r}")

    def test_money_named_properties_are_strings_in_every_sdk_model(self):
        offenders = []
        for model in self.ir.models.values():
            for prop in model.props:
                if not MONEY_NAME.search(prop.wire):
                    continue
                t = prop.type
                while t.kind == "array":
                    t = t.item
                if t.kind in ("double", "decimal", "int32", "int64"):
                    offenders.append(f"{model.name}.{prop.wire}: {t.kind}")
        self.assertEqual([], offenders, "decisions 138 and 211: money never travels as a float or an integer")

    def test_new_financial_shapes_use_money_amount(self):
        def ref_of(schema, prop):
            p = self.schemas[schema]["properties"][prop]
            return (p.get("$ref") or p.get("allOf", [{}])[0].get("$ref", "")).split("/")[-1]
        for schema, prop in (("Task", "amount"), ("UnverifiedPaymentRequest", "amount"), ("UnverifiedPayment", "amount"),
                             ("PowerLimitDecision", "amount"), ("PowerLimitEffective", "max_amount"),
                             ("PowerLimitCap", "max_amount")):
            self.assertEqual("MoneyAmount", ref_of(schema, prop), f"{schema}.{prop}")

    def test_power_limit_decision_keeps_three_distinct_outcomes(self):
        s = self.schemas["PowerLimitDecision"]
        self.assertEqual(["sangam.power-decision/1"], s["properties"]["contract_version"]["enum"])
        self.assertEqual(["PERMIT", "DENY"], s["properties"]["decision"]["enum"])
        self.assertEqual(["DENY", "LIMITED", "UNLIMITED"], self.schemas["PowerLimitEffective"]["properties"]["kind"]["enum"])
        effective = s["properties"]["effective"]
        self.assertEqual("PowerLimitEffective",
                         (effective.get("$ref") or effective.get("allOf", [{}])[0].get("$ref", "")).split("/")[-1])
        for member in ("contract_version", "decision", "effective", "applicable_limits", "mandatory_caps", "reason"):
            self.assertIn(member, s["required"], member)
        problem = self.schemas["Problem"]["properties"]["powerLimit"]
        self.assertEqual("#/components/schemas/PowerLimitDecision", problem["allOf"][0]["$ref"])

    def test_typed_value_decimal_is_decimal_string_text(self):
        self.assertIn("Decimal", self.schemas["TypedValue"]["properties"]["type"]["enum"])
        self.assertIn("DecimalString", self.schemas["TypedValue"]["properties"]["value"]["description"])


if __name__ == "__main__":
    unittest.main()
