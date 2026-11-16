# SPDX-License-Identifier: Apache-2.0
"""Static guards over CI workflows, Dockerfiles and scripts (CR-01, CR-10, CR-11, CR-12, CR-14, CR-18; decision 130).

  --check pins         every external `uses:` is pinned to a 40-hex commit SHA with a version comment
                       ("@<sha> # vX.Y.Z"); the allowlist in ci-tool-pins.json ("tag_pinned_actions") holds the
                       slsa-github-generator, which verifies its own tag (CR-10)
  --check contexts     no `runner.*` expression outside a job's `steps:` (workflow-level or job-level `env`, `if`,
                       `with` of a reusable call, ...): the runner context exists only inside steps, and GitHub
                       rejects the whole workflow file ("Unrecognized named-value: 'runner'"), so no job starts
  --check images       every image reference on a release path carries @sha256: (images.txt lines, Dockerfile
                       `ARG *_IMAGE=` defaults and direct `FROM <image>` lines, decisions 253 and 261); a tag alone,
                       `latest` or `nonroot` fails (CR-10)
  --check deploy-images  RM-33: the same rule for compose `image:` lines (a ${VAR:-default} is judged by its
                       default), Helm values (repository/tag/digest, block or inline, or image: <name>:<tag>; an
                       empty image is supplied by the operator) and the SANGAM_IT_IMAGE_* integration-test images
                       of a workflow; first-party sangam-* images are pinned by the digest the release records
  --check runtimes     every java-, node-, python-, dotnet- and go-version of a workflow is an exact patch version,
                       also through a ${{ matrix.<key> }} list (RM-56, CR-18)
  --check silent       no status-masking construct: `|| true`, `|| :`, `continue-on-error: true` (or an expression),
                       `--exit-code 0`, `exit-code: "0"`, `--allow-missing`, `if-no-files-found: ignore` (CR-01, RM-12)
  --check curl         no `curl ... | sh` or `wget ... | sh`: tools are fixed release assets with a committed
                       SHA-256 (ci-tool-pins.json "tools", fetched by fetch_tool.py) (CR-11)
  --check npx          no `npx` in command position and no `npm exec` without `--no` (and never --yes/-y): on a CI
                       runner (no terminal) a bare `npx <tool>` whose package is missing installs the latest
                       release without asking, and npm 11 parses `npx --no <tool>` wrongly (it ran npm itself and
                       the step exited 128), so the one accepted form is `npm exec --no -- <tool>`, which fails
                       when the tool is not in the committed lockfile (CR-12)
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
# npx / npm exec in command position (line start, after run:/RUN, a shell separator, a subshell or an env prefix),
# so prose such as a step name or "--check npx" is not a command.
NPX_RUN = re.compile(r"(?:^|[;&|(`]|\brun:|\bRUN\b)\s*(?:\w+=\S+\s+)*(?:npx|npm\s+exec)\s+\S")
NPX_CMD = re.compile(r"(?:^|[;&|(`]|\brun:|\bRUN\b)\s*(?:\w+=\S+\s+)*npx\s+\S")
NPM_EXEC_NO = re.compile(r"\bnpm\s+exec\s+(?:--?[\w-]+(?:=\S+)?\s+)*--no\s+(?:--?[\w-]+(?:=\S+)?\s+)*--\s")
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


COMPOSE_IMAGE = re.compile(r"^\s*image:\s*['\"]?([^'\"\s#{][^'\"\s#]*|\$\{[^}]*\}[^'\"\s#]*)['\"]?\s*(#.*)?$")
ENV_DEFAULT = re.compile(r"^\$\{\w+:?-([^}]+)\}$")
IT_IMAGE_ENV = re.compile(r"^\s*SANGAM_IT_IMAGE_\w+:\s*['\"]?([^'\"\s#]*)['\"]?\s*(#.*)?$")
HELM_KEY = re.compile(r"^(\s*)(repository|tag|digest):\s*['\"]?([^'\"\s#]*)['\"]?\s*(#.*)?$")
HELM_INLINE = re.compile(r"^\s*image:\s*\{([^}]*)\}")


def first_party(ref):
    return ref.startswith(("sangam-", "${")) or "/sangam-" in ref


def compose_ref(raw):
    """A compose image: a literal reference, or a ${VAR:-default} whose default is what runs when VAR is unset."""
    m = ENV_DEFAULT.match(raw)
    if m:
        return m.group(1)
    return raw


def check_helm_values(path, lines):
    """RM-33: a third-party image in Helm values (repository + tag, block or inline form, or image: name:tag) needs
    its digest; an empty image ("" = the operator supplies it, decision 129) and first-party repositories, whose
    digests the release records, are not judged here."""
    out = []
    for i, line in enumerate(lines):
        inline = HELM_INLINE.match(line)
        if inline:
            fields = dict((k.strip(), v.strip().strip("'\"")) for k, _, v in
                          (p.partition(":") for p in inline.group(1).split(",")) if k.strip())
            repo, tag, digest = fields.get("repository", ""), fields.get("tag", ""), fields.get("digest", "")
            if repo and not first_party("/" + repo) and not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
                out.append((path, i + 1, f"image not pinned by digest: {repo}:{tag or '?'} (digest {digest or 'empty'})"))
            continue
        m = COMPOSE_IMAGE.match(line)
        if m and ":" in m.group(1):
            ref = m.group(1)
            if not first_party(ref) and not image_ok(ref):
                out.append((path, i + 1, f"image not pinned by digest: {ref}"))
            continue
        k = HELM_KEY.match(line)
        if not k or k.group(2) != "repository" or not k.group(3) or first_party("/" + k.group(3)):
            continue
        indent, block = k.group(1), {"repository": k.group(3)}
        # the sibling keys of the same mapping: same indent, until a line that is neither a sibling nor a comment
        for nxt in lines[i + 1:]:
            if not nxt.strip() or nxt.lstrip().startswith("#"):
                continue
            depth = len(nxt) - len(nxt.lstrip())
            if depth < len(indent):
                break
            s = HELM_KEY.match(nxt)
            if depth == len(indent) and s:
                block[s.group(2)] = s.group(3)
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", block.get("digest", "")):
            out.append((path, i + 1, f"image not pinned by digest: {block['repository']}:{block.get('tag') or '?'} "
                                     f"(digest {block.get('digest') or 'empty'})"))
    return out


def check_images(path, lines, _cfg, deploy=False):
    """--check images (deploy=False): images.txt and Dockerfiles. --check deploy-images (deploy=True, RM-33): compose
    files, Helm values and the integration-test images of workflows; nothing else is judged by it."""
    name = os.path.basename(path)
    is_values = name in ("values.yaml", "values.yml")
    is_compose = name.startswith(("docker-compose", "compose")) and name.endswith((".yml", ".yaml"))
    is_workflow = ".github/workflows/" in path.replace(os.sep, "/")
    if deploy:
        if is_values:
            return check_helm_values(path, lines)
        if not (is_compose or is_workflow):
            return []
    else:
        is_compose = is_workflow = False
    out = []
    is_list = name == "images.txt"
    is_dockerfile = name.endswith("Dockerfile")
    stages = set()
    for i, line in enumerate(lines):
        ref = None
        if is_compose:
            m = COMPOSE_IMAGE.match(line)
            if m:
                ref = compose_ref(m.group(1))
                if ref.startswith("${") or "/sangam-" in m.group(1):
                    continue   # first-party image, or a variable with no default (the operator names it)
        elif is_workflow:
            # RM-33: the integration-test images a workflow hands to Testcontainers (SANGAM_IT_IMAGE_*); an empty
            # value is a lane that does not run and pulls nothing (db-matrix.yml, M-108)
            m = IT_IMAGE_ENV.match(line)
            if m and m.group(1):
                ref = m.group(1)
        elif is_list:
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
    yes = NPX_YES.search(code)
    return [(yes, "npx --yes fetches an unpinned package (install it from a committed lockfile)"),
            (not yes and NPX_CMD.search(code),
             "npx installs a missing package without asking on CI and misparses --no on npm 11 (use npm exec --no -- <tool>)"),
            (not yes and not NPX_CMD.search(code) and NPX_RUN.search(code) and not NPM_EXEC_NO.search(code),
             "npm exec without --no installs a missing package without asking on CI (use npm exec --no -- <tool>)")]


def npm_rules(code):
    m = NPM_INSTALL.search(code)
    return [(m and "--ignore-scripts" not in code, "npm install without --ignore-scripts (decision 130)")]


RUNNER_EXPR = re.compile(r"\$\{\{[^}]*\brunner\.")
JOB_KEY = re.compile(r"^  [A-Za-z0-9_-]+:\s*(#.*)?$")
TOP_KEY = re.compile(r"^[A-Za-z0-9_-]+:")


def check_contexts(path, lines, _cfg):
    """The runner context is available only inside jobs.<id>.steps; anywhere else the workflow file is invalid."""
    if not path.endswith((".yml", ".yaml")):
        return []
    out, in_steps = [], False
    for i, line in enumerate(lines):
        if TOP_KEY.match(line) or JOB_KEY.match(line):
            in_steps = False
        elif re.match(r"^    steps:\s*(#.*)?$", line):
            in_steps = True
        if in_steps or line.lstrip().startswith("#"):
            continue
        if RUNNER_EXPR.search(line):
            out.append((path, i + 1, "runner context used outside steps (the workflow file is invalid; set the value "
                                     f"in a step, e.g. from $RUNNER_TEMP): {line.strip()}"))
    return out


RUNTIME_KEY = re.compile(r"\b(java|node|python|dotnet|go)-version:\s*(\"[^\"]*\"|'[^']*'|\$\{\{[^}]*\}\}|[^,}\s#]+)")
MATRIX_REF = re.compile(r"^\$\{\{\s*matrix\.([A-Za-z0-9_-]+)\s*\}\}$")
EXACT_RUNTIME = re.compile(r"^\d+\.\d+\.\d+(\+\d+)?$")


def matrix_values(lines, key):
    """Every value a matrix key takes in the file: its list (`key: [a, b]`) and each `include` entry that sets it,
    inline (`- { line: "25", key: "25.0.1" }`) or as a block item (`- key: 25.0.1` / `  key: 25.0.1`)."""
    k = re.escape(key)
    listed = re.compile(r"^\s*" + k + r":\s*\[([^\]]*)\]")
    inline = re.compile(r"[{,]\s*" + k + r":\s*(\"[^\"]*\"|'[^']*'|[^,}\s]+)")
    item = re.compile(r"^\s*(?:-\s+)?" + k + r":\s*(\"[^\"]*\"|'[^']*'|[^\s#\[{]+)\s*(#.*)?$")
    values = []
    for line in lines:
        m = listed.match(line)
        if m:
            values += [v.strip().strip("'\"") for v in m.group(1).split(",") if v.strip()]
            continue
        found = inline.findall(line) if "{" in line else []
        if not found:
            b = item.match(line)
            found = [b.group(1)] if b else []
        values += [v.strip().strip("'\"") for v in found if "${{" not in v]
    return values


def check_runtimes(path, lines, _cfg):
    """RM-56 (CR-18): every setup-* runtime is an exact patch version (25.0.1, 22.18.0, 3.13.7, 8.0.404), never a
    major line ("21"), a minor line ("3.13") or a wildcard ("8.0.x"), so a CI run names the runtime it used. A
    ${{ matrix.<key> }} value is judged by each item of that key's list in the same file; one that cannot be
    resolved there is a finding, never a pass."""
    if not path.endswith((".yml", ".yaml")):
        return []
    out = []
    for i, line in enumerate(lines):
        code = line.split(" #", 1)[0] if not line.lstrip().startswith("#") else ""
        for m in RUNTIME_KEY.finditer(code):
            kind, value = m.group(1), m.group(2).strip("'\"").strip()
            mx = MATRIX_REF.match(value)
            if mx:
                values = matrix_values(lines, mx.group(1))
                if not values:
                    out.append((path, i + 1, f"{kind}-version from matrix.{mx.group(1)}, whose list is not in this file"))
                    continue
            else:
                values = [value]
            for v in values:
                if not EXACT_RUNTIME.match(v):
                    out.append((path, i + 1, f"{kind}-version {v!r} is not an exact patch version (RM-56): {line.strip()}"))
    return out


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


CHECKS = {"pins": check_pins, "contexts": check_contexts, "images": check_images,
          "deploy-images": lambda path, lines, cfg: check_images(path, lines, cfg, deploy=True), "runtimes": check_runtimes,
          "silent": per_line(silent_rules),
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
