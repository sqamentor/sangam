import os,re,sys
R=sys.argv[1]
c={}
n=0;sdk=0;alljava=0
for d,ds,fs in os.walk(R):
    ds[:]=[x for x in ds if not(x.startswith("target") or x in("node_modules",".git"))]
    for f in fs:
        if not f.endswith(".java"): continue
        p=os.path.join(d,f); r=os.path.relpath(p,R).replace("\\","/"); alljava+=1
        if "/src/main/" not in r: continue
        t=open(p,"rb").read().decode("utf-8")
        ls=re.split(r"\r\n|\r|\n",t)
        if ls and ls[-1]=="": ls=ls[:-1]
        c[r]=len(ls); n+=1
        if r.startswith("sdks/"): sdk+=1
print("java",alljava,"main",n,"sdk main",sdk)
for r,v in sorted(c.items(),key=lambda x:-x[1])[:22]: print(v,r)
