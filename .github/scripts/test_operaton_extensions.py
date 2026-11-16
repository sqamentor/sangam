# SPDX-License-Identifier: Apache-2.0
"""RM-52: the Operaton extension inventory is generated and current. Run by governance.yml
(python -I -m unittest discover -s .github/scripts)."""
import contextlib
import io
import os
import tempfile
import unittest

import operaton_extensions as oe

BPMN = """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
             xmlns:flowable="http://flowable.org/bpmn" id="d">
  <process id="p" isExecutable="true" operaton:historyTimeToLive="P30D">
    <serviceTask id="s" operaton:class="a.B" flowable:async="true">
      <extensionElements><operaton:field name="x"><operaton:string>1</operaton:string></operaton:field></extensionElements>
    </serviceTask>
  </process>
</definitions>
"""
DMN = """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:operaton="http://operaton.org/schema/1.0/dmn"
             id="d" name="d" namespace="n"><decision id="x" operaton:historyTimeToLive="P1D"/></definitions>
"""


class Inventory(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def put(self, rel, text):
        p = os.path.join(self.root, *rel.split("/"))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(text)

    def test_every_extension_is_listed_with_its_model_element_and_count(self):
        self.put("mod-a/src/main/resources/p.bpmn", BPMN)
        self.put("mod-b/src/main/resources/d.dmn", DMN)
        text = oe.render(self.root)
        self.assertIn("Model files scanned: 2 (BPMN 1, DMN 1, CMMN 0). Files with an Operaton extension: 2.", text)
        self.assertIn("| `operaton:field` | element | bpmn | extensionElements | 1 | 1 |", text)
        self.assertIn("| `operaton:string` | element | bpmn | field | 1 | 1 |", text)
        self.assertIn("| `operaton:class` | attribute | bpmn | serviceTask | 1 | 1 |", text)
        self.assertIn("| `operaton:historyTimeToLive` | attribute | bpmn | process | 1 | 1 |", text)
        self.assertIn("| `operaton:historyTimeToLive` | attribute | dmn | decision | 1 | 1 |", text)
        self.assertIn("| mod-a | 1 | 4 |", text)
        self.assertIn("| mod-b | 1 | 1 |", text)
        # another engine's namespace is a second migration target, so it is named, never folded in
        self.assertIn("| http://flowable.org/bpmn | mod-a/src/main/resources/p.bpmn | 1 |", text)

    def test_test_sources_and_build_output_are_left_out(self):
        self.put("mod-a/src/test/resources/p.bpmn", BPMN)
        self.put("mod-a/target/classes/p.bpmn", BPMN)
        self.put("mod-a/target-ci22/classes/p.bpmn", BPMN)
        self.put("ui/node_modules/x/p.bpmn", BPMN)
        self.assertIn("Model files scanned: 0 (BPMN 0, DMN 0, CMMN 0)", oe.render(self.root))

    def test_an_unreadable_model_or_a_dtd_is_blocked(self):
        self.put("m/a.bpmn", "<definitions")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(2, oe.main(["--check", "--root", self.root, "--out", os.path.join(self.root, "o.md")]))
        self.put("m/a.bpmn", '<?xml version="1.0"?><!DOCTYPE d [<!ENTITY e "x">]><d>&e;</d>')
        with self.assertRaises(oe.Blocked):
            oe.render(self.root)

    def test_check_fails_when_stale_and_passes_after_write(self):
        self.put("m/p.bpmn", BPMN)
        out = os.path.join(self.root, "docs", "INV.md")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, oe.main(["--check", "--root", self.root, "--out", out]), "a missing inventory is stale")
            self.assertEqual(0, oe.main(["--write", "--root", self.root, "--out", out]))
            self.assertEqual(0, oe.main(["--check", "--root", self.root, "--out", out]))
            self.put("m/q.bpmn", BPMN)
            self.assertEqual(1, oe.main(["--check", "--root", self.root, "--out", out]), "a new model makes it stale")

    def test_the_committed_inventory_is_current(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            code = oe.main(["--check"])
        self.assertEqual(0, code, out.getvalue())


if __name__ == "__main__":
    unittest.main()
