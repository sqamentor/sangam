import hashlib, os, sys, zipfile, collections
root = os.path.join(os.path.expanduser("~"), ".m2", "repository", "org", "operaton")
groups = collections.defaultdict(list)
texts = {}
nonotice = []
for d, _s, fs in os.walk(root):
    for f in fs:
        if f.endswith("-2.2.0.jar") and not any(x in f for x in ("sources", "javadoc", "tests")):
            p = os.path.join(d, f)
            with zipfile.ZipFile(p) as z:
                ns = [n for n in z.namelist() if n.upper().startswith("META-INF/NOTICE")]
                if not ns:
                    nonotice.append(f)
                for n in ns:
                    b = z.read(n)
                    k = hashlib.sha256(b).hexdigest().upper()
                    groups[k].append(f + "!" + n)
                    texts[k] = b
for k, v in groups.items():
    print(k, len(v), v[:4])
print("no NOTICE:", nonotice)
if len(sys.argv) > 1:
    for k, b in texts.items():
        open(os.path.join(sys.argv[1], k[:12] + ".txt"), "wb").write(b)
