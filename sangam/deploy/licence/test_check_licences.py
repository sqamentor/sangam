# SPDX-License-Identifier: Apache-2.0
"""Unit tests of check_licences.py (RM-17, RM-18, RM-19, CR-05, CR-06, CR-17; decisions 116-119, 123, 127).

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml). Written in Wave 4,
run in the test phase."""
import json
import os
import tempfile
import unittest
import zipfile

import check_licences as cl

HERE = os.path.dirname(os.path.abspath(__file__))
POLICY = cl.load_json(os.path.join(HERE, "licence-policy.json"))


def comp(purl, *names, expr=None, ctype="library"):
    lic = [{"expression": expr}] if expr else [{"license": {"name": n}} for n in names]
    group, _, rest = purl.partition("/")[2].rpartition("/")
    name, _, version = rest.partition("@")
    return {"type": ctype, "group": group, "name": name, "version": version, "purl": purl, "licenses": lic}


def check(components, **kw):
    ch = cl.Checker(POLICY, kw.pop("strict", True), **kw)
    for c in components:
        ch.check_component(c, "test")
    return ch


# The 27 strict failures of w3x1-evidence/simgate.txt (2026-11-12): (purl, names) as the POMs spell them.
SIMGATE = [
    ("pkg:maven/com.adobe.xmp/xmpcore@6.1.11", ["The BSD 3-Clause License (BSD3)"]),
    ("pkg:maven/com.github.luben/zstd-jni@1.5.6-4", ["BSD 2-Clause License"]),
    ("pkg:maven/com.networknt/json-schema-validator@1.5.1", ["Apache License Version 2.0"]),
    ("pkg:maven/com.opencsv/opencsv@5.11.2", ["Apache 2"]),
    ("pkg:maven/io.nats/jnats@2.26.4", ["The Apache License, Version 2.0"]),
    ("pkg:maven/io.opentelemetry.proto/opentelemetry-proto@1.10.0-alpha", ["The Apache License, Version 2.0"]),
    ("pkg:maven/io.opentelemetry.semconv/opentelemetry-semconv@1.41.1", ["The Apache License, Version 2.0"]),
] + [(f"pkg:maven/io.opentelemetry/{a}@1.62.0", ["The Apache License, Version 2.0"]) for a in (
    "opentelemetry-api", "opentelemetry-common", "opentelemetry-context", "opentelemetry-exporter-common",
    "opentelemetry-exporter-otlp-common", "opentelemetry-exporter-otlp", "opentelemetry-exporter-sender-okhttp",
    "opentelemetry-extension-trace-propagators", "opentelemetry-sdk-common",
    "opentelemetry-sdk-extension-autoconfigure-spi", "opentelemetry-sdk-logs", "opentelemetry-sdk-metrics",
    "opentelemetry-sdk-trace", "opentelemetry-sdk")] + [
    ("pkg:maven/org.apache.kafka/kafka-clients@3.9.2", ["The Apache License, Version 2.0"]),
    ("pkg:maven/org.cryptacular/cryptacular@1.3.0", ["Apache 2", "GNU Lesser General Public License"]),
    ("pkg:maven/org.eclipse.paho/org.eclipse.paho.client.mqttv3@1.2.5", ["Eclipse Public License - Version 2.0"]),
    ("pkg:maven/org.glassfish.jersey.core/jersey-server@4.0.3",
     ["EPL 2.0", "The GNU General Public License (GPL), Version 2, With Classpath Exception", "Apache License, 2.0", "Modified BSD"]),
    ("pkg:maven/org.glassfish.jersey.media/jersey-media-json-jackson@4.0.3",
     ["EPL 2.0", "The GNU General Public License (GPL), Version 2, With Classpath Exception", "Apache License, 2.0"]),
    ("pkg:maven/org.jspecify/jspecify@1.0.1", ["The Apache License, Version 2.0"]),
]


class PolicyShape(unittest.TestCase):
    def test_unknown_licences_fail_by_policy(self):
        self.assertEqual("fail", POLICY["unknown_license"], "decision 116 (C-01)")
        self.assertTrue(cl.Checker(POLICY, False).strict)

    def test_allowed_ids_are_spdx_or_licenseref(self):
        for a in POLICY["allowed"]:
            self.assertNotIn(a, ("EDL-1.0", "Bouncy-Castle", "GPL-2.0-with-classpath-exception"), "RM-18: SPDX ids only")

    def test_the_decision_127_set_is_exactly_thirteen_coordinates(self):
        regs = [e["register"] for e in POLICY["build_tool_exceptions"]]
        self.assertEqual({"DR-04": 2, "DR-05": 7, "DR-06": 4}, {r: regs.count(r) for r in set(regs)})
        self.assertTrue(all("@" in e["purl"] for e in POLICY["build_tool_exceptions"]), "exact versions only")

    def test_decision_229_test_scope_only(self):
        gpl = "GNU General Public License, version 2, with the Classpath Exception"  # POM name
        kit = [comp("pkg:maven/io.smallrye.classfile/jdk-classfile-backport@26", gpl),
               comp("pkg:maven/org.eclipse.sisu/org.eclipse.sisu.inject@1.0.1", "Eclipse Public License, Version 2.0"),
               comp("pkg:maven/org.eclipse.sisu/org.eclipse.sisu.plexus@1.0.1", "Eclipse Public License, Version 2.0")]
        self.assertEqual(3, len(POLICY["test_scope_exceptions"]))
        b = cl.Checker(POLICY, True, build_scope=True)
        for c in kit:
            b.check_component(c, "test")
        self.assertEqual([], b.errors)
        self.assertEqual(3, sum("decision 229 test scope" in n for n in b.notes))
        # never shipped: every one fails in a shipped SBOM, also at another version
        ch = check(kit + [comp("pkg:maven/org.eclipse.sisu/org.eclipse.sisu.inject@1.0.2", "Eclipse Public License, Version 2.0")])
        self.assertEqual(4, sum(e.startswith("TEST-SCOPE ONLY") and "decision 229" in e for e in ch.errors))


class Normalise(unittest.TestCase):
    def test_the_27_simgate_spellings_pass_strict(self):
        self.assertEqual(27, len(SIMGATE))
        # decision 232: jnats ships only in the nats add-on, so its spelling is judged in that distribution
        ch = check([comp(p, *names) for p, names in SIMGATE if "/io.nats/" not in p])
        self.assertEqual([], ch.errors)
        self.assertEqual([], check([comp(p, *names) for p, names in SIMGATE if "/io.nats/" in p], distribution="nats").errors)

    def test_case_and_space_insensitive(self):
        self.assertEqual("Apache-2.0", cl.normalise("  the apache   license, version 2.0 "))

    def test_old_non_spdx_ids_are_aliases(self):
        self.assertEqual("BSD-3-Clause", cl.normalise("EDL-1.0"))
        self.assertEqual("LicenseRef-Bouncy-Castle", cl.normalise("Bouncy-Castle"))
        self.assertEqual(cl.CPE_ONLY, cl.normalise("GPL-2.0-with-classpath-exception"))


class Expressions(unittest.TestCase):
    def test_and_binds_tighter_than_or(self):
        self.assertEqual([["MIT"], ["Apache-2.0", "Unicode-3.0"]], cl.expression_alternatives("MIT OR Apache-2.0 AND Unicode-3.0"))

    def test_parentheses_group(self):
        self.assertEqual([["MIT", "Unicode-3.0"], ["Apache-2.0", "Unicode-3.0"]],
                         cl.expression_alternatives("(MIT OR Apache-2.0) AND Unicode-3.0"))

    def test_classpath_exception(self):
        self.assertEqual([[cl.CPE_ONLY]], cl.expression_alternatives("GPL-2.0-only WITH Classpath-exception-2.0"))
        self.assertEqual([[cl.CPE_LATER]], cl.expression_alternatives("GPL-2.0-or-later WITH Classpath-exception-2.0"))

    def test_unbalanced_expression_is_an_error_not_a_pass(self):
        ch = check([comp("pkg:maven/a/b@1", expr="(MIT OR Apache-2.0")])
        self.assertTrue(any("BAD LICENCE EXPRESSION" in e for e in ch.errors))


class AndReading(unittest.TestCase):
    def test_a_plain_list_is_and_so_one_forbidden_term_fails(self):
        ch = check([comp("pkg:maven/x/y@1", "Apache-2.0", "GPL-3.0-only")])
        self.assertTrue(any("FORBIDDEN LICENCE" in e for e in ch.errors), ch.errors)

    def test_a_plain_list_of_allowed_terms_passes(self):
        self.assertEqual([], check([comp("pkg:maven/x/y@1", "Apache-2.0", "MIT")]).errors)

    def test_an_or_choice_without_an_election_is_refused(self):
        # decision 383 (RM-17): before, "GPL-3.0-only OR MIT" passed on its allowed alternative with no election
        for strict in (True, False):
            ch = check([comp("pkg:maven/x/y@1", expr="GPL-3.0-only OR MIT")], strict=strict)
            self.assertTrue(any(e.startswith("NO ELECTION") and "decision 383" in e for e in ch.errors), ch.errors)
        ch = check([comp("pkg:npm/x@1", expr="MIT OR Apache-2.0")])
        self.assertTrue(any(e.startswith("NO ELECTION") for e in ch.errors), "both allowed is still a choice: " + str(ch.errors))

    def test_unversioned_lgpl_spelling_is_forbidden_not_unknown(self):
        ch = check([comp("pkg:maven/x/y@1", "GNU Lesser General Public License")], strict=False)
        self.assertTrue(any("FORBIDDEN LICENCE" in e for e in ch.errors), ch.errors)


class Elections(unittest.TestCase):
    def test_logback_is_judged_by_its_election(self):
        ch = check([comp("pkg:maven/ch.qos.logback/logback-core@1.5.38", expr="EPL-2.0 OR LGPL-2.1-only")])
        self.assertEqual([], ch.errors)
        self.assertTrue(any("ELECTED" in n and "EPL-2.0" in n for n in ch.notes))

    def test_cryptacular_list_needs_its_election(self):
        self.assertEqual([], check([comp("pkg:maven/org.cryptacular/cryptacular@1.3.0", "Apache 2", "GNU Lesser General Public License")]).errors)
        ch = check([comp("pkg:maven/org.cryptacular/cryptacular@1.4.0", "Apache 2", "GNU Lesser General Public License")])
        self.assertTrue(ch.errors, "a new version has no election, so the AND reading fails")

    def test_an_elected_licence_that_is_not_offered_fails(self):
        ch = check([comp("pkg:maven/com.h2database/h2@2.3.232", "EPL-1.0")])
        self.assertTrue(any("ELECTION MISMATCH" in e for e in ch.errors), ch.errors)

    def test_verapdf_election_is_live_only_in_the_pdf_validator(self):
        c = comp("pkg:maven/org.verapdf/validation-model-jakarta@1.30.4", expr="GPL-3.0-or-later OR MPL-2.0")
        self.assertEqual([], check([c], distribution="pdf-validator").errors)
        self.assertTrue(any("ELECTION OUT OF SCOPE" in e for e in check([c], distribution="server").errors))


class NoElectionRefusal(unittest.TestCase):
    """Decision 383: the Rust class election, the one-by-one Jersey elections, then the refusal, shipped or not."""

    def test_a_build_scope_choice_without_an_election_is_refused_too(self):
        ch = check([comp("pkg:maven/x/build-tool@1", expr="EPL-1.0 OR LGPL-2.1-only")], build_scope=True)
        self.assertTrue(any(e.startswith("NO ELECTION (build)") for e in ch.errors), ch.errors)
        ok = check([comp("pkg:maven/ch.qos.logback/logback-core@1.5.38", expr="EPL-2.0 OR LGPL-2.1-only")], build_scope=True)
        self.assertEqual([], ok.errors)

    def test_a_single_licence_or_a_plain_list_is_not_a_choice(self):
        self.assertEqual([], check([comp("pkg:maven/x/y@1", expr="MIT")]).errors)
        self.assertEqual([], check([comp("pkg:maven/x/y@1", "Apache-2.0", "MIT")]).errors, "a list is AND, RM-18")
        self.assertEqual([], check([comp("pkg:maven/x/y@1", expr="MIT OR MIT")]).errors, "one distinct alternative")

    def test_rust_crates_offered_as_mit_or_apache_elect_apache(self):
        for expr in ("MIT OR Apache-2.0", "Apache-2.0 OR MIT", "(MIT OR Apache-2.0)"):
            ch = check([comp("pkg:cargo/serde@1.0.228", expr=expr)])
            self.assertEqual([], ch.errors, expr)
            self.assertTrue(any("ELECTED" in n and "Apache-2.0" in n and "383" in n for n in ch.notes), ch.notes)

    def test_the_rust_class_election_needs_exactly_that_choice_and_a_crate(self):
        for c in (comp("pkg:cargo/x@1", expr="MIT OR Apache-2.0 OR Zlib"),
                  comp("pkg:cargo/x@1", expr="MIT"),
                  comp("pkg:npm/x@1", expr="MIT OR Apache-2.0")):
            ch = check([c])
            if c["licenses"][0]["expression"] == "MIT":
                self.assertFalse(any("ELECTED" in n for n in ch.notes), "a single licence is not elected")
                continue
            self.assertTrue(any(e.startswith("NO ELECTION") for e in ch.errors), (c, ch.errors))

    def test_each_jersey_jar_has_its_own_election_with_the_evidence_of_its_texts(self):
        jars = ["containers/jersey-container-servlet", "core/jersey-client", "core/jersey-common", "core/jersey-server",
                "ext/jersey-bean-validation", "ext/jersey-entity-filtering", "ext/jersey-spring6", "inject/jersey-hk2"]
        for j in jars:
            g, a = j.split("/")
            purl = f"pkg:maven/org.glassfish.jersey.{g}/{a}@4.0.3"
            entries = [e for e in POLICY["elections"] if purl in e.get("purls", [])]
            self.assertEqual(1, len(entries), purl)
            e = entries[0]
            self.assertEqual([purl], e["purls"], "elected one by one")
            self.assertEqual("EPL-2.0", e["elected"])
            self.assertIn("383", e["decision"])
            self.assertIn("META-INF/NOTICE.md", e["evidence"])
            self.assertRegex(e["evidence"], r"SHA-256 [0-9a-f]{64}")
            ch = check([comp(purl, expr="EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0")])
            self.assertEqual([], ch.errors, purl)

    def test_annotate_records_a_class_election(self):
        import tempfile as tf
        with tf.TemporaryDirectory() as d:
            p = os.path.join(d, "b.json")
            with open(p, "w", encoding="utf-8") as f:
                json.dump({"bomFormat": "CycloneDX", "components": [comp("pkg:cargo/serde@1.0.228", expr="MIT OR Apache-2.0")]}, f)
            hit, elected = cl.annotate_sbom(p, POLICY)
            self.assertEqual(1, elected)


class PendingReview(unittest.TestCase):
    JERSEY = "pkg:maven/org.glassfish.jersey.core/jersey-client@4.0.3"

    def test_a_pending_entry_fails_until_reviewed(self):
        policy = json.loads(json.dumps(POLICY))
        policy["pending_review"] = [{"purl": self.JERSEY, "register": "DR-01", "reason": "test"}]
        ch = cl.Checker(policy, True)
        ch.check_component(comp(self.JERSEY, "EPL 2.0", "MIT license"), "test")
        self.assertTrue(any("REVIEW REQUIRED" in e and "DR-01" in e for e in ch.errors), ch.errors)

    def test_decision_176_closed_dr01_and_dr02(self):
        self.assertEqual(POLICY["pending_review"], [])
        # the ten-licence POM list of the Jersey jars is judged by the EPL-2.0 election only
        names = ("EPL 2.0", "GPL2 w/ CPE", "EDL 1.0", "BSD 2-Clause", "Apache License, 2.0", "Public Domain",
                 "Modified BSD", "jQuery license", "MIT license", "W3C license")
        for purl in (self.JERSEY, "pkg:maven/org.glassfish.jersey.ext/jersey-bean-validation@4.0.3",
                     "pkg:maven/org.glassfish.jersey.containers/jersey-container-servlet@4.0.3",
                     "pkg:maven/org.glassfish.jersey.ext/jersey-entity-filtering@4.0.3",
                     "pkg:maven/org.glassfish.jersey.ext/jersey-spring6@4.0.3",
                     "pkg:maven/org.glassfish.jersey.inject/jersey-hk2@4.0.3"):
            ch = check([comp(purl, *names)])
            self.assertEqual([], ch.errors, purl)
            self.assertTrue(any("ELECTED" in n and "EPL-2.0" in n for n in ch.notes), purl)
        self.assertEqual([], check([comp("pkg:maven/com.thoughtworks.paranamer/paranamer@2.8.3", "BSD")]).errors)

    def test_decision_263_mxparser_and_xmlpull(self):
        iu = "Indiana University Extreme! Lab Software License"  # mxparser 1.2.2 POM licence name
        ch = check([comp("pkg:maven/io.github.x-stream/mxparser@1.2.2", iu)])
        self.assertEqual([], ch.errors)
        self.assertTrue(any(n.startswith("CONDITIONAL") and "decision 263" in n for n in ch.notes))
        self.assertEqual([], check([comp("pkg:maven/xmlpull/xmlpull@1.1.3.1", "Public Domain")]).errors)
        # the IU licence is conditional on mxparser only; xpp3_min (out under decision 263) and any other component fail
        self.assertTrue(check([comp("pkg:maven/xpp3/xpp3_min@1.1.4c", iu)]).errors)
        self.assertTrue(check([comp("pkg:maven/org.ogce/xpp3@1.1.6", iu)]).errors)


class ReviewedLicences(unittest.TestCase):
    """ui.yml "Licence policy (strict, distribution ui)", 2026-11-15: nine bpmn.io packages declare the pointer
    "SEE LICENSE IN LICENSE" and component-props declares nothing, so the gate saw no licence it could judge although
    each version is in the decision register with the licence read from its files."""
    BPMN = "pkg:npm/bpmn-js@17.11.1"
    FORM = "pkg:npm/%40bpmn-io/form-js-editor@1.12.0"
    PROPS = "pkg:npm/component-props@1.1.1"

    def test_a_reviewed_bpmn_io_version_is_judged_by_its_recorded_licence_and_stays_conditional(self):
        ch = check([comp(self.BPMN, "SEE LICENSE IN LICENSE"), comp(self.FORM, "SEE LICENSE IN LICENSE")], distribution="ui")
        self.assertEqual([], ch.errors)
        self.assertEqual(2, sum("REVIEWED" in n and "LicenseRef-bpmn.io" in n for n in ch.notes), ch.notes)
        self.assertEqual(2, sum("CONDITIONAL" in n and "LIC-8" in n for n in ch.notes), ch.notes)

    def test_a_package_without_any_licence_member_is_judged_by_its_recorded_licence(self):
        c = comp(self.PROPS)
        self.assertEqual([], c["licenses"])
        self.assertEqual([], check([c], distribution="ui").errors)

    def test_another_version_is_not_covered(self):
        ch = check([comp("pkg:npm/bpmn-js@17.11.2", "SEE LICENSE IN LICENSE"), comp("pkg:npm/component-props@1.1.2")])
        self.assertTrue(any("UNKNOWN LICENCE" in e and "bpmn-js@17.11.2" in e for e in ch.errors), ch.errors)
        self.assertTrue(any("NO LICENCE DATA" in e and "component-props@1.1.2" in e for e in ch.errors), ch.errors)

    def test_changed_metadata_needs_a_new_review(self):
        ch = check([comp(self.BPMN, "MIT"), comp(self.PROPS, "ISC")])
        self.assertEqual(2, sum("REVIEWED LICENCE MISMATCH" in e for e in ch.errors), ch.errors)

    def test_an_entry_accepts_nothing_by_itself(self):
        policy = json.loads(json.dumps(POLICY))
        policy["reviewed_licences"] = [
            {"licence": "GPL-3.0-only", "declared": "SEE LICENSE IN LICENSE", "evidence": "test", "purls": ["pkg:npm/x@1.0.0"]},
            {"licence": "LicenseRef-bpmn.io", "declared": None, "evidence": "test", "purls": ["pkg:npm/not-bpmn-io@1.0.0"]},
            {"licence": "LicenseRef-not-in-the-policy", "declared": None, "evidence": "test", "purls": ["pkg:npm/y@1.0.0"]}]
        ch = cl.Checker(policy, True)
        for c in (comp("pkg:npm/x@1.0.0", "SEE LICENSE IN LICENSE"), comp("pkg:npm/not-bpmn-io@1.0.0"), comp("pkg:npm/y@1.0.0")):
            ch.check_component(c, "test")
        self.assertEqual(2, sum("FORBIDDEN LICENCE" in e for e in ch.errors), ch.errors)
        self.assertEqual(1, sum("UNKNOWN LICENCE" in e for e in ch.errors), ch.errors)

    def test_every_entry_agrees_with_the_decision_register(self):
        register = cl.load_json(os.path.join(HERE, "decision-register.json"))["components"]
        entries = POLICY["reviewed_licences"]
        self.assertTrue(entries)
        for e in entries:
            for purl in e["purls"]:
                row = register.get(purl)
                self.assertIsNotNone(row, f"{purl} is not in decision-register.json")
                recorded, _, declared = row["spdx"].partition(" (declared '")
                self.assertEqual(e["licence"], recorded, purl)
                if declared:  # the register names the declared spelling only where it kept one
                    self.assertEqual(e["declared"], declared[:-2], purl)
                self.assertTrue(row["decision"].startswith("ACCEPTED"), purl)

    def test_decision_176_paranamer_as_the_cyclonedx_maven_plugin_reports_it(self):
        # licence-check.yml 2026-11-15: "UNKNOWN LICENCE com.thoughtworks.paranamer:paranamer@2.8.3: BSD-4-Clause"
        purl = "pkg:maven/com.thoughtworks.paranamer/paranamer@2.8.3"
        c = comp(purl)
        c["licenses"] = [{"license": {"id": "BSD-4-Clause"}}]
        ch = check([c], distribution="server")
        self.assertEqual([], ch.errors)
        self.assertTrue(any("REVIEWED" in n and "BSD-3-Clause" in n and "decision 176" in n for n in ch.notes), ch.notes)
        # BSD-4-Clause itself is not accepted: any other component or version that declares it still fails
        other = comp("pkg:maven/com.thoughtworks.paranamer/paranamer@2.8", "BSD-4-Clause")
        self.assertTrue(any("UNKNOWN LICENCE" in e for e in check([other]).errors))

    def test_a_policy_prefix_spelled_as_in_an_sbom_matches(self):
        self.assertTrue(cl.Checker.matches("pkg:npm/@bpmn-io/form-js-viewer@1.12.0", ["pkg:npm/%40bpmn-io/form-js-viewer"]))
        self.assertTrue(cl.Checker.matches("pkg:npm/%40bpmn-io/form-js-viewer@1.12.0", ["pkg:npm/%40bpmn-io/form-js-viewer"]))
        self.assertFalse(cl.Checker.matches("pkg:npm/@other/form-js-viewer@1.12.0", ["pkg:npm/%40bpmn-io/form-js-viewer"]))


class Scoping(unittest.TestCase):
    def test_bouncy_castle_and_jnats_only_in_their_add_ons(self):
        # decisions 217 and 232
        lts = comp("pkg:maven/org.bouncycastle/bcprov-lts8on@2.73.12.1", "Bouncy Castle Licence")
        jdk = comp("pkg:maven/org.bouncycastle/bcprov-jdk18on@1.84", "Bouncy Castle Licence")
        nats = comp("pkg:maven/io.nats/jnats@2.26.4", "The Apache License, Version 2.0")
        for dist in ("server", "control-plane", "lib-ext", None):
            ch = check([lts, jdk, nats], distribution=dist)
            self.assertEqual(3, sum(e.startswith("FORBIDDEN COMPONENT") for e in ch.errors), dist)
        self.assertEqual([], check([lts, nats], distribution="nats").errors)
        self.assertEqual([], check([jdk], distribution="esign").errors)
        self.assertTrue(any("FORBIDDEN COMPONENT" in e and "jnats" in e for e in check([nats], distribution="esign").errors))

    def test_decision_243_each_bouncy_castle_jar_only_in_its_own_add_on(self):
        # decisions 235 and 243: the eSign jar (also Fidelius of sangam-connectors-health) never in the nats add-on,
        # the jnats provider never in the esign add-on; neither in the server
        lts = comp("pkg:maven/org.bouncycastle/bcprov-lts8on@2.73.12.1", "Bouncy Castle Licence")
        jdk = comp("pkg:maven/org.bouncycastle/bcprov-jdk18on@1.84", "Bouncy Castle Licence")
        pkix = comp("pkg:maven/org.bouncycastle/bcpkix-jdk18on@1.84", "Bouncy Castle Licence")
        ch = check([jdk], distribution="nats")
        self.assertTrue(any("FORBIDDEN COMPONENT" in e and "bcprov-jdk18on" in e for e in ch.errors), ch.errors)
        ch = check([lts], distribution="esign")
        self.assertTrue(any("FORBIDDEN COMPONENT" in e and "bcprov-lts8on" in e for e in ch.errors), ch.errors)
        self.assertEqual([], check([jdk, pkix], distribution="esign").errors)
        self.assertEqual(2, sum(e.startswith("FORBIDDEN COMPONENT") for e in check([jdk, pkix], distribution="server").errors))

    def test_rhino_only_in_the_pdf_validator(self):
        rhino = comp("pkg:maven/org.mozilla/rhino@1.7.15.1", "Mozilla Public License, Version 2.0")
        self.assertTrue(any("FORBIDDEN COMPONENT" in e for e in check([rhino], distribution="server").errors))
        self.assertTrue(any("FORBIDDEN COMPONENT" in e for e in check([rhino]).errors), "no distribution named = not allowed")
        self.assertEqual([], check([rhino], distribution="pdf-validator").errors)

    def test_nuclear_disclaimer_only_in_the_pdf_validator(self):
        c = comp("pkg:maven/com.github.jai-imageio/jai-imageio-core@1.4.0", expr="LicenseRef-BSD-3-Clause-Nuclear-Disclaimer")
        self.assertEqual([], check([c], distribution="pdf-validator").errors)
        self.assertTrue(check([c], distribution="server").errors)

    def test_mariadb_lgpl_is_conditional(self):
        ch = check([comp("pkg:maven/org.mariadb.jdbc/mariadb-java-client@3.5.11", expr="LGPL-2.1-or-later")])
        self.assertEqual([], ch.errors)


class OsPackages(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def offer(self):
        path = os.path.join(self.tmp.name, POLICY["os_packages"]["source_offer"])
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write("offer\n")

    def test_gpl_os_package_passes_in_an_image_with_the_source_offer(self):
        self.offer()
        ch = check([comp("pkg:deb/debian/bash@5.2.15", expr="GPL-3.0-or-later")], image=True, distribution="server-image")
        ch.check_os_source_offer(self.tmp.name)
        self.assertEqual([], ch.errors)

    def test_missing_source_offer_fails(self):
        ch = check([comp("pkg:apk/alpine/busybox@1.36.1", "GPL-2")], image=True)
        ch.check_os_source_offer(self.tmp.name)
        self.assertTrue(any("SOURCE OFFER MISSING" in e for e in ch.errors), ch.errors)

    def test_agpl_os_package_stays_forbidden(self):
        self.offer()
        ch = check([comp("pkg:deb/debian/x@1", expr="AGPL-3.0-only")], image=True)
        self.assertTrue(any("FORBIDDEN LICENCE" in e for e in ch.errors))

    def test_gpl_outside_an_image_or_outside_os_purls_fails(self):
        self.assertTrue(check([comp("pkg:deb/debian/bash@5", expr="GPL-3.0-or-later")]).errors, "not an image SBOM")
        self.assertTrue(check([comp("pkg:maven/a/b@1", expr="GPL-3.0-only")], image=True).errors, "a library in an image")


class BuildScope(unittest.TestCase):
    def test_decision_127_exception_is_reported_not_failed(self):
        ch = check([comp("pkg:maven/io.github.eisop/dataflow-errorprone@3.41.0-eisop1", expr="GPL-2.0-only WITH Classpath-exception-2.0"),
                    comp("pkg:maven/dom4j/dom4j@1.1")], build_scope=True)
        self.assertEqual([], ch.errors)
        self.assertEqual(2, sum("BUILD-TOOL EXCEPTION" in n for n in ch.notes))

    def test_unknown_build_licence_is_a_warning(self):
        ch = check([comp("pkg:maven/a/tool@1", "Some Odd Licence")], build_scope=True)
        self.assertEqual([], ch.errors)
        self.assertTrue(ch.warnings)

    def test_a_forbidden_build_licence_or_another_version_fails(self):
        self.assertTrue(check([comp("pkg:maven/a/tool@1", expr="AGPL-3.0-only")], build_scope=True).errors)
        self.assertTrue(check([comp("pkg:maven/dom4j/dom4j@1.2", expr="GPL-3.0-only")], build_scope=True).errors)

    def test_ojdbc_in_a_default_build_fails(self):
        ch = check([comp("pkg:maven/com.oracle.database.jdbc/ojdbc11@23.8.0.25.04", "Oracle Free Use Terms and Conditions (FUTC)")], build_scope=True)
        self.assertTrue(any("BUILD-SCOPE FORBIDDEN" in e for e in ch.errors))


class Cli(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.policy = os.path.join(HERE, "licence-policy.json")

    def write_bom(self, components):
        p = os.path.join(self.tmp.name, "a.cdx.json")
        with open(p, "w", encoding="utf-8") as f:
            json.dump({"bomFormat": "CycloneDX", "specVersion": "1.6", "components": components}, f)
        return p

    def test_missing_sbom_is_blocked_exit_2(self):
        self.assertEqual(2, cl.main(["--policy", self.policy, "--sbom", os.path.join(self.tmp.name, "none.json")]))

    def test_pass_and_fail_exit_codes(self):
        ok = self.write_bom([comp("pkg:maven/a/b@1", expr="MIT")])
        self.assertEqual(0, cl.main(["--policy", self.policy, "--sbom", ok]))
        bad = self.write_bom([comp("pkg:maven/a/b@1", expr="SSPL-1.0")])
        self.assertEqual(1, cl.main(["--policy", self.policy, "--sbom", bad]))

    def test_a_jar_without_boot_inf_lib_fails(self):
        jar = os.path.join(self.tmp.name, "thin.jar")
        with zipfile.ZipFile(jar, "w") as z:
            z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        self.assertEqual(1, cl.main(["--policy", self.policy, "--jar", jar]))

    def fat_jar(self, name, *nested):
        jar = os.path.join(self.tmp.name, name)
        with zipfile.ZipFile(jar, "w") as z:
            z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            z.writestr("BOOT-INF/classes/x.class", b"")
            for n in nested:
                z.writestr(f"BOOT-INF/lib/{n}", b"PK\x05\x06" + b"\x00" * 18)
        return jar

    def test_a_planted_mariadb_driver_inside_a_fat_jar_fails(self):
        # RM-15 / CR-04: LIC-5, the MariaDB driver must be a separate replaceable jar, never inside the fat jar
        clean = self.fat_jar("clean.jar", "postgresql-42.7.8.jar", "spring-core-6.2.10.jar")
        self.assertEqual(0, cl.main(["--policy", self.policy, "--jar", clean]))
        planted = self.fat_jar("planted.jar", "postgresql-42.7.8.jar", "mariadb-java-client-3.5.6.jar")
        self.assertEqual(1, cl.main(["--policy", self.policy, "--jar", clean, "--jar", planted]))
        ch = cl.Checker(cl.load_json(self.policy), True)
        ch.check_jar(planted)
        self.assertTrue(any("FORBIDDEN JAR ENTRY planted.jar!BOOT-INF/lib/mariadb-java-client-3.5.6.jar" in e
                            and "LIC-5" in e for e in ch.errors), ch.errors)

    def test_a_missing_jar_is_blocked_never_passed(self):
        # RM-15: a jar named on the command line that does not exist blocks the gate (exit 2), it is never skipped
        self.assertEqual(2, cl.main(["--policy", self.policy, "--jar", os.path.join(self.tmp.name, "absent.jar")]))

    def test_annotate_records_elections_in_the_sbom(self):
        p = self.write_bom([comp("pkg:maven/com.h2database/h2@2.3.232", expr="MPL-2.0 OR EPL-1.0")])
        self.assertEqual(0, cl.main(["--policy", self.policy, "--sbom", p, "--annotate"]))
        with open(p, encoding="utf-8") as f:
            bom = json.load(f)
        props = {x["name"]: x["value"] for x in bom["components"][0]["properties"]}
        self.assertEqual("MPL-2.0", props["sangam:election"])


if __name__ == "__main__":
    unittest.main()
