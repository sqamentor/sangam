"""Decision 285 move preparation: files that move, test helpers they need, and every path reference elsewhere."""
import os
import re
import sys

root = sys.argv[1]          # sangam folder
repo = os.path.dirname(root)
PKGS = ["port", "adapter/jdbc", "adapter/memory", "adapter/operaton", "support", "extension"]
rest = os.path.join(root, "sangam-rest")
moving = {"main": [], "test": []}
for scope in moving:
    for p in PKGS:
        base = os.path.join(rest, "src", scope, "java", "org", "sangam", "rest", *p.split("/"))
        if not os.path.isdir(base):
            continue
        for f in sorted(os.listdir(base)):
            full = os.path.join(base, f)
            if os.path.isfile(full):
                moving[scope].append(os.path.relpath(full, root).replace(os.sep, "/"))
    # sub-packages below the six? (whole-package move must include them only if they are separate packages)
for scope in moving:
    print(f"--- {scope}: {len(moving[scope])}")
    for f in moving[scope]:
        print(f)

# sub-directories inside the six packages (they are other packages; report)
for p in PKGS:
    base = os.path.join(rest, "src", "main", "java", "org", "sangam", "rest", *p.split("/"))
    for d in os.listdir(base) if os.path.isdir(base) else []:
        if os.path.isdir(os.path.join(base, d)):
            print("SUBPACKAGE (not moved unless listed):", p + "/" + d)

# test classes in the moving test packages: references to sangam-rest test classes outside them
test_base = os.path.join(rest, "src", "test", "java")
test_classes = {}
for d, _, fs in os.walk(test_base):
    for f in fs:
        if f.endswith(".java"):
            fq = os.path.relpath(os.path.join(d, f), test_base)[:-5].replace(os.sep, ".")
            test_classes[fq] = open(os.path.join(d, f), encoding="utf-8").read()
movpk = {"org.sangam.rest." + p.replace("/", ".") for p in PKGS}
print("--- moved tests referencing other sangam-rest test classes or non-moved main classes")
main_base = os.path.join(rest, "src", "main", "java")
main_classes = set()
for d, _, fs in os.walk(main_base):
    for f in fs:
        if f.endswith(".java"):
            main_classes.add(os.path.relpath(os.path.join(d, f), main_base)[:-5].replace(os.sep, "."))
for fq, t in test_classes.items():
    if fq.rsplit(".", 1)[0] not in movpk:
        continue
    for other in list(test_classes) + list(main_classes):
        opk = other.rsplit(".", 1)[0]
        if opk in movpk:
            continue
        if other in t:
            print(fq, "->", other)
# test resources
print("--- sangam-rest test resources")
tr = os.path.join(rest, "src", "test", "resources")
for d, _, fs in os.walk(tr):
    for f in fs:
        print(os.path.relpath(os.path.join(d, f), rest))

# path references anywhere in the repo (non-java and java) to the six package folders of sangam-rest
pat = re.compile(r"sangam-rest[/\\]+src[/\\]+(main|test)[/\\]+java[/\\]+org[/\\]+sangam[/\\]+rest[/\\]+(port|adapter|support|extension)")
print("--- path references")
for d, dirs, fs in os.walk(repo):
    dirs[:] = [x for x in dirs if x not in (".git", "target", "node_modules", "target-w4f", ".claude") and not x.startswith("target")]
    for f in fs:
        if not f.endswith((".java", ".py", ".md", ".yml", ".yaml", ".json", ".xml", ".txt", ".properties", ".sh", ".ps1", ".toml", ".cfg", ".gradle", ".kts")):
            continue
        p = os.path.join(d, f)
        try:
            text = open(p, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        n = len(pat.findall(text))
        if n:
            print(n, os.path.relpath(p, repo))
