import sys, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot()
for tc in r.iter("testcase"):
    for c in tc:
        if c.tag in ("failure", "error"):
            print((c.get("message") or "")[:3000])
