import re,os,sys
root=sys.argv[1]
P=re.compile(r"\b(?:java\.time\.)?(?:LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|YearMonth|Year|MonthDay)\s*\.\s*now\s*\("
 r"|\bZoneId\s*\.\s*systemDefault\s*\(|\bTimeZone\s*\.\s*getDefault\s*\(|\bClock\s*\.\s*systemDefaultZone\s*\(")
found={}
for d,dirs,fs in os.walk(root):
  dirs[:]=[x for x in dirs if x not in ("node_modules",".git")]
  for f in fs:
    if not f.endswith(".java"): continue
    p=os.path.join(d,f).replace("\\","/")
    if "/src/main/" not in p or "/target" in p: continue
    rel=os.path.relpath(p,root).replace("\\","/")
    for l in open(p,encoding="utf-8",errors="replace").read().split("\n"):
      s=l.strip()
      if s.startswith("*") or s.startswith("//") or s.startswith("/*"): continue
      n=len(P.findall(l))
      if n: found[rel]=found.get(rel,0)+n
for k in sorted(found): print(found[k],k)
