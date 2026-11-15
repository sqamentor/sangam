# SPDX-License-Identifier: Apache-2.0
"""Sangam licence gate (SANGAM.md X.3 / X.3a LIC-1..LIC-11). Standard library only (runs air-gapped).

Checks
  1. every component of one or more CycloneDX JSON SBOMs against licence-policy.json:
     forbidden licences (also by name pattern), conditional licences (only for the named components and
     distributions, checked before the forbidden list), recorded elections (decisions 90, 94, 117), components
     pending review in the decision register, the OS-package rule for image SBOMs (decision 118), forbidden
     components (Oracle/Db2/MySQL drivers, GraalJS, Rhino outside the PDF validator, Nashorn/Jython/JRuby,
     AngularJS), version ceilings/floors (Liquibase < 5, Jackson >= 2.22.3);
  2. optionally the Spring Boot fat jar(s): no forbidden nested jar in BOOT-INF/lib (LIC-2..5, LIC-1, LIC-7);
  3. optionally a directory tree (e.g. an unpacked image or bundle) for stray forbidden driver jars;
  4. with --build-scope, a build/test SBOM (CR-17): the build-tool exceptions of decision 127 are reported, unknown
     licences are reported and not failed, forbidden licences and build_scope_forbidden components fail.

A licence list without an SPDX expression and without an election is read as AND: every term must pass (RM-18).
Exit code 0 = pass, 1 = policy violation, 2 = usage / input error (a missing or unreadable input is never a pass).
Unknown licences fail when --strict is given or the policy says "unknown_license": "fail" (decision 116).

  python -I deploy/licence/check_licences.py --policy deploy/licence/licence-policy.json --distribution server \
      --sbom target/sbom/sangam-server.cdx.json --jar sangam-server/target/sangam-server-0.1.0.jar \
      --report licence-report.md
"""
import argparse
import json
import os
import re
import sys
import zipfile
from urllib.parse import unquote


class InputError(Exception):
    """An input that cannot be read: the check is BLOCKED, never PASS (exit 2)."""


def load_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        raise InputError(f"cannot read {path}: {e}") from e


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


CPE_ONLY = "GPL-2.0-only WITH Classpath-exception-2.0"
CPE_LATER = "GPL-2.0-or-later WITH Classpath-exception-2.0"

# Spellings seen in POMs and package metadata -> SPDX id (RM-18; w3x1-evidence/simgate.txt lists the 27 strict
# failures these cover). Matching ignores case and runs of white space.
_ALIASES = {
    "The Apache Software License, Version 2.0": "Apache-2.0", "Apache License, Version 2.0": "Apache-2.0",
    "The Apache License, Version 2.0": "Apache-2.0", "Apache License Version 2.0": "Apache-2.0",
    "Apache License, 2.0": "Apache-2.0", "Apache 2.0": "Apache-2.0", "Apache License 2.0": "Apache-2.0",
    "Apache-2": "Apache-2.0", "Apache 2": "Apache-2.0", "Apache Software License - Version 2.0": "Apache-2.0",
    "The MIT License": "MIT", "MIT License": "MIT", "MIT license": "MIT", "The MIT License (MIT)": "MIT",
    "BSD": "BSD-3-Clause", "New BSD License": "BSD-3-Clause", "The BSD License": "BSD-3-Clause",
    "Modified BSD": "BSD-3-Clause", "The BSD 3-Clause License (BSD3)": "BSD-3-Clause",
    "BSD 3-Clause License": "BSD-3-Clause", "BSD-3-Clause License": "BSD-3-Clause",
    "BSD 2-Clause License": "BSD-2-Clause", "The BSD 2-Clause License": "BSD-2-Clause", "BSD 2-Clause": "BSD-2-Clause",
    "Eclipse Public License 2.0": "EPL-2.0", "Eclipse Public License - v 2.0": "EPL-2.0",
    "Eclipse Public License v2.0": "EPL-2.0", "Eclipse Public License - Version 2.0": "EPL-2.0", "EPL 2.0": "EPL-2.0",
    "Eclipse Public License 1.0": "EPL-1.0", "Eclipse Public License - v 1.0": "EPL-1.0",
    "Eclipse Distribution License - v 1.0": "BSD-3-Clause", "EDL 1.0": "BSD-3-Clause", "EDL-1.0": "BSD-3-Clause",
    "GNU Lesser General Public License v2.1": "LGPL-2.1", "LGPL-2.1-only": "LGPL-2.1",
    "LGPL-2.1-or-later": "LGPL-2.1", "GNU Lesser General Public License, version 2.1": "LGPL-2.1",
    "GNU Lesser General Public License v3.0": "LGPL-3.0", "LGPL-3.0-only": "LGPL-3.0", "LGPL-3.0-or-later": "LGPL-3.0",
    "GNU Lesser General Public License, version 3": "LGPL-3.0", "GNU Lesser General Public License v3": "LGPL-3.0",
    "LGPL-2.0-only": "LGPL-2.0", "LGPL-2.0-or-later": "LGPL-2.0", "GNU Library General Public License v2": "LGPL-2.0",
    "GNU Lesser General Public License": "LicenseRef-LGPL-unversioned", "LGPL": "LicenseRef-LGPL-unversioned",
    "Public Domain": "LicenseRef-Public-Domain",
    "Mozilla Public License 2.0": "MPL-2.0", "MPL 2.0": "MPL-2.0", "Mozilla Public License, Version 2.0": "MPL-2.0",
    "Mozilla Public License 1.1": "MPL-1.1", "MPL 1.1": "MPL-1.1",
    "PostgreSQL License": "PostgreSQL",
    "Universal Permissive License, Version 1.0": "UPL-1.0", "Bouncy Castle Licence": "LicenseRef-Bouncy-Castle",
    "Bouncy-Castle": "LicenseRef-Bouncy-Castle",
    # Decision 263: io.github.x-stream:mxparser 1.2.2 POM licence name (no version); the jar META-INF/LICENSE is
    # version 1.2, so a LicenseRef is used, not SPDX "xpp" (whose text is 1.1.1).
    "Indiana University Extreme! Lab Software License": "LicenseRef-IU-Extreme-Lab",
    "CDDL + GPLv2 with classpath exception": "CDDL-1.1",
    "GPL2 w/ CPE": CPE_ONLY, "GPL-2.0-with-classpath-exception": CPE_ONLY,
    "The GNU General Public License (GPL), Version 2, With Classpath Exception": CPE_ONLY,
    "GNU General Public License, version 2 with the GNU Classpath Exception": CPE_ONLY,
    "bpmn.io License": "LicenseRef-bpmn.io", "SEE LICENSE IN LICENSE": "LicenseRef-see-license-file",
    "W3C license": "W3C", "CC0": "CC0-1.0", "Unicode License v3": "Unicode-3.0",
    # Debian copyright-format short names, as syft reports them for OS packages (decision 118)
    "GPL-2": "GPL-2.0-only", "GPL-2+": "GPL-2.0-or-later", "GPL-2.0+": "GPL-2.0-or-later", "GPL-3": "GPL-3.0-only",
    "GPL-3+": "GPL-3.0-or-later", "GPL-3.0+": "GPL-3.0-or-later", "LGPL-2": "LGPL-2.0", "LGPL-2+": "LGPL-2.0",
    "LGPL-2.1+": "LGPL-2.1", "LGPL-3": "LGPL-3.0", "LGPL-3+": "LGPL-3.0", "AGPL-3+": "AGPL-3.0-or-later",
    "AGPL-3.0+": "AGPL-3.0-or-later",
}
_ALIAS_KEY = {re.sub(r"\s+", " ", k).strip().casefold(): v for k, v in _ALIASES.items()}


def normalise(term):
    t = re.sub(r"\s+", " ", term).strip()
    return _ALIAS_KEY.get(t.casefold(), t)


_TOKEN = re.compile(r"\(|\)|[^\s()]+")


def expression_alternatives(expr):
    """SPDX expression -> list of OR-alternatives, each a list of AND-ed terms (disjunctive normal form).

    Precedence (SPDX annex D): WITH binds tightest, then AND, then OR; parentheses group."""
    tokens = _TOKEN.findall(expr)
    pos = 0

    def peek():
        return tokens[pos] if pos < len(tokens) else None

    def take():
        nonlocal pos
        pos += 1
        return tokens[pos - 1]

    def atom():
        if peek() == "(":
            take()
            v = disj()
            if peek() != ")":
                raise ValueError(f"unbalanced parenthesis in {expr!r}")
            take()
            return v
        words = []
        while peek() is not None and peek() not in ("(", ")") and peek().upper() not in ("AND", "OR"):
            words.append(take())
        if not words:
            raise ValueError(f"empty term in {expr!r}")
        term = " ".join(words)
        m = re.match(r"^(.*?)\s+WITH\s+(\S+)$", term, re.IGNORECASE)
        if m:
            base, exc = normalise(m.group(1)), m.group(2)
            if exc.lower() == "classpath-exception-2.0" and base in ("GPL-2.0", "GPL-2.0-only"):
                term = CPE_ONLY
            elif exc.lower() == "classpath-exception-2.0" and base in ("GPL-2.0+", "GPL-2.0-or-later"):
                term = CPE_LATER
            else:
                term = base          # other exceptions only add permissions; the base licence decides
        return [[normalise(term)]]

    def conj():
        left = atom()
        while peek() is not None and peek().upper() == "AND":
            take()
            right = atom()
            left = [a + b for a in left for b in right]
        return left

    def disj():
        left = conj()
        while peek() is not None and peek().upper() == "OR":
            take()
            left = left + conj()
        return left

    result = disj()
    if peek() is not None:
        raise ValueError(f"unexpected {peek()!r} in {expr!r}")
    return result


def purl_base(purl):
    """pkg:type/ns/name@version?q#s -> (pkg:type/ns/name, version)."""
    coord = purl.split("?", 1)[0].split("#", 1)[0]
    if "@" in coord:
        base, version = coord.rsplit("@", 1)
        return base, version
    return coord, ""


class Checker:
    def __init__(self, policy, strict, distribution=None, build_scope=False, image=False):
        self.p = policy
        self.allowed = set(policy.get("allowed", []))
        self.forbidden = set(policy.get("forbidden_licenses", []))
        self.restricted = set(policy.get("restricted_licenses", []))
        self.forbidden_patterns = [re.compile(x) for x in policy.get("forbidden_license_name_patterns", [])]
        self.conditional = policy.get("conditional", [])
        self.elections = policy.get("elections", [])
        self.pending = {e["purl"]: e for e in policy.get("pending_review", [])}
        # reviewed_licences: exact purl -> entry; the policy spells purls as an SBOM does (pkg:npm/%40scope/name@version)
        self.reviewed = {unquote(u): e for e in policy.get("reviewed_licences", []) for u in e["purls"]}
        self.os = policy.get("os_packages") or {}
        self.strict = strict or policy.get("unknown_license") == "fail"
        self.distribution = distribution
        self.build_scope = build_scope
        self.image = image
        self.errors, self.warnings, self.notes = [], [], []
        self.os_copyleft_seen = []
        exceptions = [e["purl"] for e in policy.get("build_tool_exceptions", [])]
        # Decision 229: exact test-scope coordinates; accepted in --build-scope, refused (any version) in a shipped SBOM.
        self.test_only = {e["purl"].rsplit("@", 1)[0]: e for e in policy.get("test_scope_exceptions", [])}
        exceptions += [e["purl"] for e in policy.get("test_scope_exceptions", [])]
        for t in policy.get("build_time_tools", []):
            c = t["coordinate"]
            if c.startswith("pypi:"):
                name, _, ver = c[len("pypi:"):].partition("==")
                exceptions.append(f"pkg:pypi/{name}@{ver}")
            else:
                g, a, v = c.split(":")
                exceptions.append(f"pkg:maven/{g}/{a}@{v}")
        self.build_exceptions = set(exceptions)

    # ------------------------------------------------------------------ scoping helpers
    def in_scope(self, entry):
        """An entry with allowed_in applies only to those distributions; without it, everywhere."""
        scope = entry.get("allowed_in")
        return scope is None or (self.distribution is not None and self.distribution in scope)

    @staticmethod
    def matches(purl, prefixes):
        """purl is compared unquoted, so a prefix written as in an SBOM (pkg:npm/%40bpmn-io/...) matches too."""
        purl = unquote(purl)
        return any(purl.startswith(unquote(x)) for x in prefixes)

    def is_os_package(self, purl):
        return self.image and self.matches(purl, self.os.get("purl_types", []))

    def election_for(self, purl):
        base, version = purl_base(purl)
        exact = f"{base}@{version}"
        for e in self.elections:
            if exact in e.get("purls", []):
                return e
            if e.get("purl_prefix") and base.startswith(e["purl_prefix"]) and version in e.get("versions", []):
                return e
        return None

    # ------------------------------------------------------------------ term rules
    def term_status(self, term, purl):
        """allowed | conditional | os-package | forbidden | unknown. Conditional entries come first (RM-18)."""
        for c in self.conditional:
            if c["license"] == term and self.matches(purl, c["components"]) and self.in_scope(c):
                return "conditional"
        bad = term in self.forbidden or any(p.search(term) for p in self.forbidden_patterns)
        if self.is_os_package(purl) and term in self.os.get("copyleft_allowed", []) and not term.startswith("AGPL"):
            return "os-package"
        if bad:
            return "forbidden"
        if term in self.restricted or any(c["license"] == term for c in self.conditional):
            return "forbidden"
        if term in self.allowed:
            return "allowed"
        return "unknown"

    def judge(self, alternatives, purl):
        """Best status over OR-alternatives; inside one alternative every AND-ed term must pass."""
        rank = {"allowed": 0, "conditional": 1, "os-package": 1, "unknown": 2, "forbidden": 3}
        best = "forbidden"
        for alt in alternatives:
            statuses = [self.term_status(t, purl) for t in alt]
            worst = max(statuses, key=rank.get) if statuses else "unknown"
            if rank[worst] < rank[best]:
                best = worst
        return best

    # ------------------------------------------------------------------ components
    def check_component(self, comp, source):
        name = comp.get("name", "?")
        group = comp.get("group")
        version = comp.get("version", "")
        purl = unquote(comp.get("purl") or "")
        purl_raw = comp.get("purl") or ""
        label = f"{group + ':' if group else ''}{name}@{version} [{source}]"
        if comp.get("scope") == "excluded":
            return

        if self.build_scope:
            self.check_build_component(comp, purl, label)
            return

        for fc in self.p.get("forbidden_components", []):
            if purl.startswith(fc["purl"]) or purl_raw.startswith(fc["purl"]):
                if self.in_scope(fc) and "allowed_in" in fc:
                    self.notes.append(f"SCOPED COMPONENT {label}: allowed in {self.distribution} ({fc['rule']})")
                    break
                self.errors.append(f"FORBIDDEN COMPONENT {label}: {fc['rule']}")
                return

        test_only = self.test_only.get(purl.split("?", 1)[0].split("#", 1)[0].rsplit("@", 1)[0])
        if test_only is not None:
            self.errors.append(f"TEST-SCOPE ONLY {label}: decision {test_only['decision']}, never in a shipped artefact")
            return

        if not self.check_pinned_exceptions(comp, purl, label):
            return

        for mv in self.p.get("max_versions", []):
            if purl.startswith(mv["purl"]) and version_tuple(version) >= version_tuple(mv["below"]):
                self.errors.append(f"VERSION {label} must be below {mv['below']}: {mv['rule']}")
        for mv in self.p.get("min_versions", []):
            if purl.startswith(mv["purl"]) and version_tuple(version) < version_tuple(mv["atLeast"]):
                self.errors.append(f"VERSION {label} must be at least {mv['atLeast']}: {mv['rule']}")

        base, ver = purl_base(purl)
        pending = self.pending.get(f"{base}@{ver}")
        if pending:
            self.errors.append(f"REVIEW REQUIRED {label}: {pending['register']} {pending['reason']}")
            return

        ids, expr = licence_terms(comp)
        try:
            alternatives = expression_alternatives(expr) if expr else [[normalise(i) for i in ids]] if ids else None
        except ValueError as e:
            self.errors.append(f"BAD LICENCE EXPRESSION {label}: {e}")
            return
        shown = expr or " AND ".join(ids)

        reviewed = self.reviewed.get(f"{base}@{ver}")
        if reviewed is not None:
            # The package metadata of this exact version names no licence (nothing, or a pointer such as
            # "SEE LICENSE IN LICENSE"); the licence was read from the package's own files and recorded. The entry
            # holds only while the metadata still says what the review saw; then the usual rules judge the recorded
            # licence (allowed, conditional for this component, forbidden ...).
            want = reviewed.get("declared")
            want = want if isinstance(want, list) else [want]   # one spelling, several, or None for no licence member
            if (shown or None) not in want:
                self.errors.append(f"REVIEWED LICENCE MISMATCH {label}: the metadata now declares {shown or 'nothing'}, "
                                   f"the review recorded {' or '.join(w or 'nothing' for w in want)}; re-verify and "
                                   "update reviewed_licences")
                return
            alternatives = [[reviewed["licence"]]]
            self.notes.append(f"REVIEWED {label}: {reviewed['licence']}, metadata declares {shown or 'nothing'} "
                              f"({reviewed['evidence']})")
            shown = reviewed["licence"]
        if alternatives is None:
            (self.errors if self.strict else self.warnings).append(f"NO LICENCE DATA {label}")
            return

        election = self.election_for(purl)
        if election is not None:
            offered = {t for alt in alternatives for t in alt}
            if not self.in_scope(election):
                self.errors.append(f"ELECTION OUT OF SCOPE {label}: decision {election['decision']} applies only in "
                                   f"{', '.join(election['allowed_in'])}, not in {self.distribution or 'an unnamed distribution'}")
                return
            if election["elected"] not in offered:
                self.errors.append(f"ELECTION MISMATCH {label}: elected {election['elected']} (decision "
                                   f"{election['decision']}) is not among the offered terms {sorted(offered)}; re-verify")
                return
            alternatives = [[election["elected"]]]
            self.notes.append(f"ELECTED {label}: {election['elected']} from {shown} (decision {election['decision']})")

        best = self.judge(alternatives, purl)
        if best == "allowed":
            return
        if best == "conditional":
            cond = next((c["condition"] for c in self.conditional
                         if self.matches(purl, c["components"]) and self.in_scope(c)), "")
            self.notes.append(f"CONDITIONAL {label}: {shown} -> {cond}")
        elif best == "os-package":
            self.os_copyleft_seen.append(label)
            self.notes.append(f"OS PACKAGE {label}: {shown} -> {self.os.get('condition', 'decision 118')}")
        elif best == "unknown":
            msg = f"UNKNOWN LICENCE {label}: {shown} (add to the policy after review)"
            (self.errors if self.strict else self.warnings).append(msg)
        else:
            self.errors.append(f"FORBIDDEN LICENCE {label}: {shown}")

    def check_build_component(self, comp, purl, label):
        """CR-17 / decision 127: build and test components. Exceptions are reported; unknown is reported only."""
        base, ver = purl_base(purl)
        for fc in self.p.get("build_scope_forbidden", []):
            if purl.startswith(fc["purl"]):
                self.errors.append(f"BUILD-SCOPE FORBIDDEN {label}: {fc['rule']}")
                return
        if f"{base}@{ver}" in self.build_exceptions:
            t = self.test_only.get(base)
            why = f"decision {t['decision']} test scope" if t else "decision 127 / build_time_tools"
            self.notes.append(f"BUILD-TOOL EXCEPTION {label}: never shipped ({why})")
            return
        ids, expr = licence_terms(comp)
        try:
            alternatives = expression_alternatives(expr) if expr else [[normalise(i) for i in ids]] if ids else None
        except ValueError as e:
            self.warnings.append(f"BAD LICENCE EXPRESSION (build) {label}: {e}")
            return
        if alternatives is None:
            self.warnings.append(f"NO LICENCE DATA (build, reported only) {label}")
            return
        election = self.election_for(purl)
        if election is not None and election["elected"] in {t for alt in alternatives for t in alt}:
            alternatives = [[election["elected"]]]
        best = self.judge(alternatives, purl)
        shown = expr or " AND ".join(ids)
        if best == "forbidden":
            self.errors.append(f"FORBIDDEN LICENCE (build) {label}: {shown}")
        elif best == "unknown":
            self.warnings.append(f"UNKNOWN LICENCE (build, reported only, decision 127) {label}: {shown}")

    def check_pinned_exceptions(self, comp, purl, label):
        """SG-27: namespace bans with exact-coordinate exceptions. Returns False when the component already failed."""
        coord = purl.split("?", 1)[0].split("#", 1)[0]
        base = coord.rsplit("@", 1)[0]
        exceptions = self.p.get("pinned_exceptions", [])
        exact = next((e for e in exceptions if e["purl"] == coord), None)
        if exact is not None:
            ids, expr = licence_terms(comp)
            try:
                terms = {t for alt in expression_alternatives(expr) for t in alt} if expr else {normalise(i) for i in ids}
            except ValueError as e:
                self.errors.append(f"BAD LICENCE EXPRESSION {label}: {e}")
                return False
            if exact["license"] not in terms:
                self.errors.append(f"PINNED EXCEPTION {label}: licence {sorted(terms) or 'none'} is not the verified "
                                   f"{exact['license']} ({exact['reason']}); re-verify")
                return False
            self.notes.append(f"EXCEPTION {label}: {exact['license']} -> {exact['reason']}")
            return True
        for e in exceptions:
            if e.get("watch") and e["purl"].rsplit("@", 1)[0] == base:
                self.errors.append(f"PINNED EXCEPTION {label}: only {e['purl']} is verified ({e['reason']}); "
                                   "a version change must be re-verified and re-listed")
                return False
        for ban in self.p.get("namespace_bans", []):
            if purl.startswith(ban["purl"]):
                self.errors.append(f"BANNED NAMESPACE {label}: {ban['rule']} (no exact exception listed)")
                return False
        return True

    def check_sbom(self, path):
        bom = load_json(path)
        if bom.get("bomFormat") != "CycloneDX":
            raise InputError(f"{path} is not a CycloneDX JSON SBOM")
        n = 0
        for comp in iter_components(bom):
            if comp.get("type") in ("application",) and not comp.get("purl"):
                continue
            self.check_component(comp, os.path.basename(path))
            n += 1
        return n

    def check_os_source_offer(self, root):
        """Decision 118 / CR-20: an image whose SBOM holds a GPL/LGPL OS package ships the source offer."""
        if not self.os_copyleft_seen:
            return
        offer = self.os.get("source_offer")
        if not offer or not os.path.isfile(os.path.join(root, offer)):
            self.errors.append(f"SOURCE OFFER MISSING: {len(self.os_copyleft_seen)} GPL/LGPL OS package(s) "
                               f"(first: {self.os_copyleft_seen[0]}) need {offer} (decision 118, CR-20)")

    def check_jar(self, path):
        patterns = [(re.compile(e["pattern"]), e["rule"]) for e in self.p.get("forbidden_jar_entries", [])]
        try:
            with zipfile.ZipFile(path) as z:
                names = z.namelist()
        except (OSError, zipfile.BadZipFile) as e:
            raise InputError(f"cannot open jar {path}: {e}") from e
        nested = [n for n in names if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")]
        if not nested:
            # a jar named as a fat jar must be one: an empty or thin jar is a FAIL, not a warning (CR-04)
            self.errors.append(f"JAR {os.path.basename(path)} has no BOOT-INF/lib (not a Spring Boot fat jar)")
        for n in nested:
            for rx, rule in patterns:
                if rx.search(n):
                    self.errors.append(f"FORBIDDEN JAR ENTRY {os.path.basename(path)}!{n}: {rule}")
        return len(nested)

    def check_tree(self, root):
        if not os.path.isdir(root):
            raise InputError(f"{root}: not a directory")
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


def annotate_sbom(path, policy):
    """SG-27 exceptions and recorded elections as CycloneDX properties, in place (RM-17: the election is in the SBOM)."""
    bom = load_json(path)
    exceptions = {e["purl"]: e for e in policy.get("pinned_exceptions", [])}
    evidence = policy.get("pinned_exceptions_evidence", "deploy/licence/SG-27-SD-301-exception.md")
    probe = Checker(policy, False)
    hit = elected = 0
    for comp in iter_components(bom):
        purl = unquote(comp.get("purl") or "")
        coord = purl.split("?", 1)[0]
        props = [p for p in comp.get("properties") or [] if not p.get("name", "").startswith(("sangam:exception", "sangam:election"))]
        e = exceptions.get(coord)
        if e:
            props += [{"name": "sangam:exception", "value": "SG-27 consumer licence-policy exception"},
                      {"name": "sangam:exception:reason", "value": e["reason"]},
                      {"name": "sangam:exception:verified-license", "value": e["license"]},
                      {"name": "sangam:exception:evidence", "value": evidence}]
            hit += 1
        el = probe.election_for(purl)
        if el:
            props += [{"name": "sangam:election", "value": el["elected"]},
                      {"name": "sangam:election:decision", "value": f"owner decision {el['decision']}"}]
            elected += 1
        if props:
            comp["properties"] = props
    meta = bom.setdefault("metadata", {})
    props = [p for p in meta.get("properties") or [] if p.get("name") not in ("sangam:exceptions", "sangam:elections")]
    props.append({"name": "sangam:exceptions", "value": f"SG-27 consumer licence-policy exception: {hit} component(s) under the owner "
                  f"exception for the Operaton DMN FEEL engine; evidence {evidence}"})
    props.append({"name": "sangam:elections", "value": f"{elected} component(s) carry a recorded licence election (owner decisions 90, 94, 117)"})
    meta["properties"] = props
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(bom, f, indent=2)
    return hit, elected


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--policy", required=True)
    ap.add_argument("--sbom", action="append", default=[], help="CycloneDX JSON (repeatable)")
    ap.add_argument("--jar", action="append", default=[], help="Spring Boot fat jar to inspect (repeatable)")
    ap.add_argument("--tree", action="append", default=[], help="directory to scan for driver jars (repeatable)")
    ap.add_argument("--distribution", help="distribution id from distributions.json (scopes allowed_in entries)")
    ap.add_argument("--image", action="store_true", help="the SBOMs describe a container image (OS-package rule, decision 118)")
    ap.add_argument("--source-root", default=".", help="where the os_packages source_offer path is resolved (with --image)")
    ap.add_argument("--build-scope", action="store_true", help="build/test SBOM review (CR-17, decision 127)")
    ap.add_argument("--strict", action="store_true", help="unknown / missing licences fail")
    ap.add_argument("--report", help="write a Markdown report here")
    ap.add_argument("--annotate", action="store_true",
                    help="write the SG-27 exception and election properties into each --sbom (in place) before checking")
    a = ap.parse_args(argv)
    if not (a.sbom or a.jar or a.tree):
        ap.error("give at least one --sbom, --jar or --tree")

    try:
        policy = load_json(a.policy)
        ch = Checker(policy, a.strict, distribution=a.distribution, build_scope=a.build_scope, image=a.image)
        summary = []
        if a.annotate:
            for s in a.sbom:
                hit, elected = annotate_sbom(s, policy)
                summary.append(f"ANNOTATED {s}: {hit} SG-27 exception component(s), {elected} election(s)")
        for s in a.sbom:
            summary.append(f"SBOM {s}: {ch.check_sbom(s)} components")
        for j in a.jar:
            summary.append(f"JAR {j}: {ch.check_jar(j)} nested jars")
        for t in a.tree:
            summary.append(f"TREE {t}: {ch.check_tree(t)} files")
        if a.image:
            ch.check_os_source_offer(a.source_root)
    except InputError as e:
        print(f"::error title=Licence gate input::{e}")
        print(f"ERROR: {e} (BLOCKED, never PASS)", file=sys.stderr)
        return 2

    scope = f" (distribution {a.distribution})" if a.distribution else ""
    lines = [f"# Sangam licence check{scope}", ""] + [f"- {s}" for s in summary] + [""]
    for title, items in (("Violations", ch.errors), ("Warnings", ch.warnings), ("Conditional, elected or excepted", ch.notes)):
        lines.append(f"## {title} ({len(set(items))})")
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
