# SPDX-License-Identifier: Apache-2.0
"""Unit tests of release_dist.py (the distributions.json reader of release.yml; M-147).

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml). Written in Wave 4,
run in the test phase."""
import contextlib
import io
import os
import unittest

import release_dist as rd

HERE = os.path.dirname(os.path.abspath(__file__))


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = rd.main(list(argv))
    return code, out.getvalue().splitlines()


class ReleaseDist(unittest.TestCase):
    def test_field_substitutes_version(self):
        self.assertEqual(run("field", "server", "sbom", "--version", "0.1.0"), (0, ["sangam-server-0.1.0.cdx.json"]))
        self.assertEqual(run("field", "server-distroless-image", "image", "--version", "0.1.0"),
                         (0, ["sangam-server:0.1.0-distroless"]))

    def test_list_field_one_per_line(self):
        code, lines = run("field", "lib-ext", "select")
        self.assertEqual(code, 0)
        self.assertEqual(lines, ["pkg:maven/org.mariadb.jdbc/mariadb-java-client@"])

    def test_unknown_id_or_key_is_exit_2(self):
        self.assertEqual(run("field", "nope", "sbom")[0], 2)
        self.assertEqual(run("field", "server", "nope")[0], 2)
        self.assertEqual(run("scope", "nope")[0], 2)

    def test_scope(self):
        self.assertEqual(run("scope", "pdf-validator-image"), (0, ["pdf-validator"]))
        self.assertEqual(run("scope", "server-image"), (0, ["server-image"]))

    def test_ids_by_kind(self):
        code, images = run("ids", "--kind", "image")
        self.assertEqual(code, 0)
        self.assertIn("operator-image", images)
        self.assertIn("ocr-image", images)  # W4-D decision 167, sangam-ocr sidecar
        self.assertTrue(all(i.endswith("-image") for i in images))
        self.assertEqual(len(images), 12)

    def test_opt_in_add_ons_are_lib_dirs(self):
        # decisions 217 and 232: esign and nats ship beside the server fat jar, each reduced to its own jars
        code, lib_dirs = run("ids", "--kind", "lib-dir")
        self.assertEqual(code, 0)
        self.assertEqual(sorted(lib_dirs), ["esign", "nats", "pdf-validator"])
        self.assertEqual(run("field", "nats", "select")[1],
                         ["pkg:maven/io.nats/jnats@", "pkg:maven/org.bouncycastle/bcprov-lts8on@"])
        self.assertEqual(run("field", "nats", "sbom", "--version", "0.1.0"), (0, ["sangam-nats-0.1.0.cdx.json"]))


if __name__ == "__main__":
    unittest.main()
