import os, re, yaml
doc = yaml.safe_load(open(os.path.join(_BK_SANGAM, "sangam-rest/api/sangam-api.yaml"), encoding="utf-8"))
S = doc["components"]["schemas"]
def res(s):
    while "$ref" in s: s = S[s["$ref"].split("/")[-1]]
    return s
web = os.path.join(_BK_SANGAM, "sangam-server/src/main/java/org/sangam/server/web")
ops = {}
for p, item in doc["paths"].items():
    for m, o in item.items(): ops[m.upper() + " " + re.sub(r"\{[^}]*\}", "{}", p)] = o
def comps(path, name):
    f = _v2_find_type(path, name)
    if not f or f[1] != "record": return None
    out = []
    for c in _v2_param_list(f[2]):
        c = re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", c).strip()
        mm = re.match(r"(.+?)\s+(\w+)$", c); out.append((mm.group(2), mm.group(1), f[0]))
    return out
def check(where, schema, path, tname, depth=0):
    cs = comps(path, re.sub(r"<.*", "", tname).strip())
    if cs is None: return
    s = res(schema); props = s.get("properties") or {}
    names = [n for n, _, _ in cs]
    if set(names) != set(props): print("DRIFT", where, sorted(names), sorted(props)); return
    for n, t, f in cs:
        inner = re.match(r"(?:List|Set|Collection)<(.+)>$", t)
        if inner and "items" in res(props[n]): check(where + "." + n + "[]", res(props[n])["items"], f, inner.group(1), depth + 1)
        else: check(where + "." + n, props[n], f, t, depth + 1)
n = 0
for fn in sorted(os.listdir(web)):
    src = open(os.path.join(web, fn), encoding="utf-8").read()
    base = re.search(r'@RequestMapping\("([^"]*)"', src)
    for mt in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\((?:value\s*=\s*)?"([^"]*)"[^)]*\))?\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*public[^(]*\(([^{]*?)\)\s*(?:throws[^{]*)?\{', src):
        bp = re.search(r"@RequestBody(?:\([^)]*\))?\s+([\w.<>]+)\s+\w+", mt.group(3))
        if not bp or bp.group(1) in ("String", "byte[]") or bp.group(1).startswith("Map"): continue
        full = (base.group(1) if base else "") + (mt.group(2) or "")
        key = mt.group(1).upper() + " " + re.sub(r"\{[^}]*\}", "{}", full[len("/api/v1"):])
        o = ops.get(key)
        if not o: print("NO OP", key, fn); continue
        n += 1
        check(key, o["requestBody"]["content"]["application/json"]["schema"], os.path.join(web, fn), bp.group(1))
print("checked", n)
