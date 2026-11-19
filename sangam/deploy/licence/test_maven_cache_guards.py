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

    def test_prefetch_runs_the_inventory_through_the_bounded_retry(self):
        with open(os.path.join(ACTION, "prefetch.sh"), encoding="utf-8") as f:
            s = f.read()
        self.assertIn("maven_inventory.py", s)
        self.assertIn("resolve-once.sh", s)
        self.assertNotIn("|| true", s)

    def test_cache_excludes_this_projects_own_artifacts(self):
        with open(os.path.join(ACTION, "action.yml"), encoding="utf-8") as f:
            self.assertTrue("!~/.m2/repository/io/github/sqamentor" in f.read(), "own SNAPSHOT artifacts must not be cached")


if __name__ == "__main__":
    unittest.main()
