"""List the deployment artifacts of every Quarkus extension jar in a dependency:list output (offline, from the jars)."""
import os
import re
import sys
import zipfile

M2 = os.path.join(os.path.expanduser("~"), ".m2", "repository")
pat = re.compile(r"^\s+([^:\s]+):([^:\s]+):jar:([^:\s]+):")
seen = set()
for line in open(sys.argv[1], encoding="utf-8"):
    m = pat.match(line)
    if not m:
        continue
    g, a, v = m.groups()
    jar = os.path.join(M2, *g.split("."), a, v, f"{a}-{v}.jar")
    if not os.path.isfile(jar):
        print("NOJAR", g, a, v)
        continue
    with zipfile.ZipFile(jar) as z:
        if "META-INF/quarkus-extension.properties" not in z.namelist():
            continue
        props = z.read("META-INF/quarkus-extension.properties").decode("utf-8", "replace")
    for p in props.splitlines():
        if p.startswith("deployment-artifact="):
            d = p.split("=", 1)[1].strip().replace("\\:", ":")
            if d not in seen:
                seen.add(d)
                dg, da, dv = d.split(":")[:3]
                pom = os.path.join(M2, *dg.split("."), da, dv, f"{da}-{dv}.pom")
                print(f"{g}:{a}:{v} -> {d} pom={'cached' if os.path.isfile(pom) else 'MISSING'}")

