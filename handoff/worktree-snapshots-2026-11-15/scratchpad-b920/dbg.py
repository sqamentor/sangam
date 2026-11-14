import sys, os, re
ROUTES = "D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-rest/api/routes"
G = {"__file__": ROUTES + "/../gen_openapi.py", "__name__": "dbg"}
G["typ"] = lambda t: {}
G["R"] = lambda n: {"$ref": n}
G["SCHEMAS"] = {}
for f in ("_v2_bodykeys.py", "_v2_spring.py"):
    exec(compile(open(ROUTES + "/" + f, encoding="utf-8").read(), f, "exec"), G)
exec(open(sys.argv[1], encoding="utf-8").read(), G)
