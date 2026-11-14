import sys, zipfile, re, os, glob
r = os.path.expanduser("~/.m2/repository")
arts = [("org/camunda/feel/feel-engine/1.22.1","feel-engine-1.22.1"),("org/scala-lang/scala-library/2.13.18","scala-library-2.13.18"),
("com/lihaoyi/fastparse_2.13/3.1.1","fastparse_2.13-3.1.1"),("com/lihaoyi/sourcecode_2.13/0.4.0","sourcecode_2.13-0.4.0"),
("com/lihaoyi/geny_2.13/1.1.0","geny_2.13-1.1.0"),("com/fasterxml/jackson/module/jackson-module-scala_2.13/2.22.3","jackson-module-scala_2.13-2.22.3"),
("org/operaton/bpm/dmn/operaton-engine-feel-scala/2.2.0","operaton-engine-feel-scala-2.2.0"),("org/operaton/bpm/dmn/operaton-engine-dmn/2.2.0","operaton-engine-dmn-2.2.0")]
for d,n in arts:
    p=os.path.join(r,d,n+".pom"); print("=====",n)
    try:
        t=open(p,encoding="utf-8").read(); m=re.search(r"<licenses>.*?</licenses>",t,re.S); print("POM", p); print(m.group(0) if m else "NO <licenses> in pom"); 
        pm=re.search(r"<parent>.*?</parent>",t,re.S); print("PARENT", re.sub(r"\s+"," ",pm.group(0)) if pm else None)
    except Exception as e: print("POM ERR",e)
    j=os.path.join(r,d,n+".jar")
    try:
        z=zipfile.ZipFile(j); 
        for e in z.namelist():
            if re.search(r"(?i)(licen|notice)",e) and not e.endswith("/"):
                b=z.read(e).decode("utf-8","replace"); print("JAR", j+"!"+e, len(b), "bytes"); print("   ", b[:600].replace("\n","\n    "))
        mf=z.read("META-INF/MANIFEST.MF").decode("utf-8","replace"); print("MANIFEST:", [l for l in mf.splitlines() if re.search(r"(?i)licen|vendor|Bundle-Version|Implementation-Version",l)])
    except Exception as e: print("JAR ERR",e)
