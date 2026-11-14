# exploration: reads of a Map variable inside a Java method
import re, sys, os
ROOT = "D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam"
def strip(s):  # keep line structure
    return re.sub(r"/\*.*?\*/|//[^\n]*", lambda m: re.sub(r"[^\n]", " ", m.group(0)), s, flags=re.S)
def find_methods(src, name):
    out = []
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", src):
        # declaration: preceded by a type token and followed by ) { or ) throws
        pre = src[max(0, m.start()-200):m.start()]
        if re.search(r"(?:\.|new\s+|return\s+|=\s*|\(\s*|,\s*|->\s*)$", pre): continue
        if not re.search(r"[\w>\]]\s+$", pre): continue
        i, d = m.end(), 1
        while d and i < len(src):
            d += {"(": 1, ")": -1}.get(src[i], 0); i += 1
        params = src[m.end():i-1]
        rest = src[i:i+200]
        bm = re.match(r"\s*(?:throws[^{;]*)?\{", rest)
        if not bm: continue
        j = i + bm.end(); d = 1; start = j
        while d and j < len(src):
            d += {"{": 1, "}": -1}.get(src[j], 0); j += 1
        out.append((params, start, j - 1))
    return out
def main(path, name, var):
    src = strip(open(os.path.join(ROOT, path), encoding="utf-8").read())
    allm = find_methods(src, name)
    if var == "-":
        ms = []
        for x in allm:
            for v in re.findall(r"Map<String,\s*\??\s*\w*>>?\s+(\w+)", x[0]):
                ms.append((x, v))
    else:
        ms = [(x, var) for x in allm if re.search(r"\b" + var + r"\b", x[0])]
    if not ms:
        print("NO METHOD", path, name, var); return
    for (params, a, b), var in ms:
        body = src[a:b]; line0 = src[:a].count("\n") + 1
        print("== %s#%s(%s) line %d" % (os.path.basename(path), name, var, line0))
        vars_ = {var}
        for am in re.finditer(r"Map<String,\s*Object>\s+(\w+)\s*=\s*([^;]*);", body):
            if re.search(r"\b(" + "|".join(vars_) + r")\b", am.group(2)): vars_.add(am.group(1)); print("   alias", am.group(1), "=", am.group(2).strip()[:80])
        V = "|".join(sorted(vars_))
        for rm in re.finditer(r"\b(?:(\w+)\.)?(\w+)\(\s*(" + V + r")\s*,\s*\"([^\"]+)\"", body):
            print("   %5d %-10s %s" % (line0 + body[:rm.start()].count("\n"), rm.group(2), rm.group(4)))
        for rm in re.finditer(r"\b(" + V + r")\.(get|getOrDefault|containsKey|remove)\(\s*\"([^\"]+)\"", body):
            pre = body[max(0, rm.start()-40):rm.start()]; post = body[rm.end():rm.end()+40]
            w = re.search(r"([\w.]+)\(\s*$", pre)
            print("   %5d .%-9s %s   <<%s>> %s" % (line0 + body[:rm.start()].count("\n"), rm.group(2), rm.group(3), w.group(1) if w else "", re.sub(r"\s+", " ", post)[:40]))
        for om in re.finditer(r"\b(" + V + r")\b", body):
            s, e = om.start(), om.end()
            after = body[e:e+30]; before = body[max(0, s-60):s]
            if re.match(r"\s*\.\s*(get|getOrDefault|containsKey|remove)\(\s*\"", after): continue
            if re.match(r"\s*,\s*\"", after): continue
            if re.match(r"\s*[=!]=\s*null", after) or re.search(r"null\s*[=!]=\s*$", before): continue
            if re.search(r"Map<String,\s*Object>\s+$", before): continue
            # enclosing call
            d, k = 0, s - 1
            while k >= 0:
                ch = body[k]
                if ch == ")": d += 1
                elif ch == "(":
                    if d == 0: break
                    d -= 1
                elif ch in ";{}" and d == 0: k = -1; break
                k -= 1
            callee = re.search(r"([\w.]+)\s*$", body[:k]).group(1) if k > 0 and re.search(r"([\w.]+)\s*$", body[:k]) else "?"
            print("   %5d PASS  %s  ...%s" % (line0 + body[:s].count("\n"), callee, re.sub(r"\s+", " ", body[max(0,s-50):e+30])))
if __name__ == "__main__":
    main(*sys.argv[1:4])
