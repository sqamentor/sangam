# SPDX-License-Identifier: Apache-2.0
"""M-022 drift guard: the hand-written wire types of the Tier-2 worker helpers (sdks/go/worker/model.go,
sdks/rust/sangam-worker/src/model.rs) say they follow sangam-rest/api/sangam-api.yaml field for field. Nothing
regenerates them, so a contract change (Wave 4 adds shapes for every lane) would leave them silently stale. This test
reads the contract and both files and fails on:

- a struct named like a contract schema whose JSON members differ from the schema's properties;
- a required member of a request body (a schema the worker sends, name ending in "Request") that the helper may drop
  (Go ``omitempty``, Rust ``Option``), which the server would answer with 400 VALIDATION_FAILED.

Run with the other generator tests: python -m unittest discover -s sangam/sdks/codegen -p "test_*.py".
"""
import os
import re
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
SDKS = os.path.abspath(os.path.join(HERE, ".."))
CONTRACT = os.path.join(SDKS, "..", "sangam-rest", "api", "sangam-api.yaml")
GO_MODEL = os.path.join(SDKS, "go", "worker", "model.go")
RUST_MODEL = os.path.join(SDKS, "rust", "sangam-worker", "src", "model.rs")


def _read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def go_structs(src):
    """name -> {json member: optional?} for every Go struct with json tags (optional = omitempty)."""
    out = {}
    for m in re.finditer(r"^type (\w+) struct \{\n(.*?)^\}", src, re.S | re.M):
        members = {}
        for tag in re.finditer(r'json:"([^",]+)((?:,[^"]*)?)"', m.group(2)):
            members[tag.group(1)] = "omitempty" in tag.group(2)
        out[m.group(1)] = members
    return out


def _camel(snake):
    return re.sub(r"_([a-z0-9])", lambda x: x.group(1).upper(), snake)


def rust_structs(src):
    """name -> {json member: optional?} for every Rust struct (serde rename_all camelCase, explicit rename, flatten
    members skipped: they carry the open RFC 9457 extension members, not a named property)."""
    out = {}
    for m in re.finditer(r"^pub struct (\w+) \{\n(.*?)^\}", src, re.S | re.M):
        members = {}
        attrs = ""
        for line in m.group(2).splitlines():
            s = line.strip()
            if s.startswith("#["):
                attrs += s
                continue
            f = re.match(r"pub (?:r#)?(\w+)\s*:\s*([^,]+),", s)
            if f:
                if "flatten" not in attrs:
                    rename = re.search(r'rename\s*=\s*"([^"]+)"', attrs)
                    members[rename.group(1) if rename else _camel(f.group(1))] = f.group(2).strip().startswith("Option<")
                attrs = ""
        out[m.group(1)] = members
    return out


def drift(structs, schemas):
    """Human-readable findings; empty when the helper matches the contract."""
    findings = []
    for name, members in sorted(structs.items()):
        schema = schemas.get(name)
        if schema is None:
            continue
        props = set(schema.get("properties", {}))
        missing, extra = sorted(props - set(members)), sorted(set(members) - props)
        if missing or extra:
            findings.append(f"{name}: missing {missing}, not in the contract {extra}")
        if name.endswith("Request"):
            droppable = sorted(r for r in schema.get("required", []) if members.get(r))
            if droppable:
                findings.append(f"{name}: required {droppable} may be left out")
    return findings


class Tier2WireTypesFollowTheContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schemas = yaml.safe_load(_read(CONTRACT))["components"]["schemas"]

    def test_go_worker_model(self):
        structs = go_structs(_read(GO_MODEL))
        self.assertIn("ExternalWork", structs, "parser found the Go wire types")
        self.assertEqual([], drift(structs, self.schemas))

    def test_rust_worker_model(self):
        structs = rust_structs(_read(RUST_MODEL))
        self.assertIn("ExternalWork", structs, "parser found the Rust wire types")
        self.assertEqual([], drift(structs, self.schemas))

    def test_every_request_the_helpers_send_is_covered(self):
        # the bodies the two loops post (fetch-and-lock, extend-lock, complete, fail, bpmn-error, outcome-unknown);
        # cancel-ack is CancelAckRequest in the contract (Rust has it; Go sends the same three members as a map)
        sent = {"FetchAndLockRequest", "TopicRequest", "ExtendLockRequest", "WorkCompleteRequest", "WorkFailRequest",
                "WorkBpmnErrorRequest", "OutcomeUnknownRequest"}
        for lang, structs in (("go", go_structs(_read(GO_MODEL))), ("rust", rust_structs(_read(RUST_MODEL)))):
            self.assertEqual(set(), sent - set(structs), lang)
            self.assertEqual(set(), sent - set(self.schemas), "contract schemas exist")

    def test_parser_reports_a_drift(self):
        schemas = {"XRequest": {"properties": {"a": {}, "b": {}}, "required": ["a"]}}
        go = 'type XRequest struct {\n\tA string `json:"a,omitempty"`\n\tC string `json:"c"`\n}\n'
        rs = ("pub struct XRequest {\n    #[serde(default)]\n    pub a: Option<String>,\n"
              "    #[serde(rename = \"b\")]\n    pub bee: String,\n}\n")
        self.assertEqual(["XRequest: missing ['b'], not in the contract ['c']", "XRequest: required ['a'] may be left out"],
                         drift(go_structs(go), schemas))
        self.assertEqual(["XRequest: required ['a'] may be left out"], drift(rust_structs(rs), schemas))


if __name__ == "__main__":
    unittest.main()
