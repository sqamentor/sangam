import csv, sys
p = sys.argv[1]
nl = "\r\n" if b"\r\n" in open(p, "rb").read() else "\n"
rows = list(csv.reader(open(p, newline="", encoding="utf-8")))
hdr = rows[0]; ix = {k: hdr.index(k) for k in ("gav", "scopes", "modules", "conditions_or_reason", "evidence")}
n = 0
for r in rows[1:]:
    if r[ix["gav"]] in ("org.bouncycastle:bcprov-lts8on:2.73.12.1", "io.nats:jnats:2.26.4"):
        assert r[ix["scopes"]] == "use=distribution [nats-lib-dir]; maven=compile,compile(optional)", r[ix["scopes"]]
        assert r[ix["modules"]] == "sangam-flowable-ports sangam-integration sangam-server sangam-spring-boot-starter", r[ix["modules"]]
        old = "; the server pom exclusion is INT-H2's"
        assert r[ix["conditions_or_reason"]].endswith(old), r[ix["conditions_or_reason"]]
        r[ix["scopes"]] = "use=distribution [nats-lib-dir]; maven=compile(optional)"
        r[ix["modules"]] = "sangam-flowable-ports sangam-integration"
        r[ix["conditions_or_reason"]] = r[ix["conditions_or_reason"]][:-len(old)] + "; the server pom exclusion has landed: sangam-server and sangam-spring-boot-starter no longer resolve it"
        r[ix["evidence"]] += "; dependency:tree offline 2026-11-13T04:32:25Z of sangam-flowable-ports, sangam-integration, sangam-server, sangam-spring-boot-starter: jnats 2.26.4 and bcprov-lts8on 2.73.12.1 compile (optional) in sangam-flowable-ports and sangam-integration only (log SHA-256 A665B8EACAADB9C9179B671705CAB384EA6696943046941F2E84D7EC5158B05F)"
        n += 1
assert n == 2, n
with open(p, "w", newline="", encoding="utf-8") as f:
    csv.writer(f, quoting=csv.QUOTE_ALL, lineterminator=nl).writerows(rows)
print("updated", n)
