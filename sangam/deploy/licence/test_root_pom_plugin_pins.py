# SPDX-License-Identifier: Apache-2.0
"""RM-45, CR-10/CR-12: the build plugins that no module declares, but that a lifecycle or a workflow calls by prefix
(site, dependency, help) or by coordinate (makeBom, versions), are pinned in the root pom pluginManagement to an exact
version, so no run picks one by itself and an offline run resolves the cached one."""
import os
import re
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT_POM = os.path.join(HERE, "..", "..", "pom.xml")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

PINNED = {
    ("org.apache.maven.plugins", "maven-site-plugin"),
    ("org.apache.maven.plugins", "maven-dependency-plugin"),
    ("org.apache.maven.plugins", "maven-help-plugin"),
    ("org.cyclonedx", "cyclonedx-maven-plugin"),
    ("org.codehaus.mojo", "versions-maven-plugin"),
}


def managed_plugins(pom_path):
    root = ET.parse(pom_path).getroot()
    props = {el.tag.split("}")[1]: (el.text or "").strip() for el in root.find("m:properties", NS)}
    out = {}
    for p in root.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS):
        group = (p.findtext("m:groupId", "org.apache.maven.plugins", NS) or "").strip()
        artifact = p.findtext("m:artifactId", "", NS).strip()
        version = (p.findtext("m:version", "", NS) or "").strip()
        m = re.fullmatch(r"\$\{([^}]+)}", version)
        out[(group, artifact)] = props.get(m.group(1), version) if m else version
    return out


class RootPomPluginPins(unittest.TestCase):
    def test_every_prefix_or_lifecycle_plugin_is_pinned_to_an_exact_version(self):
        managed = managed_plugins(ROOT_POM)
        for key in sorted(PINNED):
            self.assertIn(key, managed, f"{key[0]}:{key[1]} is not pinned in the root pom pluginManagement")
            self.assertRegex(managed[key], r"^\d+(\.\d+)+$", f"{key[1]} needs an exact version, not {managed[key]!r}")

    def test_the_site_plugin_is_the_version_the_maven_wrapper_binds(self):
        # Maven 3.9.9 (sangam/.mvn/wrapper) binds maven-site-plugin 3.12.1 in its maven-core components.xml
        with open(os.path.join(HERE, "..", "..", ".mvn", "wrapper", "maven-wrapper.properties"), encoding="utf-8") as f:
            self.assertIn("apache-maven/3.9.9/", f.read(), "the wrapper changed: re-check the bound site plugin")
        self.assertEqual("3.12.1", managed_plugins(ROOT_POM)[("org.apache.maven.plugins", "maven-site-plugin")])


if __name__ == "__main__":
    unittest.main()
