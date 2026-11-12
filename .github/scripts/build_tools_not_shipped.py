# SPDX-License-Identifier: Apache-2.0
"""Build-time tools are never shipped (owner decision 2026-11-12, licence-policy.json build_time_tools).

The LGPL build-time tools (SpotBugs, FindSecBugs, the Semgrep CLI, sonar-maven-plugin) are allowed only because they
never reach what Sangam ships. This check proves it for every shipped form it is given:
  --sbom  CycloneDX JSON (Maven, npm or image SBOM): no component purl starts with a tool's never_ship prefix;
  --jar   Spring Boot fat jar: no BOOT-INF/lib/<tool artifact>-<version>.jar;
  --tree  a directory (unpacked image, bundle, lib folder): no <tool artifact>-<version>.jar and no Python
          <tool>-<version>.dist-info.
The tool list is read from the policy, so a tool added there is checked here without a code change.

Exit 0 = none found, 1 = a build-time tool is in a shipped artefact, 2 = unreadable input or policy. This is a licence
guard, not a quality gate, so it fails on a finding (today there is none).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote


class InputError(Exception):
    pass


@dataclass(frozen=True)
class Tool:
    name: str
    never_ship: str      # purl prefix
    artifact: str        # file-name stem: Maven artifactId or PyPI project


def load_tools(policy_path: Path) -> list[Tool]:
    try:
        policy = json.loads(policy_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise InputError(f"{policy_path}: {e}") from e
    entries = policy.get("build_time_tools")
    if not entries:
        raise InputError(f"{policy_path}: no build_time_tools entries")
    tools = []
    for e in entries:
        try:
            coordinate, prefix, name = e["coordinate"], e["never_ship"], e["tool"]
        except KeyError as k:
            raise InputError(f"{policy_path}: build_time_tools entry {e} lacks {k}") from k
        if coordinate.startswith("pypi:"):
            artifact = coordinate[len("pypi:"):].split("==", 1)[0]
        else:
            parts = coordinate.split(":")
            if len(parts) != 3:
                raise InputError(f"{policy_path}: coordinate {coordinate!r} is not group:artifact:version")
            artifact = parts[1]
        tools.append(Tool(name, prefix, artifact))
    return tools


def file_pattern(tool: Tool) -> re.Pattern[str]:
    """<artifact>-<digit>... as a jar or a Python dist-info/egg-info name (PyPI normalises - and _)."""
    stem = "[-_]".join(re.escape(part) for part in re.split(r"[-_]", tool.artifact))
    return re.compile(rf"^{stem}-[0-9][^/\\]*\.(jar|dist-info|egg-info)$", re.IGNORECASE)


def components(bom: dict):
    stack = list(bom.get("components") or [])
    while stack:
        c = stack.pop()
        yield c
        stack.extend(c.get("components") or [])


def check_sbom(path: Path, tools: list[Tool]) -> list[str]:
    try:
        bom = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise InputError(f"{path}: {e}") from e
    if bom.get("bomFormat") != "CycloneDX":
        raise InputError(f"{path}: not a CycloneDX JSON SBOM")
    out = []
    for c in components(bom):
        purl = unquote(c.get("purl") or "")
        for t in tools:
            if purl.startswith(t.never_ship):
                out.append(f"{path.name}: {purl} is the build-time tool {t.name}")
    return out


def check_jar(path: Path, tools: list[Tool]) -> list[str]:
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
    except (OSError, zipfile.BadZipFile) as e:
        raise InputError(f"{path}: {e}") from e
    out = []
    for n in names:
        if not (n.startswith("BOOT-INF/lib/") and n.endswith(".jar")):
            continue
        base = n.rsplit("/", 1)[1]
        for t in tools:
            if file_pattern(t).match(base):
                out.append(f"{path.name}!{n} is the build-time tool {t.name}")
    return out


def check_tree(root: Path, tools: list[Tool]) -> list[str]:
    if not root.is_dir():
        raise InputError(f"{root}: not a directory")
    out = []
    for dirpath, dirs, files in os.walk(root):
        for n in list(files) + list(dirs):
            for t in tools:
                if file_pattern(t).match(n):
                    out.append(f"{os.path.relpath(os.path.join(dirpath, n), root)} in {root} is the build-time tool {t.name}")
    return out


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--policy", type=Path, default=Path("sangam/deploy/licence/licence-policy.json"))
    p.add_argument("--sbom", type=Path, action="append", default=[])
    p.add_argument("--jar", type=Path, action="append", default=[])
    p.add_argument("--tree", type=Path, action="append", default=[])
    a = p.parse_args(argv)
    if not (a.sbom or a.jar or a.tree):
        p.error("give at least one --sbom, --jar or --tree")
    try:
        tools = load_tools(a.policy)
        found = []
        for s in a.sbom:
            found += check_sbom(s, tools)
        for j in a.jar:
            found += check_jar(j, tools)
        for t in a.tree:
            found += check_tree(t, tools)
    except InputError as e:
        print(f"::error title=Build-time tools check::{e}")
        return 2
    checked = len(a.sbom) + len(a.jar) + len(a.tree)
    for f in found:
        print(f"::error title=Build-time tool shipped::{f}")
    print(f"build-time tools ({', '.join(t.name for t in tools)}): {len(found)} found in {checked} shipped artefact(s)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
