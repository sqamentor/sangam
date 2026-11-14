# SPDX-License-Identifier: Apache-2.0
"""Unit tests of backup_image_guard.py (decision 129, RM-34), with helm replaced by a fake.

Run: python -I -m unittest discover -s sangam/deploy/helm -p "test_*.py" (governance.yml). Written in Wave 4,
run in the test phase."""
import os
import unittest

import backup_image_guard as g

HERE = os.path.dirname(os.path.abspath(__file__))
CHART = os.path.join(HERE, "sangam")


def fake(defaults=None, message="Error: backup.{db}.image is required"):
    """A helm stand-in: fails when the image is set empty, unless defaults[db] says the chart has a default."""
    defaults = defaults or {}

    def run(chart, values, *sets):
        db = os.path.basename(values)[len("values-"):-len(".yaml")]
        if sets and not defaults.get(db):
            return 1, "", message.format(db=db)
        image = defaults.get(db) if sets else g.committed_image(values)
        return 0, f'kind: CronJob\n          image: "{image}"\n', ""
    return run


class Guard(unittest.TestCase):
    def test_committed_examples_name_their_images(self):
        for db in g.TYPES:
            self.assertTrue(g.committed_image(os.path.join(CHART, "examples", f"values-{db}.yaml")), db)

    def test_pass_when_required(self):
        self.assertEqual(g.check(CHART, run=fake()), [])

    def test_schema_style_message_counts(self):
        self.assertEqual(g.check(CHART, run=fake(message="at '/backup/{db}/image': minLength: got 0, want 1")), [])

    def test_default_image_fails(self):
        out = g.check(CHART, run=fake(defaults={"mysql": "mysql:8.4"}))
        self.assertEqual(len(out), 1)
        self.assertIn("mysql", out[0])

    def test_unrelated_error_fails(self):
        out = g.check(CHART, run=fake(message="Error: something else"))
        self.assertEqual(len(out), 3)

    def test_committed_values_default_is_empty(self):
        with open(os.path.join(CHART, "values.yaml"), encoding="utf-8") as f:
            text = f.read()
        block = text[text.index("\nbackup:"):]
        for db in g.TYPES:
            section = block[block.index(f"\n  {db}:"):]
            image_line = next(line for line in section.splitlines()[1:] if line.strip().startswith("image:"))
            self.assertEqual(image_line.split(":", 1)[1].split("#")[0].strip(), '""', db)


if __name__ == "__main__":
    unittest.main()
