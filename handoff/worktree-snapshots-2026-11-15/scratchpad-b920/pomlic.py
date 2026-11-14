import sys, os, re, json
import xml.etree.ElementTree as ET

REPO = sys.argv[1]
GAVS = [l.strip() for l in open(sys.argv[2], encoding="utf-8") if l.strip()]
NS = "{http://maven.apache.org/POM/4.0.0}"

def pom_path(g, a, v):
    return os.path.join(REPO, *g.split("."), a, v, f"{a}-{v}.pom")

def find(el, tag):
    r = el.find(NS + tag)
    if r is None:
        r = el.find(tag)
    return r

def findall(el, path):
    out = el.findall("/".join(NS + p for p in path.split("/")))
    return out or el.findall(path)

def licences(g, a, v, depth=0):
    p = pom_path(g, a, v)
    if not os.path.exists(p) or depth > 10:
        return None, f"(pom missing {g}:{a}:{v})"
    try:
        root = ET.parse(p).getroot()
    except ET.ParseError as e:
        txt = open(p, encoding="utf-8", errors="replace").read()
        names = re.findall(r"<license>\s*<name>([^<]+)</name>", txt)
        return (names or None), "regex"
    lic = [ (find(l, "name").text or "").strip() for l in findall(root, "licenses/license") if find(l, "name") is not None]
    if lic:
        return lic, f"{g}:{a}:{v}" if depth else "self"
    par = find(root, "parent")
    if par is not None:
        pg, pa, pv = (find(par, t).text.strip() for t in ("groupId", "artifactId", "version"))
        return licences(pg, pa, pv, depth + 1)
    return None, "none declared"

for gav in GAVS:
    g, a, v = gav.split(":")
    jar = os.path.exists(os.path.join(REPO, *g.split("."), a, v, f"{a}-{v}.jar")) or any(
        f.endswith(".jar") for f in os.listdir(os.path.join(REPO, *g.split("."), a, v)))
    lic, src = licences(g, a, v)
    print(json.dumps({"gav": gav, "jar": jar, "licences": lic, "from": src}))
