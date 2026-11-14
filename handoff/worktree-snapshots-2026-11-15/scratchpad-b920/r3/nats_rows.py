import csv, sys
p = sys.argv[1]
with open(p, "rb") as f:
    term = "\r\n" if b"\r\n" in f.read(4096) else "\n"
with open(p, encoding="utf-8", newline="") as f:
    rows = list(csv.reader(f))
hdr = rows[0]; gi, si, ci = hdr.index("gav"), hdr.index("scopes"), hdr.index("conditions_or_reason")
targets = {"io.nats:jnats:2.26.4", "org.bouncycastle:bcprov-lts8on:2.73.12.1"}
n = 0
for r in rows[1:]:
    if r[gi] in targets:
        r[si] = "use=distribution [nats-lib-dir]; maven=compile,compile(optional)"
        note = ("decision 232 (2026-11-13): shipped only in the opt-in nats add-on (lib/), not in the server fat jar; "
                "the server pom exclusion is INT-H2's")
        if note not in r[ci]:
            r[ci] = (r[ci] + "; " if r[ci] else "") + note
        n += 1
assert n == 2, n
with open(p, "w", encoding="utf-8", newline="") as f:
    csv.writer(f, quoting=csv.QUOTE_ALL, lineterminator=term).writerows(rows)
print("updated", n)

