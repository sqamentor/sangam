import csv, sys
rows = list(csv.reader(open(sys.argv[1], newline="", encoding="utf-8")))
hdr = rows[0]
for r in rows[1:]:
    if r[0].startswith(("org.bouncycastle:bcprov-lts8on", "io.nats:jnats")):
        d = dict(zip(hdr, r))
        print(d["gav"], "|", d["scopes"], "|", d["modules"])
        print("   cond:", d["conditions_or_reason"][-260:])
        print("   evid:", d["evidence"][-200:])
