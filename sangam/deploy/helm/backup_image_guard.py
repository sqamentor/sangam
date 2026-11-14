# SPDX-License-Identifier: Apache-2.0
"""Decision 129 (RM-34) check on the rendered chart: the vendor backup images have no default and are required.

For each database type whose backup image the chart must not choose (oracle, sqlserver, mysql):
  1. its example values file, with the backup image removed (--set backup.<db>.image=), must FAIL to render, and
     the error must name backup.<db>.image (the schema or the template's required guard);
  2. the same example as committed (with the operator's image) must render, and the backup CronJobs must use that
     image.
Needs helm on PATH. Exit codes: 0 pass, 1 a rule broken, 2 helm missing or a file missing.

  python3 -I sangam/deploy/helm/backup_image_guard.py sangam/deploy/helm/sangam
Standard library only.
"""
import os
import re
import shutil
import subprocess
import sys

TYPES = ("oracle", "sqlserver", "mysql")


def helm_template(chart, values, *sets):
    cmd = ["helm", "template", "sangam", chart, "--kube-version", "1.30.0", "-f", values]
    for s in sets:
        cmd += ["--set", s]
    p = subprocess.run(cmd, capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def committed_image(values):
    """The backup.<db>.image of an example file (a two-level YAML read: enough for these flat example files)."""
    with open(values, encoding="utf-8") as f:
        text = f.read()
    m = re.search(r"^backup:\s*\n((?:[ \t].*\n|\s*\n)*)", text, re.MULTILINE)
    if not m:
        return None
    im = re.search(r"^\s+image:\s*(\S+)", m.group(1), re.MULTILINE)
    return im.group(1).strip("'\"") if im else None


def check(chart, run=helm_template):
    findings = []
    for db in TYPES:
        values = os.path.join(chart, "examples", f"values-{db}.yaml")
        if not os.path.isfile(values):
            raise FileNotFoundError(values)
        image = committed_image(values)
        if not image:
            findings.append(f"{values}: backup is enabled but sets no backup.{db}.image")
            continue
        code, _out, err = run(chart, values, f"backup.{db}.image=")
        if code == 0:
            findings.append(f"{db}: rendered without backup.{db}.image (a default or a missing guard)")
        elif f"backup.{db}.image" not in err and f"/backup/{db}/image" not in err:
            findings.append(f"{db}: render failed, but the error does not name backup.{db}.image: {err.strip()[:300]}")
        code, out, err = run(chart, values)
        if code != 0:
            findings.append(f"{db}: the committed example does not render: {err.strip()[:300]}")
        elif f"image: {image!r}".replace("'", '"') not in out and f"image: {image}" not in out:
            findings.append(f"{db}: the rendered backup jobs do not use {image}")
    return findings


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 1:
        print("usage: backup_image_guard.py CHART_DIR", file=sys.stderr)
        return 2
    if shutil.which("helm") is None:
        print("::error title=backup image guard BLOCKED::helm is not on PATH")
        return 2
    try:
        findings = check(argv[0])
    except FileNotFoundError as e:
        print(f"::error title=backup image guard BLOCKED::missing {e}")
        return 2
    for f in findings:
        print(f"::error title=backup image guard::{f}")
    print(f"backup image guard (decision 129): {'FAIL' if findings else 'PASS'}, {len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
