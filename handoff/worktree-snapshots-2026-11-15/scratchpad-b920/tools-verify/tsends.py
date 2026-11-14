"""Read-only: list tool calls of given names (default SendMessage, Agent, Task) and real
user-typed messages in a transcript after <since-iso>, with their results' first line.

Usage: python -I tsends.py <transcript> <since-iso> [ToolName ...]
"""
import json
import re
import sys


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    path, since = sys.argv[1], sys.argv[2]
    names = set(sys.argv[3:]) or {"SendMessage", "Agent", "Task"}
    pending = {}
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                rec = json.loads(line)
            except Exception:
                continue
            ts = rec.get("timestamp") or ""
            if ts < since:
                continue
            msg = rec.get("message") or {}
            if not isinstance(msg, dict):
                continue
            content = msg.get("content")
            role = msg.get("role")
            if role == "user":
                if isinstance(content, str):
                    t = re.sub(r"\s+", " ", content)
                    if not t.startswith("<"):
                        print(f"{ts} | USER | {t[:200]}")
                    elif "<task-notification>" in t or "usage limit" in t.lower():
                        print(f"{ts} | NOTIFY | {t[:260]}")
                    else:
                        print(f"{ts} | USER* | {t[:200]}")
                elif isinstance(content, list):
                    for c in content:
                        if not isinstance(c, dict):
                            continue
                        if c.get("type") == "text":
                            t = re.sub(r"\s+", " ", c.get("text", ""))
                            print(f"{ts} | USERTXT | {t[:260]}")
                        elif c.get("type") == "tool_result" and c.get("tool_use_id") in pending:
                            nm = pending.pop(c.get("tool_use_id"))
                            rc = c.get("content")
                            if isinstance(rc, list):
                                rc = " ".join(x.get("text", "") for x in rc if isinstance(x, dict))
                            rc = re.sub(r"\s+", " ", str(rc))
                            print(f"{ts} |   result({nm}) | {rc[:200]}")
            elif role == "assistant" and isinstance(content, list):
                for c in content:
                    if isinstance(c, dict) and c.get("type") == "tool_use" and c.get("name") in names:
                        inp = c.get("input") or {}
                        to = inp.get("to") or inp.get("subagent_type") or inp.get("description") or ""
                        body = inp.get("message") or inp.get("prompt") or ""
                        body = re.sub(r"\s+", " ", str(body))
                        pending[c.get("id")] = c.get("name")
                        print(f"{ts} | {c.get('name')} to={to} | {body[:220]}")
            elif rec.get("type") == "system" or rec.get("subtype"):
                t = re.sub(r"\s+", " ", json.dumps(rec)[:300])
                if "limit" in t.lower():
                    print(f"{ts} | SYSTEM | {t}")


if __name__ == "__main__":
    main()
