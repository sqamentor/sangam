import csv, sys
rows = list(csv.reader(open(sys.argv[1], newline="", encoding="utf-8")))
hdr = rows[0]
for r in rows[1:]:
    d = dict(zip(hdr, r))
    if d["scopes"].startswith("use=runtime") or "hapi" in d["gav"] or "fhir" in d["gav"]:
        print(d["gav"], "|", d["scopes"], "|", d["modules"], "|", d["spdx"], "|", d["decision"])
