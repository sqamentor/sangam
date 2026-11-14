import zipfile, sys, difflib, hashlib
lic = zipfile.ZipFile(sys.argv[1]).read("META-INF/LICENSE")
print("jar LICENSE bytes", len(lic), "sha256", hashlib.sha256(lic).hexdigest(), "CR" if b"\r" in lic else "LF", "trailing NL", lic.endswith(b"\n"))
cur = open(sys.argv[2], "rb").read()
a = cur.decode().splitlines(); b = lic.decode().splitlines()
for l in difflib.unified_diff(a, b, "committed", "mxparser", lineterm="", n=0): print(l)
