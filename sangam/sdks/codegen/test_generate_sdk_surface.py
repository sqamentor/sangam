# SPDX-License-Identifier: Apache-2.0
"""Unit tests of the SDK surface generator (M-020). Run: python -m pytest sdks/codegen (or python -m unittest)."""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import yaml  # noqa: E402

import contract_ir as ir  # noqa: E402
import emit_dotnet  # noqa: E402
import emit_java  # noqa: E402
import emit_python  # noqa: E402
import generate_sdk_surface as gen  # noqa: E402


def doc(schemas, paths=None):
    return {"openapi": "3.1.0", "info": {"version": "9.9.9"}, "paths": paths or {},
            "components": {"schemas": schemas, "parameters": {
                "page": {"name": "page", "in": "query", "schema": {"type": "integer"}},
                "size": {"name": "size", "in": "query", "schema": {"type": "integer"}},
                "IdempotencyKey": {"name": "Idempotency-Key", "in": "header", "schema": {"type": "string"}}},
                "responses": {}}}


class TypeMapping(unittest.TestCase):
    def test_scalar_and_container_kinds(self):
        c = ir.build(doc({"M": {"type": "object", "required": ["a"], "properties": {
            "a": {"type": "number"}, "b": {"type": "number", "format": "double"}, "c": {"type": "integer"},
            "d": {"type": "integer", "format": "int32"}, "e": {"type": "string", "format": "date-time"},
            "f": {"type": "string", "format": "date"}, "g": {"type": "string", "enum": ["X", "Y"]},
            "h": {"type": "array", "items": {"type": "string"}}, "i": {"type": "object", "additionalProperties": True},
            "j": {"type": "object", "additionalProperties": {"type": "integer"}}, "k": {},
            "l": {"anyOf": [{"type": "string", "enum": ["A"]}, {"type": "string"}]},
            "m": {"anyOf": [{"type": "integer"}, {"type": "string"}]}}}}))
        kinds = {p.wire: p.type for p in c.models["M"].props}
        self.assertEqual(kinds["a"].kind, "decimal", "money is never a binary float (R33)")
        self.assertEqual(kinds["b"].kind, "double")
        self.assertEqual(kinds["c"].kind, "int64", "an integer without format must not overflow int32")
        self.assertEqual(kinds["d"].kind, "int32")
        self.assertEqual(kinds["e"].kind, "datetime")
        self.assertEqual(kinds["f"].kind, "date")
        self.assertEqual(kinds["g"].enum, ("X", "Y"))
        self.assertEqual((kinds["h"].kind, kinds["h"].item.kind), ("array", "string"))
        self.assertEqual((kinds["i"].kind, kinds["i"].item.kind), ("map", "any"))
        self.assertEqual((kinds["j"].kind, kinds["j"].item.kind), ("map", "int64"))
        self.assertEqual(kinds["k"].kind, "any")
        self.assertEqual(kinds["l"].kind, "string")
        self.assertEqual(kinds["m"].kind, "any")
        self.assertTrue(c.models["M"].props[0].required)

    def test_aliases_are_resolved_and_single_allof_is_the_ref(self):
        c = ir.build(doc({
            "Money": {"type": "string"},
            "Vars": {"type": "object", "additionalProperties": {"anyOf": [{"$ref": "#/components/schemas/TV"}, {}]}},
            "TV": {"type": "object", "properties": {"type": {"type": "string"}}},
            "W": {"type": "object", "properties": {"amount": {"$ref": "#/components/schemas/Money"},
                                                   "vars": {"$ref": "#/components/schemas/Vars"},
                                                   "tv": {"allOf": [{"$ref": "#/components/schemas/TV"}],
                                                          "description": "d"}}}}))
        self.assertNotIn("Money", c.models)
        self.assertNotIn("Vars", c.models)
        w = {p.wire: p.type for p in c.models["W"].props}
        self.assertEqual(w["amount"].kind, "string")
        self.assertEqual(w["vars"].kind, "map")
        self.assertEqual((w["tv"].kind, w["tv"].name), ("ref", "TV"))

    def test_inline_objects_are_hoisted_with_deterministic_unique_names(self):
        c = ir.build(doc({
            "AB": {"type": "object", "properties": {"c": {"type": "object", "properties": {"x": {"type": "string"}}}}},
            "A": {"type": "object", "properties": {"bC": {"type": "object", "properties": {"y": {"type": "string"}}}}},
        }))
        self.assertIn("ABC", c.models)
        self.assertIn("ABC2", c.models, "a second, different inline schema with the same hint gets its own name")
        self.assertEqual({p.wire for p in c.models["ABC"].props} | {p.wire for p in c.models["ABC2"].props}, {"x", "y"})

    def test_operations_paging_raw_and_common_headers(self):
        paths = {"/items/{id}/rows": {"get": {"operationId": "listRows", "tags": ["T"], "parameters": [
            {"name": "id", "in": "path", "required": True, "schema": {"type": "string"}},
            {"$ref": "#/components/parameters/page"}, {"$ref": "#/components/parameters/size"},
            {"$ref": "#/components/parameters/IdempotencyKey"},
            {"name": "If-Match", "in": "header", "required": True, "schema": {"type": "string"}}],
            "responses": {"200": {"content": {"application/json": {"schema": {
                "type": "array", "items": {"$ref": "#/components/schemas/M"}}}}}}}},
            "/export": {"get": {"operationId": "export", "tags": ["T"], "responses": {"200": {"content": {
                "text/csv": {"schema": {"type": "string"}}}}}}}}
        c = ir.build(doc({"M": {"type": "object", "properties": {"a": {"type": "string"}}}}, paths))
        rows, export = c.operations
        self.assertTrue(rows.response.paged)
        self.assertEqual(rows.common_headers, ["Idempotency-Key"])
        self.assertEqual([p.wire for p in rows.header_params], ["If-Match"])
        self.assertEqual([p.wire for p in rows.required_params], ["If-Match"])
        self.assertEqual((export.response.kind, export.response.media), ("raw", "text/csv"))


class Citations(unittest.TestCase):
    def test_decision_ids_are_removed_from_generated_docs(self):
        # audit X2-03: the contract does not say which register a bare id belongs to; the SDK docs carry none
        self.assertEqual(ir.uncite("Lifecycle fields (FC-52, R37)."), "Lifecycle fields (R37).")
        self.assertEqual(ir.uncite("Per ADR-D-20 and D-32 rules"), "Per and rules")
        self.assertEqual(ir.uncite("see (D-0)"), "see")
        self.assertEqual(ir.uncite("R13, VI.3 rule 2, SL-16"), "R13, VI.3 rule 2, SL-16")
        self.assertEqual(ir.uncite("ISO-8601, UTF-8"), "ISO-8601, UTF-8")


class Split(unittest.TestCase):
    def test_large_groups_split_by_sub_path_and_small_ones_stay(self):
        import api_groups
        ops = []
        paths = [f"/reg/parties/p{i}" for i in range(8)] + ["/reg/x/{id}/ack", "/reg/y"]
        for i, p in enumerate(paths):
            ops.append(ir.Operation(f"op{i}", "Reg", "GET", p, "", "", [], [], [], [], None,
                                    ir.Response("none", "204"), False, False, "", False))
        c = ir.Contract("1", {}, ops, ["Reg"], len(paths))
        groups = api_groups.plan(c, lambda cc, g: 100 * len(g.ops))   # 100 lines per operation: 1,000 > 800
        names = {g.cls: len(g.ops) for g in groups}
        self.assertEqual(names, {"RegApi": 2, "RegPartiesApi": 8})
        self.assertTrue(all(100 * len(g.ops) <= api_groups.LIMIT for g in groups))

    def test_every_generated_class_is_under_the_limit(self):
        import json
        with open(os.path.join(gen.SDKS, gen.MANIFEST), encoding="utf-8") as fh:
            m = json.load(fh)
        over = [c for c in m["apiClasses"] if c["maxLines"] > m["lineLimit"]]
        self.assertEqual(over, [], "M-098 class-size gate (X2-04)")
        self.assertEqual(sum(c["operations"] for c in m["apiClasses"]), m["operations"])


class Identifiers(unittest.TestCase):
    def test_keywords_are_escaped(self):
        self.assertEqual(emit_java.ident("default"), "default_")
        self.assertEqual(emit_java.ident("hashCode"), "hashCode_", "a record accessor must not clash with Object")
        self.assertEqual(emit_python.ident("from"), "from_")
        self.assertEqual(emit_python.ident("hub.verify_token"), "hub_verify_token")
        self.assertEqual(emit_python.ident("getTaskById"), "get_task_by_id")
        self.assertEqual(emit_dotnet.method_name("fetchAndLock"), "FetchAndLockAsync")

    def test_all_caps_names_become_one_lower_case_word(self):
        # M-125 ModelDiffSummary members ELEMENT..PROPERTY and the AI / SCIM tags: never eLEMENT, aI, sCIM
        self.assertEqual(ir.camel("ELEMENT"), "element")
        self.assertEqual(ir.camel("SCIM"), "scim")
        self.assertEqual(emit_java.ident("AI"), "ai")
        self.assertEqual(ir.camel("Idempotency-Key"), "idempotencyKey", "mixed-case names keep camel case")
        self.assertEqual(ir.camel("processInstanceId"), "processInstanceId")
        self.assertEqual(ir.camel("X-Sangam-Seat"), "xSangamSeat")
        self.assertEqual(ir.camel("2fa"), "n2fa", "a leading digit still gets the N prefix")


class RealContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(gen.CONTRACT, encoding="utf-8") as fh:
            cls.raw = yaml.safe_load(fh)
        cls.c = ir.build(cls.raw)

    def test_every_operation_exactly_once(self):
        ids = [op["operationId"] for item in self.raw["paths"].values() for m, op in item.items()
               if m in ("get", "post", "put", "patch", "delete")]
        self.assertEqual(sorted(ids), sorted(o.op_id for o in self.c.operations))
        self.assertEqual(self.c.path_count, len(self.raw["paths"]))

    def test_component_models_invent_no_field(self):
        for name, s in self.raw["components"]["schemas"].items():
            if name in self.c.models:
                self.assertEqual([p.wire for p in self.c.models[name].props], list((s.get("properties") or {}).keys()),
                                 name)
                self.assertEqual({p.wire for p in self.c.models[name].props if p.required}, set(s.get("required") or []),
                                 name)

    def test_generated_tree_is_up_to_date(self):
        self.assertEqual(gen.main(["--check"]), 0, "run sdks/codegen/generate_sdk_surface.py")


if __name__ == "__main__":
    unittest.main()
