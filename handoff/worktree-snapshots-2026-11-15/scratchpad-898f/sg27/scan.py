import zipfile, re, os, sys, collections
r=os.path.expanduser("~/.m2/repository")
j=os.path.join(r,"org/camunda/feel/feel-engine/1.22.1/feel-engine-1.22.1.jar"); z=zipfile.ZipFile(j)
names=z.namelist(); print("entries",len(names)); print([n for n in names if n.startswith("META-INF")])
pk=collections.Counter("/".join(n.split("/")[:3]) for n in names if n.endswith(".class")); print(pk.most_common(20))
print("NON-org/camunda classes:", sorted({"/".join(n.split("/")[:2]) for n in names if n.endswith(".class") and not n.startswith("org/camunda/feel")}))
pat=re.compile(rb"(?i)(https?://[\x21-\x7e]{4,80}|telemetry|licen[cs]e.?key|enterprise|java/net/(URL|HttpURLConnection|Socket)|java/net/http|okhttp|apache/http|camunda\.(com|io|cloud)|zeebe|optimize|LicenseKey|phone.?home|usage.?metric)")
hits=collections.defaultdict(set)
for n in names:
    if n.endswith(".class"):
        for m in pat.findall(z.read(n)):
            hits[m[0] if isinstance(m,tuple) else m].add(n)
for k,v in hits.items(): print(k, len(v), sorted(v)[:4])
print("=== scala NOTICE full"); zs=zipfile.ZipFile(os.path.join(r,"org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.jar")); print(zs.read("NOTICE").decode())
