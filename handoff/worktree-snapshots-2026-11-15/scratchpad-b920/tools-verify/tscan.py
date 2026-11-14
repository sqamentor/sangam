"""Read-only scan of a Claude Code transcript (JSONL).

Usage: python -I tscan.py <transcript> <since-iso> [regex ...]
Prints, for each record at or after <since-iso>: timestamp, role/kind and a short
snippet. With regexes, prints only records whose text matches one of them, with a
snippet around the first match.
"""
import json
import re
import sys


def texts(obj):
    out = []
    if isinstance(obj, str):
        out.append(obj)
    elif isinstance(obj, list):
        for x in obj:
            out.extend(texts(x))
    elif isinstance(obj, dict):
        for k, v in obj.items():
            if k in ("signature",):
                continue
            out.extend(texts(v))
    return out


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    path, since = sys.argv[1], sys.argv[2]
    pats = [re.compile(p, re.I | re.S) for p in sys.argv[3:]]
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                rec = json.loads(line)
            except Exception:
                continue
            ts = rec.get("timestamp") or ""
            if ts < since:
                continue
            kind = rec.get("type", "?")
            msg = rec.get("message") or {}
            role = msg.get("role", "") if isinstance(msg, dict) else ""
            content = msg.get("content") if isinstance(msg, dict) else None
            tool = ""
            if isinstance(content, list):
                names = [c.get("name") for c in content if isinstance(c, dict) and c.get("type") == "tool_use"]
                if names:
                    tool = "tool_use:" + ",".join(n or "?" for n in names)
                elif any(isinstance(c, dict) and c.get("type") == "tool_result" for c in content):
                    tool = "tool_result"
            body = " ".join(texts(content if content is not None else rec))
            body = re.sub(r"\s+", " ", body)
            if pats:
                m = None
                for p in pats:
                    m = p.search(body)
                    if m:
                        break
                if not m:
                    continue
                a = max(0, m.start() - 160)
                snippet = body[a:m.end() + 240]
            else:
                snippet = body[:300]
            print(f"{ts} | {kind}/{role} {tool} | {snippet}")


if __name__ == "__main__":
    main()
