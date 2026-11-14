import os,re,sys
R=sys.argv[1]
def walk(root,skip=lambda n: n.startswith("target") or n in ("node_modules",".git")):
    for d,ds,fs in os.walk(root):
        ds[:]=[x for x in ds if not skip(x)]
        for f in fs:
            p=os.path.join(d,f); yield os.path.relpath(p,root).replace("\\","/"),p
rd=lambda p: open(p,encoding="utf-8").read()
# --- FeelBoundaryGuardTest (Files.walk: no dir skipping; filter on path)
IMPORT=re.compile(r"(?m)^\s*import\s+(?:static\s+)?((?:org\.camunda|scala)\.[\w.*]+)\s*;")
QUAL=re.compile(r"(?<![\w.\"/:])((?:org\.camunda|scala)\.[a-z]\w*\.[A-Za-z_]\w*)")
def strip(s):
    s=re.sub(r"(?s)/\*.*?\*/"," ",s); s=re.sub(r"(?m)//.*$"," ",s); return re.sub(r'"(?:\\.|[^"\\])*"','""',s)
v=[];n=0
for r,p in walk(R,skip=lambda x: x==".git"):
    s="/"+r
    if not(s.endswith(".java") and "/src/main/java/" in s and "/node_modules/" not in s and not re.fullmatch(r".*/target[^/]*/.*",s)): continue
    n+=1
    if r.startswith("sangam-engine/src/main/java/org/sangam/engine/feel/"): continue
    c=strip(rd(p))
    for rx in (IMPORT,QUAL):
        for m in rx.finditer(c): v.append(r+": "+m.group(1))
print("== FeelBoundary scanned",n,"violations",v)
# --- TestResourcesNotAutoDeployedTest (sangam-server/src/test/resources)
tr=os.path.join(R,"sangam-server/src/test/resources")
m=[r for r,p in walk(tr,skip=lambda x:False) if re.fullmatch(r"(?i).*\.(bpmn|bpmn20\.xml|dmn|dmn11\.xml|cmmn|cmmn11\.xml)$",os.path.basename(r))]
print("== TestResources models",m)
# --- ModelCodeAllowListGuardTest own class list
DEL=re.compile(r"(?:class)\s+(\w+)[^{]*\bimplements\s+[^{]*\b(?:JavaDelegate|ExecutionListener|TaskListener|ActivityBehavior|SignallableActivityBehavior|CaseExecutionListener)\b")
PKG=re.compile(r"(?m)^package\s+([\w.]+);")
found=set()
for r,p in walk(R):
    if not("/src/main/java/" in r and r.endswith(".java") and not r.startswith("examples/")): continue
    t=rd(p); pk=PKG.search(t)
    if not pk or not pk.group(1).startswith("org.sangam"): continue
    outer=os.path.basename(r)[:-5]
    for mm in DEL.finditer(t):
        sn=mm.group(1); found.add(pk.group(1)+"."+(outer if sn==outer else outer+"$"+sn))
mc=rd(os.path.join(R,"sangam-engine/src/main/java/org/sangam/engine/deploy/ModelCodeAllowList.java"))
blk=mc[mc.index("SANGAM_CLASSES = Set.of("):]; blk=blk[:blk.index(");")]
listed=set(re.findall(r'"([^"]+)"',blk))
print("== ModelCodeAllowList listed",len(listed),"found",len(found),"missing-from-list",sorted(found-listed),"stale",sorted(listed-found))
models=[r for r,p in walk(R) if "/src/main/" in r and re.search(r"\.(bpmn|bpmn20\.xml|cmmn|cmmn11\.xml|cmmn\.xml)$",r.lower())]
print("   shipped models",len(models))
e46=os.path.join(R,"sangam-server/src/test/resources/acceptance/consumer/ifms/e46")
print("   e46 models",len([r for r,p in walk(e46) if r.endswith(".bpmn.model")]) if os.path.isdir(e46) else "MISSING")
