# SPDX-License-Identifier: Apache-2.0
"""Static guards over CI workflows, Dockerfiles and scripts (CR-01, CR-10, CR-11, CR-12, CR-14, CR-18; decision 130).

  --check pins         every external `uses:` is pinned to a 40-hex commit SHA with a version comment
                       ("@<sha> # vX.Y.Z"); the allowlist in ci-tool-pins.json ("tag_pinned_actions") holds the
                       slsa-github-generator, which verifies its own tag (CR-10)
  --check images       every image reference on a release path carries @sha256: (images.txt lines, Dockerfile
                       `ARG *_IMAGE=` defaults and direct `FROM <image>` lines, decisions 253 and 261); a tag alone,
                       `latest` or `nonroot` fails (CR-10)
  --check silent       no status-masking construct: `|| true`, `|| :`, `continue-on-error: true` (or an expression),
                       `--exit-code 0`, `exit-code: "0"`, `--allow-missing`, `if-no-files-found: ignore` (CR-01, RM-12)
  --check curl         no `curl ... | sh` or `wget ... | sh`: tools are fixed release assets with a committed
                       SHA-256 (ci-tool-pins.json "tools", fetched by fetch_tool.py) (CR-11)
  --check npx          no `npx --yes`, `npx -y` or `npm exec --yes`: generators come from a committed lockfile (CR-12)
  --check npm-scripts  every `npm ci`, `npm install` or `npm i` passes --ignore-scripts (decision 130, CR-14)
  --check lockfiles    every package.json has package-lock.json, composer.json has composer.lock, *.csproj has
                       packages.lock.json, Cargo.toml has Cargo.lock under the given --lock-root dirs (CR-18)

A line (or the line above it) that carries "guard-allow: <reason>" is exempt from silent/npx/npm-scripts and is
listed in the output, so every exception is visible in review. A reason is required.

  python -I workflow_guards.py --check pins --check silent .github/workflows sangam/deploy/docker
Exit codes: 0 clean, 1 findings, 2 unreadable input. Standard library only.
"""
import argparse
import json
import os
import re
import sys

USES = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)(\s*#\s*(\S+))?")
SHA_REF = re.compile(r"@[0-9a-f]{40}$")
ALLOW = re.compile(r"guard-allow:\s*(\S.*)")
SILENT = [
    (re.compile(r"\|\|\s*true\b"), "'|| true' masks a failure"),
    (re.compile(r"\|\|\s*:(\s|$)"), "'|| :' masks a failure"),
    (re.compile(r"^\s*continue-on-error:\s*(true|\$\{\{)"), "continue-on-error lets a failing step pass"),
    (re.compile(r"--exit-code[ =]0\b"), "--exit-code 0 turns findings into a pass"),
    (re.compile(r"^\s*exit-code:\s*['\"]?0['\"]?\s*$"), "exit-code 0 turns findings into a pass"),
    (re.compile(r"--allow-missing\b"), "--allow-missing skips a required input"),
    (re.compile(r"if-no-files-found:\s*ignore\b"), "if-no-files-found: ignore hides a missing output"),
]
CURL_SH = re.compile(r"\b(curl|wget)\b[^|\n]*\|\s*(sudo\s+)?(ba|z|da)?sh\b")
NPX_YES = re.compile(r"\bnpx\s+(--yes|-y)\b|\bnpm\s+exec\s+(--yes|-y)\b")
NPM_INSTALL = re.compile(r"\bnpm\s+(ci|install|i)(\s|$)")
IMAGE_ARG = re.compile(r"^\s*ARG\s+\w*IMAGE\w*=(\S+)")
FROM_LINE = re.compile(r"^\s*FROM\s+(?:--\S+\s+)*(\S+)(?:\s+AS\s+(\S+))?\s*$", re.IGNORECASE)
SCANNED = (".yml", ".yaml", "Dockerfile", ".Dockerfile", ".sh", ".ps1", ".txt")
SKIP_DIRS = {"node_modules", "target", "bin", "obj", "vendor", ".git", "dist"}


def files(paths):
    for p in paths:
        if os.path.isfile(p):
            yield p
        elif os.path.isdir(p):
            for d, dirs, names in os.walk(p):
                dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
                for n in sorted(names):
                    if n.endswith(SCANNED):
                        yield os.path.join(d, n)
        else:
            raise OSError(f"{p} does not exist")


def read_lines(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


def allowed(lines, i):
    for j in (i, i - 1):
        if j >= 0:
            m = ALLOW.search(lines[j])
            if m:
                return m.group(1).strip()
    return None


def check_pins(path, lines, cfg):
    out = []
    tag_ok = tuple(cfg.get("tag_pinned_actions", []))
    for i, line in enumerate(lines):
        m = USES.match(line)
        if not m:
            continue
        ref = m.group(1).strip("'\"")
        if ref.startswith("./") or ref.startswith("docker://") and "@sha256:" in ref:
            continue
        if ref.startswith(tag_ok):
            continue
        if not SHA_REF.search(ref):
            out.append((path, i + 1, f"action not pinned to a commit SHA: {ref}"))
        elif not m.group(3):
            out.append((path, i + 1, f"pinned action has no '# vX.Y.Z' version comment: {ref}"))
    return out


def image_ok(ref):
    return "@sha256:" in ref and re.search(r"@sha256:[0-9a-f]{64}$", ref) is not None


def check_images(path, lines, _cfg):
    out = []
    is_list = os.path.basename(path) == "images.txt"
    is_dockerfile = os.path.basename(path).endswith("Dockerfile")
    stages = set()
    for i, line in enumerate(lines):
        ref = None
        if is_list:
            s = line.split("#", 1)[0].split()
            if s:
                ref = s[-1]
        else:
            m = IMAGE_ARG.match(line)
            if m:
                ref = m.group(1).strip("'\"")
            elif is_dockerfile:
                # decisions 253 and 261: a direct FROM <image> is checked like an ARG default; a FROM of an earlier
                # stage, of a build arg (${...}, whose default is checked above) or of scratch is not an image reference
                f = FROM_LINE.match(line)
                if f:
                    if f.group(2):
                        stages.add(f.group(2).lower())
                    base = f.group(1)
                    if base.lower() not in stages and base != "scratch" and not base.startswith("$"):
                        ref = base
        if ref is None or ref.startswith(("sangam-", "${")):
            continue   # first-party images are built by the release itself and pinned by the digest it records
        if not image_ok(ref):
            out.append((path, i + 1, f"image not pinned by digest: {ref}"))
    return out


def per_line(rules):
    def check(path, lines, _cfg):
        out = []
        for i, line in enumerate(lines):
            code = line.split(" #", 1)[0] if not line.lstrip().startswith("#") else ""
            if not code.strip():
                continue
            for rx, why in rules(code):
                if rx:
                    reason = allowed(lines, i)
                    if reason:
                        print(f"allowed ({reason}): {path}:{i + 1}: {line.strip()}")
                    else:
                        out.append((path, i + 1, f"{why}: {line.strip()}"))
        return out
    return check


def silent_rules(code):
    return [(rx.search(code), why) for rx, why in SILENT]


def curl_rules(code):
    return [(CURL_SH.search(code), "curl | sh runs an unverified download (use a pinned asset and its SHA-256)")]


def npx_rules(code):
    return [(NPX_YES.search(code), "npx --yes fetches an unpinned package (install it from a committed lockfile)")]


def npm_rules(code):
    m = NPM_INSTALL.search(code)
    return [(m and "--ignore-scripts" not in code, "npm install without --ignore-scripts (decision 130)")]


def check_lockfiles(roots):
    pairs = {"package.json": ["package-lock.json"], "composer.json": ["composer.lock"], "Cargo.toml": ["Cargo.lock"]}
    out = []
    for root in roots:
        if not os.path.isdir(root):
            raise OSError(f"{root} is not a directory")
        for d, dirs, names in os.walk(root):
            dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
            for n in names:
                need = pairs.get(n) or (["packages.lock.json"] if n.endswith(".csproj") else None)
                if need and not any(os.path.isfile(os.path.join(d, x)) for x in need):
                    out.append((os.path.join(d, n), 0, f"no {need[0]} next to {n} (CR-18)"))
    return out


CHECKS = {"pins": check_pins, "images": check_images, "silent": per_line(silent_rules),
          "curl": per_line(curl_rules), "npx": per_line(npx_rules), "npm-scripts": per_line(npm_rules)}


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="append", required=True, choices=sorted(CHECKS) + ["lockfiles"])
    ap.add_argument("--config", default=os.path.join(here, "ci-tool-pins.json"))
    ap.add_argument("--lock-root", action="append", default=[])
    ap.add_argument("paths", nargs="*")
    a = ap.parse_args(argv)
    findings = []
    try:
        with open(a.config, encoding="utf-8") as f:
            cfg = json.load(f)
        line_checks = [c for c in a.check if c != "lockfiles"]
        if line_checks:
            if not a.paths:
                raise OSError("no paths to scan")
            for path in files(a.paths):
                lines = read_lines(path)
                for c in line_checks:
                    findings += CHECKS[c](path.replace(os.sep, "/"), lines, cfg)
        if "lockfiles" in a.check:
            if not a.lock_root:
                raise OSError("--check lockfiles needs --lock-root")
            findings += check_lockfiles(a.lock_root)
    except (OSError, ValueError) as e:
        print(f"::error title=workflow guards BLOCKED::{e}")
        return 2
    for path, line, msg in findings:
        print(f"::error file={path},line={line}::{msg}")
    print(f"workflow guards {','.join(a.check)}: {'FAIL' if findings else 'PASS'}, {len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
