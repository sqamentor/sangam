import csv, io, sys
for p, q in ((sys.argv[1], csv.QUOTE_ALL), (sys.argv[2], csv.QUOTE_MINIMAL)):
    raw = open(p, encoding="utf-8", newline="").read()
    nl = "\r\n" if "\r\n" in raw else "\n"
    rd = csv.DictReader(io.StringIO(raw, newline=""))
    rows = list(rd)
    out = io.StringIO(newline="")
    w = csv.DictWriter(out, fieldnames=rd.fieldnames, quoting=q, lineterminator=nl)
    w.writeheader(); w.writerows(rows)
    d = out.getvalue()
    if not raw.endswith(nl): d = d[:-len(nl)]
    print(p, "roundtrip identical:", d == raw, repr(nl), raw.startswith("\ufeff"))
