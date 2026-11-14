# SPDX-License-Identifier: Apache-2.0
"""Register check of one distribution SBOM against decision-register.json (CR-08, CR-09, M-148 new-transitive diff).

For every Maven or npm component in the SBOM (first-party io.github.sqamentor (decision 210) and @sangam excluded):
  - no register entry for that exact version         -> FAIL "NOT IN REGISTER" (a new or changed transitive needs a
                                                         register row with evidence before it ships)
  - entry, but this distribution is not in its list  -> FAIL "NOT ACCEPTED FOR <dist>"
  - entry with decision REVIEW REQUIRED or REJECTED  -> FAIL with that decision
OS packages (pkg:deb, pkg:apk, pkg:rpm) are judged by the OS-package rule of check_licences.py --image, not here.
Any other purl type is listed as outside the register (reported, not judged), so nothing passes unseen.

--baseline OLD.cdx.json additionally prints the components added and removed against an earlier SBOM of the same
distribution (the new-transitive diff); added components still have to pass the register check above.

Image distributions are checked against the register rows of the distribution they carry ("server-image" and
"server-distroless-image" -> "server"; "scope_as" in distributions.json wins).

Exit codes: 0 pass, 1 findings, 2 unreadable input (BLOCKED). Standard library only.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_licences import InputError, iter_components, load_json, purl_base  # noqa: E402

ACCEPTED = ("ACCEPTED FOR DEFINED USE", "ACCEPTED WITH CONDITIONS")
OS_TYPES = ("pkg:deb/", "pkg:apk/", "pkg:rpm/")
# Decision 210: the Maven groupId is io.github.sqamentor (sweep done); an org.sangam component is not first-party.
FIRST_PARTY = ("pkg:maven/io.github.sqamentor/", "pkg:npm/%40sangam/")
REGISTER_TYPES = ("pkg:maven/", "pkg:npm/")


def register_scope(dist_id, distributions):
    """Maps a distribution id to the id its register rows are filed under."""
    for d in distributions.get("distributions", []):
        if d.get("id") == dist_id:
            if d.get("scope_as"):
                return d["scope_as"]
            if d.get("kind") == "image":
                base = dist_id[:-len("-image")] if dist_id.endswith("-image") else dist_id
                return base[:-len("-distroless")] if base.endswith("-distroless") else base
            return dist_id
    raise InputError(f"distribution {dist_id!r} is not in distributions.json")


def component_key(comp):
    purl = comp.get("purl") or ""
    if not purl:
        return None
    base, version = purl_base(purl)
    return f"{base}@{version}" if version else base


def check(sbom, register, scope):
    """Returns (findings, outside). findings: list of strings; outside: purls not judged here."""
    entries = register.get("components") or {}
    findings, outside = [], []
    seen = set()
    for comp in iter_components(sbom):
        key = component_key(comp)
        if key is None or key in seen:
            continue
        seen.add(key)
        if key.startswith(FIRST_PARTY) or key.startswith(OS_TYPES):
            continue
        if not key.startswith(REGISTER_TYPES):
            outside.append(key)
            continue
        e = entries.get(key)
        if e is None:
            findings.append(f"{key}: NOT IN REGISTER (new or changed component; add a register row with evidence)")
        elif e.get("decision") not in ACCEPTED:
            findings.append(f"{key}: {e.get('decision')} in the register")
        elif scope not in (e.get("distributions") or []):
            findings.append(f"{key}: NOT ACCEPTED FOR {scope} (register lists {', '.join(e.get('distributions') or [])})")
    return findings, sorted(outside)


def diff(sbom, baseline):
    def keys(b):
        return {k for k in (component_key(c) for c in iter_components(b)) if k}
    new, old = keys(sbom), keys(baseline)
    return sorted(new - old), sorted(old - new)


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sbom")
    ap.add_argument("--distribution", required=True)
    ap.add_argument("--register", default=os.path.join(here, "decision-register.json"))
    ap.add_argument("--distributions", default=os.path.join(here, "distributions.json"))
    ap.add_argument("--baseline", help="earlier SBOM of the same distribution, for the new-transitive diff")
    a = ap.parse_args(argv)
    try:
        sbom = load_json(a.sbom)
        if not isinstance(sbom, dict) or sbom.get("bomFormat") != "CycloneDX":
            raise InputError(f"{a.sbom} is not a CycloneDX SBOM")
        scope = register_scope(a.distribution, load_json(a.distributions))
        findings, outside = check(sbom, load_json(a.register), scope)
        added = removed = None
        if a.baseline:
            added, removed = diff(sbom, load_json(a.baseline))
    except InputError as e:
        print(f"::error title=register check BLOCKED::{e}")
        return 2
    if added is not None:
        print(f"new-transitive diff against {a.baseline}: {len(added)} added, {len(removed)} removed")
        for k in added:
            print(f"  + {k}")
        for k in removed:
            print(f"  - {k}")
    if outside:
        print(f"{len(outside)} component(s) of a type outside the register (reported, not judged here):")
        for k in outside:
            print(f"  ? {k}")
    for f in findings:
        print(f"::error title=register check {a.distribution}::{f}")
    print(f"register check {a.distribution} (register scope {scope}): {'FAIL' if findings else 'PASS'}, "
          f"{len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
