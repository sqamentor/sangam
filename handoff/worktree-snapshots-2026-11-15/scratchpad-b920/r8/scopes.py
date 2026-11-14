import csv, sys, collections
rows = list(csv.reader(open(sys.argv[1], newline="", encoding="utf-8")))
hdr = rows[0]; si = hdr.index("scopes"); di = hdr.index("decision")
c = collections.Counter(r[si] for r in rows[1:])
for k, v in c.most_common(40): print(v, k)
print(collections.Counter(r[di] for r in rows[1:]))
for r in rows[1:]:
    if r[0].startswith("org.javassist:"): print(dict(zip(hdr, r))["conditions_or_reason"])
