# SPDX-License-Identifier: Apache-2.0
"""Generates the per-release standards profile (M-052; SANGAM.md R44, CF-8: "the pilot publishes its supported BPMN/DMN
profile"): which BPMN, DMN and CMMN constructs this release supports, with what rules, and the FEEL policy (ADR-D-26).

It is built from the data that already enforces the profile, so the published profile cannot disagree with it:
  - sangam-ui/src/modeller/compatibility.json, the R46 modeller/runtime matrix written by sangam-ui/scripts/gen-compat.mjs
    from the editors' palettes, Operaton's parser and scripts/compat-rules.json (the deploy-time rules of
    SangamBpmnParseListener); the pre-deploy check and the modeller show the same rows;
  - SANGAM.md section X.0g (ADR-D-26), the FEEL policy table that org.sangam.engine.feel.SangamFeel implements;
  - the release version: --version, else sangam/pom.xml without -SNAPSHOT.
Outputs (next to this script): STANDARDS-PROFILE.md and standards-profile.json. No timestamps, so a second run with the
same inputs writes the same bytes. --check compares instead of writing and exits 1 on a difference.
Publishing both files from release.yml with the evidence pack is the M-052 release step (Wave 5). Standard library only.

  python -I gen_standards_profile.py [--version 0.1.0] [--check]
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SANGAM = os.path.normpath(os.path.join(HERE, "..", ".."))
COMPAT = os.path.join(SANGAM, "sangam-ui", "src", "modeller", "compatibility.json")
SANGAM_MD = os.path.join(SANGAM, "..", "SANGAM.md")
POM = os.path.join(SANGAM, "pom.xml")
OUT_MD = os.path.join(HERE, "STANDARDS-PROFILE.md")
OUT_JSON = os.path.join(HERE, "standards-profile.json")

NOTATIONS = [("BPMN", "BPMN 2.0.2 (ISO/IEC 19510)"), ("DMN", "DMN 1.3"), ("CMMN", "CMMN 1.1")]
# Order and meaning of the support status (compat-rules.json and gen-compat.mjs use the same names)
STATUS = [
    ("SUPPORTED", "Supported", "Deploys and runs as the standard says."),
    ("SUPPORTED_WITH_RULES", "Supported with rules", "Deploys and runs under the Sangam rule in the note (a required "
                                                     "attribute, a binding default or a limit)."),
    ("PORTED", "Ported", "Not run by the base engine; Sangam provides the behaviour (see the note)."),
    ("NOT_EXECUTABLE", "Not executable", "Accepted in a model as documentation; it has no runtime effect."),
    ("REJECTED", "Rejected", "Refused at deployment."),
    ("UNSUPPORTED", "Unsupported", "Not supported in this release (see the note for what happens to a model that "
                                   "uses it)."),
]


def version_from_pom(pom_text):
    m = re.search(r"<artifactId>sangam-parent</artifactId>\s*<version>([^<]+)</version>", pom_text) or \
        re.search(r"<version>([^<$]+)</version>", pom_text)
    if not m:
        raise SystemExit("no version in " + POM)
    return m.group(1).strip().replace("-SNAPSHOT", "")


def feel_policy(md_text):
    """Rows (topic, rule) of the ADR-D-26 table in SANGAM.md X.0g, in table order."""
    start = md_text.find("## X.0g ADR-D-26")
    if start < 0:
        raise SystemExit("SANGAM.md has no section X.0g ADR-D-26")
    end = md_text.find("\n## ", start + 5)
    section = md_text[start:end if end > 0 else len(md_text)]
    rows = []
    for line in section.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if not line.startswith("|") or len(cells) < 2 or cells[0] in ("Topic", "") or set(cells[0]) <= set("-: "):
            continue
        rows.append({"topic": cells[0], "rule": "|".join(cells[1:]).strip()})
    if not rows:
        raise SystemExit("SANGAM.md X.0g has no policy table")
    return rows


def profile(compat, feel, version):
    known = [s for s, _, _ in STATUS]
    rows = []
    for r in compat["rows"]:
        if r["status"] not in known:
            raise SystemExit("unknown status %s for %s:%s" % (r["status"], r["notation"], r["construct"]))
        if r["notation"] not in [n for n, _ in NOTATIONS]:
            raise SystemExit("unknown notation %s" % r["notation"])
        rows.append({"notation": r["notation"], "construct": r["construct"], "status": r["status"],
                     "modeller": r.get("modeller"), "engineParses": r.get("engine") == "PARSED",
                     "note": r.get("note") or "", "source": r.get("source") or "", "xml": list(r.get("xml") or [])})
    order = {s: i for i, s in enumerate(known)}
    rows.sort(key=lambda x: ([n for n, _ in NOTATIONS].index(x["notation"]), order[x["status"]], x["construct"]))
    counts = {n: {s: sum(1 for x in rows if x["notation"] == n and x["status"] == s) for s in known} for n, _ in NOTATIONS}
    return {"profile": "sangam-standards-profile", "version": version,
            "inputs": {"compatibility": "sangam-ui/src/modeller/compatibility.json", "compatibilityGeneratedAt":
                       compat.get("generatedAt"), "feelPolicy": "SANGAM.md X.0g (ADR-D-26)"},
            "notations": [{"notation": n, "standard": s, "counts": counts[n]} for n, s in NOTATIONS],
            "constructs": rows, "feel": feel}


def _cell(s):
    return str(s).replace("|", "\\|").replace("\n", " ")


def markdown(p):
    out = ["# Sangam standards profile %s" % p["version"], "",
           "Generated by `sangam/deploy/evidence/gen_standards_profile.py` (M-052, R44, CF-8); do not edit. Inputs: "
           "`%s` (generated %s) and %s." % (p["inputs"]["compatibility"], p["inputs"]["compatibilityGeneratedAt"],
                                            p["inputs"]["feelPolicy"]), "",
           "The same rows drive the modeller's compatibility view and the pre-deploy check, and the engine enforces the "
           "rules at deployment (SangamBpmnParseListener). A construct not listed is not part of this profile.", "",
           "## Status meanings", "", "| Status | Meaning |", "|---|---|"]
    out += ["| %s | %s |" % (label, meaning) for _, label, meaning in STATUS]
    labels = {s: label for s, label, _ in STATUS}
    out += ["", "## Summary", "", "| Notation | " + " | ".join(label for _, label, _ in STATUS) + " |",
            "|---|" + "---|" * len(STATUS)]
    for n in p["notations"]:
        out.append("| %s | %s |" % (n["standard"], " | ".join(str(n["counts"][s]) for s, _, _ in STATUS)))
    for n, std in NOTATIONS:
        out += ["", "## %s" % std, "", "| Construct | Status | Editor | Base engine parses | Rule or note | Source |",
                "|---|---|---|---|---|---|"]
        for r in p["constructs"]:
            if r["notation"] == n:
                out.append("| %s | %s | %s | %s | %s | %s |" % (_cell(r["construct"]), labels[r["status"]],
                                                              _cell(r["modeller"] or ""), "yes" if r["engineParses"] else "no",
                                                              _cell(r["note"]), _cell(r["source"])))
    out += ["", "## FEEL policy (ADR-D-26)", "", "| Topic | Rule |", "|---|---|"]
    out += ["| %s | %s |" % (_cell(f["topic"]), _cell(f["rule"])) for f in p["feel"]]
    return "\n".join(out) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--version")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args(argv)
    with open(COMPAT, encoding="utf-8") as f:
        compat = json.load(f)
    with open(SANGAM_MD, encoding="utf-8") as f:
        feel = feel_policy(f.read())
    with open(POM, encoding="utf-8") as f:
        version = a.version or version_from_pom(f.read())
    p = profile(compat, feel, version)
    outputs = {OUT_JSON: json.dumps(p, ensure_ascii=False, indent=2) + "\n", OUT_MD: markdown(p)}
    stale = []
    for path, text in outputs.items():
        if a.check:
            old = open(path, encoding="utf-8").read() if os.path.exists(path) else None
            if old != text:
                stale.append(path)
        else:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(text)
    if stale:
        print("standards profile is stale: " + ", ".join(stale) + " (run gen_standards_profile.py)")
        return 1
    print("standards profile %s: %d constructs, %d FEEL rules" % (version, len(p["constructs"]), len(feel)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
