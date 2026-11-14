"""Offline licence check of the D-5 closure from the cached POMs (parent chain), against licence-policy.json."""
import importlib.util
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

SANGAM, TREE, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
M2 = os.path.join(os.path.expanduser("~"), ".m2", "repository")
spec = importlib.util.spec_from_file_location("cl", os.path.join(SANGAM, "deploy", "licence", "check_licences.py"))
cl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cl)
policy = json.load(open(os.path.join(SANGAM, "deploy", "licence", "licence-policy.json"), encoding="utf-8"))
allowed = set(policy["allowed"])
restricted = set(policy["restricted_licenses"])
forbidden = set(policy["forbidden_licenses"])
elections = {}
for e in policy.get("elections", []):
    for p in e.get("purls", []):  # prefix elections (veraPDF, pdf-validator only) never apply here
        elections[p] = e["elected"]
mins = {m["purl"]: m["atLeast"] for m in policy.get("min_versions", [])}
NS = "{http://maven.apache.org/POM/4.0.0}"


def pom_path(g, a, v):
    return os.path.join(M2, *g.split("."), a, v, f"{a}-{v}.pom")


def text(el, tag):
    x = el.find(NS + tag)
    if x is None:
        x = el.find(tag)
    return x.text.strip() if x is not None and x.text else None


def licences(g, a, v, depth=0):
    p = pom_path(g, a, v)
    if not os.path.isfile(p) or depth > 12:
        return None, f"pom missing {g}:{a}:{v}"
    root = ET.parse(p).getroot()
    lic = root.find(NS + "licenses")
    if lic is None:
        lic = root.find("licenses")
    if lic is not None and len(list(lic)):
        out = []
        for l in lic:
            out.append((text(l, "name") or "", text(l, "url") or ""))
        return out, f"{g}:{a}:{v}"
    par = root.find(NS + "parent")
    if par is None:
        par = root.find("parent")
    if par is None:
        return [], f"{g}:{a}:{v} (no licence, no parent)"
    return licences(text(par, "groupId"), text(par, "artifactId"), text(par, "version"), depth + 1)


rows = []
pat = re.compile(r"([\w.\-]+):([\w.\-]+):(jar|pom)(?::([\w.\-]+))?:([\w.\-]+):(compile|runtime|test|provided)")
seen = set()
for line in open(TREE, encoding="utf-8"):
    m = pat.search(line)
    if not m:
        continue
    g, a, _, clf, v, scope = m.groups()
    key = (g, a, v)
    if key in seen:
        continue
    seen.add(key)
    lics, src = licences(g, a, v)
    purl = f"pkg:maven/{g}/{a}@{v}"
    terms = []
    for name, url in lics or []:
        t = cl.normalise(name) if name else ""
        if t not in allowed | restricted | forbidden and url:
            t2 = cl.normalise(url)
            if t2 in allowed | restricted | forbidden:
                t = t2
        terms.append(t or f"?{name}|{url}")
    if purl in elections:
        verdict, terms_eff = "ALLOWED (election " + elections[purl] + ")", [elections[purl]]
    elif not terms:
        verdict, terms_eff = "UNKNOWN (fail)", []
    else:
        terms_eff = terms
        if any(t in forbidden for t in terms):
            verdict = "FORBIDDEN"
        elif any(t in restricted for t in terms):
            verdict = "RESTRICTED"
        elif all(t in allowed for t in terms):
            verdict = "ALLOWED" if len(terms) == 1 else "ALLOWED (all terms allowed)"
        else:
            verdict = "UNKNOWN (fail)"
    base = f"pkg:maven/{g}/{a}"
    if base in mins and cl.version_tuple(v) < cl.version_tuple(mins[base]):
        verdict += f"; BELOW min_versions {mins[base]}"
    rows.append((f"{g}:{a}:{v}", scope, " + ".join(terms_eff), src, verdict))

with open(OUT, "w", encoding="utf-8") as f:
    for r in rows:
        f.write("\t".join(r) + "\n")
bad = [r for r in rows if not r[4].startswith("ALLOWED") or "BELOW" in r[4]]
print(len(rows), "coordinates;", len(bad), "not allowed or flagged")
for r in bad:
    print("\t".join(r))
