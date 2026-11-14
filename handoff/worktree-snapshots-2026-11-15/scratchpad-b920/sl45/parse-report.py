"""Per test case of a failsafe XML report: status, time and the failure message split into its leak entries."""
import re
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
width = int(sys.argv[2]) if len(sys.argv) > 2 else 700
STARTS = r"(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) /|tenant A's /engine-rest batches"

for _, el in ET.iterparse(path, events=("end",)):
    if el.tag != "testcase":
        continue
    name, t = el.get("name"), el.get("time")
    bad = [c for c in el if c.tag in ("failure", "error")]
    if not bad:
        print(f"PASS  {name}  ({t} s)")
    for c in bad:
        msg = c.get("message") or (c.text or "")[:4000]
        print(f"{c.tag.upper()}  {name}  ({t} s)  type={c.get('type')}")
        body = msg
        k = body.find("but was: <[")
        if k >= 0:
            head, body = body[:k], body[k + len("but was: <["):]
            print("  " + head.strip()[:300])
            parts = re.split(r", (?=" + STARTS + ")", body)
            print(f"  {len(parts)} entries")
            for p in parts:
                print("  - " + p[:width].replace("\n", " "))
        else:
            print("  " + body[:width * 3].replace("\n", " "))
    el.clear()
