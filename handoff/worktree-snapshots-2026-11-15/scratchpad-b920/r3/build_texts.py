"""One-time builder of sangam/deploy/licence/texts/ from artefacts already in the local caches (round 3, no download).
Usage: python -I build_texts.py <texts dir> <m2 repo> <node_modules dir>"""
import hashlib, json, os, re, sys, zipfile

out, m2, nm = sys.argv[1], sys.argv[2], sys.argv[3]
CDX = os.path.join(m2, "org", "cyclonedx", "cyclonedx-core-java", "9.0.5", "cyclonedx-core-java-9.0.5.jar")
JAK = os.path.join(m2, "jakarta", "annotation", "jakarta.annotation-api", "3.0.0", "jakarta.annotation-api-3.0.0.jar")
XPP = os.path.join(m2, "xpp3", "xpp3_min", "1.1.4c", "xpp3_min-1.1.4c-sources.jar")
BPMN = os.path.join(nm, "bpmn-js")
SPDX_IDS = ["0BSD", "BSD-2-Clause", "BSD-3-Clause", "BSD-3-Clause-Clear", "BlueOak-1.0.0", "CC-BY-3.0", "CC-BY-4.0",
            "CC0-1.0", "CDDL-1.0", "CDDL-1.1", "EPL-1.0", "EPL-2.0", "GPL-2.0-only", "GPL-2.0-or-later", "ISC", "LGPL-2.1",
            "MIT", "MIT-0", "MPL-2.0", "OFL-1.1", "Plexus", "PostgreSQL", "Python-2.0", "UPL-1.0", "Unicode-3.0",
            "Unicode-DFS-2016", "Unlicense", "W3C", "Zlib"]

def sha(b):
    return hashlib.sha256(b).hexdigest().upper()

def fsha(p):
    with open(p, "rb") as f:
        return sha(f.read())

os.makedirs(out, exist_ok=True)
src = {}

def put(lic, data, record):
    if isinstance(data, str):
        data = data.encode("utf-8")
    with open(os.path.join(out, f"{lic}.txt"), "wb") as f:
        f.write(data)
    record["sha256"] = sha(data)
    src[lic] = record

with zipfile.ZipFile(CDX) as z:
    ver = json.loads(z.read("licenses/licenses.json"))
    ENTRY = {"LGPL-2.1": "LGPL-2.1-only"}  # deprecated SPDX id: the bundled LGPL-2.1.txt is a "404: Not Found" stub
    for lic in SPDX_IDS:
        data = z.read(f"licenses/{ENTRY.get(lic, lic)}.txt")
        assert len(data) > 300 and not data.startswith(b"404"), lic
        put(lic, data, {
            "source": f"SPDX License List {ver['licenseListVersion']} ({ver['releaseDate']}) text as bundled in "
                      "org.cyclonedx:cyclonedx-core-java:9.0.5 (local Maven cache)",
            "artefact_sha256": fsha(CDX), "entry": f"licenses/{ENTRY.get(lic, lic)}.txt",
            "change": "none (verbatim bytes)" + (" ; text of the current id LGPL-2.1-only, the policy uses the deprecated id LGPL-2.1" if lic in ENTRY else "")})

with zipfile.ZipFile(JAK) as z:
    t = z.read("META-INF/LICENSE.md").decode("utf-8")
    block = t[t.index("## CLASSPATH EXCEPTION"):].split("\n", 2)[2]
    lines = [l[4:] if l.startswith("    ") else l for l in block.rstrip().split("\n")]
    put("Classpath-exception-2.0", "\n".join(lines) + "\n", {
        "source": "jakarta.annotation:jakarta.annotation-api:3.0.0 META-INF/LICENSE.md, section CLASSPATH EXCEPTION "
                  "(local Maven cache; the SPDX copy in cyclonedx-core-java has no exception texts)",
        "artefact_sha256": fsha(JAK), "entry": "META-INF/LICENSE.md",
        "change": "section body only; Markdown heading and 4-space indent removed, wording unchanged"})

with zipfile.ZipFile(XPP) as z:
    t = z.read("org/xmlpull/mxp1/MXParser.java").decode("latin-1")
    i = t.rfind("Indiana University Extreme! Lab Software License")
    block = t[t.rfind("/*", 0, i) + 2:t.index("*/", i)]
    lines = [re.sub(r"^ \* ?", "", l).rstrip() for l in block.strip("\n").split("\n")]
    while lines and not lines[0]:
        lines.pop(0)
    while lines and not lines[-1]:
        lines.pop()
    put("LicenseRef-IU-Extreme-Lab", "\n".join(lines) + "\n", {
        "source": "xpp3:xpp3_min:1.1.4c sources jar, org/xmlpull/mxp1/MXParser.java closing licence comment "
                  "(Indiana University Extreme! Lab Software License 1.2; decision 226)",
        "artefact_sha256": fsha(XPP), "entry": "org/xmlpull/mxp1/MXParser.java",
        "change": "Java comment markers removed, wording unchanged"})

BC = os.path.join(m2, "org", "bouncycastle", "bcprov-jdk18on", "1.85.2", "bcprov-jdk18on-1.85.2.jar")
with zipfile.ZipFile(BC) as z:
    put("LicenseRef-Bouncy-Castle", z.read("META-INF/LICENSE.md"), {
        "source": "org.bouncycastle:bcprov-jdk18on:1.85.2 META-INF/LICENSE.md (local Maven cache; build-only copy pulled "
                  "by sonar-maven-plugin). The shipped 1.84 jars carry no licence file; the licence wording is the same "
                  "MIT-style Bouncy Castle licence, the copyright line of this copy reads 2000-2026",
        "artefact_sha256": fsha(BC), "entry": "META-INF/LICENSE.md", "change": "none (verbatim bytes)"})
JAI = os.path.join(m2, "com", "github", "jai-imageio", "jai-imageio-core", "1.4.0", "jai-imageio-core-1.4.0.jar")
with zipfile.ZipFile(JAI) as z:
    put("LicenseRef-BSD-3-Clause-Nuclear-Disclaimer", z.read("META-INF/LICENSE"), {
        "source": "com.github.jai-imageio:jai-imageio-core:1.4.0 META-INF/LICENSE (local Maven cache; decision 102, "
                  "PDF validator only). Its copyright lines are those of jai-imageio-core",
        "artefact_sha256": fsha(JAI), "entry": "META-INF/LICENSE", "change": "none (verbatim bytes)"})
with open(os.path.join(BPMN, "package.json"), encoding="utf-8") as f:
    bv = json.load(f)["version"]
lp = os.path.join(BPMN, "LICENSE")
with open(lp, "rb") as f:
    put("LicenseRef-bpmn.io", f.read(), {
        "source": f"npm bpmn-js {bv} LICENSE (sangam-ui node_modules, installed from the committed lockfile); the "
                  "bpmn.io licence (MIT with the watermark clause, LIC-8)",
        "artefact_sha256": fsha(lp), "entry": "LICENSE", "change": "none (verbatim bytes)"})

with open(os.path.join(out, "SOURCES.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump({"_comment": "SPDX-License-Identifier: Apache-2.0. Where each licence text in this directory comes from "
               "(W4-C round 3, 2026-11-13, no download: local Maven and npm caches only). sha256 is of the file here; "
               "artefact_sha256 is of the cached artefact it was taken from. test_release_assets.py checks both lists.",
               "texts": dict(sorted(src.items()))}, f, indent=2)
    f.write("\n")
print(len(src), "texts written")



