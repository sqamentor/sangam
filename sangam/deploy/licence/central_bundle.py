# SPDX-License-Identifier: Apache-2.0
"""Maven Central bundle for publish-packages.yml (M-019, decisions 105, 112, 181).

The reactor is deployed with `mvn deploy -DaltDeploymentRepository=<id>::file://<staging>`; this script turns that
staging repository into a Central Publisher Portal bundle and checks the Central requirements it can check offline
(central.sonatype.org/publish/requirements, read 2026-11-12):
  - groupId: the decision 210 namespace io.github.sqamentor, given by the owner's repository variable (never a
    default); every component under it;
  - every component at the release version;
  - packaging other than pom: <artifactId>-<version>.jar with -sources.jar and -javadoc.jar;
  - POM metadata name, description, url, licenses, developers and scm, in the pom or a parent pom in the bundle;
  - every file signed (.asc) and with .md5 and .sha1 checksum files (sha256/sha512 are optional and not written).
The Portal validates again on upload; this check is a fail-early copy, not the authority.

  prepare STAGING --group G --version V   remove maven-metadata*.xml and old checksum files, check the components
  finish  STAGING [--unsigned-dry-run]     require .asc for every file (unless the dry run says so), write .md5/.sha1
  zip     STAGING --out FILE               deterministic zip of the bundle (Maven repository layout)
Exit 0 ok, 1 a requirement fails, 2 BLOCKED (namespace not chosen by the owner, staging missing). Standard library only.
"""
import argparse
import hashlib
import os
import sys
import xml.etree.ElementTree as ET
import zipfile

# Decision 105 left the choice to the owner; decision 210 (2026-11-13) chose io.github.sqamentor. Nothing else is accepted.
NAMESPACES = ("io.github.sqamentor",)
CHECKSUMS = (".md5", ".sha1", ".sha256", ".sha512")
METADATA = ("name", "description", "url", "licenses", "developers", "scm")


class Blocked(Exception):
    pass


def local(tag):
    return tag.rsplit("}", 1)[-1]


def child(el, name):
    for c in el:
        if local(c.tag) == name:
            return c
    return None


def text(el, name):
    c = child(el, name) if el is not None else None
    return (c.text or "").strip() if c is not None and c.text else ""


def files_under(root):
    for d, _, names in os.walk(root):
        for n in names:
            yield os.path.join(d, n)


class Pom:
    def __init__(self, path):
        self.path = path
        self.root = ET.parse(path).getroot()
        parent = child(self.root, "parent")
        self.parent = (text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version")) if parent is not None else None
        self.group = text(self.root, "groupId") or (self.parent[0] if self.parent else "")
        self.artifact = text(self.root, "artifactId")
        self.version = text(self.root, "version") or (self.parent[2] if self.parent else "")
        self.packaging = text(self.root, "packaging") or "jar"

    def has(self, name):
        el = child(self.root, name)
        if el is None:
            return False
        if name in ("licenses", "developers"):
            return any(True for _ in el)
        if name == "scm":
            return bool(text(el, "url") or text(el, "connection"))
        return bool((el.text or "").strip())


def components(staging):
    poms = {}
    for f in files_under(staging):
        if f.endswith(".pom"):
            p = Pom(f)
            poms[(p.group, p.artifact, p.version)] = p
    return poms


def inherited(pom, name, poms):
    seen = set()
    while pom is not None and id(pom) not in seen:
        seen.add(id(pom))
        if pom.has(name):
            return True
        if name == "name":  # Maven does not inherit <name>
            return False
        pom = poms.get(pom.parent) if pom.parent else None
    return False


def check(staging, group, version):
    if group not in NAMESPACES:
        raise Blocked(f"namespace {group!r} is not {', '.join(NAMESPACES)} (decisions 105 and 210; the owner sets "
                      "the repository variable SANGAM_MAVEN_GROUP_ID)")
    if not os.path.isdir(staging):
        raise Blocked(f"staging repository {staging} does not exist")
    poms = components(staging)
    problems = []
    if not poms:
        problems.append("no .pom in the staging repository")
    for key, p in sorted(poms.items()):
        g, a, v = key
        rel = os.path.relpath(os.path.dirname(p.path), staging).replace(os.sep, "/")
        where = f"{g}:{a}:{v}"
        if g != group:
            problems.append(f"{where}: groupId {g} is not the chosen namespace {group}")
        if v != version:
            problems.append(f"{where}: version {v} is not the release version {version}")
        if rel != f"{g.replace('.', '/')}/{a}/{v}":
            problems.append(f"{where}: stored at {rel}, not in the Maven repository layout")
        if p.packaging != "pom":
            d = os.path.dirname(p.path)
            for suffix in (".jar", "-sources.jar", "-javadoc.jar"):
                if not os.path.isfile(os.path.join(d, f"{a}-{v}{suffix}")):
                    problems.append(f"{where}: {a}-{v}{suffix} missing (Central: packaging {p.packaging} needs a jar, "
                                    "sources and javadoc)")
        for name in METADATA:
            if not inherited(p, name, poms):
                problems.append(f"{where}: POM <{name}> missing (own pom{'' if name == 'name' else ' or parent in the bundle'})")
    return problems


def prepare(staging, group, version):
    if not os.path.isdir(staging):
        raise Blocked(f"staging repository {staging} does not exist")
    for f in list(files_under(staging)):
        base = os.path.basename(f)
        if base.startswith("maven-metadata") or base.endswith(CHECKSUMS):
            os.remove(f)
    return check(staging, group, version)


def digest(path, algo):
    h = hashlib.new(algo)
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def finish(staging, unsigned_dry_run=False):
    problems = []
    payload = [f for f in files_under(staging) if not f.endswith(CHECKSUMS) and not f.endswith(".asc")]
    for f in payload:
        if not os.path.isfile(f + ".asc") and not unsigned_dry_run:
            problems.append(f"{os.path.relpath(f, staging)}: no .asc signature")
    if problems:
        return problems
    for f in [f for f in files_under(staging) if not f.endswith(CHECKSUMS)]:
        for algo, ext in (("md5", ".md5"), ("sha1", ".sha1")):
            with open(f + ext, "w", encoding="ascii", newline="\n") as out:
                out.write(digest(f, algo))
    return []


def make_zip(staging, out):
    names = sorted(os.path.relpath(f, staging).replace(os.sep, "/") for f in files_under(staging))
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for n in names:
            info = zipfile.ZipInfo(n, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(os.path.join(staging, n), "rb") as f:
                z.writestr(info, f.read())
    return len(names)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("staging")
    p.add_argument("--group", required=True)
    p.add_argument("--version", required=True)
    f = sub.add_parser("finish")
    f.add_argument("staging")
    f.add_argument("--unsigned-dry-run", action="store_true")
    z = sub.add_parser("zip")
    z.add_argument("staging")
    z.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    try:
        if a.cmd == "prepare":
            problems = prepare(a.staging, a.group, a.version)
        elif a.cmd == "finish":
            problems = finish(a.staging, a.unsigned_dry_run)
        else:
            if not os.path.isdir(a.staging):
                raise Blocked(f"staging repository {a.staging} does not exist")
            print(f"{make_zip(a.staging, a.out)} files -> {a.out}")
            return 0
    except Blocked as e:
        print(f"::error title=central_bundle BLOCKED::{e}")
        return 2
    except ET.ParseError as e:
        print(f"::error title=central_bundle::unreadable pom: {e}")
        return 1
    for msg in problems:
        print(f"::error title=central_bundle {a.cmd}::{msg}")
    if problems:
        return 1
    print(f"central_bundle {a.cmd}: OK" + (" (UNSIGNED: dry run, nothing can be uploaded)" if getattr(a, "unsigned_dry_run", False) else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
