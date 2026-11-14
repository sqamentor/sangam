import sys, re, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot()
for tc in r.iter("testcase"):
    for c in tc:
        if c.tag in ("failure", "error") and c.text:
            causes = [l.strip() for l in c.text.splitlines() if l.strip().startswith("Caused by:")]
            print(tc.get("name"))
            for x in causes[-6:]:
                print("   ", x[:300])
            break
    else:
        continue
    break
