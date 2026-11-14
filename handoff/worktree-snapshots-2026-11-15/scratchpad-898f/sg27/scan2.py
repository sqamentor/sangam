import zipfile, re, os, collections
r=os.path.expanduser("~/.m2/repository")
for rel in ["org/operaton/bpm/dmn/operaton-engine-feel-scala/2.2.0/operaton-engine-feel-scala-2.2.0.jar","org/operaton/bpm/dmn/operaton-engine-dmn/2.2.0/operaton-engine-dmn-2.2.0.jar","org/operaton/bpm/operaton-engine/2.2.0/operaton-engine-2.2.0.jar"]:
    j=os.path.join(r,rel); z=zipfile.ZipFile(j); n=z.namelist()
    tel=[x for x in n if re.search(r"(?i)telemetry|licen[cs]ekey|LicenseKey",x)]
    print("==",rel.split("/")[-1],"classes",len(n),"telemetry/licence-key entries:",tel[:10])
    print("  META-INF licence files:",[x for x in n if re.search(r"(?i)META-INF/.*(licen|notice)",x)])
    pat=re.compile(rb"(?i)(https?://[\x21-\x7e]{4,60}|java/net/HttpURLConnection|java/net/http/HttpClient|camunda\.(com|io|cloud))")
    hits=collections.Counter()
    for x in n:
        if x.endswith(".class"):
            for m in pat.findall(z.read(x)): hits[(m[0] if isinstance(m,tuple) else m)]+=1
    print("  url/http hits:",hits.most_common(15))
