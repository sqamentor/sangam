import re, os, sys
root = sys.argv[1]
MONEY = re.compile(r"(?i:amount|amt\b|balance|limit|price|tariff|fee\b|fees\b|penalt|interest|total|budget|cost|charge|payable|receivable|paise|money|salary|grant|fund|outstanding|arrear|refund|collected|expended|allocated|released|committed)")
FLOAT = re.compile(r"\b(?:double|float|Double|Float)\b|\.doubleValue\s*\(|\.floatValue\s*\(|parseDouble|parseFloat")
STR = re.compile(r'"(?:[^"\\]|\\.)*"')
for d, dirs, fs in os.walk(root):
    for f in fs:
        if not f.endswith(".java"):
            continue
        p = os.path.join(d, f).replace("\\", "/")
        if "/src/main/" not in p or "/target/" in p or "/node_modules/" in p:
            continue
        rel = os.path.relpath(p, root).replace("\\", "/")
        n = 0
        ls = []
        for i, l in enumerate(open(p, encoding="utf-8").read().splitlines()):
            c = l.strip()
            if c.startswith("*") or c.startswith("//") or c.startswith("/*"):
                continue
            ns = STR.sub('""', c)
            if FLOAT.search(ns) and MONEY.search(ns):
                n += 1
                ls.append(f"{i+1}: {c}")
        if n:
            print(n, rel)
            for x in ls:
                print("   ", x)
