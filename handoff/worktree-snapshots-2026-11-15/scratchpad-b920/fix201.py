"""One-off: correct the 0.1.0 baseline where the generated contract now (from the controller source) says 201 instead of
200, and record each correction in sangam-api-deprecations.yaml (owner precedent 2026-11-12, 0.1.0 unreleased)."""
import sys
import yaml

gen, base, dep = sys.argv[1], sys.argv[2], sys.argv[3]
g = yaml.safe_load(open(gen, encoding="utf-8"))
b = yaml.safe_load(open(base, encoding="utf-8"))
changed = []
for p, item in g["paths"].items():
    for m, o in item.items():
        bo = b["paths"].get(p, {}).get(m)
        if not bo:
            continue
        s1 = sorted(k for k in bo["responses"] if k.startswith("2"))
        s2 = sorted(k for k in o["responses"] if k.startswith("2"))
        if s1 == ["200"] and s2 == ["201"]:
            changed.append((m, p, o["operationId"], o["description"]))
        elif s1 != s2:
            raise SystemExit("unexpected success change %s %s %s -> %s" % (m, p, s1, s2))
raw = open(base, "rb").read()
crlf = b"\r\n" in raw
text = raw.decode("utf-8").replace("\r\n", "\n")
for m, p, opid, desc in changed:
    i = text.index("      operationId: %s\n" % opid)
    j = text.index("      responses:\n        '200':", i)
    nxt = text.find("      operationId: ", i + 10)
    assert nxt == -1 or j < nxt, opid
    text = text[:j] + "      responses:\n        '201':" + text[j + len("      responses:\n        '200':"):]
open(base, "wb").write((text.replace("\n", "\r\n") if crlf else text).encode("utf-8"))
draw = open(dep, "rb").read()
dcrlf = b"\r\n" in draw
d = draw.decode("utf-8").replace("\r\n", "\n").rstrip("\n") + "\n"
for m, p, opid, desc in changed:
    d += ("  - method: %s\n    path: %s\n    corrected: 2026-11-12\n    baseline: baseline/sangam-api-0.1.0.yaml\n"
          "    from: \"success response 200\"\n    to: \"success response 201\"\n"
          "    reason: \"%s answers 201 Created (Lists.created / ResponseEntity.created / status 201); the generator now "
          "reads the status from the controller source\"\n"
          "    decision: \"owner precedent 2026-11-12 (0.1.0 unreleased; W2-C contract gaps)\"\n") % (
        m.upper(), p, desc.split(" (")[0])
open(dep, "wb").write((d.replace("\n", "\r\n") if dcrlf else d).encode("utf-8"))
print(len(changed), "operations corrected")
