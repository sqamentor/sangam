import zipfile, sys
z = zipfile.ZipFile(sys.argv[1])
names = z.namelist()
print([n for n in names if n.startswith("META-INF") and not n.endswith("/")])
print("xmlpull classes inside:", any(n.startswith("org/xmlpull/v1/") for n in names))
for n in names:
    if "LICENSE" in n.upper() or "NOTICE" in n.upper():
        print("=====", n); print(z.read(n).decode("utf-8", "replace"))
