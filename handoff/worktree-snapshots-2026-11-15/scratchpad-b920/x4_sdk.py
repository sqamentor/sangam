import json, re, os, sys, hashlib
R = sys.argv[1]  # .../sangam
c = open(os.path.join(R, "sangam-rest/api/sangam-api.yaml"), encoding="utf-8").read()
ops = set(re.findall(r"(?m)^ {6}operationId: (\S+)$", c))
man = json.load(open(os.path.join(R, "sdks/codegen/surface-manifest.json"), encoding="utf-8"))
gen = {r["operationId"] for r in man["surface"]}
print("contract ops", len(ops), "manifest ops", len(gen), "missing", sorted(ops - gen), "extra", sorted(gen - ops))
lines = c.split("\n")
st = lines.index("  schemas:")
schemas = []
for l in lines[st + 1:]:
    if l.strip() and len(l) - len(l.lstrip(" ")) < 4: break
    m = re.match(r"^ {4}([A-Za-z0-9_.$-]+):\s*$", l)
    if m: schemas.append(m.group(1))
mm = man.get("models")
mm = set(mm if isinstance(mm, list) else (mm or {}).keys())
print("contract schemas", len(schemas), "manifest models", len(mm), "missing", sorted(set(schemas) - mm)[:30], "extra", sorted(mm - set(schemas))[:30])
print("manifest keys", list(man.keys()))
for k in man:
    if k not in ("surface", "models"):
        v = man[k]; print("  ", k, v if not isinstance(v, (list, dict)) else type(v).__name__ + str(len(v)))
# java generated method presence: every manifest entry with a "java" key -> method name in generated api file
miss = []
for r in man["surface"]:
    j = r.get("java")
    if not j: continue
    grp, name = j.split(".") if "." in j else (None, j)
    if grp:
        fn = os.path.join(R, "sdks/java/src/main/java/org/sangam/sdk/generated/api", grp[0].upper() + grp[1:] + "Api.java")
        if not os.path.isfile(fn) or not re.search(r"\b" + re.escape(name) + r"\s*\(", open(fn, encoding="utf-8").read()):
            miss.append(j)
print("java manifest entries without a generated method (heuristic):", len(miss), miss[:10])
print("sample entry", man["surface"][0])
