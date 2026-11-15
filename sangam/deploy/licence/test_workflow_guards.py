# SPDX-License-Identifier: Apache-2.0
"""Unit tests of workflow_guards.py and fetch_tool.py (CR-01, CR-10, CR-11, CR-12, CR-14, CR-18; decision 130).

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml). Written in Wave 4,
run in the test phase."""
import hashlib
import io
import json
import os
import re
import shutil
import tarfile
import tempfile
import unittest

import fetch_tool as ft
import workflow_guards as wg

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
WORKFLOWS = os.path.join(REPO, ".github", "workflows")
with open(os.path.join(HERE, "ci-tool-pins.json"), encoding="utf-8") as f:
    PINS = json.load(f)
SHA = "11d5960a326750d5838078e36cf38b85af677262"


def scan(check, text):
    return wg.CHECKS[check]("w.yml", text.splitlines(), PINS)


class Pins(unittest.TestCase):
    def test_sha_with_comment_passes(self):
        self.assertEqual(scan("pins", f"    - uses: actions/checkout@{SHA} # v4.4.0"), [])

    def test_tag_fails(self):
        self.assertEqual(len(scan("pins", "      - uses: actions/checkout@v4")), 1)

    def test_sha_without_comment_fails(self):
        self.assertIn("version comment", scan("pins", f"uses: actions/checkout@{SHA}")[0][2])

    def test_local_and_allowlisted(self):
        self.assertEqual(scan("pins", "    uses: ./.github/workflows/db-matrix.yml\n"
                                      "    uses: slsa-framework/slsa-github-generator/.github/workflows/g.yml@v2.1.0"), [])

    def test_committed_workflows_match_the_pin_list(self):
        rx = re.compile(r"uses:\s*([\w.-]+/[\w.-]+)[^@\s]*@([0-9a-f]{40}) # (\S+)")
        for name in sorted(os.listdir(WORKFLOWS)):
            with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as f:
                for m in rx.finditer(f.read()):
                    self.assertEqual(PINS["actions"].get(m.group(1)), f"{m.group(2)} {m.group(3)}", f"{name}: {m.group(0)}")


class Contexts(unittest.TestCase):
    """A `runner.*` expression outside steps makes GitHub reject the whole file (release.yml, 2026-11-14: the images
    job had `BIN: ${{ runner.temp }}/bin` in its job-level env, so no release job could start)."""

    JOB = "jobs:\n  images:\n    runs-on: ubuntu-24.04\n"

    def test_job_level_env_fails(self):
        found = scan("contexts", self.JOB + "    env:\n      BIN: ${{ runner.temp }}/bin\n    steps:\n      - run: x\n")
        self.assertEqual([f[1] for f in found], [5])

    def test_workflow_level_env_and_job_if_fail(self):
        self.assertEqual(len(scan("contexts", "env:\n  T: ${{ runner.temp }}\njobs:\n")), 1)
        self.assertEqual(len(scan("contexts", self.JOB + "    if: ${{ runner.os == 'Linux' }}\n")), 1)

    def test_inside_steps_and_next_job(self):
        text = (self.JOB + "    steps:\n      - run: echo ${{ runner.temp }}\n        env:\n          T: ${{ runner.os }}\n"
                "  next:\n    env:\n      T: ${{ runner.temp }}\n")
        self.assertEqual([f[1] for f in scan("contexts", text)], [10])

    def test_comment_shell_variable_and_other_files(self):
        self.assertEqual(scan("contexts", self.JOB + "    # ${{ runner.temp }}\n    env:\n      B: $RUNNER_TEMP/bin\n"), [])
        self.assertEqual(wg.CHECKS["contexts"]("x.sh", ["T=${{ runner.temp }}"], PINS), [])

    def test_committed_workflows(self):
        for name in sorted(os.listdir(WORKFLOWS)):
            with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as f:
                self.assertEqual(wg.CHECKS["contexts"](name, f.read().splitlines(), PINS), [], name)


class SemgrepGatePaths(unittest.TestCase):
    """security.yml SEMGREP_GATE_PATHS (decisions 133 and 177): every listed path exists. The list named
    sangam-integration .../document/sign after that code had moved to sangam-esign, so the Semgrep gate step failed
    on its own path check (main, 2026-11-14) before scanning anything."""

    def gate_paths(self):
        with open(os.path.join(WORKFLOWS, "security.yml"), encoding="utf-8") as f:
            lines = f.read().splitlines()
        start = next(i for i, line in enumerate(lines) if line.strip().startswith("SEMGREP_GATE_PATHS:"))
        self.assertEqual(lines[start].split(":", 1)[1].strip(), ">-", "the list is a folded block scalar")
        indent = len(lines[start]) - len(lines[start].lstrip())
        paths = []
        for line in lines[start + 1:]:
            if line.strip() and len(line) - len(line.lstrip()) <= indent:
                break
            paths += line.split()
        return paths

    def test_every_gate_path_exists(self):
        paths = self.gate_paths()
        self.assertGreater(len(paths), 5, "the list was not read")
        self.assertEqual([p for p in paths if not os.path.exists(os.path.join(REPO, *p.split("/")))], [])

    def test_the_rest_core_module_is_gated(self):
        # decision 285 moved Permissions and the audit adapters from sangam-rest to sangam-rest-core; the gate list
        # (decisions 133 and 177: authorisation, money and audit paths) must follow the code
        self.assertIn("sangam/sangam-rest-core/src/main", self.gate_paths())

    def test_the_signing_module_is_gated(self):
        self.assertIn("sangam/sangam-esign/src/main", self.gate_paths())


class Images(unittest.TestCase):
    def test_images(self):
        ok = "ARG JRE_IMAGE=eclipse-temurin:25@sha256:" + "a" * 64
        self.assertEqual(wg.check_images("x.Dockerfile", [ok, "ARG SERVER_IMAGE=sangam-server:dev"], PINS), [])
        self.assertEqual(len(wg.check_images("x.Dockerfile", ["ARG RUNTIME_IMAGE=gcr.io/distroless/base:nonroot"], PINS)), 1)
        self.assertEqual(len(wg.check_images("images.txt", ["# c", "runtime  postgres:17", ""], PINS)), 1)

    def test_direct_from_lines(self):
        # decisions 253 and 261: a FROM that names an image directly must carry the digest too
        pinned = "maven:3.9.11-eclipse-temurin-25@sha256:" + "b" * 64
        ok = [f"FROM {pinned} AS build", "FROM build AS layout", "FROM --platform=$BUILDPLATFORM ${JRE_IMAGE} AS runtime",
              "FROM scratch", "FROM runtime", "from Build AS other"]
        self.assertEqual(wg.check_images("x.Dockerfile", ok + ["FROM build"], PINS), [])
        bad = wg.check_images("x.Dockerfile", ["FROM maven:3.9.11-eclipse-temurin-25 AS build", "FROM node:22 AS n",
                                               "FROM gcr.io/distroless/base:nonroot", "FROM postgres@sha256:short"], PINS)
        self.assertEqual([n for _, n, _ in bad], [1, 2, 3, 4])
        # only Dockerfiles: a FROM in another scanned file (a shell heredoc, a workflow) is not judged here
        self.assertEqual(wg.check_images("notes.sh", ["FROM node:22"], PINS), [])


class Silent(unittest.TestCase):
    def test_masking_constructs_fail(self):
        for text in ("run: mvn verify || true", "    continue-on-error: true", "  continue-on-error: ${{ x }}",
                     "  gitleaks git --exit-code 0", '          exit-code: "0"', "  save.sh --allow-missing",
                     "          if-no-files-found: ignore", "  cmd || :"):
            self.assertEqual(len(scan("silent", text)), 1, text)

    def test_clean_and_allowed(self):
        self.assertEqual(scan("silent", "git diff --exit-code -- x\n# a comment with || true\n"), [])
        self.assertEqual(scan("silent", "n=$(grep -c x f || true)  # guard-allow: grep exits 1 on zero matches"), [])
        self.assertEqual(scan("silent", "# guard-allow: count only\nn=$(grep -c x f || true)"), [])


class LineEndings(unittest.TestCase):
    """The repo .gitattributes keeps POSIX shell scripts and container build files LF in every checkout (W4-C,
    2026-11-13). A CR byte in one of them breaks sh ("set -eu\\r") or an image build, so any CR fails here."""

    LF_NAMES = re.compile(r"(\.sh|\.envsh|\.Dockerfile)$|^(Dockerfile.*|mvnw|entrypoint|.*-entrypoint)$")

    def lf_files(self):
        for d, dirs, names in os.walk(REPO):
            dirs[:] = [x for x in dirs if x not in wg.SKIP_DIRS and x != ".git"]
            for n in names:
                if self.LF_NAMES.search(n):
                    yield os.path.join(d, n)

    def test_name_rule(self):
        for n in ("load-images.sh", "17-sangam-dsc.envsh", "server.Dockerfile", "Dockerfile", "mvnw", "entrypoint"):
            self.assertTrue(self.LF_NAMES.search(n), n)
        for n in ("save-images.ps1", "mvnw.cmd", "notes.shx", "README.md"):
            self.assertFalse(self.LF_NAMES.search(n), n)

    def test_no_carriage_return_in_shell_scripts(self):
        seen, bad = 0, []
        for p in self.lf_files():
            seen += 1
            with open(p, "rb") as f:
                if b"\r" in f.read():
                    bad.append(os.path.relpath(p, REPO).replace(os.sep, "/"))
        self.assertGreater(seen, 0, "no shell script found: the walk is broken")
        self.assertEqual([], bad, "CR bytes (check .gitattributes, then re-checkout or convert to LF)")

    def test_gitattributes_keeps_the_rules(self):
        with open(os.path.join(REPO, ".gitattributes"), encoding="utf-8") as f:
            rules = {tuple(line.split()[:3]) for line in f if line.strip() and not line.startswith("#")}
        for pattern in ("*.sh", "*.envsh", "Dockerfile*", "*.Dockerfile", "mvnw", "entrypoint", "*-entrypoint"):
            self.assertIn((pattern, "text", "eol=lf"), rules, pattern)
        for pattern in ("*.ps1", "*.cmd", "*.bat"):
            self.assertIn((pattern, "text", "eol=crlf"), rules, pattern)


class Downloads(unittest.TestCase):
    def test_curl_sh(self):
        self.assertEqual(len(scan("curl", "curl -sSfL https://x/install.sh | sh -s -- -b /bin")), 1)
        self.assertEqual(len(scan("curl", "wget -qO- https://x | sudo bash")), 1)
        self.assertEqual(scan("curl", "curl -sSfL -o a.tgz https://x/a.tgz"), [])

    def test_npx(self):
        self.assertEqual(len(scan("npx", "npx --yes @cyclonedx/cyclonedx-npm@2.1.0")), 1)
        self.assertEqual(scan("npx", "npm exec --no -- tsc -p ."), [])
        self.assertEqual(scan("npx", "(cd ts && npm ci --ignore-scripts && npm exec --no -- tsc -p x.json)"), [])
        # npm 11 misparses "npx --no <tool>": on CI run 43717963580 it ran npm itself and the step exited 128.
        self.assertEqual(len(scan("npx", "npx --no tsc -p .")), 1)
        self.assertEqual(len(scan("npx", "npx --no-install tsc")), 1)
        self.assertEqual(len(scan("npx", "npm exec --no tsc")), 1)
        # A bare npx on a CI runner installs a missing package without asking (the 2026-11-15 ui e2e run fetched
        # playwright@1.65.1 that way): it must pass --no so a package missing from the lockfile fails the step.
        self.assertEqual(len(scan("npx", "npx tsc -p .")), 1)
        self.assertEqual(len(scan("npx", "npx playwright install --with-deps chromium")), 1)
        self.assertEqual(len(scan("npx", "npm exec -- tsc")), 1)
        self.assertEqual(len(scan("npx", "npx --yes @cyclonedx/cyclonedx-npm@2.1.0")), 1)

    def test_npm_scripts(self):
        self.assertEqual(len(scan("npm-scripts", "run: npm ci --no-audit")), 1)
        self.assertEqual(len(scan("npm-scripts", "RUN npm install")), 1)
        self.assertEqual(scan("npm-scripts", "npm ci --ignore-scripts --no-audit && npm run build"), [])


class Lockfiles(unittest.TestCase):
    def test_missing_lock(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "a"))
            os.makedirs(os.path.join(d, "b", "node_modules", "x"))
            for p in ("a/package.json", "a/composer.json", "a/composer.lock", "b/node_modules/x/package.json", "p.csproj"):
                open(os.path.join(d, p), "w").close()
            out = sorted(msg for _, _, msg in wg.check_lockfiles([d]))
        self.assertEqual(len(out), 2)
        self.assertTrue(any("package-lock.json" in m for m in out))
        self.assertTrue(any("packages.lock.json" in m for m in out))

    def test_cli_exit_codes(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "w.yml")
            with open(p, "w", encoding="utf-8") as f:
                f.write("run: npm ci\n")
            self.assertEqual(wg.main(["--check", "npm-scripts", p]), 1)
            self.assertEqual(wg.main(["--check", "silent", p]), 0)
            self.assertEqual(wg.main(["--check", "silent", os.path.join(d, "nope")]), 2)


class FetchTool(unittest.TestCase):
    def archive(self, d, members):
        path = os.path.join(d, "src.tar.gz")
        with tarfile.open(path, "w:gz") as tar:
            for name, data in members.items():
                info = tarfile.TarInfo(name)
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        with open(path, "rb") as f:
            return path, hashlib.sha256(f.read()).hexdigest()

    UNSIGNED = {"kind": "none", "reason": "test"}
    KEYLESS = {"kind": "cosign-keyless", "checksums_url": "https://x/sums.txt", "certificate_url": "https://x/sums.pem",
               "signature_url": "https://x/sums.sig", "identity": "https://github.com/v/t/.github/workflows/r.yaml@refs/heads/main",
               "issuer": "https://token.actions.githubusercontent.com"}
    BUNDLE = {"kind": "cosign-bundle", "checksums_url": "https://x/sums.txt", "bundle_url": "https://x/sums.sigstore.json",
              "identity": "https://github.com/v/t/.github/workflows/r.yaml@refs/heads/main",
              "issuer": "https://token.actions.githubusercontent.com"}

    def pins(self, sha, signature=UNSIGNED):
        t = {"version": "1", "url": "https://x/syft.tar.gz", "sha256": sha, "binary": "syft"}
        if signature is not None:
            t["signature"] = signature
        return {"tools": {"syft": t}}

    def fetcher(self, src, sums_text, calls):
        def fetch(url, path):
            calls.append(url)
            if url == "https://x/syft.tar.gz":
                shutil.copyfile(src, path)
            elif url == "https://x/sums.txt":
                with open(path, "w", encoding="utf-8") as f:
                    f.write(sums_text)
            else:
                with open(path, "w", encoding="utf-8") as f:
                    f.write("signature material")
        return fetch

    class Cosign:
        def __init__(self, returncode=0, missing=False):
            self.returncode, self.missing, self.args = returncode, missing, None

        def __call__(self, args, **kw):
            if self.missing:
                raise FileNotFoundError("cosign")
            self.args = args
            return type("R", (), {"returncode": self.returncode, "stdout": "", "stderr": "error: bad signature"})()

    def test_verified_install(self):
        with tempfile.TemporaryDirectory() as d:
            src, sha = self.archive(d, {"README": b"r", "syft": b"#!bin"})
            out, status = ft.install("syft", self.pins(sha), os.path.join(d, "bin"),
                                     fetch=lambda u, p: shutil.copyfile(src, p))
            with open(out, "rb") as f:
                self.assertEqual(f.read(), b"#!bin")
            self.assertIn("NO vendor signature", status)

    def test_hash_mismatch_unpacks_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            src, _ = self.archive(d, {"syft": b"x"})
            with self.assertRaises(ValueError):
                ft.install("syft", self.pins("0" * 64), os.path.join(d, "bin"), fetch=lambda u, p: shutil.copyfile(src, p))
            self.assertFalse(os.path.exists(os.path.join(d, "bin", "syft")))

    def test_missing_signature_record_fails_before_any_fetch(self):
        calls = []
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                ft.install("syft", self.pins("0" * 64, signature=None), os.path.join(d, "bin"),
                           fetch=lambda u, p: calls.append(u))
        self.assertEqual(calls, [])

    def test_keyless_signature_then_checksum_then_asset(self):
        with tempfile.TemporaryDirectory() as d:
            src, sha = self.archive(d, {"syft": b"#!bin"})
            calls, cosign = [], self.Cosign()
            out, status = ft.install("syft", self.pins(sha, self.KEYLESS), os.path.join(d, "bin"),
                                     fetch=self.fetcher(src, f"{'f' * 64}  other.tar.gz\n{sha}  syft.tar.gz\n", calls),
                                     run=cosign)
            self.assertTrue(os.path.exists(out))
            self.assertEqual(calls[-1], "https://x/syft.tar.gz")  # the asset only after the signature check
            a = cosign.args
            self.assertEqual(a[:2], ["cosign", "verify-blob"])
            self.assertEqual(a[a.index("--certificate-identity") + 1], self.KEYLESS["identity"])
            self.assertEqual(a[a.index("--certificate-oidc-issuer") + 1], self.KEYLESS["issuer"])
            self.assertIn("--signature", a)
            self.assertIn("--certificate", a)
            self.assertIn("vendor signature verified", status)

    def test_bundle_uses_new_bundle_format(self):
        with tempfile.TemporaryDirectory() as d:
            src, sha = self.archive(d, {"syft": b"#!bin"})
            cosign = self.Cosign()
            ft.install("syft", self.pins(sha, self.BUNDLE), os.path.join(d, "bin"),
                       fetch=self.fetcher(src, f"{sha} *syft.tar.gz\n", []), run=cosign)
            self.assertIn("--bundle", cosign.args)
            self.assertIn("--new-bundle-format", cosign.args)

    def test_bad_signature_fetches_no_asset(self):
        with tempfile.TemporaryDirectory() as d:
            src, sha = self.archive(d, {"syft": b"#!bin"})
            calls = []
            with self.assertRaises(ValueError):
                ft.install("syft", self.pins(sha, self.KEYLESS), os.path.join(d, "bin"),
                           fetch=self.fetcher(src, f"{sha}  syft.tar.gz\n", calls), run=self.Cosign(returncode=1))
            self.assertNotIn("https://x/syft.tar.gz", calls)
            self.assertFalse(os.path.exists(os.path.join(d, "bin", "syft")))

    def test_signed_checksum_file_must_agree_with_the_pin(self):
        with tempfile.TemporaryDirectory() as d:
            src, sha = self.archive(d, {"syft": b"#!bin"})
            for sums in (f"{'a' * 64}  syft.tar.gz\n", f"{sha}  other.tar.gz\n", f"{sha}  syft.tar.gz\n{sha}  syft.tar.gz\n"):
                with self.assertRaises(ValueError, msg=sums):
                    ft.install("syft", self.pins(sha, self.KEYLESS), os.path.join(d, "bin"),
                               fetch=self.fetcher(src, sums, []), run=self.Cosign())

    def test_no_cosign_is_blocked(self):
        with tempfile.TemporaryDirectory() as d:
            src, sha = self.archive(d, {"syft": b"#!bin"})
            with self.assertRaises(ft.Blocked):
                ft.install("syft", self.pins(sha, self.KEYLESS), os.path.join(d, "bin"),
                           fetch=self.fetcher(src, f"{sha}  syft.tar.gz\n", []), run=self.Cosign(missing=True))

    def test_committed_pins_are_sha256(self):
        for name, t in PINS["tools"].items():
            self.assertRegex(t["sha256"], r"^[0-9a-f]{64}$", name)
            self.assertTrue(t["url"].startswith("https://github.com/"), name)

    def test_committed_pins_have_signature_records(self):
        repo_of = lambda url: "/".join(url.split("/")[3:5])  # noqa: E731
        for name, t in PINS["tools"].items():
            sig = ft.signature_record(name, t)
            if sig["kind"] == "none":
                continue
            self.assertEqual(sig["issuer"], "https://token.actions.githubusercontent.com", name)
            # the identity is the vendor's own release workflow, never a pattern
            self.assertTrue(sig["identity"].startswith(f"https://github.com/{repo_of(t['url'])}/.github/workflows/"), name)
            for k, v in sig.items():
                if k.endswith("_url"):
                    self.assertEqual(repo_of(v), repo_of(t["url"]), f"{name} {k}")


if __name__ == "__main__":
    unittest.main()
