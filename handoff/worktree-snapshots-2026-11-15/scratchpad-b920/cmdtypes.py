import sys, yaml, collections, json
spec = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
ext = collections.Counter()
pairs = collections.defaultdict(set)
noperm = []
for path, item in spec.get("paths", {}).items():
    for method, op in item.items():
        if not isinstance(op, dict):
            continue
        for k in op:
            if k.startswith("x-sangam"):
                ext[k] += 1
        ct = op.get("x-sangam-command-type")
        if ct is None:
            continue
        perm = op.get("x-sangam-permission")
        if perm is None:
            noperm.append((ct, method, path, {k: v for k, v in op.items() if k.startswith("x-sangam")}))
        pairs[ct].add(json.dumps(perm))
print(ext)
print("distinct command types", len(pairs))
multi = {k: v for k, v in pairs.items() if len(v) > 1}
print("types with >1 permission", multi)
print("no permission", len(noperm))
for n in noperm[:20]:
    print(" ", n)
with open(sys.argv[2], "w", encoding="utf-8") as f:
    for k in sorted(pairs):
        f.write(k + "\t" + ",".join(sorted(pairs[k])) + "\n")
