"""Applies the unified-diff hunks of one file (from sl45.diff) to the current file, verifying every context and removed
line exactly; refuses (exit 2, file untouched) on any mismatch. Keeps the file's own line ending.

usage: apply-hunks.py <diff> <path-in-diff, e.g. sangam/sangam-server/...> <target file>
"""
import re
import sys

diff_path, name, target = sys.argv[1], sys.argv[2], sys.argv[3]
lines = open(diff_path, encoding="utf-8").read().split("\n")
# collect the hunks of this file
hunks, i, cur = [], 0, None
while i < len(lines):
    l = lines[i]
    if l.startswith("--- a/"):
        inside = l[6:] == name and i + 1 < len(lines) and lines[i + 1] == "+++ b/" + name
        i += 2
        while i < len(lines) and not lines[i].startswith("--- a/"):
            if inside:
                m = re.match(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", lines[i])
                if m:
                    cur = {"old_start": int(m.group(1)), "body": []}
                    hunks.append(cur)
                elif cur is not None and lines[i][:1] in (" ", "+", "-"):
                    cur["body"].append(lines[i])
                elif cur is not None and lines[i] == "":
                    pass  # trailing split artefact
            i += 1
        continue
    i += 1
if not hunks:
    sys.exit(f"no hunks for {name}")

raw = open(target, "rb").read().decode("utf-8")
eol = "\r\n" if "\r\n" in raw else "\n"
text = raw.replace("\r\n", "\n")
trailing = text.endswith("\n")
old = text.split("\n")
if trailing:
    old = old[:-1]

out, pos = [], 0  # pos: 0-based index into old
for h in hunks:
    start = h["old_start"] - 1
    if start < pos:
        sys.exit(f"overlapping hunk at {h['old_start']}")
    out.extend(old[pos:start])
    pos = start
    for b in h["body"]:
        tag, content = b[0], b[1:]
        if tag in (" ", "-"):
            if pos >= len(old) or old[pos] != content:
                print(f"MISMATCH at line {pos + 1}: expected {content!r}, found {old[pos] if pos < len(old) else None!r}")
                sys.exit(2)
            if tag == " ":
                out.append(content)
            pos += 1
        else:
            out.append(content)
out.extend(old[pos:])
result = eol.join(out) + (eol if trailing else "")
open(target, "wb").write(result.encode("utf-8"))
print(f"applied {len(hunks)} hunk(s) to {target} (eol {'CRLF' if eol == chr(13) + chr(10) else 'LF'})")
