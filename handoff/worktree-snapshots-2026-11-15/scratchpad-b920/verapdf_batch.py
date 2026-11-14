import io, json
R = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"

def patch(path, old, new):
    s = io.open(path, encoding="utf-8", newline="").read()
    nl = "\r\n" if "\r\n" in s else "\n"
    old = old.replace("\n", nl); new = new.replace("\n", nl)
    assert s.count(old) == 1, (path, old[:60], s.count(old))
    io.open(path, "w", encoding="utf-8", newline="").write(s.replace(old, new))
    return s.replace(old, new)

pol = R + r"\deploy\licence\licence-policy.json"
entries = (
    ',\n    {\n      "license": "GPL-3.0-or-later",\n'
    '      "components": ["pkg:maven/org.verapdf/"],\n'
    '      "condition": "Owner decision 94 (2026-11-12): veraPDF 1.30.4 (jakarta) is dual-licensed GPL-3.0-or-later OR MPL-2.0; Sangam elects MPL-2.0. Used only as the PDF/A-2b and PDF/UA-1 validator of the M-081 renderer. Exactly version 1.30.4 (wcag-algorithms 1.30.3); any other version is re-approved."\n    },\n'
    '    {\n      "license": "LicenseRef-BSD-3-Clause-Nuclear-Disclaimer",\n'
    '      "components": ["pkg:maven/com.github.jai-imageio/jai-imageio-core@1.4.0", "pkg:maven/com.github.jai-imageio/jai-imageio-jpeg2000@1.3.0"],\n'
    '      "condition": "Owner decision 102 (2026-11-12): BSD-3-Clause with a nuclear-facility disclaimer (jpeg2000 also under the JJ2000 licence). Reached only through veraPDF (decision 94) for image checks during PDF validation. Exactly these versions."\n    },\n'
    '    {\n      "license": "LicenseRef-BSD-stax-utils",\n'
    '      "components": ["pkg:maven/net.java.dev.stax-utils/stax-utils@20070216"],\n'
    '      "condition": "Owner decision 102 (2026-11-12): BSD-style licence of stax-utils, reached only through veraPDF (decision 94). Exactly this version."\n    }\n  ],'
)
s = io.open(pol, encoding="utf-8", newline="").read()
if "pkg:maven/org.verapdf/" not in s:
    anchor = 'Exactly this version; any other version is re-approved."\n    }\n  ],'
    s2 = patch(pol, anchor, anchor[:-len('\n  ],')] + entries)
    json.loads(s2)
    print("policy ok")

root = R + r"\pom.xml"
s = io.open(root, encoding="utf-8", newline="").read()
if "version.verapdf" not in s:
    patch(root, "    <version.pdfbox>3.0.8</version.pdfbox>\n",
          "    <version.pdfbox>3.0.8</version.pdfbox>\n"
          "    <!-- M-081: PDF/A-2b + PDF/UA-1 validator, MPL-2.0 elected (decisions 94, 102); jakarta line for Jakarta EE 11 -->\n"
          "    <version.verapdf>1.30.4</version.verapdf>\n")
    patch(root, "      <dependency>\n        <groupId>org.apache.pdfbox</groupId>\n        <artifactId>pdfbox</artifactId>\n        <version>${version.pdfbox}</version>\n      </dependency>\n",
          "      <dependency>\n        <groupId>org.apache.pdfbox</groupId>\n        <artifactId>pdfbox</artifactId>\n        <version>${version.pdfbox}</version>\n      </dependency>\n"
          "      <dependency>\n        <groupId>org.verapdf</groupId>\n        <artifactId>validation-model-jakarta</artifactId>\n        <version>${version.verapdf}</version>\n      </dependency>\n")
    print("pom ok")
