import json, os, re, hashlib, datetime, collections
I=r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
S=r"C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad"
SNAP=os.path.join(I,"handoff","worktree-snapshots")
BUILD=re.compile(r'(^|[\/])(obj|bin|\.pytest_cache|reference|tscale-[^\/]*)([\/]|$)|\.(dll|pdb|cache|exe)$', re.I)
def h(p):
    try: return hashlib.sha1(open(p,'rb').read().replace(b'\r\n',b'\n')).hexdigest()
    except Exception: return None
out=[]
for f in ["r_git.json","r_dir.json"]:
    d=json.load(open(f))
    for k,v in d.items():
        base=k if os.path.isabs(k) else os.path.join(S,k)
        name=os.path.basename(base.rstrip("/\\"))
        for x in v:
            st,rel,c=x[0],x[1],x[2]
            if not (c.startswith("DIFF") or c=="MISSING" or c.startswith("MISSING ")): continue
            if "snap" in c or "inSnapshot" in c: continue
            if BUILD.search(rel): continue
            p=os.path.join(base,rel); hv=h(p)
            r2=rel.replace("\\","/"); alt=r2[len("sangam/"):] if r2.startswith("sangam/") else r2
            if hv and (h(os.path.join(SNAP,name,r2))==hv or h(os.path.join(SNAP,name,alt))==hv): continue
            mt=datetime.datetime.fromtimestamp(os.path.getmtime(p)).strftime("%m-%d %H:%M")
            out.append((name,st,r2,c,mt))
json.dump(out,open("unpreserved.json","w"),indent=0)
cnt=collections.Counter(o[0] for o in out)
print(cnt)
for o in out: print(*o)
