import json, re, sys, os, glob
root, dj = sys.argv[1], sys.argv[2]
D = {r["ref"]: r["digest"] for r in json.load(open(dj, encoding="utf-8-sig"))}
files = sorted(glob.glob(os.path.join(root, "sangam/deploy/docker/*.Dockerfile"))) + [os.path.join(root, "sangam/deploy/operator/Dockerfile"),
         os.path.join(root, "sangam/deploy/airgap/images.txt"), os.path.join(root, "sangam/deploy/README.md")]
PIN = re.compile(r"([a-z0-9][\w./-]*:[\w.-]+)@(sha256:[0-9a-f]{64})")
seen, bad = set(), []
for f in files:
    txt = open(f, encoding="utf-8").read()
    for m in PIN.finditer(txt):
        tag, dg = m.group(1), m.group(2)
        if tag not in D: bad.append((f, tag, "not looked up")); continue
        if D[tag] != dg: bad.append((f, tag, "digest differs from the lookup"))
        seen.add(tag)
    if f.endswith("Dockerfile"):
        args = set(re.findall(r"^ARG (\w+)", txt, re.M))
        stages = set()
        for ln in txt.splitlines():
            m = re.match(r"^FROM\s+(?:--\S+\s+)*(\S+)(?:\s+AS\s+(\S+))?", ln, re.I)
            if not m: continue
            base = m.group(1)
            v = re.fullmatch(r"\$\{(\w+)\}", base)
            if v and v.group(1) not in args: bad.append((f, base, "build arg not declared"))
            elif not v and base.lower() not in stages and base != "scratch" and not PIN.fullmatch(base): bad.append((f, base, "FROM not pinned"))
            if m.group(2): stages.add(m.group(2).lower())
        for left in re.findall(r"^ARG (\w*IMAGE\w*)=(\S+)", txt, re.M):
            print("kept ARG", os.path.relpath(f, root), left[0])
print("pinned tags found:", len(seen), "of", len(D), "looked up; not used:", sorted(set(D) - seen))
print("problems:", bad)
sys.exit(1 if bad or set(D) - seen else 0)
