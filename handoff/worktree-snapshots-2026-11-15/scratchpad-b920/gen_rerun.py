import hashlib, os, subprocess, sys
S = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
DIRS = ["sangam-packs/src/main/resources", "sangam-compliance/src/main/resources", "sangam-persistence/src/main/resources",
        "sangam-rest/api", "sangam-rest/src/main/resources", "deploy/compose/keycloak", "sangam-server/src/main/resources",
        "sangam-ui/src/api"]
def snap():
    out = {}
    for d in DIRS:
        for root, _, files in os.walk(os.path.join(S, d)):
            if "target" in root.split(os.sep) or "node_modules" in root:
                continue
            for f in files:
                p = os.path.join(root, f)
                out[os.path.relpath(p, S)] = hashlib.sha256(open(p, "rb").read()).hexdigest()
    return out
GENS = [("sangam-persistence", ["tools/generate_changelogs.py"]), ("sangam-rest/api", ["gen_openapi.py", "sangam-api.yaml"])]
before = snap()
for g in GENS:
    r = subprocess.run([sys.executable, "-X", "utf8"] + g[1], cwd=os.path.join(S, g[0]), capture_output=True, text=True, encoding="utf-8", errors="replace")
    print("==", g[1][0], "exit", r.returncode, "|", (r.stdout.strip().splitlines() or [""])[-1][:160], "|", (r.stderr.strip().splitlines() or [""])[-1][:200])
after = snap()
diff = sorted(k for k in set(before) | set(after) if before.get(k) != after.get(k))
print("changed files:", len(diff))
for k in diff[:40]:
    print("  ", k, "new" if k not in before else ("removed" if k not in after else "modified"))
