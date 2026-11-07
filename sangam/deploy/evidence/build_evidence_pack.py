# SPDX-License-Identifier: Apache-2.0
"""Assembles the per-release audit evidence pack (SANGAM.md R52, GOV-3: CERT-In-empanelled audit).

Standard library only. Input: a directory per evidence area (see AREAS). Output:
  <out>/sangam-evidence-<version>/  01-sbom/ ... 08-native-profile/, MANIFEST.json, INDEX.md, SHA256SUMS
  <out>/sangam-evidence-<version>.tar.gz
Signing SHA256SUMS is a separate step (release.yml: cosign sign-blob), so this script also runs air-gapped.

A mandatory area with no files fails the build (exit 1): R52 says a release without the pack fails the Stage D gate.
--allow-missing downgrades that to an entry in INDEX.md, for dry runs only.

  python -I build_evidence_pack.py --version 1.2.3 --commit abc --out out \
      --area sbom=dist/sbom --area licence=dist/sbom/licence-report.md ...
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import sys
import tarfile

# key -> (directory, mandatory, title, what an auditor uses it for)
AREAS = {
    "sbom": ("01-sbom", True, "Software bill of materials (CycloneDX)", "Component inventory: Maven, npm, images"),
    "licence": ("02-licence", True, "Licence report", "X.3a LIC-1..11 gate result at this tag"),
    "dependency-check": ("03-dependency-check", True, "OWASP Dependency-Check report", "Known CVEs; fails at CVSS >= 7"),
    "codeql": ("04-codeql", True, "CodeQL summary", "Open code-scanning alerts at this ref"),
    "tests": ("05-tests", True, "Test reports", "Unit tests and the full Tier-1 database matrix"),
    "compliance": ("06-compliance", True, "sangam-compliance evidence report", "DPDP / CERT-In obligation status"),
    "config": ("07-config", True, "Configuration and version manifest", "Default chart values, support matrix"),
    "native": ("08-native-profile", False, "Native-light profile build evidence", "R7 smoke result"),
}

SCOPE_NOTE = """## Scope and limits

- This pack is evidence **from the vendor release pipeline**. It does not replace the audit itself: the audit is
  performed by a CERT-In-empanelled auditor, before hosting, every year and after major changes (GOV-3).
- The compliance report in `06-compliance` was generated from a **fresh development instance** (H2, no tenant data).
  It shows what the product implements. Each department must generate its own report from its production install
  (`GET /api/v1/compliance/reports`), which also covers logs, NTP and hosting evidence that depend on the deployment.
- Deployer evidence not in this pack: penetration test results, hosting and network configuration, 180-day log
  retention (R51), backup and restore records (T26/T27), incident contacts.

## Verifying

```
cosign verify-blob --bundle SHA256SUMS.sigstore.json \\
  --certificate-identity-regexp '^https://github.com/.+/\\.github/workflows/release\\.yml@refs/tags/' \\
  --certificate-oidc-issuer https://token.actions.githubusercontent.com SHA256SUMS
sha256sum --strict -c SHA256SUMS
```
"""


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def copy_source(src, dest):
    """Copies a file or a directory tree. Returns the number of files copied."""
    if os.path.isfile(src):
        os.makedirs(dest, exist_ok=True)
        shutil.copy2(src, dest)
        return 1
    count = 0
    if os.path.isdir(src):
        base = os.path.basename(os.path.normpath(src))
        for root, _dirs, files in os.walk(src):
            for f in files:
                rel = os.path.relpath(root, src)
                target = os.path.normpath(os.path.join(dest, base, rel))
                os.makedirs(target, exist_ok=True)
                shutil.copy2(os.path.join(root, f), target)
                count += 1
    return count


def pom_versions(pom):
    if not pom or not os.path.isfile(pom):
        return {}
    text = open(pom, encoding="utf-8").read()
    props = dict(re.findall(r"<(version\.[\w.-]+|maven\.compiler\.release)>([^<]+)<", text))
    return dict(sorted(props.items()))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", required=True)
    ap.add_argument("--commit", default="unknown")
    ap.add_argument("--out", required=True)
    ap.add_argument("--area", action="append", default=[], metavar="KEY=PATH", help="repeatable")
    ap.add_argument("--pom", help="parent pom.xml, for the version manifest")
    ap.add_argument("--image", action="append", default=[], metavar="REF@DIGEST", help="repeatable")
    ap.add_argument("--allow-missing", action="store_true", help="dry runs only")
    a = ap.parse_args()

    name = f"sangam-evidence-{a.version}"
    root = os.path.join(a.out, name)
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(root)

    counts = {k: 0 for k in AREAS}
    for spec in a.area:
        key, sep, path = spec.partition("=")
        if not sep or key not in AREAS:
            ap.error(f"bad --area {spec!r}; keys: {', '.join(AREAS)}")
        counts[key] += copy_source(path, os.path.join(root, AREAS[key][0]))

    missing = [k for k, (_d, mandatory, _t, _w) in AREAS.items() if mandatory and counts[k] == 0]

    manifest = {
        "product": "Sangam", "version": a.version, "commit": a.commit,
        "createdUtc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "dependencyVersions": pom_versions(a.pom), "images": a.image,
        "areas": {k: {"directory": v[0], "files": counts[k], "mandatory": v[1]} for k, v in AREAS.items()},
        "missingMandatoryAreas": missing,
    }
    with open(os.path.join(root, "MANIFEST.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")

    lines = [f"# Sangam {a.version}: audit evidence pack (R52)", "", f"Commit `{a.commit}`, created {manifest['createdUtc']}.", "",
             "| Directory | Evidence | Use | Files |", "|---|---|---|---|"]
    for k, (d, _m, title, use) in AREAS.items():
        lines.append(f"| `{d}` | {title} | {use} | {counts[k] if counts[k] else '**MISSING**' if k in missing else 'none'} |")
    with open(os.path.join(root, "INDEX.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n\n" + SCOPE_NOTE)

    sums = []
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            p = os.path.join(dirpath, f)
            sums.append(f"{sha256(p)}  ./{os.path.relpath(p, root).replace(os.sep, '/')}")
    with open(os.path.join(root, "SHA256SUMS"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(sorted(sums, key=lambda s: s.split("  ", 1)[1])) + "\n")

    if missing and not a.allow_missing:
        print("ERROR: mandatory evidence missing (R52): " + ", ".join(missing), file=sys.stderr)
        return 1

    with tarfile.open(os.path.join(a.out, name + ".tar.gz"), "w:gz") as t:
        t.add(root, arcname=name)
    print(f"evidence pack: {root} ({sum(counts.values())} files); missing: {missing or 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
