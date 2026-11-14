"""Read-only survey of org.sangam Maven groupId uses (decision 210 sizing). Prints counts per category."""
import collections, os, re, subprocess, sys
root = sys.argv[1]
files = subprocess.run(["git", "ls-files", "-co", "--exclude-standard"], cwd=root, capture_output=True, text=True,
                       encoding="utf-8").stdout.splitlines()
pats = {
    "pom <groupId>": re.compile(r"<groupId>\s*org\.sangam\s*</groupId>"),
    "coordinate org.sangam:artifact": re.compile(r"\borg\.sangam:[a-z][a-z0-9-]*"),
    "purl pkg:maven/org.sangam": re.compile(r"pkg:maven/org\.sangam/"),
    "gradle/kts group": re.compile(r"group(Id)?\s*[=:(]\s*[\"']org\.sangam[\"']"),
}
by = collections.defaultdict(list)
for f in files:
    if f.startswith("handoff/") or "/node_modules/" in f or f.endswith((".java", ".class", ".jar", ".png")):
        continue
    p = os.path.join(root, f)
    try:
        t = open(p, encoding="utf-8").read()
    except (OSError, UnicodeDecodeError):
        continue
    for k, r in pats.items():
        n = len(r.findall(t))
        if n:
            by[k].append((f, n))
for k, v in by.items():
    print(f"{k}: {sum(n for _, n in v)} hits in {len(v)} files")
    for f, n in sorted(v)[:200]:
        print(f"   {n:4d} {f}")
