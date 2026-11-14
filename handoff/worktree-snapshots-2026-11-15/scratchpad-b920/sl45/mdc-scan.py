"""MDC leak scan of an IT log (ECS JSON lines): tenant A's log lines whose sangam.* context carries tenant B's values.

usage: mdc-scan.py <it-log> <run-prefix, e.g. i4b09239e>
"""
import json
import sys

path, run = sys.argv[1], sys.argv[2]
# strict: the business key of B's REST-started instance, which tenant A never sends (the IT reserves it)
b_markers = (run + "-bkb-rest",)
a_lines = leaks = 0
examples = []
with open(path, encoding="utf-8", errors="replace") as f:
    for line in f:
        if not line.startswith("{"):
            continue
        try:
            e = json.loads(line)
        except ValueError:
            continue
        who = e.get("principal") or (e.get("enduser") or {}).get("id") or ""
        if not who.startswith("sl45-a-"):
            continue
        a_lines += 1
        ctx = e.get("sangam") or {}
        hits = {k: v for k, v in ctx.items() if isinstance(v, str) and any(m in v for m in b_markers)}
        if hits:
            leaks += 1
            if len(examples) < 5:
                examples.append((e.get("@timestamp"), who, (e.get("process") or {}).get("thread", {}).get("name"),
                                 hits, (e.get("message") or "")[:160]))
print(f"tenant A log lines: {a_lines}; carrying tenant B's sangam.* values: {leaks}")
for x in examples:
    print("  ", x)
