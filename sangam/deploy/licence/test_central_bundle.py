# SPDX-License-Identifier: Apache-2.0
"""Unit tests of central_bundle.py (M-019, decisions 105, 181).

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml). Written in Wave 4b,
run in the test phase."""
import os
import tempfile
import unittest
import zipfile

import central_bundle as cb

NS = "io.github.sqamentor"
V = "0.1.0"
META = ("<name>Sangam</name><description>d</description><url>https://example.invalid/sangam</url>"
        "<licenses><license><name>Apache-2.0</name></license></licenses>"
        "<developers><developer><id>o</id></developer></developers><scm><url>https://example.invalid/scm</url></scm>")


def pom(artifact, packaging="jar", group=NS, version=V, parent=None, meta=""):
    p = f"<parent><groupId>{group}</groupId><artifactId>{parent}</artifactId><version>{version}</version></parent>" if parent else ""
    g = "" if parent else f"<groupId>{group}</groupId>"
    return (f'<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>{p}{g}'
            f"<artifactId>{artifact}</artifactId>{'' if parent else f'<version>{version}</version>'}"
            f"<packaging>{packaging}</packaging>{meta}</project>")


def put(root, group, artifact, version, files):
    d = os.path.join(root, *group.split("."), artifact, version)
    os.makedirs(d, exist_ok=True)
    for name, content in files.items():
        with open(os.path.join(d, name), "w", encoding="utf-8") as f:
            f.write(content)
    return d


def good_repo(root):
    put(root, NS, "sangam-parent", V, {f"sangam-parent-{V}.pom": pom("sangam-parent", "pom", meta=META),
                                       "maven-metadata-local.xml": "<m/>"})
    put(root, NS, "sangam-core", V, {f"sangam-core-{V}.pom": pom("sangam-core", parent="sangam-parent",
                                                                  meta="<name>core</name>"),
                                     f"sangam-core-{V}.jar": "j", f"sangam-core-{V}-sources.jar": "s",
                                     f"sangam-core-{V}-javadoc.jar": "d", f"sangam-core-{V}.jar.sha1": "stale"})


class Prepare(unittest.TestCase):
    def test_complete_bundle_passes_and_strips_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            good_repo(d)
            self.assertEqual(cb.prepare(d, NS, V), [])
            left = [os.path.basename(f) for f in cb.files_under(d)]
            self.assertFalse(any(n.startswith("maven-metadata") or n.endswith(".sha1") for n in left))

    def test_namespace_must_be_chosen_by_the_owner(self):
        with tempfile.TemporaryDirectory() as d:
            good_repo(d)
            for g in ("", "com.example", "org.sangam", "org.sangam.extra"):  # decision 210: only io.github.sqamentor
                with self.assertRaises(cb.Blocked):
                    cb.prepare(d, g, V)
            self.assertEqual(cb.main(["prepare", d, "--group", "", "--version", V]), 2)

    def test_component_outside_the_namespace_and_wrong_version_fail(self):
        with tempfile.TemporaryDirectory() as d:
            good_repo(d)
            put(d, "org.sangam", "sangam-old", V, {f"sangam-old-{V}.pom": pom("sangam-old", "pom", group="org.sangam", meta=META)})
            out = cb.prepare(d, NS, "0.1.1")
            self.assertTrue(any("org.sangam:sangam-old" in m and f"is not the chosen namespace {NS}" in m for m in out))
            self.assertTrue(any("is not the release version 0.1.1" in m for m in out))

    def test_missing_sources_javadoc_and_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            put(d, NS, "sangam-bom", V, {f"sangam-bom-{V}.pom": pom("sangam-bom", "pom")})
            put(d, NS, "sangam-api", V, {f"sangam-api-{V}.pom": pom("sangam-api", meta=META), f"sangam-api-{V}.jar": "j"})
            out = cb.prepare(d, NS, V)
            self.assertTrue(any("sangam-api-0.1.0-sources.jar missing" in m for m in out))
            self.assertTrue(any("sangam-api-0.1.0-javadoc.jar missing" in m for m in out))
            self.assertFalse(any("sangam-bom-0.1.0-sources.jar" in m for m in out))  # packaging pom: no jars needed
            self.assertTrue(any("sangam-bom" in m and "<licenses>" in m for m in out))
            self.assertEqual(cb.main(["prepare", d, "--group", NS, "--version", V]), 1)

    def test_name_is_not_inherited(self):
        with tempfile.TemporaryDirectory() as d:
            good_repo(d)
            put(d, NS, "sangam-x", V, {f"sangam-x-{V}.pom": pom("sangam-x", "pom", parent="sangam-parent")})
            out = cb.prepare(d, NS, V)
            self.assertEqual(out, ["io.github.sqamentor:sangam-x:0.1.0: POM <name> missing (own pom)"])


class Finish(unittest.TestCase):
    def test_unsigned_fails_unless_dry_run(self):
        with tempfile.TemporaryDirectory() as d:
            good_repo(d)
            cb.prepare(d, NS, V)
            self.assertTrue(cb.finish(d))
            self.assertFalse([f for f in cb.files_under(d) if f.endswith(".md5")])  # nothing written on failure
            self.assertEqual(cb.finish(d, unsigned_dry_run=True), [])

    def test_signed_bundle_gets_md5_and_sha1_for_every_file(self):
        with tempfile.TemporaryDirectory() as d:
            good_repo(d)
            cb.prepare(d, NS, V)
            for f in list(cb.files_under(d)):
                with open(f + ".asc", "w", encoding="ascii") as s:
                    s.write("sig")
            self.assertEqual(cb.finish(d), [])
            names = {os.path.relpath(f, d) for f in cb.files_under(d)}
            jar = os.path.join(*NS.split("."), "sangam-core", V, f"sangam-core-{V}.jar")
            for ext in (".asc", ".md5", ".sha1", ".asc.md5", ".asc.sha1"):
                self.assertIn(jar + ext, names)
            with open(os.path.join(d, jar + ".sha1"), encoding="ascii") as f:
                self.assertEqual(f.read(), "5c2dd944dde9e08881bef0894fe7b22a5c9c4b06")  # sha1 of "j"


class Zip(unittest.TestCase):
    def test_deterministic_layout(self):
        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as o:
            good_repo(d)
            cb.prepare(d, NS, V)
            a, b = os.path.join(o, "a.zip"), os.path.join(o, "b.zip")
            cb.make_zip(d, a)
            cb.make_zip(d, b)
            with open(a, "rb") as fa, open(b, "rb") as fb:
                self.assertEqual(fa.read(), fb.read())
            with zipfile.ZipFile(a) as z:
                self.assertIn(f"io/github/sqamentor/sangam-core/{V}/sangam-core-{V}.jar", z.namelist())


if __name__ == "__main__":
    unittest.main()
