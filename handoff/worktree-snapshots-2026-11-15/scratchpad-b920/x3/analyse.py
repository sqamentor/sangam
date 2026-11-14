import json, collections, re, os, sys
D = os.path.dirname(os.path.abspath(__file__))
r = json.load(open(os.path.join(D, "claims_report.json"), encoding="utf-8"))["files"]
W1_START = "2026-11-12T00:00:00Z"

def lane_key(l):
    l = l.strip()
    m = re.match(r"(B\d+[a-z]?|D\d+|INTEGRATOR|IFMS-WF|SL-\d+(?:/[A-Z0-9\-]+)?|security|B\d+-[A-Z0-9\-]+)", l)
    return (m.group(1) if m else l).split("-SL")[0].split("/")[0]

out = collections.defaultdict(list)
# ---- hash checks ----
hash_rows = []
for p, d in r.items():
    evs = d["events"]
    rels = [e for e in evs if e["action"] == "RELEASE" and e.get("hash_after")]
    for e in evs:
        if e["action"] != "RELEASE" or e["ts"] < W1_START:
            continue
        hv = d["sha"] or {}
        h = e.get("hash_after")
        if not h and not e.get("hash_any_match"):
            continue
        matched = [k for k, v in hv.items() if h and v.startswith(h)]
        later = [x for x in evs if (x["ts"], x["line"]) > (e["ts"], e["line"])]
        hash_rows.append({"file": p, "line": e["line"], "ts": e["ts"], "lane": e["lane"], "hash": h,
                          "match": matched, "any_in_line": e.get("hash_any_match"), "later_events": len(later),
                          "is_last": not later, "mt": e["match"]})
ok = [x for x in hash_rows if x["match"]]
alt = [x for x in hash_rows if not x["match"] and x["any_in_line"]]
bad = [x for x in hash_rows if not x["match"] and not x["any_in_line"]]
print("hash rows (11-12 releases)", len(hash_rows), "match", len(ok), "match-other-token", len(alt), "nomatch", len(bad))
print("match variants", collections.Counter(tuple(x["match"]) for x in ok))
print("\nNO MATCH (any token):")
for x in sorted(bad, key=lambda x: x["line"]):
    print(" L%d %s %s %s hash=%s last=%s mt=%s" % (x["line"], x["ts"], x["lane"], x["file"], x["hash"], x["is_last"], x["mt"]))
print("\nmatched only via other token in line:")
for x in sorted(alt, key=lambda x: x["line"]):
    print(" L%d %s %s hash_after=%s other=%s" % (x["line"], x["lane"], x["file"], x["hash"], x["any_in_line"]))

# ---- 11-12 claimed files: lanes, overlaps, open at checkpoint ----
print("\n==== per-file lane analysis (files with any 11-12 event) ====")
multi = []
openc = []
noclaim_release = []
for p, d in r.items():
    evs = [e for e in d["events"] if e["ts"] >= W1_START]
    if not evs:
        continue
    lanes = collections.OrderedDict()
    intervals = collections.defaultdict(list)
    state = {}
    for e in d["events"]:
        lk = lane_key(e["lane"])
        if e["action"] == "CLAIM":
            if lk in state:
                pass
            state[lk] = e["ts"]
        else:
            st = state.pop(lk, None)
            if st is None and e["ts"] >= W1_START:
                noclaim_release.append((p, e["line"], e["lane"]))
            intervals[lk].append((st or e["ts"], e["ts"]))
    for lk, st in state.items():
        openc.append((p, lk, st))
        intervals[lk].append((st, "9999"))
    lk11 = sorted({lane_key(e["lane"]) for e in evs})
    if len(lk11) > 1:
        ov = []
        ks = list(intervals)
        for i in range(len(ks)):
            for j in range(i + 1, len(ks)):
                for a in intervals[ks[i]]:
                    for b in intervals[ks[j]]:
                        if a[0] < b[1] and b[0] < a[1] and max(a[0], b[0]) >= W1_START:
                            ov.append((ks[i], a, ks[j], b))
        multi.append((p, lk11, ov))
print("files edited by >1 lane on 11-12:", len(multi))
for p, l, ov in multi:
    print(" ", p, l, "OVERLAP" if ov else "", ov[:2])
print("\nopen claims at checkpoint:", len(openc))
for x in openc:
    if x[2] >= "2026-11-11":
        print(" ", x)
print("\nRELEASE on 11-12 without a preceding CLAIM by same lane:", len(noclaim_release))
for x in noclaim_release:
    print(" ", x)

# ---- unclaimed files changed after 0923 snapshot / 1924 ----
print("\n==== unclaimed ====")
for p, d in r.items():
    if d["age"]["after0923"] and not d["events"]:
        print(" after0923 none:", p)
print("files changed after 03:53Z (0923 snapshot):", sum(1 for d in r.values() if d["age"]["after0923"]))
cnt = collections.Counter()
for p, d in r.items():
    if d["age"]["after0923"]:
        has11 = any(e["ts"] >= W1_START for e in d["events"])
        cnt["with 11-12 event" if has11 else ("only older events" if d["events"] else "none")] += 1
print(cnt)
for p, d in r.items():
    if d["age"]["after0923"] and d["events"] and not any(e["ts"] >= W1_START for e in d["events"]):
        print(" after0923 only-old-claims:", p, [(e["ts"], e["lane"], e["action"]) for e in d["events"]][-2:])
