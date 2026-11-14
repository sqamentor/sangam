import csv, sys
p = sys.argv[1]
raw = open(p, "rb").read()
nl = "\r\n" if b"\r\n" in raw else "\n"
rows = list(csv.reader(open(p, newline="", encoding="utf-8")))
hdr = rows[0]; gi = hdr.index("gav")
n = 0
for r in rows[1:]:
    if r[gi] == "org.bouncycastle:bcprov-jdk18on:1.84":
        d = dict(zip(hdr, r))
        assert d["scopes"] == "use=distribution [esign-lib-dir]; maven=compile", d["scopes"]
        assert d["modules"] == "sangam-esign", d["modules"]
        r[hdr.index("scopes")] = "use=distribution [esign-lib-dir]; maven=compile,compile(optional)"
        r[hdr.index("modules")] = "sangam-connectors-health sangam-esign"
        c = hdr.index("conditions_or_reason")
        r[c] = r[c] + "; decision 243 (2026-11-13): also an optional dependency of sangam-connectors-health for Fidelius, never in the server fat jar; at run time Fidelius uses this same jar from the esign add-on, so its licence and notice ship with the esign distribution; health never runs with the nats add-on (decision 235); the health pom change is W4-K's (dependency:tree to be re-taken when it lands)"
        n += 1
assert n == 1, n
with open(p, "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f, quoting=csv.QUOTE_ALL, lineterminator=nl)
    w.writerows(rows)
print("rows changed", n, "terminator", repr(nl))
