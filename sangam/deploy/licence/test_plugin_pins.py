# SPDX-License-Identifier: Apache-2.0
"""RM-45 (X1 U-1, DR-07): build plugins the workflows or the site lifecycle run are pinned in the root pom.

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml)."""
import os
import re
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT_POM = os.path.normpath(os.path.join(HERE, "..", "..", "pom.xml"))
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def managed_plugins(pom_path):
    """{groupId:artifactId: version text} of the root pom's build/pluginManagement."""
    root = ET.parse(pom_path).getroot()
    out = {}
    for p in root.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS):
        g = (p.findtext("m:groupId", "org.apache.maven.plugins", NS) or "").strip()
        a = (p.findtext("m:artifactId", "", NS) or "").strip()
        out[f"{g}:{a}"] = (p.findtext("m:version", "", NS) or "").strip()
    return out


class PluginPins(unittest.TestCase):
    def test_rm45_the_site_plugin_is_pinned_to_a_literal_version(self):
        version = managed_plugins(ROOT_POM).get("org.apache.maven.plugins:maven-site-plugin")
        self.assertIsNotNone(version, "maven-site-plugin is not in the root pom pluginManagement (RM-45)")
        self.assertRegex(version, r"^\d+\.\d+(\.\d+)?$", "an exact version, never a range or a property left empty")

    def test_every_managed_plugin_has_a_version(self):
        props = {}
        root = ET.parse(ROOT_POM).getroot()
        for el in root.find("m:properties", NS) or []:
            props[el.tag.split("}", 1)[1]] = (el.text or "").strip()
        for coord, version in managed_plugins(ROOT_POM).items():
            resolved = re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), version)
            self.assertRegex(resolved, r"^\d+\.\d+(\.\d+)?([.-][\w.]+)?$", f"{coord} has no exact version ({version!r})")


if __name__ == "__main__":
    unittest.main()
