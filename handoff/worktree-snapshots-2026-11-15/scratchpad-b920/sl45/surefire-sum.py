import sys, glob, os, xml.etree.ElementTree as ET
d = sys.argv[1]
tot = {"tests": 0, "fail": 0, "err": 0}
for f in sorted(glob.glob(os.path.join(d, "TEST-*.xml"))):
    r = ET.parse(f).getroot()
    tot["tests"] += int(r.get("tests")); tot["fail"] += int(r.get("failures")); tot["err"] += int(r.get("errors"))
    bad = [(tc.get("name"), c.tag, (c.get("type") or ""), (c.get("message") or "")[:230].replace("\n", " ")) for tc in r.iter("testcase") for c in tc if c.tag in ("failure", "error")]
    if bad:
        print(f"{r.get('name')}: tests={r.get('tests')} failures={r.get('failures')} errors={r.get('errors')}")
        seen = set()
        for n, tag, typ, msg in bad:
            key = (typ, msg[:120])
            if key in seen: continue
            seen.add(key)
            print(f"   {tag} {n}: {typ.split('.')[-1]}: {msg}")
print("TOTAL", tot)
