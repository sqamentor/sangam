"""Read-only: dump the full text of transcript records whose timestamp starts with one of
the given prefixes (e.g. 2026-11-09T23:18:10).

Usage: python -I tdump.py <transcript> <maxchars> <ts-prefix> [<ts-prefix> ...]
"""
import json
import re
import sys


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    path, maxc = sys.argv[1], int(sys.argv[2])
    prefixes = sys.argv[3:]
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                rec = json.loads(line)
            except Exception:
                continue
            ts = rec.get("timestamp") or ""
            if not any(ts.startswith(p) for p in prefixes):
                continue
            msg = rec.get("message") or {}
            content = msg.get("content") if isinstance(msg, dict) else None
            parts = []
            if isinstance(content, str):
                parts.append(content)
            elif isinstance(content, list):
                for c in content:
                    if not isinstance(c, dict):
                        continue
                    if c.get("type") == "text":
                        parts.append(c.get("text", ""))
                    elif c.get("type") == "tool_use":
                        inp = c.get("input") or {}
                        parts.append(f"[tool_use {c.get('name')} to={inp.get('to','')}] " + str(inp.get("message") or inp.get("prompt") or inp))
                    elif c.get("type") == "tool_result":
                        rc = c.get("content")
                        if isinstance(rc, list):
                            rc = " ".join(x.get("text", "") for x in rc if isinstance(x, dict))
                        parts.append("[tool_result] " + str(rc))
            text = "\n".join(parts)
            print(f"===== {ts} ({msg.get('role') if isinstance(msg, dict) else '?'})")
            print(text[:maxc])
            print()


if __name__ == "__main__":
    main()
