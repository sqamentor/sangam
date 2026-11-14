import csv, sys
rows = list(csv.reader(open(sys.argv[1], newline="", encoding="utf-8")))
hdr = rows[0]
for r in rows[1:]:
    if r[0].startswith("net.java.dev.jna"):
        print(" | ".join(f"{k}={v}" for k, v in zip(hdr, r) if k not in ("obligations",)))
        print("---")
