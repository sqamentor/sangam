# SPDX-License-Identifier: Apache-2.0
"""RM-31 (X1 V-9, V-11, G-9): artefact families that must ship as one version, read from the committed decision register.

- org.slf4j: slf4j-api and its bridges (jul-to-slf4j, jcl-over-slf4j, ...) ship one version per distribution, because
  the bridges call into the API of their own release.
- one org.apache.commons.logging implementation per distribution: commons-logging and jcl-over-slf4j never together.

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml)."""
import json
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))

ONE_VERSION_GROUPS = ("org.slf4j",)
ONE_OF = ({"commons-logging/commons-logging", "org.slf4j/jcl-over-slf4j"},)


def shipped(register):
    """{distribution: {(group, artifact): {version, ...}}} of the register's Maven entries."""
    out = {}
    for purl, entry in register.items():
        if not purl.startswith("pkg:maven/"):
            continue
        coord, version = purl[len("pkg:maven/"):].split("?", 1)[0].rsplit("@", 1)
        group, artifact = coord.rsplit("/", 1)
        for dist in entry.get("distributions", []):
            out.setdefault(dist, {}).setdefault((group, artifact), set()).add(version)
    return out


def violations(register):
    found = []
    for dist, comps in sorted(shipped(register).items()):
        for group in ONE_VERSION_GROUPS:
            versions = {(a, v) for (g, a), vs in comps.items() if g == group for v in vs}
            if len({v for _, v in versions}) > 1:
                found.append(f"{dist}: {group} ships more than one version: {sorted(versions)}")
        for alternatives in ONE_OF:
            present = sorted(c for c in alternatives if tuple(c.split("/", 1)) in comps)
            if len(present) > 1:
                found.append(f"{dist}: more than one of {present}")
    return found


class VersionFamilies(unittest.TestCase):
    def test_the_committed_register_ships_each_family_once(self):
        with open(os.path.join(HERE, "decision-register.json"), encoding="utf-8") as f:
            register = json.load(f)["components"]
        self.assertTrue(any(p.startswith("pkg:maven/org.slf4j/slf4j-api@") for p in register), "slf4j-api is registered")
        self.assertEqual([], violations(register))

    def test_a_split_family_and_two_implementations_are_found(self):
        reg = {
            "pkg:maven/org.slf4j/slf4j-api@2.0.17": {"distributions": ["server", "gateway"]},
            "pkg:maven/org.slf4j/jul-to-slf4j@2.0.20": {"distributions": ["server"]},
            "pkg:maven/org.slf4j/jcl-over-slf4j@2.0.17": {"distributions": ["gateway"]},
            "pkg:maven/commons-logging/commons-logging@1.3.6": {"distributions": ["gateway"]},
            "pkg:npm/slf4j@1.0.0": {"distributions": ["ui"]},
        }
        v = violations(reg)
        self.assertEqual(2, len(v), v)
        self.assertTrue(v[0].startswith("gateway: more than one of"), v)
        self.assertTrue(v[1].startswith("server: org.slf4j ships more than one version"), v)


if __name__ == "__main__":
    unittest.main()
