# SPDX-License-Identifier: Apache-2.0
"""SBOM-to-artefact match for one distribution (CR-03, M-147): the published SBOM lists exactly what the artefact ships.

Input: the per-module CycloneDX SBOM from cyclonedx-maven-plugin makeBom (shipped scopes) and the built artefact:
  --jar  a Spring Boot fat jar: the shipped libraries are BOOT-INF/lib/*.jar
  --dir  a directory of jars (lib-ext)
Rules:
  1. every jar the artefact ships must be a component of the SBOM, else FAIL "SHIPPED BUT NOT IN SBOM";
  2. every library component of the SBOM must be shipped, else FAIL "IN SBOM BUT NOT SHIPPED", except a component
     whose purl starts with an "excluded_from_jar" entry of the distribution in distributions.json (a dependency the
     build deliberately keeps out of the artefact, for example the LGPL MariaDB driver, which ships in lib-ext). Such
     a component is removed from the output SBOM, with its dependency-graph edges;
  3. a jar whose file name starts with a "boot_tooling" entry (launcher tooling the Spring Boot plugin adds, not a
     dependency) is listed and kept out of rule 1.
  4. a distribution with "select" (lib-ext: the MariaDB driver that the server build keeps out of its fat jar) takes
     only the components whose purl starts with a "select" entry from the input SBOM (the SBOM of the module that
     declares them, "sbom_from" in distributions.json); every other component is dropped before rules 1 and 2.
--out writes the SBOM of the artefact (the input minus the excluded components); that file is the release asset.
Components of type other than library/framework (the module itself, BOM imports with type=pom) are not shipped jars.

Exit codes: 0 match, 1 mismatch, 2 unreadable input (BLOCKED). Standard library only.
"""
import argparse
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_licences import InputError, iter_components, load_json  # noqa: E402

LIB_TYPES = ("library", "framework")


def distribution(dists, dist_id):
    for d in dists.get("distributions", []):
        if d.get("id") == dist_id:
            return d
    raise InputError(f"distribution {dist_id!r} is not in distributions.json")


def shipped_jars(jar=None, directory=None):
    if jar:
        try:
            with zipfile.ZipFile(jar) as z:
                names = [n for n in z.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")]
        except (OSError, zipfile.BadZipFile) as e:
            raise InputError(f"cannot read {jar}: {e}") from e
        if not names:
            raise InputError(f"{jar} has no BOOT-INF/lib: not a Spring Boot fat jar")
        return sorted(n.rsplit("/", 1)[1] for n in names)
    if not os.path.isdir(directory or ""):
        raise InputError(f"{directory} is not a directory")
    return sorted(n for n in os.listdir(directory) if n.endswith(".jar") and not n.endswith("-sources.jar"))


def qualifiers(purl):
    q = purl.split("?", 1)[1].split("#", 1)[0] if "?" in purl else ""
    return dict(p.split("=", 1) for p in q.split("&") if "=" in p)


def expected_file(comp):
    """The file name a Maven component has inside the artefact, or None when it is not a shipped jar."""
    purl = comp.get("purl") or ""
    if comp.get("type") not in LIB_TYPES or not purl.startswith("pkg:maven/"):
        return None
    q = qualifiers(purl)
    if q.get("type", "jar") != "jar":
        return None
    name, version = comp.get("name"), comp.get("version")
    if not name or not version:
        return None
    return f"{name}-{version}" + (f"-{q['classifier']}" if q.get("classifier") else "") + ".jar"


def match(sbom, jars, excluded_prefixes=(), tooling_prefixes=()):
    """Returns (findings, excluded_refs, tooling_jars)."""
    findings, excluded_refs = [], set()
    by_file = {}
    for comp in iter_components(sbom):
        f = expected_file(comp)
        if f is None:
            continue
        if any((comp.get("purl") or "").startswith(p) for p in excluded_prefixes):
            excluded_refs.add(comp.get("bom-ref") or comp.get("purl"))
            continue
        by_file.setdefault(f, comp)
    shipped = set(jars)
    tooling = sorted(j for j in shipped if any(j.startswith(p) for p in tooling_prefixes))
    for j in sorted(shipped - set(by_file) - set(tooling)):
        findings.append(f"{j}: SHIPPED BUT NOT IN SBOM")
    for f in sorted(set(by_file) - shipped):
        findings.append(f"{by_file[f].get('purl')}: IN SBOM BUT NOT SHIPPED (expected {f})")
    return findings, excluded_refs, tooling


def prune(sbom, refs):
    """Removes the components with these bom-refs (top level and nested) and their dependency edges."""
    def keep(c):
        return (c.get("bom-ref") or c.get("purl")) not in refs

    def walk(comps):
        out = []
        for c in comps:
            if keep(c):
                if c.get("components"):
                    c = dict(c, components=walk(c["components"]))
                out.append(c)
        return out

    sbom = dict(sbom, components=walk(sbom.get("components") or []))
    if "dependencies" in sbom:
        sbom["dependencies"] = [dict(d, dependsOn=[x for x in d.get("dependsOn", []) if x not in refs])
                                for d in sbom["dependencies"] if d.get("ref") not in refs]
    return sbom


def select(sbom, prefixes):
    """Keeps only the components whose purl starts with one of the prefixes (rule 4)."""
    drop = {c.get("bom-ref") or c.get("purl") for c in iter_components(sbom)
            if not any((c.get("purl") or "").startswith(p) for p in prefixes)}
    return prune(sbom, drop)


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sbom")
    ap.add_argument("--distribution", required=True)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--jar")
    src.add_argument("--dir")
    ap.add_argument("--distributions", default=os.path.join(here, "distributions.json"))
    ap.add_argument("--out", help="write the SBOM of the artefact here")
    a = ap.parse_args(argv)
    try:
        sbom = load_json(a.sbom)
        if not isinstance(sbom, dict) or sbom.get("bomFormat") != "CycloneDX":
            raise InputError(f"{a.sbom} is not a CycloneDX SBOM")
        d = distribution(load_json(a.distributions), a.distribution)
        jars = shipped_jars(a.jar, a.dir)
        if d.get("select"):
            sbom = select(sbom, d["select"])
            if not any(True for _ in iter_components(sbom)):
                raise InputError(f"{a.sbom} has no component matching {d['select']}")
    except InputError as e:
        print(f"::error title=SBOM match BLOCKED::{e}")
        return 2
    findings, excluded, tooling = match(sbom, jars, d.get("excluded_from_jar", []), d.get("boot_tooling", []))
    for j in tooling:
        print(f"launcher tooling (not a dependency): {j}")
    for r in sorted(excluded):
        print(f"kept out of the artefact by the build (excluded_from_jar): {r}")
    for f in findings:
        print(f"::error title=SBOM match {a.distribution}::{f}")
    if a.out and not findings:
        with open(a.out, "w", encoding="utf-8", newline="\n") as f:
            json.dump(prune(sbom, excluded), f, indent=2)
            f.write("\n")
    print(f"SBOM match {a.distribution}: {'FAIL' if findings else 'PASS'}, {len(jars)} shipped jar(s), "
          f"{len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
