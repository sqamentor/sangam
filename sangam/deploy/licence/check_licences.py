# SPDX-License-Identifier: Apache-2.0
"""Sangam licence gate (SANGAM.md X.3 / X.3a LIC-1..LIC-11). Standard library only (runs air-gapped).

Checks
  1. every component of one or more CycloneDX JSON SBOMs against licence-policy.json:
     forbidden licences (also by name pattern), conditional licences (only for the named components),
     forbidden components (Oracle/Db2/MySQL drivers, GraalJS, Rhino/Nashorn/Jython/JRuby, AngularJS),
     version ceilings/floors (Liquibase < 5, Jackson >= 2.22.3);
  2. optionally the Spring Boot fat jar(s): no forbidden nested jar in BOOT-INF/lib (LIC-2..5, LIC-1, LIC-7);
  3. optionally a directory tree (e.g. an unpacked image or bundle) for stray forbidden driver jars.

Exit code 0 = pass, 1 = policy violation, 2 = usage / input error. Unknown licences are warnings unless
--strict (or the policy's "unknown_license": "fail").

  python -I deploy/licence/check_licences.py --policy deploy/licence/licence-policy.json \
      --sbom target/sangam-maven.cdx.json --sbom sangam-ui/sbom.cdx.json \
      --jar sangam-server/target/sangam-server-0.1.0.jar --report licence-report.md
"""
import argparse
import json
import os
import re
import sys
import zipfile
from urllib.parse import unquote


def load_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        print(f"ERROR: cannot read {path}: {e}", file=sys.stderr)
        sys.exit(2)


def version_tuple(v):
    parts = []
    for p in re.split(r"[.\-+]", v or ""):
        if p.isdigit():
            parts.append(int(p))
        else:
            m = re.match(r"(\d+)", p)
            if m:
                parts.append(int(m.group(1)))
            break
    return tuple(parts) or (0,)


def iter_components(bom):
    """Yields every component, including nested ones."""
    stack = list(bom.get("components") or [])
    while stack:
        c = stack.pop()
        yield c
        stack.extend(c.get("components") or [])


def licence_terms(component):
    """Returns (list of SPDX-ish ids/names, raw expression or None)."""
    ids, expr = [], None
    for entry in component.get("licenses") or []:
        if "expression" in entry:
            expr = entry["expression"]
            continue
        lic = entry.get("license") or {}
        if lic.get("id"):
            ids.append(lic["id"])
        elif lic.get("name"):
            ids.append(lic["name"])
    return ids, expr


def normalise(term):
    t = term.strip()
    aliases = {
        "The Apache Software License, Version 2.0": "Apache-2.0", "Apache License, Version 2.0": "Apache-2.0",
        "Apache 2.0": "Apache-2.0", "Apache License 2.0": "Apache-2.0", "Apache-2": "Apache-2.0",
        "The MIT License": "MIT", "MIT License": "MIT", "BSD": "BSD-3-Clause", "New BSD License": "BSD-3-Clause",
        "The BSD License": "BSD-3-Clause", "Eclipse Public License 2.0": "EPL-2.0",
        "Eclipse Public License - v 2.0": "EPL-2.0", "Eclipse Public License v2.0": "EPL-2.0",
        "Eclipse Public License 1.0": "EPL-1.0", "Eclipse Distribution License - v 1.0": "EDL-1.0",
        "EDL 1.0": "EDL-1.0", "GNU Lesser General Public License v2.1": "LGPL-2.1", "LGPL-2.1-only": "LGPL-2.1",
        "LGPL-2.1-or-later": "LGPL-2.1", "Public Domain": "LicenseRef-Public-Domain",
        "Mozilla Public License 2.0": "MPL-2.0", "MPL 2.0": "MPL-2.0", "PostgreSQL License": "PostgreSQL",
        "Universal Permissive License, Version 1.0": "UPL-1.0", "Bouncy Castle Licence": "Bouncy-Castle",
        "CDDL + GPLv2 with classpath exception": "CDDL-1.1",
        "GPL2 w/ CPE": "GPL-2.0-with-classpath-exception",
        "bpmn.io License": "LicenseRef-bpmn.io", "SEE LICENSE IN LICENSE": "LicenseRef-see-license-file",
        "GNU General Public License, version 2 with the GNU Classpath Exception": "GPL-2.0-with-classpath-exception",
    }
    return aliases.get(t, t)


def expression_alternatives(expr):
    """Splits an SPDX expression into OR-alternatives, each a list of AND-ed terms (no nesting depth > 1)."""
    expr = expr.replace("(", " ").replace(")", " ")
    alts = []
    for alt in re.split(r"\s+OR\s+", expr, flags=re.IGNORECASE):
        terms = []
        for t in re.split(r"\s+AND\s+", alt, flags=re.IGNORECASE):
            t = t.strip()
            if re.search(r"\s+WITH\s+Classpath-exception-2\.0", t, re.IGNORECASE):
                t = "GPL-2.0-with-classpath-exception"
            else:
                t = re.sub(r"\s+WITH\s+\S+", "", t, flags=re.IGNORECASE)
            if t:
                terms.append(normalise(t))
        alts.append(terms)
    return alts


class Checker:
    def __init__(self, policy, strict):
        self.p = policy
        self.allowed = set(policy.get("allowed", []))
        self.forbidden = set(policy.get("forbidden_licenses", []))
        self.forbidden_patterns = [re.compile(x) for x in policy.get("forbidden_license_name_patterns", [])]
        self.conditional = policy.get("conditional", [])
        self.strict = strict or policy.get("unknown_license") == "fail"
        self.errors, self.warnings, self.notes = [], [], []

    def term_status(self, term, purl):
        if term in self.forbidden or any(p.search(term) for p in self.forbidden_patterns):
            return "forbidden"
        if term in self.allowed:
            return "allowed"
        for c in self.conditional:
            if c["license"] == term:
                if any(purl.startswith(x) for x in c["components"]):
                    return "conditional"
                return "forbidden"
        return "unknown"

    def check_component(self, comp, source):
        name = comp.get("name", "?")
        group = comp.get("group")
        version = comp.get("version", "")
        purl = unquote(comp.get("purl") or "")
        purl_raw = comp.get("purl") or ""
        label = f"{group + ':' if group else ''}{name}@{version} [{source}]"
        if comp.get("scope") == "excluded":
            return

        for fc in self.p.get("forbidden_components", []):
            if purl.startswith(fc["purl"]) or purl_raw.startswith(fc["purl"]):
                self.errors.append(f"FORBIDDEN COMPONENT {label}: {fc['rule']}")
                return

        for mv in self.p.get("max_versions", []):
            if purl.startswith(mv["purl"]) and version_tuple(version) >= version_tuple(mv["below"]):
                self.errors.append(f"VERSION {label} must be below {mv['below']}: {mv['rule']}")
        for mv in self.p.get("min_versions", []):
            if purl.startswith(mv["purl"]) and version_tuple(version) < version_tuple(mv["atLeast"]):
                self.errors.append(f"VERSION {label} must be at least {mv['atLeast']}: {mv['rule']}")

        ids, expr = licence_terms(comp)
        if expr:
            alternatives = expression_alternatives(expr)
        elif ids:
            # several plain licence entries = the component is offered under each of them (dual licence)
            alternatives = [[normalise(i)] for i in ids]
        else:
            msg = f"NO LICENCE DATA {label}"
            (self.errors if self.strict else self.warnings).append(msg)
            return

        best = None
        for alt in alternatives:
            statuses = [self.term_status(t, purl) for t in alt]
            if all(s in ("allowed", "conditional") for s in statuses):
                best = "conditional" if "conditional" in statuses else "allowed"
                if best == "allowed":
                    break
            elif best is None and "unknown" in statuses and "forbidden" not in statuses:
                best = "unknown"
        shown = expr or " | ".join(ids)
        if best == "allowed":
            return
        if best == "conditional":
            cond = next((c["condition"] for c in self.conditional
                         if any(purl.startswith(x) for x in c["components"])), "")
            self.notes.append(f"CONDITIONAL {label}: {shown} -> {cond}")
        elif best == "unknown":
            msg = f"UNKNOWN LICENCE {label}: {shown} (add to the policy after review)"
            (self.errors if self.strict else self.warnings).append(msg)
        else:
            self.errors.append(f"FORBIDDEN LICENCE {label}: {shown}")

    def check_sbom(self, path):
        bom = load_json(path)
        if bom.get("bomFormat") != "CycloneDX":
            print(f"ERROR: {path} is not a CycloneDX JSON SBOM", file=sys.stderr)
            sys.exit(2)
        n = 0
        for comp in iter_components(bom):
            if comp.get("type") in ("application",) and not comp.get("purl"):
                continue
            self.check_component(comp, os.path.basename(path))
            n += 1
        return n

    def check_jar(self, path):
        patterns = [(re.compile(e["pattern"]), e["rule"]) for e in self.p.get("forbidden_jar_entries", [])]
        try:
            with zipfile.ZipFile(path) as z:
                names = z.namelist()
        except (OSError, zipfile.BadZipFile) as e:
            print(f"ERROR: cannot open jar {path}: {e}", file=sys.stderr)
            sys.exit(2)
        nested = [n for n in names if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")]
        if not nested:
            self.warnings.append(f"JAR {os.path.basename(path)} has no BOOT-INF/lib (not a Spring Boot fat jar?)")
        for n in nested:
            for rx, rule in patterns:
                if rx.search(n):
                    self.errors.append(f"FORBIDDEN JAR ENTRY {os.path.basename(path)}!{n}: {rule}")
        return len(nested)

    def check_tree(self, root):
        rx = re.compile(r"^(ojdbc|ucp|db2jcc|jcc-|mysql-connector)[^/\\]*\.jar$", re.IGNORECASE)
        count = 0
        for dirpath, _dirs, files in os.walk(root):
            for f in files:
                if rx.match(f):
                    rel = os.path.relpath(os.path.join(dirpath, f), root)
                    # bring-your-own driver directories may hold them only on the customer's side
                    self.errors.append(f"FORBIDDEN DRIVER FILE {rel} in {root} (LIC-2/3/4)")
                count += 1
        return count


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--policy", required=True)
    ap.add_argument("--sbom", action="append", default=[], help="CycloneDX JSON (repeatable)")
    ap.add_argument("--jar", action="append", default=[], help="Spring Boot fat jar to inspect (repeatable)")
    ap.add_argument("--tree", action="append", default=[], help="directory to scan for driver jars (repeatable)")
    ap.add_argument("--strict", action="store_true", help="unknown / missing licences fail")
    ap.add_argument("--report", help="write a Markdown report here")
    a = ap.parse_args()
    if not (a.sbom or a.jar or a.tree):
        ap.error("give at least one --sbom, --jar or --tree")

    ch = Checker(load_json(a.policy), a.strict)
    summary = []
    for s in a.sbom:
        summary.append(f"SBOM {s}: {ch.check_sbom(s)} components")
    for j in a.jar:
        summary.append(f"JAR {j}: {ch.check_jar(j)} nested jars")
    for t in a.tree:
        summary.append(f"TREE {t}: {ch.check_tree(t)} files")

    lines = ["# Sangam licence check", ""] + [f"- {s}" for s in summary] + [""]
    for title, items in (("Violations", ch.errors), ("Warnings", ch.warnings), ("Conditional (allowed with conditions)", ch.notes)):
        lines.append(f"## {title} ({len(items)})")
        lines += [f"- {i}" for i in sorted(set(items))] or ["- none"]
        lines.append("")
    verdict = "FAIL" if ch.errors else "PASS"
    lines.append(f"**Result: {verdict}**")
    text = "\n".join(lines) + "\n"
    print(text)
    if a.report:
        os.makedirs(os.path.dirname(os.path.abspath(a.report)), exist_ok=True)
        with open(a.report, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    gh_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if gh_summary:
        with open(gh_summary, "a", encoding="utf-8") as f:
            f.write(text)
    return 1 if ch.errors else 0


if __name__ == "__main__":
    sys.exit(main())
