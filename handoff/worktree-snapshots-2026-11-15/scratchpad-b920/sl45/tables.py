import re, sys, xml.etree.ElementTree as ET
for _, el in ET.iterparse(sys.argv[1], events=("end",)):
    if el.tag != "testcase":
        continue
    for c in el:
        if c.tag not in ("failure", "error"):
            continue
        msg = c.get("message") or ""
        print("=====", el.get("name"))
        # entries of engine-rest: split at top-level leak starts
        parts = re.split(r", (?=(?:GET|POST|PUT|PATCH|DELETE) /|tenant A's /engine-rest batches)", msg[msg.find("<[") + 2:] if "<[" in msg else msg)
        for p in parts:
            head = p[:160].replace("\n", " ")
            tables = re.findall(r"(public\.[a-z_]+): (\d+) -> (\d+) rows; removed \[(.*?)\]; added \[", p, re.S)
            print("  *", head if not tables else head.split(" changed tenant B's rows")[0] + " changed tenant B's rows:")
            for t, a, b, removed in tables:
                nrem = len(re.findall(r"\(", removed)) if removed else 0
                print(f"      {t}: {a} -> {b} rows (removed {nrem} row(s))")
    el.clear()
