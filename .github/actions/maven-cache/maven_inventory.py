# SPDX-License-Identifier: Apache-2.0
"""Everything the workflows' Maven steps will fetch beyond `test-compile dependency:go-offline` of the reactor, read
from the workflows themselves (not a hand-kept list), so the single cache writer can resolve it once.

  * explicit plugin goals  g:a:v:goal            (versions-maven-plugin, pitest, dependency-check, cyclonedx, ...)
  * explicit artifacts     -Dartifact=g:a:v[...] (openapi-generator-cli, the MariaDB driver, ...)
  * profiles               -P...                 (their plugins and dependencies are invisible without them)
  * other POMs             -f path/pom.xml       (sdks/kotlin, deploy/native/sdk-worker), with their own profiles
  * test providers         surefire/failsafe junit-platform provider + launcher: resolved only when tests run

Shell variables are expanded from workflow/job env blocks, from literal assignments in the workflows
(profiles=a,b / profiles="$profiles,c") and from `x=$(mvn ... -Dexpression=<pom property>)`. Anything that stays
unresolved is reported; the guard test fails on it, so a new Maven fetch cannot silently bypass the cache.

Run: python3 -I maven_inventory.py [--repo DIR] [--json]   (prints one prefetch command per line by default)"""
import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
MVN = re.compile(r"(?<![\w./$-])(?:bash\s+)?(?:\./)?mvnw?(?=[ \t])")
GAV_GOAL = re.compile(r"^([\w.-]+):([\w.-]+):(\d[\w.-]*):([\w-]+)$")
VAR = re.compile(r"\$\{?([A-Za-z_]\w*)\}?")
SANGAM_GROUP = "io.github.sqamentor"


def pom_properties(path):
    root = ET.parse(path).getroot()
    props = {}
    el = root.find("m:properties", POM_NS)
    if el is not None:
        for p in el:
            props[p.tag.split("}")[1]] = (p.text or "").strip()
    return root, props


def pom_profiles(path):
    root = ET.parse(path).getroot()
    return {e.text.strip() for e in root.findall("m:profiles/m:profile/m:id", POM_NS)}


def plugin_version(root, artifact):
    for p in root.iter("{http://maven.apache.org/POM/4.0.0}plugin"):
        a = p.find("m:artifactId", POM_NS)
        v = p.find("m:version", POM_NS)
        if a is not None and a.text == artifact and v is not None:
            return v.text.strip()
    return None


def logical_lines(text):
    out = []
    for line in text.replace("\r\n", "\n").split("\n"):
        if line.lstrip().startswith("#"):
            continue
        if out and out[-1].endswith("\\"):
            out[-1] = out[-1][:-1] + " " + line.strip()
        else:
            out.append(line)
    return out


def inventory(repo):
    sangam = os.path.join(repo, "sangam")
    root, props = pom_properties(os.path.join(sangam, "pom.xml"))
    wf_dir = os.path.join(repo, ".github", "workflows")
    env, assigns, lines = {}, {}, []
    for name in sorted(os.listdir(wf_dir)):
        with open(os.path.join(wf_dir, name), encoding="utf-8") as f:
            for line in logical_lines(f.read()):
                lines.append((name, line))
                m = re.match(r"^\s+([A-Z][A-Z0-9_]*):\s*([^#{}]\S*)\s*$", line)
                if m:
                    env.setdefault(m.group(1), set()).add(m.group(2).strip("\"'"))
                for m in re.finditer(r"(?:^|[\s;(])([a-z_]\w*)=\"?([^\s\"';)]+)\"?", line):
                    if not m.group(2).startswith("$("):
                        assigns.setdefault(m.group(1), set()).add(m.group(2))
                m = re.search(r"\b([a-z_]\w*)=\$\(mvnw?\b[^)]*-Dexpression=([\w.-]+)", line)
                if m and m.group(2) in props:
                    assigns.setdefault(m.group(1), set()).add(props[m.group(2)])

    def expand(tok, depth=0):
        """All values a token can take (variables expanded); a value that keeps a $ is unresolved."""
        m = VAR.search(tok)
        if not m or depth > 4:
            return {tok}
        vals = env.get(m.group(1)) or {v for v in assigns.get(m.group(1), set()) if f"${m.group(1)}" not in v}
        if not vals:
            return {tok}
        out = set()
        for v in vals:
            out |= expand(tok[:m.start()] + v + tok[m.end():], depth + 1)
        return out

    plugins, artifacts, unresolved = set(), set(), []
    profiles = {}  # pom (relative to sangam/) -> set of profile ids
    for name, line in lines:
        for start in [m.end() for m in MVN.finditer(line)]:
            cmd = re.split(r"\s(?:\||&&|;)\s|'|\)", line[start:])[0]
            toks = [t.strip("\"'") for t in cmd.split()]
            pom = "pom.xml"
            if "-f" in toks and toks.index("-f") + 1 < len(toks):
                pom = toks[toks.index("-f") + 1]
                profiles.setdefault(pom, set())
            for t in toks:
                t = t.replace("\"", "").replace("'", "")
                if t.startswith("${{"):
                    continue
                if t.startswith("-P"):
                    for v in expand(t[2:]):
                        for p in v.split(","):
                            if "$" in p:
                                if not p.startswith("$profiles"):
                                    unresolved.append(f"{name}: profile {p}")
                            elif p:
                                profiles.setdefault(pom, set()).add(p)
                elif t.startswith("-Dartifact="):
                    for v in expand(t[len("-Dartifact="):]):
                        (unresolved.append(f"{name}: artifact {v}") if "$" in v else artifacts.add(v))
                elif t.startswith("$") or GAV_GOAL.match(t):
                    for v in expand(t):
                        g = GAV_GOAL.match(v)
                        if g:
                            plugins.add(f"{g.group(1)}:{g.group(2)}:{g.group(3)}")
    # test providers: resolved by surefire/failsafe only when tests run, never by go-offline
    providers = set()
    for plugin in ("maven-surefire-plugin", "maven-failsafe-plugin"):
        v = plugin_version(root, plugin)
        if not v:
            unresolved.append(f"pom.xml: no pinned {plugin} version")
            continue
        providers.add(f"org.apache.maven.surefire:surefire-junit-platform:{v}")
    junit = props.get("version.junit")
    if junit:  # JUnit 6 aligns the platform launcher with the junit-bom version
        providers.add(f"org.junit.platform:junit-platform-launcher:{junit}")
    else:
        unresolved.append("pom.xml: no version.junit for the junit-platform launcher")
    root_profiles = set()  # the reactor: profiles of every module POM
    for d, subdirs, files in os.walk(sangam):
        subdirs[:] = [s for s in subdirs if s not in ("target", "node_modules", ".git") and not s.startswith(".")]
        if "pom.xml" in files:
            root_profiles |= pom_profiles(os.path.join(d, "pom.xml"))
    for pom, ids in profiles.items():
        known = root_profiles if pom == "pom.xml" else (
            pom_profiles(os.path.join(sangam, pom)) if os.path.exists(os.path.join(sangam, pom)) else set())
        for p in sorted(ids - known):
            unresolved.append(f"{pom}: profile {p} is not defined")
    # -f POMs outside the reactor that depend on this project's own modules (sdks/kotlin -> sangam-sdk-java): those
    # modules must be installed locally first, exactly as the workflow does, or go-offline cannot read the POM's
    # dependency tree. The installed io.github.sqamentor artifacts are excluded from the cache.
    modules = {}
    for d, subdirs, files in os.walk(sangam):
        subdirs[:] = [s for s in subdirs if s not in ("target", "node_modules", ".git") and not s.startswith(".")]
        if "pom.xml" in files and d != sangam:
            a = ET.parse(os.path.join(d, "pom.xml")).getroot().find("m:artifactId", POM_NS)
            if a is not None:
                modules[a.text.strip()] = os.path.relpath(d, sangam).replace(os.sep, "/")
    installs = set()
    for pom in profiles:
        path = os.path.join(sangam, pom)
        if pom == "pom.xml" or not os.path.exists(path):
            continue
        for dep in ET.parse(path).getroot().findall("m:dependencies/m:dependency", POM_NS):
            g, a = dep.find("m:groupId", POM_NS), dep.find("m:artifactId", POM_NS)
            if g is not None and a is not None and g.text.strip() in (SANGAM_GROUP, "${project.groupId}"):
                if a.text.strip() in modules:
                    installs.add(modules[a.text.strip()])
                else:
                    unresolved.append(f"{pom}: depends on {a.text.strip()}, which is no reactor module")
    return {"plugins": sorted(plugins), "artifacts": sorted(artifacts), "providers": sorted(providers),
            "installs": sorted(installs),
            "profiles": {k: sorted(v) for k, v in sorted(profiles.items())}, "unresolved": unresolved}


def commands(inv):
    """Prefetch commands for the writer job (run from sangam/ with the pinned mvnw)."""
    out = []
    root_profiles = ",".join(inv["profiles"].get("pom.xml", []))
    out.append(f"-DskipTests -P{root_profiles} test-compile dependency:go-offline")
    if inv.get("installs"):
        out.append(f"-DskipTests -pl {','.join(inv['installs'])} -am install")
    for pom, ids in inv["profiles"].items():
        if pom != "pom.xml":
            p = f" -P{','.join(ids)}" if ids else ""
            out.append(f"-f {pom}{p} dependency:go-offline -DexcludeGroupIds={SANGAM_GROUP}")
            # Its test provider: a POM outside the reactor may run another surefire than the root pins (sdks/kotlin
            # gets Maven's default 3.2.5, run 44454304569). Running surefire with a filter that matches nothing makes
            # Maven resolve exactly the provider that POM uses, without hand-keeping its version.
            out.append(f"-f {pom}{p} test -Dtest=SangamCachePrefetchNoSuchTest -Dsurefire.failIfNoSpecifiedTests=false")
    for gav in inv["plugins"]:
        out.append(f"dependency:get -Dartifact={gav}:maven-plugin")
    for gav in inv["artifacts"] + inv["providers"]:
        out.append(f"dependency:get -Dartifact={gav}")
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..")))
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    inv = inventory(a.repo)
    if inv["unresolved"]:
        for u in inv["unresolved"]:
            print(f"::error title=maven inventory::unresolved Maven fetch: {u}", file=sys.stderr)
        return 1
    print(json.dumps(inv, indent=1) if a.json else "\n".join(commands(inv)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
