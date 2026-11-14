"""Which artefacts of the reactor's sangam-host-conformance test closure would the Quarkus module's BOM imports move?

Managed versions are computed from the cached BOM POMs in the module's import order (first import wins, as in Maven),
with property substitution and recursive imports. Usage: drift.py <closure-list> <first-line-of-conformance-block>
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

REPO = os.path.join(os.path.expanduser("~"), ".m2", "repository")
NS = "{http://maven.apache.org/POM/4.0.0}"


def pom_path(g, a, v):
    return os.path.join(REPO, *g.split("."), a, v, f"{a}-{v}.pom")


def load(g, a, v):
    root = ET.parse(pom_path(g, a, v)).getroot()
    props = {"project.version": v, "project.groupId": g}
    parent = root.find(f"{NS}parent")
    chain = []
    if parent is not None:
        pg, pa, pv = (parent.findtext(f"{NS}{t}") for t in ("groupId", "artifactId", "version"))
        pprops, pchain = load(pg, pa, pv)
        props.update(pprops)
        chain = pchain
        props["project.parent.version"] = pv
    p = root.find(f"{NS}properties")
    if p is not None:
        for e in p:
            props[e.tag.replace(NS, "")] = (e.text or "").strip()
    return props, chain + [root]


def subst(s, props):
    for _ in range(10):
        n = re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), s)
        if n == s:
            break
        s = n
    return s


def managed(g, a, v, out, missing):
    try:
        props, chain = load(g, a, v)
    except FileNotFoundError:
        missing.append(f"{g}:{a}:{v}")
        return
    for root in chain:
        for d in root.findall(f"./{NS}dependencyManagement/{NS}dependencies/{NS}dependency"):
            dg = subst(d.findtext(f"{NS}groupId"), props)
            da = subst(d.findtext(f"{NS}artifactId"), props)
            dv = subst(d.findtext(f"{NS}version") or "", props)
            if d.findtext(f"{NS}scope") == "import":
                managed(dg, da, dv, out, missing)
            else:
                out.setdefault((dg, da), dv)


closure_file, start = sys.argv[1], int(sys.argv[2])
reactor = {}
for i, line in enumerate(open(closure_file, encoding="utf-8", errors="replace"), 1):
    if i < start:
        continue
    m = re.match(r"\s+([\w.\-]+):([\w.\-]+):(\w+)(?::([\w.\-]+))?:([\w.\-]+):(\w+)", line)
    if m and m.group(1) != "org.sangam":
        reactor[(m.group(1), m.group(2))] = (m.group(5), m.group(6))

# the module's own management, in declaration order (first wins): jackson-bom, explicit pins, junit-bom, quarkus-bom
out, missing = {}, []
managed("com.fasterxml.jackson", "jackson-bom", sys.argv[3], out, missing)
for k, v in {("org.apache.commons", "commons-text"): "1.13.1", ("commons-io", "commons-io"): "2.19.0",
             ("org.jspecify", "jspecify"): "1.0.1"}.items():
    out.setdefault(k, v)
managed("org.junit", "junit-bom", sys.argv[4], out, missing)
managed("io.quarkus", "quarkus-bom", "3.40.1.1", out, missing)

print(f"conformance closure={len(reactor)} managed={len(out)} missing-boms={missing}")
for (g, a), (rv, scope) in sorted(reactor.items()):
    qv = out.get((g, a))
    if qv is not None and qv != rv:
        cached = os.path.exists(os.path.join(REPO, *g.split("."), a, qv))
        print(f"DRIFT {g}:{a} reactor={rv} ({scope}) module={qv} cached={cached}")
