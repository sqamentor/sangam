# SPDX-License-Identifier: Apache-2.0
"""Guard for the Maven Central HTTP 429 root cause (2026-11-18): every job that runs Maven restores the shared Maven
cache (.github/actions/maven-cache) before its first Maven call, no job keeps a private setup-java `cache: maven`, at
most one job per workflow writes the cache, and the Maven distribution comes from that cache with its pinned SHA-256.

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml)."""
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
WORKFLOWS = os.path.join(REPO, ".github", "workflows")
ACTION = os.path.join(REPO, ".github", "actions", "maven-cache")
USES = "uses: ./.github/actions/maven-cache"
# A Maven invocation in a run line: mvn or mvnw (./mvnw, bash ./mvnw, -- mvn, $(mvn ...)); not part of a word or path.
MVN = re.compile(r"(?<![\w./$-])(?:\./)?mvnw?(?=[ \t])")


def strip_comments(text):
    return "\n".join("" if line.lstrip().startswith("#") else line for line in text.splitlines())


def jobs(text):
    """Split a workflow into (job id, body) by the two-space job keys under `jobs:`."""
    text = strip_comments(text)
    start = re.search(r"(?m)^jobs:\s*$", text)
    if not start:
        return []
    body = text[start.end():]
    keys = list(re.finditer(r"(?m)^  ([\w-]+):\s*$", body))
    return [(m.group(1), body[m.end():(keys[i + 1].start() if i + 1 < len(keys) else len(body))])
            for i, m in enumerate(keys)]


def maven_jobs():
    for name in sorted(os.listdir(WORKFLOWS)):
        with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as f:
            text = f.read()
        for job, body in jobs(text):
            m = MVN.search(body)
            if m:
                yield name, job, body, m.start()


class Parser(unittest.TestCase):
    def test_detects_maven_calls(self):
        for line in ["mvn -B verify", "bash ./mvnw $MAVEN_ARGS x", "v=$(mvn -q help:evaluate)", "-- mvn $MAVEN_ARGS"]:
            self.assertTrue(MVN.search(line), line)
        for line in ["echo $MVN_X", "tools/mvnw.cmd", "mvn-wrapper:", "apache-maven-3.9.9"]:
            self.assertFalse(MVN.search(line), line)

    def test_jobs_split(self):
        wf = "on: push\njobs:\n  a:\n    steps:\n      - run: mvn -v\n  b:\n    steps:\n      - run: echo\n"
        self.assertEqual([j for j, _ in jobs(wf)], ["a", "b"])


class MavenCache(unittest.TestCase):
    def test_some_jobs_use_maven(self):
        self.assertGreater(len(list(maven_jobs())), 5)

    def test_every_maven_job_restores_the_shared_cache_first(self):
        bad = [f"{wf}:{job}" for wf, job, body, first in maven_jobs()
               if USES not in body or body.index(USES) > first]
        self.assertEqual(bad, [], "jobs that call Maven before (or without) the shared maven-cache action")

    def test_no_private_setup_java_maven_cache(self):
        bad = []
        for name in sorted(os.listdir(WORKFLOWS)):
            with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as f:
                for i, line in enumerate(f, 1):
                    if re.search(r"(?<![\w-])cache:\s*['\"]?maven\b", line):
                        bad.append(f"{name}:{i}")
        self.assertEqual(bad, [], "setup-java/setup-graalvm `cache: maven` writes a per-job cache with no fallback key")

    def test_at_most_one_writer_job_per_workflow(self):
        for name in sorted(os.listdir(WORKFLOWS)):
            with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as f:
                writers = [job for job, body in jobs(f.read())
                           if re.search(r"maven-cache\s*\n\s*with:\s*\n\s*save:", body)]
            self.assertLessEqual(len(writers), 1, f"{name}: several cache writer jobs {writers}")

    def test_no_forced_update(self):
        bad = [f"{wf}:{job}" for wf, job, body, _ in maven_jobs()
               if re.search(r"mvnw?\b[^\n]*\s(?:-U|--update-snapshots)\b", body)]
        self.assertEqual(bad, [], "-U re-checks every artifact against Maven Central")

    def test_no_uncached_wrapper_fetch_loop(self):
        for name in sorted(os.listdir(WORKFLOWS)):
            with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as f:
                self.assertFalse("Fetch the Maven distribution" in f.read(), f"{name}: uncached wrapper fetch loop")


class Distribution(unittest.TestCase):
    def setUp(self):
        if not os.path.isdir(ACTION):
            self.fail(f"missing {ACTION}")
        with open(os.path.join(ACTION, "action.yml"), encoding="utf-8") as f:
            self.action = f.read()
        with open(os.path.join(ACTION, "maven-dist.sh"), encoding="utf-8") as f:
            self.script = f.read()

    def test_cache_covers_repository_and_distribution_with_stable_key(self):
        self.assertIn("actions/cache/restore@", self.action)
        self.assertIn("~/.m2/repository", self.action)
        self.assertIn("~/.cache/sangam-maven-dist", self.action)
        self.assertIn("hashFiles('sangam/**/pom.xml', 'sangam/.mvn/wrapper/maven-wrapper.properties')", self.action)
        self.assertIn("restore-keys:", self.action)
        self.assertNotIn("github.run_id", self.action)
        self.assertNotIn("github.sha", self.action)

    def test_distribution_is_pinned_and_served_from_the_cache(self):
        self.assertIn("distributionSha256Sum", self.script)
        self.assertIn("sha256sum -c", self.script)
        self.assertIn("MVNW_REPOURL=file://", self.script)
        self.assertNotIn("--retry-delay", self.script)  # keeps curl honouring Retry-After
        self.assertNotIn("--insecure", self.script)
        self.assertNotRegex(self.script, r"curl[^\n]*\s-k\b")

    def test_plain_mvn_runs_the_pinned_wrapper(self):
        # The runner image's Maven binds other default plugin versions than the pinned 3.9.9 (resources-plugin 3.5.0
        # vs 3.3.1), which the shared cache does not hold: those jobs went back to Maven Central.
        self.assertIn('exec bash "%s/sangam/mvnw"', self.script)
        self.assertIn('>> "$GITHUB_PATH"', self.script)

    def test_cache_never_stores_not_found_markers(self):
        self.assertIn("!~/.m2/repository/**/*.lastUpdated", self.action)
        self.assertIn("!~/.m2/repository/**/resolver-status.properties", self.action)

    def test_wrapper_properties_keep_the_checksum(self):
        with open(os.path.join(REPO, "sangam", ".mvn", "wrapper", "maven-wrapper.properties"), encoding="utf-8") as f:
            self.assertRegex(f.read(), r"(?m)^distributionSha256Sum=[0-9a-f]{64}\s*$")


class SingleFetch(unittest.TestCase):
    def test_deps_job_resolves_serially_with_bounded_retries(self):
        with open(os.path.join(WORKFLOWS, "ci-lanes.yml"), encoding="utf-8") as f:
            deps = dict(jobs(f.read()))["deps"]
        self.assertIn("prefetch.sh", deps)  # which runs every resolution through resolve-once.sh
        self.assertNotRegex(deps, r"\s-T\s", "parallel resolution makes the one cold fetch bursty")
        with open(os.path.join(ACTION, "resolve-once.sh"), encoding="utf-8") as f:
            s = f.read()
        self.assertIn("for attempt in 1 2 3", s)
        self.assertIn("retryHandler.serviceUnavailable=429,503", s)
        self.assertIn("exit 1", s)
        self.assertNotRegex(s, r"\s-T\s|maven\.test\.failure\.ignore|checksumPolicy|--lax-checksums")


class WriterCoversLaterFetches(unittest.TestCase):
    """Gap found on bce8c0ee (run 44437469840 IT mysql: surefire-junit-platform 3.6.1 'absent'; sdks run 44440781547:
    maven-dependency-plugin 3.8.1 not found): the writer resolved only the reactor, not what later steps fetch."""

    @classmethod
    def setUpClass(cls):
        import importlib.util
        spec = importlib.util.spec_from_file_location("maven_inventory", os.path.join(ACTION, "maven_inventory.py"))
        cls.mi = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.mi)
        cls.inv = cls.mi.inventory(REPO)
        cls.cmds = "\n".join(cls.mi.commands(cls.inv))

    def test_every_maven_fetch_in_the_workflows_is_resolved_by_the_inventory(self):
        self.assertEqual(self.inv["unresolved"], [])

    def test_known_gaps_are_in_the_prefetch(self):
        for gav in ["org.apache.maven.surefire:surefire-junit-platform:3.6.1",
                    "org.apache.maven.plugins:maven-dependency-plugin:3.8.1",
                    "org.openapitools:openapi-generator-cli:7.10.0"]:
            self.assertIn(gav, self.cmds)
        self.assertIn("-f sdks/kotlin/pom.xml", self.cmds)

    def test_every_extra_pom_resolves_its_own_test_provider(self):
        for pom in self.inv["profiles"]:
            if pom != "pom.xml":
                self.assertRegex(self.cmds, rf"(?m)^@provider -f {re.escape(pom)}\b")

    def test_every_explicit_coordinate_in_a_workflow_is_prefetched(self):
        rx = re.compile(r"(?<![\w.-])([a-z][\w.-]*\.[\w.-]+):([\w.-]+):(\d[\w.-]*)(?::[\w-]+)?")
        missing = []
        for wf, job, body, _ in maven_jobs():
            for line in body.splitlines():
                if not MVN.search(line):
                    continue
                for m in rx.finditer(line):
                    gav = ":".join(m.groups())
                    if gav not in self.cmds:
                        missing.append(f"{wf}:{job}: {gav}")
        self.assertEqual(missing, [], "a Maven step fetches something the cache writer does not resolve")

    def test_every_writer_runs_the_prefetch(self):
        bad = []
        for name in sorted(os.listdir(WORKFLOWS)):
            with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as f:
                for job, body in jobs(f.read()):
                    if re.search(r"maven-cache\s*\n\s*with:\s*\n\s*save:", body) and "prefetch.sh" not in body:
                        bad.append(f"{name}:{job}")
        self.assertEqual(bad, [], "cache writer jobs that do not run .github/actions/maven-cache/prefetch.sh")

    def test_the_default_branch_scope_has_a_cache_writer(self):
        # A ci/* branch can restore only its own caches and the default branch's (GitHub cache scoping). Before this,
        # the only writer was ci-lanes `deps`, which runs on ci/** only, so main never held the Maven cache and every
        # new lane branch started cold (run 44879593721: both keys missed; `gh cache list` 2026-11-21: 43 entries,
        # 11.1 GB, none on refs/heads/main).
        found = []
        for name in sorted(os.listdir(WORKFLOWS)):
            with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as f:
                text = f.read()
            on = re.search(r"(?ms)^on:\s*\n(.*?)^\S", text)
            if not on or not re.search(r"(?m)^  schedule:", on.group(1)) \
                    or not re.search(r"(?ms)^  push:\s*\n\s+branches:\s*\[\s*main\s*\]", on.group(1)):
                continue
            for job, body in jobs(text):
                if re.search(r"maven-cache\s*\n\s*with:\s*\n\s*save:\s*\"true\"", body) and "prefetch.sh" in body:
                    found.append(f"{name}:{job}")
        self.assertEqual(len(found), 1, f"exactly one main-scope (push main + schedule) Maven cache writer: {found}")

    def test_prefetch_runs_the_inventory_through_the_bounded_retry(self):
        with open(os.path.join(ACTION, "prefetch.sh"), encoding="utf-8") as f:
            s = f.read()
        self.assertIn("maven_inventory.py", s)
        self.assertIn("resolve-once.sh", s)
        self.assertNotIn("|| true", s)

    def test_cache_excludes_this_projects_own_artifacts(self):
        with open(os.path.join(ACTION, "action.yml"), encoding="utf-8") as f:
            self.assertTrue("!~/.m2/repository/io/github/sqamentor" in f.read(), "own SNAPSHOT artifacts must not be cached")



class DistributionFetch(unittest.TestCase):
    """Runs 44489607242 / 44473072992: the one fetch of the pinned apache-maven-3.9.9-bin.zip got HTTP 404 (and 429 x5),
    failed at once on the 404 and deleted the response headers, blocking whole lane runs before any test ran."""

    def setUp(self):
        with open(os.path.join(ACTION, "action.yml"), encoding="utf-8") as f:
            self.action = f.read()

    def test_distribution_has_its_own_cache_keyed_on_url_and_checksum(self):
        steps = self.action.split("    - name: ")
        dist = [s for s in steps if "uses: actions/cache@" in s and "steps.dist.outputs.key" in s]
        self.assertEqual(len(dist), 1, "no separate Maven distribution cache step")
        self.assertRegex(dist[0], r"path: ~/\.cache/sangam-maven-dist\s*\n", "only the distribution in that cache")
        self.assertNotIn("hashFiles", dist[0], "the key must not change with the poms")
        with open(os.path.join(ACTION, "maven-dist-key.sh"), encoding="utf-8") as f:
            key = f.read()
        self.assertIn("distributionUrl", key)
        self.assertIn("distributionSha256Sum", key)
        order = [i for i, s in enumerate(steps) if "steps.dist.outputs.key" in s or "maven-dist.sh" in s]
        self.assertEqual(order, sorted(order))
        self.assertIn("maven-dist.sh", steps[order[-1]], "the distribution cache restores before maven-dist.sh")

    def run_fetch(self, codes):
        """Run maven-dist.sh with a fake curl answering `codes` in turn and a no-op sleep; return (exit, log, calls)."""
        import shutil
        import subprocess
        import tempfile
        bash = shutil.which("bash")
        if not bash:
            self.skipTest("bash not available")
        tmp = tempfile.mkdtemp(prefix=".maven-dist-test-", dir=REPO)
        self.addCleanup(shutil.rmtree, tmp, True)
        fake = os.path.join(tmp, "bin")
        os.makedirs(fake)
        with open(os.path.join(tmp, "codes"), "w", newline="\n") as f:
            f.write("\n".join(codes) + "\n")
        curl = r"""#!/usr/bin/env bash
hdr=""; part=""; url=""; while [ $# -gt 0 ]; do case "$1" in -D) hdr="$2"; shift ;; -o) part="$2"; shift ;; https://*) url="$1" ;; esac; shift; done
n=$(( $(cat "$T/calls" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$T/calls"; echo "$url" >> "$T/urls"
entry=$(sed -n "${n}p" "$T/codes"); [ -n "$entry" ] || entry=$(tail -1 "$T/codes")
code=${entry%%:*}; ra=""; case "$entry" in *:*) ra="Retry-After: ${entry#*:}\r\n" ;; esac
printf "HTTP/1.1 %s X\r\nServer: Fake-Edge\r\nX-Cache: MISS, MISS\r\nX-Served-By: cache-fake-1\r\nCF-RAY: 0123abcd-IAD\r\n${ra}Set-Cookie: secret=s3cr3t\r\nAuthorization: Bearer s3cr3t\r\n\r\n" "$code" > "$hdr"
printf 'not the zip' > "$part"; printf '%s' "$code"
"""
        for name, body in (("curl", curl), ("sleep", "#!/usr/bin/env bash\nexit 0\n")):
            with open(os.path.join(fake, name), "w", newline="\n") as f:
                f.write(body)
            os.chmod(os.path.join(fake, name), 0o755)
        # Every path is built inside bash from $PWD (the repository) and the scratch directory's name, so the same
        # test runs under Linux bash, Git for Windows bash and WSL bash (whose drive mounts differ).
        rel = os.path.basename(tmp)
        with open(os.path.join(tmp, "run.sh"), "w", newline="\n") as f:
            f.write(f'T="$PWD/{rel}"; export T HOME="$T" RUNNER_TEMP="$T" GITHUB_ENV="$T/env" GITHUB_PATH="$T/path" '
                    'GITHUB_WORKSPACE="$PWD" PATH="$T/bin:$PATH"\nexec bash .github/actions/maven-cache/maven-dist.sh\n')
        p = subprocess.run([bash, f"{rel}/run.sh"], cwd=REPO, capture_output=True, text=True, timeout=120)
        log = p.stdout + p.stderr
        calls_file = os.path.join(tmp, "calls")
        self.assertTrue(os.path.exists(calls_file), f"maven-dist.sh never called curl (exit {p.returncode}):\n{log}")
        with open(calls_file) as f:
            calls = int(f.read().strip())
        with open(os.path.join(tmp, "urls")) as f:
            self.urls = f.read().split()
        self.cached = os.path.exists(os.path.join(tmp, ".cache", "sangam-maven-dist", "org", "apache", "maven",
                                                  "apache-maven", "3.9.9", "apache-maven-3.9.9-bin.zip"))
        return p.returncode, log, calls

    def test_404_on_the_pinned_file_is_retried_and_headers_are_logged(self):
        rc, log, calls = self.run_fetch(["404"])
        self.assertNotEqual(rc, 0)
        self.assertEqual(calls, 5, "a 404 on the pinned, checksum-verified file is transient: bounded retries")
        for h in ("Server: Fake-Edge", "X-Cache: MISS", "X-Served-By: cache-fake-1", "CF-RAY: 0123abcd-IAD"):
            self.assertIn(h, log, "which server answered must reach the job log")
        self.assertNotIn("s3cr3t", log, "only allow-listed headers are printed")

    def test_other_errors_still_fail_at_once_and_checksum_still_applies(self):
        rc, log, calls = self.run_fetch(["403"])
        self.assertNotEqual(rc, 0)
        self.assertEqual(calls, 1)
        rc, log, calls = self.run_fetch(["429", "200"])
        self.assertNotEqual(rc, 0)
        self.assertEqual(calls, 2)
        self.assertIn("SHA-256 mismatch", log, "wrong bytes are refused after a retried fetch")

    FALLBACK = "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip"

    def test_long_retry_after_takes_the_apache_archive_fallback(self):
        # Run 44493122674: 'HTTP/2 404, server: cloudflare, retry-after: 28304', a ~7.9 h edge block, not a transient.
        for first in ("404:28304", "429:28304"):
            rc, log, calls = self.run_fetch([first, "200"])
            self.assertEqual(calls, 2, f"{first}: one Central attempt, then the fallback, no 300 s waits")
            self.assertTrue(self.urls[0].startswith("https://repo.maven.apache.org/maven2/"), self.urls)
            self.assertEqual(self.urls[1], self.FALLBACK, "fallback derived from distributionUrl's version")
            self.assertIn(f"from {self.FALLBACK}", log, "the job log names the source")

    def test_short_retry_after_keeps_waiting_on_central(self):
        rc, log, calls = self.run_fetch(["429:30", "404:300", "404"])
        self.assertEqual(calls, 5)
        self.assertNotIn(self.FALLBACK, self.urls)

    def test_fallback_bytes_with_a_wrong_checksum_are_refused(self):
        rc, log, calls = self.run_fetch(["404:28304", "200"])
        self.assertNotEqual(rc, 0)
        self.assertIn(f"SHA-256 mismatch for {self.FALLBACK}", log)
        self.assertFalse(self.cached, "refused bytes must never land in the distribution cache")
        self.assertNotIn("MVNW_REPOURL", log)

    def test_429_without_retry_after_on_every_attempt_takes_the_archive_once(self):
        # Run 44879593721 (ci/packs-boot-15ce4cdb-e07382, cold cache): 'HTTP/2 429' with no Retry-After on all 5
        # attempts (waits 60+120+180+240 s), then the job failed. Waiting longer on the same egress cannot help.
        rc, log, calls = self.run_fetch(["429", "429", "429", "429", "429", "200"])
        self.assertEqual(calls, 6, "5 bounded Central attempts, then exactly one archive attempt")
        self.assertTrue(all(u.startswith("https://repo.maven.apache.org/maven2/") for u in self.urls[:5]), self.urls)
        self.assertEqual(self.urls[5], self.FALLBACK)
        self.assertIn(f"SHA-256 mismatch for {self.FALLBACK}", log, "the archive bytes pass the same checksum")
        self.assertNotEqual(rc, 0)
        self.assertFalse(self.cached)

    def test_429_then_success_within_the_bound_never_touches_the_archive(self):
        rc, log, calls = self.run_fetch(["429", "429", "200"])
        self.assertEqual(calls, 3)
        self.assertNotIn(self.FALLBACK, self.urls)

    def test_429_exhausted_and_archive_error_fails_naming_both(self):
        rc, log, calls = self.run_fetch(["429", "429", "429", "429", "429", "403"])
        self.assertNotEqual(rc, 0)
        self.assertEqual(calls, 6)
        self.assertIn("still HTTP 429 after 5 attempts", log, "the Central error is not hidden")
        self.assertIn(f"::error::{self.FALLBACK} answered HTTP 403", log)

    def test_fallback_error_fails_with_its_headers(self):
        rc, log, calls = self.run_fetch(["404:28304", "503"])
        self.assertNotEqual(rc, 0)
        self.assertEqual(calls, 2)
        self.assertIn(f"::error::{self.FALLBACK} answered HTTP 503", log)


if __name__ == "__main__":
    unittest.main()
