import json, collections, os, re
BUILD=re.compile(r'(^|[\/])(obj|bin|\.pytest_cache|reference|\.mvn|tscale-[^\/]*|ev)([\/]|$)|\.(dll|pdb|cache|exe)$', re.I)
for f in ["r_git.json","r_dir.json"]:
    d=json.load(open(f))
    for k,v in d.items():
        name=os.path.basename(k.rstrip("/\\"))
        un=[x for x in v if (x[2].startswith("DIFF") or x[2]=="MISSING" or x[2].startswith("MISSING ")) and "snap" not in x[2] and "inSnapshot" not in x[2]]
        nb=[x for x in un if not BUILD.search(x[1])]
        b=len(un)-len(nb)
        print(f"## {name}: unpreserved={len(nb)} (+{b} build/reference artefacts)")
        for x in nb[:60]: print("   ", x[0], x[1], x[2])
