import sys, yaml, collections
d = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
S = d["components"]["schemas"]
def opn(s):
    if "$ref" in s:
        s = S[s["$ref"].split("/")[-1]]
    return s.get("type") == "object" and not s.get("properties") and s.get("additionalProperties") is True
rows = []
for p, item in d["paths"].items():
    for m, o in item.items():
        rb = o.get("requestBody")
        if not rb: continue
        for ct, c in rb["content"].items():
            if ct == "application/json" and opn(c.get("schema", {})):
                rows.append((o["tags"][0], m.upper(), p, o["operationId"], o["description"][:90]))
c = collections.Counter(r[0] for r in rows)
print(len(rows), c)
for r in rows: print(*r, sep=" | ")
