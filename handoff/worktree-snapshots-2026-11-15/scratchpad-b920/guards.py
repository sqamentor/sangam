import os, re, subprocess
W = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
S = os.path.join(W, "sangam")
st = subprocess.run(["git", "status", "--porcelain", "-uall"], cwd=W, capture_output=True, text=True, encoding="utf-8").stdout.splitlines()
changed = [l[3:].strip().strip('"') for l in st]
new = [l[3:].strip().strip('"') for l in st if l.startswith("??") or l.startswith("A ")]
print("changed paths:", len(changed), "new:", len(new))
big, nospdx, disabled, secrets = [], [], [], []
SECRET = re.compile(r"(AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|ghp_[A-Za-z0-9]{36}|xox[baprs]-[A-Za-z0-9-]{10,}|(?i:password|secret|api[_-]?key)\s*[:=]\s*['\"][^'\"\s${}]{12,}['\"])")
for rel in changed:
    p = os.path.join(W, rel)
    if not os.path.isfile(p) or "/target" in rel.replace("\\", "/") or "node_modules" in rel:
        continue
    try:
        txt = open(p, encoding="utf-8").read()
    except Exception:
        continue
    r = rel.replace("\\", "/")
    if r.endswith(".java") and "/src/main/" in r and txt.count("\n") > 800:
        big.append((r, txt.count("\n")))
    if r.endswith((".java", ".py", ".ts", ".tsx", ".cs", ".php", ".sh")) and rel in new and "SPDX-License-Identifier" not in txt[:600]:
        nospdx.append(r)
    if r.endswith(".java") and re.search(r"^\s*@Disabled", txt, re.M):
        disabled.append(r)
    for m in SECRET.finditer(txt):
        line = txt[:m.start()].count("\n") + 1
        secrets.append((r, line, m.group(0)[:12] + "..."))
print("main java > 800 lines:", big)
print("new files without SPDX:", nospdx)
print("@Disabled:", disabled)
print("secret-like hits:", len(secrets))
for s in secrets[:30]:
    print("  ", s)
