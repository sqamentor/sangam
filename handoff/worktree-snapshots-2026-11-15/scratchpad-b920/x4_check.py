import os, re, sys, json
# Port of CheckResultGuardTest (sangam-packs, SL-115), all three scans.
RES = os.path.join(sys.argv[1], "sangam-packs/src/main/resources")
PROC = re.compile(r'<process\b[^>]*\bid="([^"]+)"[^>]*>(.*?)</process>', re.S)
TASK = re.compile(r'<serviceTask\b[^>]*\bid="([^"]+)"[^>]*>(.*?)</serviceTask>', re.S)
FIELD = re.compile(r'<operaton:field name="(\w+)"><operaton:string>([^<]*)</operaton:string>')
CALL = re.compile(r'calledElement="([^"]+)"')
FLOW = re.compile(r'<sequenceFlow\b[^>]*\bsourceRef="([^"]+)"[^>]*\btargetRef="([^"]+)"')
COND = re.compile(r'<conditionExpression[^>]*>([^<]*)</conditionExpression>')
NOT_GATED = {"sangam-sec-tel-ucc-complaint consentFound", "sangam-ifms-receipt scrollAmount"}
xml = {}
for d, ds, fs in os.walk(os.path.join(RES, "packs")):
    for f in fs:
        if f.endswith(".bpmn"):
            p = os.path.join(d, f)
            xml[os.path.relpath(p, RES).replace("\\", "/")] = open(p, encoding="utf-8").read()
procs = {}
for path in sorted(xml):
    for p in PROC.finditer(xml[path]):
        prefixes = {}; tasks = {}
        for t in TASK.finditer(p.group(2)):
            fields = {f.group(1): f.group(2) for f in FIELD.finditer(t.group(2))}
            tasks[t.group(1)] = fields
            c = fields.get("connector")
            if c and c.strip():
                pre = fields.get("resultPrefix")
                prefixes.setdefault(pre if pre and pre.strip() else c[c.rfind(".") + 1:], c)
        procs[p.group(1)] = dict(path=path, body=p.group(2), prefixes=prefixes, tasks=tasks, calls=list(dict.fromkeys(CALL.findall(p.group(2)))))
def is_result(v, pre):
    return len(v) > len(pre) and v.startswith(pre) and v[len(pre)].isupper()
found = {}
for pid, proc in procs.items():
    prefixes = {}; seen = {pid}; todo = [pid]
    while todo:
        q = procs.get(todo.pop(0))
        if not q: continue
        for k, v in q["prefixes"].items(): prefixes.setdefault(k, v)
        for c in q["calls"]:
            if c not in seen: seen.add(c); todo.append(c)
    for tid, fields in proc["tasks"].items():
        dft = fields.get("defaults")
        if dft is None: continue
        for pair in dft.split(";"):
            eq = pair.find("=")
            if eq <= 0 or pair[eq + 1:].strip() == "null": continue
            name = pair[:eq].strip()
            for k, v in prefixes.items():
                if is_result(name, k):
                    found[pid + " " + name] = f"{proc['path']} {tid}: {pair.strip()} pre-fills the result of {v}"; break
rv = json.load(open(os.path.join(RES, "META-INF/sangam/check-results.json"), encoding="utf-8"))
listed = set(); dup = []; noreason = []
for lst in ("allowed", "pending"):
    for e in rv.get(lst, []):
        k = e.get("process", "") + " " + e.get("variable", "")
        if not (e.get("reason" if lst == "allowed" else "question") or "").strip(): noreason.append(k)
        if k in listed: dup.append(k)
        listed.add(k)
print("T1 found", len(found), "unreviewed", [v for k, v in found.items() if k not in listed], "stale", sorted(listed - set(found)), "dup", dup, "noreason", noreason)
bad = []; clears = 0; missing = []
for pid, p in procs.items():
    conds = COND.findall(p["body"])
    for tid, fields in p["tasks"].items():
        if not tid.endswith("_clear"): continue
        clears += 1
        chk = tid[:-6]; tgt = p["tasks"].get(chk)
        nxt = [f.group(2) for f in FLOW.finditer(p["body"]) if f.group(1) == tid]
        if tgt is None or tgt.get("connector") is None or nxt != [chk]:
            bad.append(f"{pid} {tid}: next {nxt}")
        else:
            c = tgt["connector"]; pre = tgt.get("resultPrefix") if tgt.get("resultPrefix") is not None else c[c.rfind(".") + 1:]
            for pair in fields.get("values", "").split(";"):
                name = pair[:max(0, pair.find("="))].strip()
                if not pair.strip().endswith("=null") or not is_result(name, pre): bad.append(f"{pid} {tid}: {pair}")
        for pair in fields.get("values", "").split(";"):
            name = pair[:max(0, pair.find("="))].strip()
            if pid + " " + name in NOT_GATED: continue
            if not any(name + " == null" in x for x in conds): missing.append(pid + " " + name)
print("T2 clears", clears, "bad", bad)
print("T3 missing", missing)
