# SPDX-License-Identifier: Apache-2.0
"""CycloneDX SBOM of a zero-dependency SDK package (npm, PyPI, NuGet, Packagist; M-147, CR-03).

The four language SDKs declare no third-party runtime dependency (NON-MAVEN-FINDINGS V-09): their SBOM is the
package itself, with its declared licence, and no components. This script reads the package manifest and writes
that SBOM. It never guesses: a declared runtime dependency FAILS (exit 1), because then a real dependency-resolving
SBOM generator is needed for that ecosystem (an owner decision), and a missing licence or name is BLOCKED (exit 2).
Development-only dependencies (devDependencies, require-dev, optional extras, PackageReference with
PrivateAssets="all") are not shipped and are ignored.

  npm        package.json   dependencies, optionalDependencies, peerDependencies, bundle(d)Dependencies must be empty
  pypi       pyproject.toml [project] dependencies must be empty
  nuget      *.csproj       every PackageReference / ProjectReference must have PrivateAssets="all"
  packagist  composer.json  require may hold only "php" and "ext-*" platform requirements

  python3 -I deploy/licence/package_sbom.py --ecosystem npm --manifest sdks/typescript/package.json \
      --version 0.1.0 --out sangam-sdk-npm-0.1.0.cdx.json
Exit codes: 0 written, 1 runtime dependency declared, 2 unreadable or incomplete manifest. Standard library only.
"""
import argparse
import json
import os
import re
import sys
import uuid
import xml.etree.ElementTree as ET

try:
    import tomllib
except ImportError:  # Python < 3.11
    tomllib = None


class ManifestError(Exception):
    pass


def _json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        raise ManifestError(f"cannot read {path}: {e}") from e


def npm(path):
    m = _json(path)
    deps = []
    for key in ("dependencies", "optionalDependencies", "peerDependencies", "bundleDependencies",
                "bundledDependencies"):
        v = m.get(key)
        if v:
            deps += [f"{key}: {d}" for d in (v if isinstance(v, list) else sorted(v))]
    name = m.get("name")
    purl_name = name.replace("@", "%40", 1) if name and name.startswith("@") else name
    return name, m.get("license"), f"pkg:npm/{purl_name}", deps


def pypi(path):
    if tomllib is None:
        raise ManifestError("Python 3.11 or later is needed to read pyproject.toml (tomllib)")
    try:
        with open(path, "rb") as f:
            m = tomllib.load(f)
    except (OSError, ValueError) as e:
        raise ManifestError(f"cannot read {path}: {e}") from e
    project = m.get("project") or {}
    name = project.get("name")
    lic = project.get("license")
    if isinstance(lic, dict):
        lic = lic.get("text") or lic.get("expression")
    norm = re.sub(r"[-_.]+", "-", name).lower() if name else None
    return name, lic, f"pkg:pypi/{norm}", [f"dependencies: {d}" for d in project.get("dependencies") or []]


def _private(el):
    if (el.get("PrivateAssets") or "").lower() == "all":
        return True
    child = el.find("PrivateAssets")
    return child is not None and (child.text or "").strip().lower() == "all"


def nuget(path):
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as e:
        raise ManifestError(f"cannot read {path}: {e}") from e
    deps = []
    for tag in ("PackageReference", "ProjectReference"):
        for el in root.iter(tag):
            if not _private(el):
                deps.append(f"{tag}: {el.get('Include')}")

    def prop(name):
        for el in root.iter(name):
            if el.text and el.text.strip():
                return el.text.strip()
        return None
    name = prop("PackageId") or prop("AssemblyName") or os.path.splitext(os.path.basename(path))[0]
    return name, prop("PackageLicenseExpression"), f"pkg:nuget/{name}", deps


def packagist(path):
    m = _json(path)
    deps = [f"require: {k}" for k in sorted(m.get("require") or {}) if k != "php" and not k.startswith("ext-")]
    lic = m.get("license")
    if isinstance(lic, list):
        lic = " OR ".join(lic) if lic else None
    name = m.get("name")
    return name, lic, f"pkg:composer/{name}", deps


READERS = {"npm": npm, "pypi": pypi, "nuget": nuget, "packagist": packagist}


def build(ecosystem, manifest, version):
    """Returns (sbom, runtime dependencies)."""
    name, lic, purl, deps = READERS[ecosystem](manifest)
    if not name:
        raise ManifestError(f"{manifest} declares no package name")
    if not lic:
        raise ManifestError(f"{manifest} declares no licence")
    component = {"type": "library", "name": name, "version": version, "purl": f"{purl}@{version}",
                 "bom-ref": f"{purl}@{version}", "licenses": [{"expression": lic}]}
    sbom = {"bomFormat": "CycloneDX", "specVersion": "1.6", "serialNumber": f"urn:uuid:{uuid.uuid4()}", "version": 1,
            "metadata": {"component": component,
                         "properties": [{"name": "sangam:sbom:generator",
                                         "value": f"deploy/licence/package_sbom.py ({ecosystem}, zero runtime dependencies)"}]},
            "components": [], "dependencies": [{"ref": component["bom-ref"], "dependsOn": []}]}
    return sbom, deps


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ecosystem", required=True, choices=sorted(READERS))
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    try:
        sbom, deps = build(a.ecosystem, a.manifest, a.version)
    except ManifestError as e:
        print(f"::error title=package SBOM BLOCKED::{e}")
        return 2
    if deps:
        for d in deps:
            print(f"::error title=package SBOM {a.ecosystem}::{a.manifest} declares a runtime dependency ({d}); "
                  "this script covers zero-dependency packages only")
        return 1
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(sbom, f, indent=2)
        f.write("\n")
    print(f"package SBOM {a.ecosystem}: {sbom['metadata']['component']['purl']} ({a.out}), no runtime dependency")
    return 0


if __name__ == "__main__":
    sys.exit(main())
