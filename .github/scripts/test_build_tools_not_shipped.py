# SPDX-License-Identifier: Apache-2.0
"""Unit tests for build_tools_not_shipped.py (owner decision 2026-11-12). Run: python -I -m unittest discover -s .github/scripts"""
from __future__ import annotations

import io
import json
import re
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path

import build_tools_not_shipped as b

REPO = Path(__file__).resolve().parents[2]
REAL_POLICY = REPO / "sangam" / "deploy" / "licence" / "licence-policy.json"


class BuildToolsNotShippedTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.policy = self.dir / "policy.json"
        self.policy.write_text(json.dumps({"build_time_tools": [
            {"tool": "SpotBugs", "coordinate": "com.github.spotbugs:spotbugs:4.10.5", "never_ship": "pkg:maven/com.github.spotbugs/spotbugs@"},
            {"tool": "FindSecBugs", "coordinate": "com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0", "never_ship": "pkg:maven/com.h3xstream.findsecbugs/findsecbugs-plugin"},
            {"tool": "Semgrep CLI", "coordinate": "pypi:semgrep==1.185.0", "never_ship": "pkg:pypi/semgrep@"},
            {"tool": "sonar-maven-plugin", "coordinate": "org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211", "never_ship": "pkg:maven/org.sonarsource.scanner.maven/"},
        ]}), encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def run_main(self, *args: str) -> tuple[int, str]:
        out = io.StringIO()
        with redirect_stdout(out):
            code = b.main(["--policy", str(self.policy), *args])
        return code, out.getvalue()

    def sbom(self, *purls: str) -> Path:
        p = self.dir / "x.cdx.json"
        p.write_text(json.dumps({"bomFormat": "CycloneDX", "components": [
            {"name": "outer", "purl": "pkg:maven/io.github.sqamentor/sangam-core@0.1.0",
             "components": [{"name": "n", "purl": u} for u in purls]}]}), encoding="utf-8")
        return p

    def jar(self, *entries: str) -> Path:
        p = self.dir / "app.jar"
        with zipfile.ZipFile(p, "w") as z:
            for e in entries:
                z.writestr(e, b"x")
        return p

    def test_the_real_policy_lists_exactly_the_approved_tools_and_their_lgpl_artefacts(self):
        tools = b.load_tools(REAL_POLICY)
        self.assertEqual(["spotbugs", "findsecbugs-plugin", "semgrep", "sonar-maven-plugin",
                          "spotbugs-annotations", "sonar-scanner-java-library",
                          "sonar-scanner-java-library-batch-interface", "sonar-scanner-download-cache"],
                         [t.artifact for t in tools])
        policy = json.loads(REAL_POLICY.read_text(encoding="utf-8"))
        forbidden = {f["purl"] for f in policy["forbidden_components"]}
        jar_rules = [e["pattern"] for e in policy["forbidden_jar_entries"]]
        for t in tools:
            self.assertIn(t.never_ship, forbidden, f"{t.name}: never_ship is also a forbidden_components entry")
            if t.never_ship.startswith("pkg:maven/"):
                jar = f"BOOT-INF/lib/{t.artifact}-1.0.jar"
                self.assertTrue(any(re.search(p, jar) for p in jar_rules), f"{t.name}: a forbidden_jar_entries pattern bans {jar}")

    def test_exact_purls_do_not_catch_a_longer_artifact_name(self):
        tools = b.load_tools(REAL_POLICY)
        p = self.dir / "real.cdx.json"
        p.write_text(json.dumps({"bomFormat": "CycloneDX", "components": [
            {"name": "x", "purl": "pkg:maven/org.sonarsource.scanner.lib/sonar-scanner-java-library-batch-interface@4.1.2.1663"}]}),
            encoding="utf-8")
        found = b.check_sbom(p, tools)
        self.assertEqual(1, len(found), found)
        self.assertIn("sonar-scanner-java-library-batch-interface", found[0])

    def test_sbom_with_a_tool_fails_nested_components_included(self):
        code, out = self.run_main("--sbom", str(self.sbom("pkg:maven/com.github.spotbugs/spotbugs@4.10.5")))
        self.assertEqual(1, code)
        self.assertIn("is the build-time tool SpotBugs", out)
        code, out = self.run_main("--sbom", str(self.sbom("pkg:pypi/semgrep@1.185.0",
                                                          "pkg:maven/org.sonarsource.scanner.maven/sonar-maven-plugin@5.8.0.7211")))
        self.assertEqual(1, code)
        self.assertIn("2 found", out)

    def test_similar_names_are_not_tools(self):
        # the synthetic policy lists spotbugs, not spotbugs-maven-plugin (Apache-2.0): a longer name is not a match
        code, out = self.run_main("--sbom", str(self.sbom("pkg:maven/com.github.spotbugs/spotbugs-maven-plugin@4.10.5.0",
                                                          "pkg:pypi/semgrep-rules-helper@1.0")))
        self.assertEqual(0, code, out)
        jar = self.jar("BOOT-INF/lib/spotbugs-maven-plugin-4.10.5.0.jar", "BOOT-INF/lib/jackson-core-2.22.3.jar")
        self.assertEqual(0, self.run_main("--jar", str(jar))[0])

    def test_fat_jar_and_tree(self):
        jar = self.jar("BOOT-INF/lib/findsecbugs-plugin-1.14.0.jar", "BOOT-INF/classes/a.class")
        code, out = self.run_main("--jar", str(jar))
        self.assertEqual(1, code)
        self.assertIn("app.jar!BOOT-INF/lib/findsecbugs-plugin-1.14.0.jar", out)
        tree = self.dir / "image"
        (tree / "usr/lib/python3/site-packages/semgrep-1.185.0.dist-info").mkdir(parents=True)
        (tree / "opt/sangam/lib-ext").mkdir(parents=True)
        (tree / "opt/sangam/lib-ext/sonar-maven-plugin-5.8.0.7211.jar").write_bytes(b"x")
        code, out = self.run_main("--tree", str(tree))
        self.assertEqual(1, code)
        self.assertIn("Semgrep CLI", out)
        self.assertIn("sonar-maven-plugin", out)
        clean = self.dir / "clean"
        clean.mkdir()
        (clean / "mariadb-java-client-3.5.11.jar").write_bytes(b"x")
        self.assertEqual(0, self.run_main("--tree", str(clean))[0])

    def test_bad_input_is_exit_two(self):
        self.assertEqual(2, self.run_main("--sbom", str(self.dir / "absent.json"))[0])
        not_bom = self.dir / "n.json"
        not_bom.write_text("{}", encoding="utf-8")
        self.assertEqual(2, self.run_main("--sbom", str(not_bom))[0])
        self.policy.write_text("{}", encoding="utf-8")
        code, out = self.run_main("--sbom", str(self.sbom()))
        self.assertEqual(2, code)
        self.assertIn("no build_time_tools entries", out)


if __name__ == "__main__":
    unittest.main()
