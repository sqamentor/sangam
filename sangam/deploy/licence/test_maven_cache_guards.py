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

    def test_wrapper_properties_keep_the_checksum(self):
        with open(os.path.join(REPO, "sangam", ".mvn", "wrapper", "maven-wrapper.properties"), encoding="utf-8") as f:
            self.assertRegex(f.read(), r"(?m)^distributionSha256Sum=[0-9a-f]{64}\s*$")


if __name__ == "__main__":
    unittest.main()
