import os, re, sys, zipfile
root = os.path.expanduser(r"~\.m2\repository")
pat = re.compile(r"(^|/)(MIT|BSD-3-Clause|BSD-2-Clause|ISC|EPL-2\.0|CDDL-1\.1|LGPL-2\.1[^/]*|MPL-2\.0|CC0-1\.0|MIT-0|GPL-2\.0[^/]*|Classpath-exception-2\.0)(\.txt|\.html|\.json)?$|licenses?\.json$|license-list", re.I)
hits = {}
n = 0
for d, _, fs in os.walk(root):
    for f in fs:
        if f.endswith(".jar") and not f.endswith("-sources.jar"):
            n += 1
            try:
                with zipfile.ZipFile(os.path.join(d, f)) as z:
                    m = [x for x in z.namelist() if pat.search(x)]
            except Exception:
                continue
            if m:
                hits[os.path.join(d, f)] = m[:12]
print("jars", n)
for k, v in hits.items():
    print(k.replace(root, "~m2"), v)
