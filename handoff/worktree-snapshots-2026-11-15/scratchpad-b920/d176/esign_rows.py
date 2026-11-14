import csv, sys
p = sys.argv[1]
with open(p, encoding="utf-8", newline="") as f:
    rows = list(csv.reader(f))
hdr = rows[0]; gi = hdr.index("gav"); si = hdr.index("scopes"); mi = hdr.index("modules"); ci = hdr.index("conditions_or_reason")
targets = {"org.bouncycastle:bcprov-jdk18on:1.84", "org.bouncycastle:bcpkix-jdk18on:1.84", "org.bouncycastle:bcutil-jdk18on:1.84"}
n = 0
for r in rows[1:]:
    if r[gi] in targets:
        r[si] = "use=distribution [esign-lib-dir]; maven=compile"
        r[mi] = "sangam-esign"
        note = "decision 217 (2026-11-13): shipped only in the opt-in esign distribution (lib/), not in the server fat jar"
        if note not in r[ci]:
            r[ci] = (r[ci] + "; " if r[ci] else "") + note
        n += 1
assert n == 3, n
with open(p, "rb") as f:
    term = "\r\n" if b"\r\n" in f.read(4096) else "\n"
with open(p, "w", encoding="utf-8", newline="") as f:
    csv.writer(f, quoting=csv.QUOTE_ALL, lineterminator=term).writerows(rows)
print("updated", n)

