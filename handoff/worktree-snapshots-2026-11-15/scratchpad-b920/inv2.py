import csv
rows = list(csv.DictReader(open(r"D:\GITHUB  Project\sangam-handoff\compliance\MAVEN-INVENTORY.csv", encoding="utf-8")))
for r in rows:
    sc = r["scopes"]
    shipped = "distribution" in sc
    if r["decision"] == "REVIEW REQUIRED" or ("GPL" in r["spdx"] and "LGPL" not in r["spdx"] and not shipped):
        print(("SHIP " if shipped else "     ") + r["gav"], "||", r["spdx"][:80], "||", r["decision"], "||", sc[:50], "||", r["conditions_or_reason"][:80])
