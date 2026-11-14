"""Round 2 (W4-J 2026-11-12T20:03:30Z, decision 162): UI runtime rows for teams-js and its closure, diagram-js direct."""
import csv
import io
import json
import sys

CSV, LOCK = sys.argv[1], sys.argv[2]
lock = json.load(open(LOCK, encoding="utf-8"))["packages"]
DIST = "dependency (transitive); distribution (bundled into the UI dist by vite)"
REASON = ("decision 162 Teams module (W4-J REQUEST 2026-11-12T20:03:30Z, W4-C round 2 2026-11-13): runtime "
          "dependency of @microsoft/teams-js 2.59.0, installed --ignore-scripts from the tarball recorded in "
          "DOWNLOADS-WAVE4.md D-3; MIT")


def lock_entry(name):
    e = lock["node_modules/" + name]
    return e["version"], e["integrity"], e["resolved"], e["license"]


raw = open(CSV, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in raw else "\n"
rd = csv.DictReader(io.StringIO(raw, newline=""))
fields, rows = rd.fieldnames, list(rd)
index = {(r["name"], r["version"]): r for r in rows}

new = {
    "@microsoft/teams-js": ("dependency (direct); distribution (bundled into the UI teams.html entry by vite)",
                            "decision 162 Teams module (W4-J REQUEST 2026-11-12T20:03:30Z, W4-C round 2 2026-11-13): "
                            "direct runtime dependency of sangam-ui, installed --ignore-scripts from the tarball recorded "
                            "in DOWNLOADS-WAVE4.md D-3; LICENSE is the MIT text, Copyright (c) Microsoft Corporation"),
    "base64-js": (DIST, REASON),
}
for name, (scope, reason) in new.items():
    version, integrity, resolved, lic = lock_entry(name)
    if lic != "MIT":
        raise SystemExit(f"{name}: lock licence {lic}")
    if (name, version) in index:
        raise SystemExit(f"{name}@{version} already in the CSV")
    rows.append({
        "ecosystem": "npm", "name": name, "version": version, "integrity": integrity, "source": resolved,
        "usage_scope": scope, "location": f"sangam/sangam-ui/package-lock.json :: node_modules/{name}", "spdx": "MIT",
        "obligations": "MIT: attribution (copyright + licence text)", "decision": "ACCEPTED FOR DEFINED USE",
        "conditions_or_reason": reason,
        "evidence": f"node_modules\\{name.replace('/', chr(92))}\\package.json license='MIT' | licence files: LICENSE",
    })

for name in ("debug", "ms"):
    version, integrity, _, lic = lock_entry(name)
    r = index[(name, version)]
    if r["integrity"] != integrity or lic != "MIT":
        raise SystemExit(f"{name}: integrity or licence differs from the lock")
    r["usage_scope"] = DIST
    r["conditions_or_reason"] = (r["conditions_or_reason"] + "; " if r["conditions_or_reason"] else "") + \
        "now also a runtime dependency (through @microsoft/teams-js 2.59.0, decision 162), W4-C round 2 2026-11-13"

r = index[("diagram-js", "14.11.3")]
r["usage_scope"] = "dependency (direct); distribution (bundled into the UI dist by vite)"
r["conditions_or_reason"] += ("; direct dependency of sangam-ui since W4-J 2026-11-12 (it was transitive through "
                              "bpmn-js); the package's own LICENSE is the plain MIT text (Camunda Services GmbH)")

out = io.StringIO(newline="")
w = csv.DictWriter(out, fieldnames=fields, quoting=csv.QUOTE_MINIMAL, lineterminator=nl)
w.writeheader()
w.writerows(rows)
data = out.getvalue()
if not raw.endswith(nl):
    data = data[:-len(nl)]
open(CSV, "w", encoding="utf-8", newline="").write(data)
print(f"{CSV}: {len(rows)} rows")
