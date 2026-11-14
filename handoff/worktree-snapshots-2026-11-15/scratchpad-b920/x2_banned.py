import re, sys, os
R = sys.argv[1]
pom = open(os.path.join(R, "pom.xml"), encoding="utf-8").read()
at = pom.find("<id>sangam-banned-dependencies</id>"); end = pom.find("</execution>", at)
print("exec found", at > 0, "after pluginManagement", pom.rfind("</pluginManagement>", 0, at) > 0)
blk = pom[at:end]
ex = [m.strip().split(":") for m in re.findall(r"<exclude>([^<]+)</exclude>", blk)]
def fm(p, i, v):
    if i >= len(p): return True
    return re.fullmatch(".*".join(re.escape(x) for x in p[i].split("*")), v) is not None
def banned(g, a):
    for p in ex:
        sc = len(p) < 5 or p[4] in ("*", "compile"); av = len(p) < 3 or p[2] == "*"
        if sc and av and fm(p, 0, g) and fm(p, 1, a): return True
    return False
pol = open(os.path.join(R, "deploy/licence/licence-policy.json"), encoding="utf-8").read()
pin = pol.find('"pinned_exceptions":')
print("pinned idx", pin)
n = 0
for spec in re.findall(r"\"purl\": \"pkg:maven/([^\"@]+)\"", pol[:pin]):
    if "/" not in spec: g, a = spec + "x", "any"
    else:
        g, a = spec.split("/", 1)
        a = a or "any"
        if g.endswith("."): g += "x"
    if g.endswith("."): g += "x"
    n += 1
    if not banned(g, a): print("  UNENFORCED", spec)
print("checked", n)
print("LIC-7", "<exclude>org.liquibase:liquibase-core:[5,)</exclude>" in blk, "grpc", banned("io.grpc", "grpc-netty-shaded"),
      "feel", "<include>org.camunda.feel:feel-engine:[${version.feel-engine}]</include>" in blk)
for p in ex:
    if p[0].startswith(("com.oracle", "com.ibm.db2", "com.mysql")) or p[0] == "mysql":
        if len(p) != 5 or p[4] in ("test", "*"): print("  DRIVER SCOPE", p)
