import hashlib, json, os, sys
L = sys.argv[1]
LIC = os.path.join(os.path.expanduser("~"), ".m2", "repository", "io", "github", "x-stream", "mxparser", "1.2.2")
JAR_SHA = "AEEEE23A3303D811BCA8790EA7F25B534314861C03CFF36DAFDCC2180969EB97"


def rd(p):
    with open(os.path.join(L, p), "rb") as f:
        return f.read().decode("utf-8")


def wr(p, s):
    with open(os.path.join(L, p), "wb") as f:
        f.write(s.encode("utf-8"))


def rep(p, old, new):
    s = rd(p)
    eol = "\r\n" if "\r\n" in s else "\n"
    old, new = old.replace("\n", eol), new.replace("\n", eol)
    assert s.count(old) == 1, (p, old[:60], s.count(old))
    wr(p, s.replace(old, new))


# jar check and licence text
import zipfile
jar = os.path.join(LIC, "mxparser-1.2.2.jar")
assert hashlib.sha256(open(jar, "rb").read()).hexdigest().upper() == JAR_SHA
lic = zipfile.ZipFile(jar).read("META-INF/LICENSE")
assert lic == open(os.path.join(L, "texts", "LicenseRef-IU-Extreme-Lab.txt"), "rb").read()
lic = lic.decode("utf-8")
assert "\r" not in lic and lic.startswith("Indiana University Extreme! Lab Software License, Version 1.2\n")

# 1 policy
rep("licence-policy.json",
    '"components": ["pkg:maven/xpp3/xpp3_min"],\n      "condition": "Owner decision 226 (2026-11-13; W4-K licence check DOWNLOADS-WAVE4.md 2026-11-13T01:29:07Z): Indiana University Extreme! Lab Software License (POM 1.1.1, shipped sources 1.2), BSD style. Only xpp3_min 1.1.4c as an optional dependency of the HAPI FHIR validation path. Where it ships, the distribution NOTICE carries the copyright, the licence and the IU acknowledgement from attributions/xpp3_min-1.1.4c.txt (notice_bundle.py); no endorsement and no \'Indiana University\' in a product name. The org.xmlpull.v1 API classes are public domain (LicenseRef-Public-Domain)."',
    '"components": ["pkg:maven/io.github.x-stream/mxparser"],\n      "condition": "Owner decision 263 (2026-11-13, replaces decision 226: xpp3_min is out; W4-K re-resolve and swap in sangam-connectors-health, DOWNLOADS-WAVE4.md): Indiana University Extreme! Lab Software License, Version 1.2 (the jar META-INF/LICENSE), BSD style. Only io.github.x-stream:mxparser 1.2.2 as an optional dependency of the HAPI FHIR validation path. Where it ships, the distribution NOTICE carries the copyright, the licence and the IU acknowledgement from attributions/mxparser-1.2.2.txt (notice_bundle.py); no endorsement and no \'Indiana University\' in a product name. Its xmlpull:xmlpull 1.1.3.1 dependency is public domain (LicenseRef-Public-Domain)."')

# 2 aliases
rep("check_licences.py",
    '    # Decision 226: xpp3:xpp3_min 1.1.4c POM name, typo "vesion" as published; the shipped sources carry version 1.2\n'
    '    # of the same licence, so a LicenseRef is used, not SPDX "xpp" (whose text is 1.1.1).\n'
    '    "Indiana University Extreme! Lab Software License, vesion 1.1.1": "LicenseRef-IU-Extreme-Lab",\n'
    '    "Indiana University Extreme! Lab Software License, version 1.1.1": "LicenseRef-IU-Extreme-Lab",\n',
    '    # Decision 263: io.github.x-stream:mxparser 1.2.2 POM licence name (no version); the jar META-INF/LICENSE is\n'
    '    # version 1.2, so a LicenseRef is used, not SPDX "xpp" (whose text is 1.1.1).\n'
    '    "Indiana University Extreme! Lab Software License": "LicenseRef-IU-Extreme-Lab",\n')

# 3 attributions
rep("attributions/attributions.json",
    'Only for components whose artefact carries no licence or copyright text of its own.',
    'Only for components whose artefact carries no NOTICE text (META-INF/NOTICE*) of its own, or whose licence asks for an acknowledgement that no NOTICE carries.')
rep("attributions/attributions.json",
    '  "_comment_xpp3_min": "Decision 226 (W4-K licence check 2026-11-13T01:29:07Z): xpp3_min carries no licence file; its IU Extreme! Lab licence requires the acknowledgement in the documentation. xmlpull 1.1.3.1 is public domain and needs no entry. Jaxen and SAXPath are not in xpp3_min (they were in org.ogce:xpp3, removed by decision 226).",\n'
    '  "pkg:maven/xpp3/xpp3_min@1.1.4c": "xpp3_min-1.1.4c.txt"\n',
    '  "_comment_mxparser": "Decision 263 (replaces decision 226, xpp3_min is out): mxparser 1.2.2 carries META-INF/LICENSE but no NOTICE, and notice_bundle.py collects NOTICE files only; its IU Extreme! Lab licence requires the acknowledgement in the documentation, so this entry adds it with the copyright and the licence. xmlpull 1.1.3.1 is public domain and needs no entry.",\n'
    '  "pkg:maven/io.github.x-stream/mxparser@1.2.2": "mxparser-1.2.2.txt"\n')
old = rd("attributions/xpp3_min-1.1.4c.txt")
eol = "\r\n" if "\r\n" in old else "\n"
att = ("SPDX-License-Identifier: LicenseRef-IU-Extreme-Lab\n"
       "Source: io.github.x-stream:mxparser:1.2.2 jar (SHA-256 " + JAR_SHA + "),\n"
       "META-INF/LICENSE, quoted byte for byte below, read 2026-11-13 (decision 263, replaces decision 226). Its\n"
       "xmlpull:xmlpull 1.1.3.1 dependency is in the public domain (http://www.xmlpull.org/v1/download/unpacked/LICENSE.txt).\n"
       "\n"
       "This product includes software developed by the Indiana University Extreme! Lab.  For further information please\n"
       "visit http://www.extreme.indiana.edu/\n"
       "\n" + lic)
wr("attributions/mxparser-1.2.2.txt", att.replace("\n", eol))
os.remove(os.path.join(L, "attributions", "xpp3_min-1.1.4c.txt"))

# 4 public domain text
rep("texts/LicenseRef-Public-Domain.txt",
    "licence text applies and none is reproduced. The dedication of the XMLPULL API (xmlpull:xmlpull 1.1.3.1 and the\n"
    "org.xmlpull.v1 classes of xpp3:xpp3_min 1.1.4c), quoted word for word from the licence URL in the xmlpull POM,\n",
    "licence text applies and none is reproduced. The dedication of the XMLPULL API (xmlpull:xmlpull 1.1.3.1, a\n"
    "dependency of io.github.x-stream:mxparser 1.2.2), quoted word for word from the licence URL in the xmlpull POM,\n")
pd_sha = hashlib.sha256(open(os.path.join(L, "texts", "LicenseRef-Public-Domain.txt"), "rb").read()).hexdigest().upper()

# 5 SOURCES.json
rep("texts/SOURCES.json",
    '"source": "xpp3:xpp3_min:1.1.4c sources jar, org/xmlpull/mxp1/MXParser.java closing licence comment (Indiana University Extreme! Lab Software License 1.2; decision 226)",\n'
    '      "artefact_sha256": "D5AA7F9F222E5B42D72523A4FA4F6E686480A9EB2970DBEF702A5022E0178398",\n'
    '      "entry": "org/xmlpull/mxp1/MXParser.java",\n'
    '      "change": "Java comment markers removed, wording unchanged",\n',
    '"source": "io.github.x-stream:mxparser:1.2.2 jar from Maven Central, META-INF/LICENSE (Indiana University Extreme! Lab Software License 1.2; decision 263, replaces the xpp3_min source of decision 226)",\n'
    '      "artefact_sha256": "' + JAR_SHA + '",\n'
    '      "entry": "META-INF/LICENSE",\n'
    '      "change": "none, byte for byte",\n')
rep("texts/SOURCES.json",
    '"sha256": "B2DF72698325FFEE27762C3F39DDF11FEE71A3CDF6738E32EF7388C876562F97"',
    '"sha256": "' + pd_sha + '"')

# 6 tests
rep("test_check_licences.py",
    '    def test_decision_226_xpp3_min_and_xmlpull(self):\n'
    '        iu = "Indiana University Extreme! Lab Software License, vesion 1.1.1"  # POM name, typo as published\n'
    '        ch = check([comp("pkg:maven/xpp3/xpp3_min@1.1.4c", iu, "Public Domain")])\n'
    '        self.assertEqual([], ch.errors)\n'
    '        self.assertTrue(any(n.startswith("CONDITIONAL") and "decision 226" in n for n in ch.notes))\n'
    '        self.assertEqual([], check([comp("pkg:maven/xmlpull/xmlpull@1.1.3.1", "Public Domain")]).errors)\n'
    '        # the IU licence is conditional on xpp3_min only; any other component with it fails\n'
    '        self.assertTrue(check([comp("pkg:maven/org.ogce/xpp3@1.1.6", iu)]).errors)\n',
    '    def test_decision_263_mxparser_and_xmlpull(self):\n'
    '        iu = "Indiana University Extreme! Lab Software License"  # mxparser 1.2.2 POM licence name\n'
    '        ch = check([comp("pkg:maven/io.github.x-stream/mxparser@1.2.2", iu)])\n'
    '        self.assertEqual([], ch.errors)\n'
    '        self.assertTrue(any(n.startswith("CONDITIONAL") and "decision 263" in n for n in ch.notes))\n'
    '        self.assertEqual([], check([comp("pkg:maven/xmlpull/xmlpull@1.1.3.1", "Public Domain")]).errors)\n'
    '        # the IU licence is conditional on mxparser only; xpp3_min (out under decision 263) and any other component fail\n'
    '        self.assertTrue(check([comp("pkg:maven/xpp3/xpp3_min@1.1.4c", iu)]).errors)\n'
    '        self.assertTrue(check([comp("pkg:maven/org.ogce/xpp3@1.1.6", iu)]).errors)\n')
rep("test_release_assets.py",
    'listed["pkg:maven/xpp3/xpp3_min@1.1.4c"]',
    'listed["pkg:maven/io.github.x-stream/mxparser@1.2.2"]')
print("ok", pd_sha)
