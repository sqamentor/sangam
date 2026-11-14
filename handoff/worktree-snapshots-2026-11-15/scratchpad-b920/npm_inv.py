import json, os, sys, re, glob

POLICY = sys.argv[1]
OUT = sys.argv[2]
projects = sys.argv[3:]  # pairs: label=dir
pol = json.load(open(POLICY, encoding="utf-8"))
ALLOWED = set(pol["allowed"])
FORBID = set(pol["forbidden_licenses"])
COND = pol["conditional"]
BPMN_PURLS = next(c["components"] for c in COND if c["license"] == "LicenseRef-bpmn.io")

def purl(name, ver):
    n = name.replace("@", "%40", 1) if name.startswith("@") else name
    return f"pkg:npm/{n}@{ver}"

def lic_of(pj):
    l = pj.get("license")
    if isinstance(l, dict):
        l = l.get("type")
    if not l and pj.get("licenses"):
        ls = pj["licenses"]
        if isinstance(ls, list):
            l = " OR ".join((x.get("type") if isinstance(x, dict) else str(x)) for x in ls)
        else:
            l = str(ls)
    return l

def licence_files(d):
    try:
        return sorted(f for f in os.listdir(d) if re.match(r"(?i)^(licen[cs]e|copying|notice|copyright)", f))
    except OSError:
        return []

def classify(expr):
    if not expr:
        return "unknown", None
    e = expr.strip()
    if re.match(r"(?i)see licen[cs]e in", e):
        return "seefile", None
    alts = [a.strip("() ") for a in re.split(r"\s+OR\s+", e.replace("(", " ").replace(")", " "))]
    statuses = []
    for a in alts:
        terms = [t.strip() for t in re.split(r"\s+AND\s+", a) if t.strip()]
        st = []
        for t in terms:
            if t in FORBID or re.match(r"^(A?GPL|LGPL)", t) and t not in ALLOWED:
                st.append("forbidden" if t in FORBID else "copyleft")
            elif t in ALLOWED:
                st.append("allowed")
            else:
                st.append("unknown:" + t)
        statuses.append((a, st))
    pref = lambda x: (0 if any(k in x[0] for k in ("MIT","Apache","BSD","ISC","0BSD")) else 1)
    for a, st in sorted(statuses, key=pref):
        if all(s == "allowed" for s in st):
            return ("allowed-dual" if len(alts) > 1 else "allowed"), a
    return "notallowed", ";".join(f"{a}={st}" for a, st in statuses)

OBLIG = {
    "MIT": "keep copyright + permission notice in distributed copies (third-party notices file)",
    "ISC": "keep copyright + permission notice",
    "BSD-2-Clause": "keep copyright notice + disclaimer in docs/notices",
    "BSD-3-Clause": "keep copyright notice + disclaimer; no endorsement use of names",
    "0BSD": "none",
    "Apache-2.0": "include licence text; carry upstream NOTICE file if present; state changes",
    "BlueOak-1.0.0": "keep licence text/notice",
    "CC0-1.0": "none", "Unlicense": "none",
    "MPL-2.0": "file-level copyleft: make source of MPL files (incl. modifications) available; keep notices",
    "CC-BY-4.0": "attribution required",
    "Python-2.0": "keep licence + copyright notice",
}

rows = []
for spec in projects:
    label, d = spec.split("=", 1)
    lockp = os.path.join(d, "package-lock.json")
    lock = json.load(open(lockp, encoding="utf-8"))
    pkgs = lock.get("packages", {})
    rel = os.path.relpath(lockp, os.path.dirname(os.path.dirname(os.path.dirname(d)))) if False else lockp
    root = pkgs.get("", {})
    direct_prod = set(root.get("dependencies", {}))
    direct_dev = set(root.get("devDependencies", {}))
    seen_paths = set()
    for path, meta in pkgs.items():
        if not path:
            continue
        seen_paths.add(path.replace("/", os.sep))
        name = meta.get("name") or path.split("node_modules/")[-1]
        ver = meta.get("version", "")
        integ = meta.get("integrity", "")
        resolved = meta.get("resolved", "")
        dev = meta.get("dev", False); devopt = meta.get("devOptional", False); opt = meta.get("optional", False); peer = meta.get("peer", False)
        if meta.get("link"):
            continue
        scope = "devDependency (transitive)" if dev else ("dev+optional" if devopt else "dependency (transitive)")
        if name in direct_prod and path == f"node_modules/{name}":
            scope = "dependency (direct)"
        elif name in direct_dev and path == f"node_modules/{name}":
            scope = "devDependency (direct)"
        if opt and not dev:
            scope += " optional"
        if opt and dev:
            scope += " optional"
        if peer:
            scope += " peer"
        shipped = not (dev or devopt)
        usage = "distribution (bundled into the UI dist by vite)" if (shipped and label == "sangam-ui") else ("build/test (dev only)" if not shipped else "runtime")
        inst_dir = os.path.join(d, *path.split("/"))
        inst_pj = os.path.join(inst_dir, "package.json")
        installed = os.path.exists(inst_pj)
        inst_ver = inst_lic = None
        lfiles = []
        if installed:
            try:
                pj = json.load(open(inst_pj, encoding="utf-8"))
                inst_ver = pj.get("version"); inst_lic = lic_of(pj)
                if not inst_lic and os.path.exists(os.path.join(inst_dir, "component.json")):
                    inst_lic = json.load(open(os.path.join(inst_dir, "component.json"), encoding="utf-8")).get("license")
            except Exception as ex:
                inst_lic = None
            lfiles = licence_files(inst_dir)
        lock_lic = meta.get("license")
        lic = inst_lic or lock_lic
        status, chosen = classify(lic)
        is_bpmn = purl(name, ver).rsplit("@", 1)[0] in BPMN_PURLS
        ev = []
        if installed:
            ev.append(f"{os.path.relpath(inst_pj, d)} license={inst_lic!r}" + (f" version={inst_ver}" if inst_ver != ver else ""))
            ev.append("licence files: " + (", ".join(lfiles) if lfiles else "NONE"))
        else:
            ev.append("NOT INSTALLED in node_modules; lockfile license field=" + repr(lock_lic))
        if installed and inst_ver and inst_ver != ver:
            ev.append(f"VERSION MISMATCH installed {inst_ver} vs lock {ver}")
        if installed and lock_lic and inst_lic and lock_lic != inst_lic:
            ev.append(f"lock license {lock_lic!r} differs")
        cond = ""
        spdx = lic or "NOASSERTION"
        oblig = ""
        if is_bpmn:
            spdx = "LicenseRef-bpmn.io" + (f" (declared {lic!r})" if lic else "")
            oblig = "bpmn.io licence: keep licence text; do not remove/obscure the bpmn.io watermark (project link) in rendered diagrams; use unmodified"
            if shipped:
                decision = "ACCEPTED WITH CONDITIONS"
                cond = "LIC-8: unmodified, watermark kept visible in modeller and viewer (BpmnDiagram.watermark.test.tsx); licence-policy conditional entry matches this purl"
            else:
                decision = "ACCEPTED FOR DEFINED USE"; cond = "dev only"
            if lic and re.match(r"(?i)see licen", lic) and not lfiles:
                decision = "REVIEW REQUIRED"; cond = "declares SEE LICENSE IN but no licence file installed"
        elif status in ("allowed", "allowed-dual"):
            first = chosen.split()[0] if chosen else lic
            oblig = OBLIG.get(chosen, OBLIG.get(first, "keep licence text and notices"))
            if status == "allowed-dual":
                decision = "ACCEPTED WITH CONDITIONS"; cond = f"dual/choice licence {lic!r}: record election of {chosen}"
            elif not installed and shipped:
                decision = "ACCEPTED WITH CONDITIONS"; cond = "licence only from lockfile registry metadata; confirm from package on install"
            elif installed and not lfiles and shipped:
                decision = "ACCEPTED WITH CONDITIONS"; cond = "no LICENSE file in package; notices file must reproduce the licence text from the declared SPDX id"
            else:
                decision = "ACCEPTED FOR DEFINED USE"; cond = "dev/build/test only, not shipped" if not shipped else "notice obligation in third-party notices"
            if "MPL-2.0" in (chosen or ""):
                decision = "ACCEPTED WITH CONDITIONS"; cond = (cond + "; " if cond else "") + "MPL-2.0 file-level copyleft: source of MPL files available on request"
            if "CC-BY" in (chosen or ""):
                decision = "ACCEPTED WITH CONDITIONS"; cond = (cond + "; " if cond else "") + "CC-BY: attribution required; confirm not shipped in dist"
        elif status == "seefile":
            decision = "REVIEW REQUIRED"; cond = "licence 'SEE LICENSE IN' a file: not an SPDX id; read the file and record it"
        elif status == "unknown":
            decision = "REVIEW REQUIRED"; cond = "no licence declared"
        else:
            if "OFL-1.1" in lic:
                decision = "REVIEW REQUIRED" if shipped else "ACCEPTED WITH CONDITIONS"
                oblig = "OFL-1.1: ship OFL text + copyright; do not sell font alone; Reserved Font Name rules for modified versions"
                cond = ("OFL-1.1 conditional in licence-policy.json covers only pkg:generic/pkg:github Noto fonts for PDF embedding (decisions 70/95); pkg:npm/@fontsource web-font use is not covered: check_licences.py classifies it FORBIDDEN (conditional licence, non-matching purl) and the strict release gate will fail; needs an owner decision") if shipped else "dev only"
            elif re.search(r"GPL|AGPL|SSPL|BUSL", lic):
                decision = "REJECTED FOR DEFINED USE" if shipped else "REVIEW REQUIRED"
                cond = f"copyleft/forbidden {lic} in {'shipped' if shipped else 'dev'} scope"
                oblig = "copyleft obligations"
            else:
                decision = "REVIEW REQUIRED"; cond = f"licence {lic!r} not in licence-policy.json allowed list ({chosen})"
        if not integ:
            ev.append("NO integrity in lockfile")
            if decision.startswith("ACCEPTED"):
                decision = "REVIEW REQUIRED"; cond = (cond + "; " if cond else "") + "no lockfile integrity hash"
        rows.append(dict(ecosystem="npm", name=name, version=ver, integrity=integ or "NONE", source=resolved or "NONE",
                         usage_scope=f"{scope}; {usage}", location=f"sangam/{label}/package-lock.json :: {path}",
                         spdx=spdx, obligations=oblig, decision=decision, conditions_or_reason=cond,
                         evidence=" | ".join(ev)))
    # extraneous installed packages not in the lockfile
    nm = os.path.join(d, "node_modules")
    if os.path.isdir(nm):
        for dirpath, dirs, files in os.walk(nm):
            if "package.json" in files and os.path.basename(os.path.dirname(dirpath)) in ("node_modules",) or ("package.json" in files and os.path.basename(os.path.dirname(os.path.dirname(dirpath))) == "node_modules" and os.path.basename(os.path.dirname(dirpath)).startswith("@")):
                relp = os.path.relpath(dirpath, d).replace(os.sep, "/")
                if relp.endswith("/.bin") or "/.cache" in relp or "/.vite" in relp:
                    continue
                if relp not in pkgs:
                    try:
                        pj = json.load(open(os.path.join(dirpath, "package.json"), encoding="utf-8"))
                    except Exception:
                        continue
                    if not pj.get("name"):
                        continue
                    rows.append(dict(ecosystem="npm", name=pj.get("name"), version=pj.get("version", ""), integrity="NONE (no lockfile entry)",
                                     source="node_modules only", usage_scope="unknown (installed, not in lockfile)",
                                     location=f"sangam/{label}/{relp}", spdx=lic_of(pj) or "NOASSERTION", obligations="",
                                     decision="REVIEW REQUIRED", conditions_or_reason="installed package with no lockfile entry (extraneous or stale install)",
                                     evidence=f"{relp}/package.json; licence files: {', '.join(licence_files(dirpath)) or 'NONE'}"))
            # do not descend into package internals except nested node_modules
            dirs[:] = [x for x in dirs if x == "node_modules" or (os.path.basename(dirpath) == "node_modules") or (os.path.basename(dirpath).startswith("@") and os.path.basename(os.path.dirname(dirpath)) == "node_modules")]

json.dump(rows, open(OUT, "w", encoding="utf-8"), indent=1)
from collections import Counter
print(len(rows), Counter(r["decision"] for r in rows))
for r in rows:
    if r["decision"] != "ACCEPTED FOR DEFINED USE":
        print(r["decision"], "|", r["name"], r["version"], "|", r["spdx"], "|", r["usage_scope"], "|", r["conditions_or_reason"][:140], "|", r["evidence"][:160])
