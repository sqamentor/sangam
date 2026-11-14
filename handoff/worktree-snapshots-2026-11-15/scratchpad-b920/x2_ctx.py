import sys, re, os
root = sys.argv[1]
for spec in sys.argv[2:]:
    f, n = spec.rsplit(":", 1); n = int(n)
    ls = re.split(r"\r\n|\r|\n", open(os.path.join(root, f), encoding="utf-8").read())
    print("===", spec)
    for i in range(max(0, n - 4), min(len(ls), n + 3)):
        print(f"  {i+1}: {ls[i]}")
