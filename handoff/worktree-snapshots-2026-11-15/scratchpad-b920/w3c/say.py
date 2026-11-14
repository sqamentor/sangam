"""Append one line to the board or the claims file. Usage: say.py board|claims TYPE TEXT [TO]. TEXT may contain {sha:path}."""
import sys, hashlib, datetime, re, os
ROOT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
H = r"D:\GITHUB  Project\sangam-handoff"
which, typ, text = sys.argv[1], sys.argv[2], sys.argv[3]
to = sys.argv[4] if len(sys.argv) > 4 else ""


def sha(m):
    p = os.path.join(ROOT, m.group(1))
    return m.group(1) + " " + hashlib.sha256(open(p, 'rb').read()).hexdigest().upper()


text = re.sub(r"\{sha:([^}]+)\}", sha, text)
ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
assert '|' not in text and '\n' not in text
if which == "board":
    line = "%s | W3-C/B44 | %s | %s | %s" % (ts, typ, text, to)
    path = os.path.join(H, "WAVE3-SYNC-BOARD.md")
else:
    line = "%s B44 %s %s" % (ts, typ, text)
    path = os.path.join(H, "FILE-CLAIMS.md")
with open(path, 'ab') as f:
    f.write((line + "\r\n").encode('utf-8'))
print(line[:300])
