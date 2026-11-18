# SPDX-License-Identifier: Apache-2.0
"""Unit tests of register_check.py and register_export.py (CR-08, CR-09, M-148 new-transitive diff).

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml). Written in Wave 4,
run in the test phase."""
import csv
import json
import os
import tempfile
import unittest

import register_check as rc
import register_export as rx

HERE = os.path.dirname(os.path.abspath(__file__))
DISTS = rc.load_json(os.path.join(HERE, "distributions.json"))

REGISTER = {"components": {
    "pkg:maven/org.example/ok@1.0": {"decision": "ACCEPTED FOR DEFINED USE", "distributions": ["server"], "spdx": "MIT"},
    "pkg:maven/org.example/cond@2.0": {"decision": "ACCEPTED WITH CONDITIONS", "distributions": ["server", "gateway"], "spdx": "EPL-2.0"},
    "pkg:maven/org.example/review@3.0": {"decision": "REVIEW REQUIRED", "distributions": ["server"], "spdx": "X"},
    "pkg:npm/%40scope/lib@1.2.3": {"decision": "ACCEPTED FOR DEFINED USE", "distributions": ["ui"], "spdx": "MIT"},
}}


def bom(*purls):
    return {"bomFormat": "CycloneDX", "components": [{"type": "library", "purl": p} for p in purls]}


class Scope(unittest.TestCase):
    def test_images_map_to_their_distribution(self):
        self.assertEqual(rc.register_scope("server-image", DISTS), "server")
        self.assertEqual(rc.register_scope("server-distroless-image", DISTS), "server")
        self.assertEqual(rc.register_scope("pdf-validator-image", DISTS), "pdf-validator")
        self.assertEqual(rc.register_scope("gateway", DISTS), "gateway")

    def test_unknown_distribution_is_blocked(self):
        with self.assertRaises(rc.InputError):
            rc.register_scope("nope", DISTS)


class Check(unittest.TestCase):
    def test_accepted_in_scope_passes(self):
        f, out = rc.check(bom("pkg:maven/org.example/ok@1.0", "pkg:maven/org.example/cond@2.0?type=jar"), REGISTER, "server")
        self.assertEqual(f, [])
        self.assertEqual(out, [])

    def test_new_component_fails(self):
        f, _ = rc.check(bom("pkg:maven/org.example/ok@1.1"), REGISTER, "server")
        self.assertEqual(len(f), 1)
        self.assertIn("NOT IN REGISTER", f[0])

    def test_wrong_distribution_fails(self):
        f, _ = rc.check(bom("pkg:maven/org.example/ok@1.0"), REGISTER, "gateway")
        self.assertIn("NOT ACCEPTED FOR gateway", f[0])

    def test_review_required_fails(self):
        f, _ = rc.check(bom("pkg:maven/org.example/review@3.0"), REGISTER, "server")
        self.assertIn("REVIEW REQUIRED", f[0])

    def test_first_party_and_os_packages_are_not_judged_here(self):
        f, out = rc.check(bom("pkg:maven/io.github.sqamentor/sangam-core@0.1.0", "pkg:npm/%40sangam/ui@0.1.0",
                              "pkg:deb/debian/libc6@2.36"), REGISTER, "server")
        self.assertEqual((f, out), ([], []))

    def test_other_types_are_reported_not_hidden(self):
        f, out = rc.check(bom("pkg:golang/example.com/x@v1.0.0"), REGISTER, "server")
        self.assertEqual(f, [])
        self.assertEqual(out, ["pkg:golang/example.com/x@v1.0.0"])

    def test_nested_components_are_checked(self):
        b = bom("pkg:maven/org.example/ok@1.0")
        b["components"][0]["components"] = [{"type": "library", "purl": "pkg:maven/org.example/hidden@9"}]
        f, _ = rc.check(b, REGISTER, "server")
        self.assertIn("hidden@9", f[0])

    def test_diff(self):
        added, removed = rc.diff(bom("pkg:maven/a/b@2", "pkg:maven/c/d@1"), bom("pkg:maven/a/b@1", "pkg:maven/c/d@1"))
        self.assertEqual((added, removed), (["pkg:maven/a/b@2"], ["pkg:maven/a/b@1"]))


class Cli(unittest.TestCase):
    def run_cli(self, sbom, *extra):
        with tempfile.TemporaryDirectory() as d:
            sp, rp = os.path.join(d, "s.json"), os.path.join(d, "r.json")
            with open(sp, "w", encoding="utf-8") as f:
                f.write(sbom if isinstance(sbom, str) else json.dumps(sbom))
            with open(rp, "w", encoding="utf-8") as f:
                json.dump(REGISTER, f)
            return rc.main([sp, "--distribution", "server", "--register", rp, *extra])

    def test_exit_codes(self):
        self.assertEqual(self.run_cli(bom("pkg:maven/org.example/ok@1.0")), 0)
        self.assertEqual(self.run_cli(bom("pkg:maven/org.example/zzz@1.0")), 1)
        self.assertEqual(self.run_cli("not json"), 2)
        self.assertEqual(self.run_cli({"components": []}), 2)


class Export(unittest.TestCase):
    def test_export_reads_distribution_rows_and_classifiers(self):
        with tempfile.TemporaryDirectory() as d:
            mp, np_ = os.path.join(d, "m.csv"), os.path.join(d, "n.csv")
            with open(mp, "w", encoding="utf-8", newline="") as f:
                w = csv.writer(f)
                w.writerow(["gav", "scopes", "spdx", "decision"])
                w.writerow(["g:a:1.0", "use=distribution [server-fatjar; gateway-fatjar]", "MIT", "ACCEPTED FOR DEFINED USE"])
                w.writerow(["g:q:2.0:linux-x86_64", "use=distribution [server-fatjar]", "Apache-2.0", "ACCEPTED FOR DEFINED USE"])
                w.writerow(["g:t:1.0", "use=test", "MIT", "ACCEPTED FOR DEFINED USE"])
                w.writerow(["g:c:1.0", "use=distribution [cli-shaded-jar (built; not a release asset)]", "MIT", "ACCEPTED FOR DEFINED USE"])
            with open(np_, "w", encoding="utf-8", newline="") as f:
                w = csv.writer(f)
                w.writerow(["ecosystem", "name", "version", "usage_scope", "spdx", "decision"])
                w.writerow(["npm", "@s/x", "1.0.0", "distribution (bundled into the UI dist)", "MIT", "ACCEPTED FOR DEFINED USE"])
                w.writerow(["npm", "dev", "1.0.0", "build", "MIT", "ACCEPTED FOR DEFINED USE"])
                # UP-L6: an optional peer installed as a devDependency is in the ui SBOM although vite never bundles it
                w.writerow(["npm", "peer", "2.0.0", "devDependency (direct); listed in the ui SBOM (devOptional)",
                            "Apache-2.0", "ACCEPTED FOR DEFINED USE"])
            out = rx.build(mp, np_)["components"]
        self.assertEqual(sorted(out), ["pkg:maven/g/a@1.0", "pkg:maven/g/c@1.0", "pkg:maven/g/q@2.0", "pkg:npm/%40s/x@1.0.0",
                                       "pkg:npm/peer@2.0.0"])
        self.assertEqual(out["pkg:npm/peer@2.0.0"]["distributions"], ["ui"])
        self.assertEqual(out["pkg:maven/g/a@1.0"]["distributions"], ["gateway", "server"])
        self.assertEqual(out["pkg:npm/%40s/x@1.0.0"]["distributions"], ["ui"])

    def test_committed_export_is_well_formed(self):
        data = rc.load_json(os.path.join(HERE, "decision-register.json"))
        ids = {d["id"] for d in DISTS["distributions"]} | set(DISTS["not_released"])
        for purl, e in data["components"].items():
            self.assertTrue(purl.startswith(("pkg:maven/", "pkg:npm/")), purl)
            self.assertNotIn("?", purl)
            self.assertTrue(set(e["distributions"]) <= ids, purl)
            self.assertTrue(e["decision"])


if __name__ == "__main__":
    unittest.main()
