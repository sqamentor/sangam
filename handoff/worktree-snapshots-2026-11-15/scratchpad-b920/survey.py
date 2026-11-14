import re, os, sys, collections
for d in sys.argv[1:]:
    c = collections.Counter(); g = collections.Counter()
    for root, _, fs in os.walk(d):
        for f in fs:
            if f.endswith(".java"):
                s = open(os.path.join(root, f), encoding="utf-8").read()
                for m in re.finditer(r'\b([A-Z]\w*)\.(\w+)\(\s*\w+\s*,\s*"', s): c[m.group(1)+"."+m.group(2)] += 1
                for m in re.finditer(r'\b([a-z]\w*)\.(get|getOrDefault|containsKey)\(\s*"', s): g[m.group(2)] += 1
                for m in re.finditer(r'\b([a-z]\w*)\((\w+)\s*,\s*"', s): c["(local) "+m.group(1)] += 1
    print("==", d); print(c.most_common(40)); print(g)
