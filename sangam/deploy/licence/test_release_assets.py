# SPDX-License-Identifier: Apache-2.0
"""Unit tests of sbom_matches_jar.py (CR-03) and notice_bundle.py (CR-07, CR-20); M-147.

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml). Written in Wave 4,
run in the test phase."""
import hashlib
import io
import json
import os
import tempfile
import unittest
import zipfile

import notice_bundle as nb
import sbom_matches_jar as sm

HERE = os.path.dirname(os.path.abspath(__file__))
POLICY = nb.load_json(os.path.join(HERE, "licence-policy.json"))


def lib(group, name, version, *lic, qual="", expr=None):
    licenses = [{"expression": expr}] if expr else [{"license": {"id": x}} for x in lic]
    purl = f"pkg:maven/{group}/{name}@{version}{qual}"
    return {"type": "library", "group": group, "name": name, "version": version, "purl": purl, "bom-ref": purl,
            "licenses": licenses}


def bom(*comps, deps=None):
    b = {"bomFormat": "CycloneDX", "specVersion": "1.6", "components": list(comps)}
    if deps is not None:
        b["dependencies"] = deps
    return b


def inner_jar(notice=None):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        if notice:
            z.writestr("META-INF/NOTICE.txt", notice)
    return buf.getvalue()


def fat_jar(path, libs):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("BOOT-INF/classes/x.class", b"")
        for name, notice in libs.items():
            z.writestr(f"BOOT-INF/lib/{name}", inner_jar(notice))


A = lib("org.a", "a", "1.0", "Apache-2.0")
B = lib("org.b", "b", "2.0", "MIT")
Q = lib("io.n", "q", "4.2", "Apache-2.0", qual="?classifier=linux-x86_64&type=jar")
M = lib("org.mariadb.jdbc", "mariadb-java-client", "3.5.1", "LGPL-2.1")
POM = dict(lib("org.x", "bom", "1", "MIT", qual="?type=pom"))
ME = {"type": "library", "group": "io.github.sqamentor", "name": "sangam-core", "version": "0.1.0",
      "purl": "pkg:maven/io.github.sqamentor/sangam-core@0.1.0", "licenses": [{"license": {"id": "Apache-2.0"}}]}


class Match(unittest.TestCase):
    def test_exact_match(self):
        f, ex, tool = sm.match(bom(A, B, Q, POM), ["a-1.0.jar", "b-2.0.jar", "q-4.2-linux-x86_64.jar"])
        self.assertEqual((f, ex, tool), ([], set(), []))

    def test_shipped_but_not_in_sbom(self):
        f, _, _ = sm.match(bom(A), ["a-1.0.jar", "stray-1.jar"])
        self.assertEqual(f, ["stray-1.jar: SHIPPED BUT NOT IN SBOM"])

    def test_in_sbom_but_not_shipped(self):
        f, _, _ = sm.match(bom(A, B), ["a-1.0.jar"])
        self.assertIn("IN SBOM BUT NOT SHIPPED", f[0])

    def test_excluded_from_jar_and_tooling(self):
        f, ex, tool = sm.match(bom(A, M), ["a-1.0.jar", "spring-boot-jarmode-tools-4.1.2.jar"],
                               ["pkg:maven/org.mariadb.jdbc/mariadb-java-client@"], ["spring-boot-jarmode-tools-"])
        self.assertEqual(f, [])
        self.assertEqual(ex, {M["purl"]})
        self.assertEqual(tool, ["spring-boot-jarmode-tools-4.1.2.jar"])

    def repo_with(self, d, comp, manifest):
        """Writes the component's jar, with this manifest, at its Maven repository path under d."""
        folder = os.path.join(d, *comp["group"].split("."), comp["name"], comp["version"])
        os.makedirs(folder)
        with zipfile.ZipFile(os.path.join(folder, f"{comp['name']}-{comp['version']}.jar"), "w") as z:
            z.writestr("META-INF/MANIFEST.MF", manifest)

    def test_spring_boot_starter_jar_type_is_omitted_not_a_finding(self):
        # licence-check on main, 2026-11-14: 14 spring-boot-starter-* components "IN SBOM BUT NOT SHIPPED". The Spring
        # Boot plugin never writes a dependencies-starter jar to BOOT-INF/lib, so the SBOM of the artefact drops it.
        s = lib("org.springframework.boot", "spring-boot-starter-jdbc", "4.1.2", "Apache-2.0")
        with tempfile.TemporaryDirectory() as d:
            self.repo_with(d, s, "Manifest-Version: 1.0\r\nSpring-Boot-Jar-Type: dependencies-starter\r\n")
            self.assertIn("IN SBOM BUT NOT SHIPPED", sm.match(bom(A, s), ["a-1.0.jar"])[0][0])  # no repository: finding
            omitted = {}
            f, ex, _ = sm.match(bom(A, s), ["a-1.0.jar"], boot_repo=d, omitted=omitted)
            self.assertEqual((f, ex, omitted), ([], {s["purl"]}, {s["purl"]: "dependencies-starter"}))

    def test_only_the_manifest_excuses_a_missing_jar(self):
        # the name alone excuses nothing: a starter-named jar without the manifest line, a jar that is not in the
        # repository, and an ordinary library that is missing all stay findings
        s = lib("org.springframework.boot", "spring-boot-starter-web", "4.1.2", "Apache-2.0")
        with tempfile.TemporaryDirectory() as d:
            self.repo_with(d, s, "Manifest-Version: 1.0\n")
            self.repo_with(d, B, "Manifest-Version: 1.0\nSpring-Boot-Jar-Type: something-else\n")
            f, ex, _ = sm.match(bom(A, B, s, Q), ["a-1.0.jar"], boot_repo=d)
            self.assertEqual(len(f), 3, f)
            self.assertEqual(ex, set())

    def test_cli_drops_the_omitted_starter_from_the_artefact_sbom(self):
        s = lib("org.springframework.boot", "spring-boot-starter", "4.1.2", "Apache-2.0")
        with tempfile.TemporaryDirectory() as d:
            repo = os.path.join(d, "repo")
            self.repo_with(repo, s, "Spring-Boot-Jar-Type: dependencies-starter\n")
            fat_jar(os.path.join(d, "app.jar"), {"a-1.0.jar": None})
            with open(os.path.join(d, "in.json"), "w", encoding="utf-8") as f:
                json.dump(bom(A, s, deps=[{"ref": A["purl"], "dependsOn": [s["purl"]]}]), f)
            with open(os.path.join(d, "dists.json"), "w", encoding="utf-8") as f:
                json.dump({"distributions": [{"id": "x"}]}, f)
            args = [os.path.join(d, "in.json"), "--distribution", "x", "--distributions", os.path.join(d, "dists.json"),
                    "--out", os.path.join(d, "out.json")]
            self.assertEqual(sm.main(args + ["--jar", os.path.join(d, "app.jar"), "--maven-repo", repo]), 0)
            with open(os.path.join(d, "out.json"), encoding="utf-8") as f:
                out = json.load(f)
            self.assertEqual([c["name"] for c in out["components"]], ["a"])
            self.assertEqual(out["dependencies"], [{"ref": A["purl"], "dependsOn": []}])
            # the same input without a repository that holds the jar is still a mismatch
            self.assertEqual(sm.main(args + ["--jar", os.path.join(d, "app.jar"), "--maven-repo", d]), 1)

    def test_prune_drops_component_and_edges(self):
        b = bom(A, M, deps=[{"ref": A["purl"], "dependsOn": [M["purl"]]}, {"ref": M["purl"], "dependsOn": []}])
        out = sm.prune(b, {M["purl"]})
        self.assertEqual([c["purl"] for c in out["components"]], [A["purl"]])
        self.assertEqual(out["dependencies"], [{"ref": A["purl"], "dependsOn": []}])

    def test_cli(self):
        with tempfile.TemporaryDirectory() as d:
            jar, sp, out = os.path.join(d, "s.jar"), os.path.join(d, "s.json"), os.path.join(d, "o.json")
            fat_jar(jar, {"a-1.0.jar": None, "spring-boot-jarmode-tools-4.1.2.jar": None})
            with open(sp, "w", encoding="utf-8") as f:
                json.dump(bom(A, M), f)
            self.assertEqual(sm.main([sp, "--distribution", "server", "--jar", jar, "--out", out]), 0)
            with open(out, encoding="utf-8") as f:
                self.assertEqual([c["name"] for c in json.load(f)["components"]], ["a"])
            self.assertEqual(sm.main([sp, "--distribution", "gateway", "--jar", jar]), 1)
            thin = os.path.join(d, "thin.jar")
            with zipfile.ZipFile(thin, "w") as z:
                z.writestr("x.class", b"")
            self.assertEqual(sm.main([sp, "--distribution", "server", "--jar", thin]), 2)
            self.assertEqual(sm.main([sp, "--distribution", "nope", "--jar", jar]), 2)

    def test_select_lib_ext_from_server_sbom(self):
        sel = sm.select(bom(A, M, deps=[{"ref": A["purl"], "dependsOn": [M["purl"]]}]),
                        ["pkg:maven/org.mariadb.jdbc/mariadb-java-client@"])
        self.assertEqual([c["purl"] for c in sel["components"]], [M["purl"]])
        self.assertEqual(sel["dependencies"], [])
        with tempfile.TemporaryDirectory() as d:
            lib_ext, sp, out = os.path.join(d, "lib-ext"), os.path.join(d, "s.json"), os.path.join(d, "o.json")
            os.makedirs(lib_ext)
            for n in ("mariadb-java-client-3.5.1.jar", "mariadb-java-client-3.5.1-sources.jar"):
                open(os.path.join(lib_ext, n), "wb").close()
            with open(sp, "w", encoding="utf-8") as f:
                json.dump(bom(A, M), f)
            self.assertEqual(sm.main([sp, "--distribution", "lib-ext", "--dir", lib_ext, "--out", out]), 0)
            with open(out, encoding="utf-8") as f:
                self.assertEqual([c["name"] for c in json.load(f)["components"]], ["mariadb-java-client"])
            open(os.path.join(lib_ext, "stray-1.jar"), "wb").close()
            self.assertEqual(sm.main([sp, "--distribution", "lib-ext", "--dir", lib_ext]), 1)
            with open(sp, "w", encoding="utf-8") as f:
                json.dump(bom(A), f)
            self.assertEqual(sm.main([sp, "--distribution", "lib-ext", "--dir", lib_ext]), 2)


class CommittedTexts(unittest.TestCase):
    """deploy/licence/texts (W4-C rounds 3 to 5): every file is recorded in SOURCES.json with its SHA-256, and every
    licence id the policy can let through has a text (LicenseRef-BSD-stax-utils: decision 233(2);
    LicenseRef-Public-Domain: decision 234)."""

    def test_every_text_is_recorded_with_its_hash(self):
        texts = os.path.join(HERE, "texts")
        rec = nb.load_json(os.path.join(texts, "SOURCES.json"))["texts"]
        files = {f[:-4] for f in os.listdir(texts) if f.endswith(".txt")}
        self.assertEqual(files, set(rec))
        for lic, r in rec.items():
            with open(os.path.join(texts, f"{lic}.txt"), "rb") as f:
                data = f.read()
            self.assertEqual(r["sha256"], hashlib.sha256(data).hexdigest().upper(), lic)
            self.assertGreater(len(data), 300, lic)  # no "404: Not Found" stub
            self.assertTrue(r["source"] and r["artefact_sha256"], lic)

    def test_every_policy_licence_has_a_text(self):
        ids = set()
        for t in (POLICY["allowed"] + [c["license"] for c in POLICY["conditional"]]
                  + [e["elected"] for e in POLICY["elections"]]):
            ids.update(p.strip() for p in t.split(" WITH "))
        have = {f[:-4] for f in os.listdir(os.path.join(HERE, "texts")) if f.endswith(".txt")} | {"Apache-2.0"}
        self.assertEqual(set(), ids - have)


class Notices(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        d = self.tmp.name
        self.root, self.texts, self.out = os.path.join(d, "root"), os.path.join(d, "texts"), os.path.join(d, "out")
        os.makedirs(self.root)
        os.makedirs(self.texts)
        for name, text in (("LICENSE", "Apache text"), ("NOTICE", "Sangam NOTICE")):
            with open(os.path.join(self.root, name), "w", encoding="utf-8") as f:
                f.write(text)

    def tearDown(self):
        self.tmp.cleanup()

    def test_missing_text_fails_and_writes_nothing(self):
        missing = nb.build(bom(A, B), POLICY, "server", self.root, self.texts, self.out)
        self.assertEqual(missing, ["MIT"])
        self.assertFalse(os.path.exists(self.out))

    def test_bundle_with_jar_notices(self):
        with open(os.path.join(self.texts, "MIT.txt"), "w", encoding="utf-8") as f:
            f.write("MIT text")
        jar = os.path.join(self.tmp.name, "s.jar")
        fat_jar(jar, {"a-1.0.jar": "Notice of A", "b-2.0.jar": None, "a2-1.0.jar": "Notice of A"})
        self.assertEqual(nb.build(bom(A, B, ME), POLICY, "server", self.root, self.texts, self.out, jar=jar), [])
        with open(os.path.join(self.out, "NOTICE"), encoding="utf-8") as f:
            notice = f.read()
        self.assertTrue(notice.startswith("Sangam NOTICE"))
        self.assertEqual(notice.count("Notice of A"), 1)
        self.assertEqual(sorted(os.listdir(os.path.join(self.out, "licenses"))), ["Apache-2.0.txt", "MIT.txt"])
        with open(os.path.join(self.out, "THIRD-PARTY.txt"), encoding="utf-8") as f:
            third = f.read()
        self.assertIn("pkg:maven/org.b/b@2.0", third)
        self.assertNotIn("io.github.sqamentor", third)

    def test_jar_notice_reproduced_in_the_repository_notice_is_not_repeated(self):
        with open(os.path.join(self.root, "NOTICE"), "w", encoding="utf-8", newline="\r\n") as f:
            f.write("Sangam NOTICE\n\nOperaton NOTICE, verbatim:\nNotice of A\nsecond line\n")
        jar = os.path.join(self.tmp.name, "s.jar")
        fat_jar(jar, {"a-1.0.jar": "Notice of A\r\nsecond line\r\n", "c-1.0.jar": "Notice of A\nother line"})
        self.assertEqual(nb.build(bom(A), POLICY, "server", self.root, self.texts, self.out, jar=jar), [])
        with open(os.path.join(self.out, "NOTICE"), encoding="utf-8", newline="") as f:
            notice = f.read()
        self.assertNotIn("==== NOTICE of a-1.0.jar", notice)
        self.assertIn("==== NOTICE of c-1.0.jar ====\nNotice of A\nother line", notice)  # a near copy is kept
        self.assertNotIn("\r", notice)

    def test_committed_root_notice_aggregates_the_operaton_notice(self):
        with open(os.path.join(HERE, "..", "..", "..", "NOTICE"), encoding="utf-8") as f:
            notice = f.read().replace("\r\n", "\n")
        for line in ("Operaton\nCopyright Andreas Klein, Andreas Zill, Tim Zöller\n",
                     "Camunda\nCopyright 2013-2024 Camunda Services GmbH\n",
                     "This product includes software developed by The Apache Software\nFoundation (http://www.apache.org/).",
                     "This product includes software developed by Odysseus Software GmbH\n(http://www.odysseus.de/).",
                     "This product includes software developed by Quartz\n(http://www.quartz-scheduler.org/).",
                     "THIRD-PARTY-LICENSES-<version>.txt"):
            self.assertIn(line, notice)

    def test_with_expression_needs_both_texts_and_election_wins(self):
        c = lib("org.c", "c", "1", expr="EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0")
        self.assertEqual(nb.used_licences(c, nb.Checker(POLICY, True))[1],
                         ["EPL-2.0", "GPL-2.0-only", "Classpath-exception-2.0"])
        elected = POLICY["elections"][0]
        purl = elected["purls"][0]
        g, rest = purl[len("pkg:maven/"):].split("/", 1)
        n, v = rest.split("@")
        e = lib(g, n, v, expr="EPL-2.0 OR LGPL-2.1")
        self.assertEqual(nb.used_licences(e, nb.Checker(POLICY, True))[1], [elected["elected"]])

    def test_attribution_text_for_a_component_without_its_own(self):
        with open(os.path.join(self.texts, "MIT.txt"), "w", encoding="utf-8") as f:
            f.write("MIT text")
        att = os.path.join(self.tmp.name, "att")
        os.makedirs(att)
        with open(os.path.join(att, "attributions.json"), "w", encoding="utf-8") as f:
            json.dump({"_comment": "x", "pkg:maven/org.b/b@2.0": "b.txt", "pkg:maven/org.z/z@9": "z.txt"}, f)
        with open(os.path.join(att, "b.txt"), "w", encoding="utf-8") as f:
            f.write("Copyright (c) B authors")
        b = dict(B, purl=B["purl"] + "?type=jar")  # SBOM qualifiers do not hide the match
        self.assertEqual(nb.build(bom(A, b), POLICY, "server", self.root, self.texts, self.out, attributions=att), [])
        with open(os.path.join(self.out, "NOTICE"), encoding="utf-8") as f:
            notice = f.read()
        self.assertIn("==== Licence and copyright of pkg:maven/org.b/b@2.0", notice)
        self.assertIn("Copyright (c) B authors", notice)
        self.assertNotIn("org.z", notice)  # listed but not in this SBOM: nothing added

    def test_listed_attribution_file_missing_is_blocked(self):
        att = os.path.join(self.tmp.name, "att")
        os.makedirs(att)
        with open(os.path.join(att, "attributions.json"), "w", encoding="utf-8") as f:
            json.dump({"pkg:maven/org.b/b@2.0": "b.txt"}, f)
        with self.assertRaises(nb.InputError):
            nb.build(bom(A, B), POLICY, "server", self.root, self.texts, self.out, attributions=att)
        self.assertFalse(os.path.exists(self.out))

    def test_committed_attributions_resolve(self):
        att = os.path.join(HERE, "attributions")
        listed = {k: v for k, v in nb.load_json(os.path.join(att, "attributions.json")).items() if not k.startswith("_")}
        self.assertIn("pkg:maven/com.thoughtworks.paranamer/paranamer@2.8.3", listed)
        with open(os.path.join(att, listed["pkg:maven/io.github.x-stream/mxparser@1.2.2"]), encoding="utf-8") as f:
            self.assertIn("This product includes software developed by the Indiana University Extreme! Lab", f.read())
        for purl, name in listed.items():
            with open(os.path.join(att, name), encoding="utf-8") as f:
                self.assertIn("copyright", f.read().lower(), purl)

    def test_cli_blocked_without_notice(self):
        os.remove(os.path.join(self.root, "NOTICE"))
        sp = os.path.join(self.tmp.name, "s.json")
        with open(sp, "w", encoding="utf-8") as f:
            json.dump(bom(A), f)
        self.assertEqual(nb.main([sp, "--distribution", "server", "--root", self.root, "--out", self.out]), 2)


if __name__ == "__main__":
    unittest.main()
