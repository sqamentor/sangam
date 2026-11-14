"""Read-only: print the last N assistant text blocks (and optionally tool_use commands)
of an agent transcript (JSONL), with timestamps.

Usage: python -I ttail.py <file> <N> <maxchars> [tools]
"""
import json
import re
import sys


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    path, n, maxc = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    with_tools = len(sys.argv) > 4
    items = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                rec = json.loads(line)
            except Exception:
                continue
            ts = rec.get("timestamp") or ""
            msg = rec.get("message") or {}
            if not isinstance(msg, dict) or msg.get("role") != "assistant":
                continue
            content = msg.get("content")
            if not isinstance(content, list):
                continue
            for c in content:
                if not isinstance(c, dict):
                    continue
                if c.get("type") == "text" and c.get("text", "").strip():
                    items.append((ts, "TEXT", c["text"]))
                elif with_tools and c.get("type") == "tool_use":
                    inp = c.get("input") or {}
                    cmd = inp.get("command") or inp.get("file_path") or inp.get("description") or ""
                    items.append((ts, "TOOL " + str(c.get("name")), str(cmd)))
    for ts, kind, text in items[-n:]:
        t = re.sub(r"\s+", " ", text)
        print(f"{ts} {kind}: {t[:maxc]}")


if __name__ == "__main__":
    main()
