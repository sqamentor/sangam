# SPDX-License-Identifier: Apache-2.0
"""RM-38 (LIC-4/LIC-5): the LGPL MariaDB driver is an optional dependency of every module that declares it, so it never
reaches a module that depends on that one; only the image adds it, as a separate replaceable jar."""
import pathlib
import unittest
import xml.etree.ElementTree as ET

SANGAM = pathlib.Path(__file__).resolve().parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
LGPL = {("org.mariadb.jdbc", "mariadb-java-client")}


def lgpl_dependencies(pom: pathlib.Path):
    root = ET.parse(pom).getroot()
    for dep in root.findall("./m:dependencies/m:dependency", NS):
        ga = (dep.findtext("m:groupId", "", NS).strip(), dep.findtext("m:artifactId", "", NS).strip())
        if ga in LGPL:
            yield ga, dep.findtext("m:scope", "compile", NS).strip(), dep.findtext("m:optional", "false", NS).strip()


class LgplDriverOptionalTest(unittest.TestCase):
    def test_every_shipped_lgpl_driver_dependency_is_optional(self):
        found = 0
        for pom in sorted(SANGAM.glob("**/pom.xml")):
            if "target" in pom.parts or "node_modules" in pom.parts:
                continue
            for ga, scope, optional in lgpl_dependencies(pom):
                if scope == "test":
                    continue  # CI-only driver profiles (LIC-2/LIC-3) never ship
                found += 1
                self.assertEqual("true", optional, f"{pom.relative_to(SANGAM)}: {ga[0]}:{ga[1]} ({scope}) must be optional")
        self.assertGreater(found, 0, "no module declares the MariaDB driver: the check would prove nothing")


if __name__ == "__main__":
    unittest.main()
