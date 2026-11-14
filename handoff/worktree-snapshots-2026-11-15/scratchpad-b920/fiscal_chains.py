import yaml, os, sys
D = sys.argv[1]
docs = {}
for idx in ("profiles.idx", "intl.idx"):
    for line in open(os.path.join(D, idx), encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"): continue
        y = yaml.safe_load(open(os.path.join(D, line), encoding="utf-8"))
        p = y["profile"]; docs[p["id"]] = (line, y)
def get(d, path):
    for k in path.split("."):
        if not isinstance(d, dict) or k not in d: return None
        d = d[k]
    return d
def merge(a, b):
    for k, v in b.items():
        if isinstance(v, dict) and isinstance(a.get(k), dict): merge(a[k], v)
        else: a[k] = v
def expand(i, steps, seen):
    _, y = docs[i]; p = y["profile"]
    for e in p.get("extends") or []:
        if e in seen: continue
        if e not in docs: steps.append(("MISSING:"+e, None)); continue
        expand(e, steps, seen)
    for a in p.get("adopt") or y.get("adopt") or []:
        donor = a.get("profile"); secs = a.get("sections") or []
        if donor in docs:
            dd = {k: v for k, v in docs[donor][1].items() if k not in ("profile","placeholders","locked")}
            pk = {}
            for s in secs:
                v = get(dd, s)
                if v is not None:
                    cur = pk; parts = s.split(".")
                    for q in parts[:-1]: cur = cur.setdefault(q, {})
                    cur[parts[-1]] = v
            steps.append((donor+str(secs), pk))
    if i not in seen:
        seen.add(i); steps.append((i, {k: v for k, v in y.items() if k not in ("profile","placeholders","locked")}))
for i in sorted(docs):
    parts = i.split("/")[0].split("."); ids = [".".join(parts[:n+1]) for n in range(len(parts))]
    steps, seen = [], set()
    for x in ids:
        if x in docs: expand(x, steps, seen)
    data = {}
    for _, s in steps:
        if s: merge(data, s)
    f = get(data, "locale.fiscal")
    print(f"{i:28} layer={docs[i][1]['profile']['layer']:13} fiscal={f} chain={'>'.join(s for s,_ in steps)}")
