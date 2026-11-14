import csv, sys
p = sys.argv[1]
nl = "\r\n" if b"\r\n" in open(p, "rb").read() else "\n"
rows = list(csv.reader(open(p, newline="", encoding="utf-8")))
hdr = rows[0]; gi = hdr.index("gav"); ci = hdr.index("conditions_or_reason"); ei = hdr.index("evidence")
n = 0
for r in rows[1:]:
    if r[gi] == "org.bouncycastle:bcprov-jdk18on:1.84":
        old = "; the health pom change is W4-K's (dependency:tree to be re-taken when it lands)"
        assert old in r[ci], r[ci]
        r[ci] = r[ci].replace(old, "; W4-K pom landed (DONE 2026-11-13T04:17:53Z)")
        r[ei] = r[ei] + "; dependency:tree offline 2026-11-13T04:27:05Z sangam-connectors-health -> bcprov-jdk18on 1.84 compile (optional), sangam-server none (log SHA-256 3FA7FE159F2B1E5E5C4B6CC8E7378E5116AE4230AFB36BF8625D7AFC0F758EB0)"
        n += 1
assert n == 1
with open(p, "w", newline="", encoding="utf-8") as f:
    csv.writer(f, quoting=csv.QUOTE_ALL, lineterminator=nl).writerows(rows)
print("updated", n)
