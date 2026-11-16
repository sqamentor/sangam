# SPDX-License-Identifier: Apache-2.0
"""Unit tests of duplicate_classes.py (RM-31). Run: python -I -m unittest discover -s .github/scripts (governance.yml)."""
import contextlib
import io
import os
import tempfile
import unittest
import zipfile

import duplicate_classes as dc


def jar_bytes(classes):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for c in classes:
            z.writestr(c, b"\xca\xfe\xba\xbe")
    return buf.getvalue()


class DuplicateClasses(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def fat(self, nested, classes=()):
        path = os.path.join(self.tmp.name, "sangam-server-0.1.0.jar")
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            for c in classes:
                z.writestr("BOOT-INF/classes/" + c, b"\xca\xfe\xba\xbe")
            for name, cls in nested.items():
                z.writestr("BOOT-INF/lib/" + name, jar_bytes(cls))
        return path

    def run_main(self, *argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = dc.main(list(argv))
        return code, out.getvalue()

    LOG = ["org/apache/commons/logging/Log.class", "org/apache/commons/logging/LogFactory.class"]

    def test_two_commons_logging_implementations_fail(self):
        jar = self.fat({"commons-logging-1.3.6.jar": self.LOG, "jcl-over-slf4j-2.0.20.jar": self.LOG,
                        "spring-core-7.0.jar": ["org/springframework/core/X.class"]})
        code, out = self.run_main("--jar", jar, "--package", "org.bouncycastle", "--package", "org.apache.commons.logging")
        self.assertEqual(1, code)
        self.assertIn("2 class(es) in commons-logging-1.3.6.jar and jcl-over-slf4j-2.0.20.jar", out)

    def test_two_crypto_providers_fail(self):
        bc = ["org/bouncycastle/jce/provider/BouncyCastleProvider.class"]
        jar = self.fat({"bcprov-jdk18on-1.84.jar": bc, "bcprov-lts8on-2.73.8.jar": bc})
        self.assertEqual(1, self.run_main("--jar", jar, "--package", "org.bouncycastle")[0])

    def test_one_owner_per_class_passes(self):
        jar = self.fat({"commons-logging-1.3.6.jar": self.LOG, "slf4j-api-2.0.20.jar": ["org/slf4j/Logger.class"],
                        # multi-release copies inside one jar are the same owner
                        "bcprov-jdk18on-1.84.jar": ["org/bouncycastle/A.class", "META-INF/versions/11/org/bouncycastle/A.class"]})
        code, out = self.run_main("--jar", jar, "--package", "org.bouncycastle", "--package", "org.apache.commons.logging")
        self.assertEqual(0, code, out)

    def test_a_duplicate_outside_the_watched_packages_is_not_judged(self):
        jar = self.fat({"a.jar": ["com/example/X.class"], "b.jar": ["com/example/X.class"]})
        self.assertEqual(0, self.run_main("--jar", jar, "--package", "org.bouncycastle")[0])

    def test_application_classes_count_as_an_owner(self):
        jar = self.fat({"commons-logging-1.3.6.jar": self.LOG}, classes=["org/apache/commons/logging/Log.class"])
        code, out = self.run_main("--jar", jar, "--package", "org.apache.commons.logging")
        self.assertEqual(1, code)
        self.assertIn("BOOT-INF/classes", out)

    def test_missing_or_thin_jar_is_blocked(self):
        self.assertEqual(2, self.run_main("--jar", os.path.join(self.tmp.name, "none.jar"), "--package", "org.x")[0])
        thin = os.path.join(self.tmp.name, "thin.jar")
        with zipfile.ZipFile(thin, "w") as z:
            z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        self.assertEqual(2, self.run_main("--jar", thin, "--package", "org.x")[0])


class Wiring(unittest.TestCase):
    """RM-31: the pull-request licence gate and the release both run the check over the four fat jars by name, and
    the results ledger requires its line."""
    REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

    def read(self, rel):
        with open(os.path.join(self.REPO, *rel.split("/")), encoding="utf-8") as f:
            return f.read()

    def test_both_workflows_run_it_over_each_fat_jar(self):
        for wf in (".github/workflows/licence-check.yml", ".github/workflows/release.yml"):
            text = self.read(wf)
            self.assertIn("--check duplicate-classes", text, wf)
            step = text[text.index("per fat jar (RM-31)"):]
            step = step[:step.index("- name:")] if "- name:" in step else step
            self.assertIn("for id in server control-plane gateway rollup; do", step, wf)
            self.assertIn('--expect "$j"', step, wf)
            self.assertIn("--package org.bouncycastle --package org.apache.commons.logging", step, wf)
            self.assertNotIn("find ", step, wf)

    def test_the_ledger_requires_it_on_a_release_and_a_pull_request(self):
        import json
        sets = json.loads(self.read("sangam/deploy/licence/release-checks.json"))
        self.assertIn("duplicate-classes", sets["release"])
        self.assertIn("duplicate-classes", sets["pr-licence-check"])

    def test_the_sshd_logging_bridge_is_excluded_at_its_source(self):
        pom = self.read("sangam/sangam-integration/pom.xml")
        sftp = pom[pom.index("<artifactId>sshd-sftp</artifactId>"):]
        sftp = sftp[:sftp.index("</dependency>")]
        self.assertIn("<artifactId>jcl-over-slf4j</artifactId>", sftp,
                      "the sshd parent pom gives every sshd module jcl-over-slf4j: exclude it on sshd-sftp")


if __name__ == "__main__":
    unittest.main()
