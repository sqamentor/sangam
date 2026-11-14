import os, re, sys
root = sys.argv[1]
skip = {"node_modules", "target", "bin", "obj", "vendor", ".git", "dist"}
rx = re.compile(r"(\.sh|\.envsh|\.Dockerfile)$|^(Dockerfile.*|mvnw|entrypoint|.*-entrypoint)$")
seen = 0; bad = []; scripts = []
for d, dirs, names in os.walk(root):
    dirs[:] = [x for x in dirs if x not in skip]
    for n in names:
        if rx.search(n):
            p = os.path.join(d, n); seen += 1
            if b"\r" in open(p, "rb").read(): bad.append(os.path.relpath(p, root))
            if not ("Dockerfile" in n):
                scripts.append(os.path.relpath(p, root).replace(os.sep, "/"))
open(sys.argv[2], "w", newline="\n").write("\n".join(scripts) + "\n")
print("scanned", seen, "with CR", len(bad), bad[:10], "scripts", len(scripts))
