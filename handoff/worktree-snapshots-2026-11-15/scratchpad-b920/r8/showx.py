import csv, sys
rows = list(csv.reader(open(sys.argv[1], newline="", encoding="utf-8")))
hdr = rows[0]
for r in rows[1:]:
    if any(k in r[0] for k in ("xpp3", "xmlpull", "mxparser")):
        for k, v in zip(hdr, r):
            print(f"  {k}: {v}")
        print("-----")
