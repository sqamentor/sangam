import json, collections, os
for f in ["r_git.json","r_dir.json"]:
    d=json.load(open(f))
    for k,v in d.items():
        name=os.path.basename(k.rstrip("/\\"))
        diffs=[x for x in v if x[2].startswith("DIFF")]
        miss=[x for x in v if x[2].startswith("MISSING") and not x[2].startswith("MISSING_OLD")]
        risky=[]
        for x in diffs:
            p=x[2].split(" ")[0].split(":"); c=float(p[1]); nm=int(p[3])
            if c<0.97 and nm>=2: risky.append((c,nm,x[1],x[2]))
        print(f"## {name}: diff={len(diffs)} risky={len(risky)} missing={len(miss)}")
        for r in sorted(risky)[:40]: print("   R", f"{r[0]:.2f}", r[1], r[2], "snap" if "snap" in r[3] or "inSnap" in r[3] else "")
        if len(miss)>40:
            print("   M-groups", collections.Counter("/".join(x[1].replace("\\","/").split("/")[:3]) for x in miss).most_common(12))
        else:
            for m in miss: print("   M", m[1], m[2])
