# SPDX-License-Identifier: Apache-2.0
"""Owner decision 381(1): the default server ships JGit (package Git sync stays off by default), so the server POM
declares it for the runtime, sangam-integration keeps it optional (no other module inherits it), and the licence
register accepts JGit and its one new transitive (JavaEWAH) for the server distribution."""
import json
import pathlib
import unittest
import xml.etree.ElementTree as ET

SANGAM = pathlib.Path(__file__).resolve().parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
JGIT = ("org.eclipse.jgit", "org.eclipse.jgit")


def jgit_dependency(pom: pathlib.Path):
    root = ET.parse(pom).getroot()
    for dep in root.findall("./m:dependencies/m:dependency", NS):
        ga = (dep.findtext("m:groupId", "", NS).strip(), dep.findtext("m:artifactId", "", NS).strip())
        if ga == JGIT:
            return dep.findtext("m:scope", "compile", NS).strip(), dep.findtext("m:optional", "false", NS).strip()
    return None


class JgitShippedTest(unittest.TestCase):
    def test_the_server_declares_jgit_for_its_runtime(self):
        self.assertEqual(("compile", "false"), jgit_dependency(SANGAM / "sangam-server" / "pom.xml"),
                         "decision 381(1): JGit ships in the default server (compile scope, not optional)")

    def test_jgit_stays_optional_in_sangam_integration(self):
        self.assertEqual(("compile", "true"), jgit_dependency(SANGAM / "sangam-integration" / "pom.xml"))

    def test_the_register_accepts_jgit_and_javaewah_for_the_server(self):
        register = json.loads((SANGAM / "deploy" / "licence" / "decision-register.json").read_text(
            encoding="utf-8"))["components"]
        version = ET.parse(SANGAM / "pom.xml").getroot().findtext("./m:properties/m:version.jgit", "", NS).strip()
        for purl, spdx in ((f"pkg:maven/org.eclipse.jgit/org.eclipse.jgit@{version}", "BSD-3-Clause"),
                           ("pkg:maven/com.googlecode.javaewah/JavaEWAH@1.2.3", "Apache-2.0")):
            entry = register.get(purl)
            self.assertIsNotNone(entry, f"{purl} has no register row")
            self.assertEqual("ACCEPTED FOR DEFINED USE", entry["decision"])
            self.assertIn("server", entry["distributions"])
            self.assertEqual(spdx, entry["spdx"])


if __name__ == "__main__":
    unittest.main()
