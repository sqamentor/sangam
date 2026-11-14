import os, zipfile
root = os.path.expanduser(r"~\.m2\repository")
for d, _, fs in os.walk(root):
    for f in fs:
        if f.endswith(".jar") and not f.endswith("-sources.jar"):
            p = os.path.join(d, f)
            try:
                with zipfile.ZipFile(p) as z:
                    for n in z.namelist():
                        u = n.upper()
                        if ("LICEN" in u or "NOTICE" in u) and not n.endswith(".class") and z.getinfo(n).file_size < 200000:
                            if b"Legion of the Bouncy Castle" in z.read(n):
                                print(p, n)
            except Exception:
                pass
