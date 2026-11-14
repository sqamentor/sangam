# Python port of MapRequestBodyContractTest's key check (scratch self-check; mirrors the Java logic)
import re, sys, yaml, importlib.util, os
ROOT = "D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam"
READERS = {"str", "required", "req", "text", "string", "integer", "intOrNull", "intOr", "intVal", "lng", "decimal", "minor",
           "num", "dbl", "bool", "flag", "optBool", "boolVal", "date", "instant", "map", "maps", "strings", "asMap", "asList"}
MEMBER = "get|getOrDefault|containsKey|remove|path|has|hasNonNull|putIfAbsent"
MAPLIKE = r"(?:java\.util\.)?(?:Map|LinkedHashMap|HashMap|TreeMap|SortedMap)\s*<[^;=(){}]*>|JsonNode|ObjectNode"
DECL_TYPE = r"(?:final\s+)?(" + MAPLIKE + r"|(?:java\.util\.)?(?:List|Collection)\s*<\s*(?:Map\s*<[^;=(){}]*>|JsonNode)\s*>|var)"
DECL = re.compile(r"(for\s*\(\s*)?" + DECL_TYPE + r"\s+(\w+)\s*([=:])")

def mask(s):
    out = list(s); i = 0; n = len(s)
    while i < n:
        c = s[i]
        if s.startswith("//", i):
            while i < n and s[i] != "\n": out[i] = " "; i += 1
        elif s.startswith("/*", i):
            j = s.index("*/", i + 2) + 2
            for k in range(i, j):
                if s[k] != "\n": out[k] = " "
            i = j
        elif s.startswith('"""', i):
            j = s.index('"""', i + 3)
            while s[j-1] == "\\": j = s.index('"""', j + 1)
            for k in range(i + 3, j):
                if s[k] != "\n": out[k] = " "
            i = j + 3
        elif c in "\"'":
            j = i + 1
            while s[j] != c: j += 2 if s[j] == "\\" else 1
            for k in range(i + 1, j): out[k] = " "
            i = j + 1
        else: i += 1
    return "".join(out)

def close(m, i, o, c):
    d = 1
    while d:
        ch = m[i]
        if ch == o: d += 1
        elif ch == c: d -= 1
        i += 1
    return i

def stmt_end(m, f, lim):
    d = 0
    for i in range(f, lim):
        ch = m[i]
        if ch in "({[": d += 1
        elif ch in ")}]":
            if d == 0: return i - f
            d -= 1
        elif ch == ";" and d == 0: return i - f
    return lim - f

def sites(m, a, b):
    out = []; cnt = {}
    for d in DECL.finditer(m, a, b):
        name = d.group(3); pos = d.start(3); cnt[name] = cnt.get(name, 0) + 1
        if d.group(1) and d.group(4) == ":":
            cl = close(m, d.start() + d.group(0).index("(") + 1, "(", ")")
            nxt = cl
            while m[nxt].isspace(): nxt += 1
            end = close(m, nxt + 1, "{", "}") if m[nxt] == "{" else nxt + stmt_end(m, nxt, b) + 1
        else:
            depth = 0; k = pos
            while k > a:
                k -= 1
                if m[k] == "}": depth += 1
                elif m[k] == "{":
                    if depth == 0: break
                    depth -= 1
            end = close(m, k + 1, "{", "}") - 1 if k > a else b
        out.append((name, cnt[name], pos, min(end, b)))
    return out

def lit(src, m, q): return src[q+1:m.index('"', q+1)]

def reads(src, m, b0, b1, var, decl, node, out):
    fr, to = b0, b1
    if decl:
        s = [x for x in sites(m, b0, b1) if x[0] == var and x[1] == decl][0]
        fr, to = s[2], s[3]
    out.setdefault(node, set())
    for f in re.compile(r"\b(\w+)\s*\(\s*" + re.escape(var) + r"\s*,\s*\"").finditer(m, fr, to):
        if f.group(1) in READERS and not m[:f.start(1)].rstrip().endswith("new"):
            out[node].add(lit(src, m, f.end() - 1))
    for g in re.compile(r"(?<![\w.])" + re.escape(var) + r"\s*\.\s*(" + MEMBER + r")\(\s*\"").finditer(m, fr, to):
        key = lit(src, m, g.end() - 1); how = g.group(1); at = node
        end = close(m, m.index("(", g.start() + len(var)) + 1, "(", ")")
        ch = re.compile(r"\s*\.\s*(path|get|has|hasNonNull)\(\s*\"")
        while how in ("path", "get"):
            c = ch.match(m, end)
            if not c: break
            out.setdefault(at, set()).add(key)
            at = key if not at else at + "." + key
            key = lit(src, m, c.end() - 1); how = c.group(1)
            end = close(m, m.index("(", c.start() + 1) + 1, "(", ")")
        out.setdefault(at, set()).add(key)

def typeblock(m, q):
    fr, to = 0, len(m)
    for simple in q.split("."):
        t = re.compile(r"\b(?:class|interface|record|enum)\s+" + re.escape(simple) + r"\b").search(m, fr, to)
        if not t: return None
        i = t.end(); d = 0
        while i < to and not (m[i] == "{" and d == 0):
            if m[i] in "(<": d += 1
            elif m[i] in ")>": d -= 1
            i += 1
        fr = i + 1; to = close(m, i + 1, "{", "}") - 1
    return fr, to

def keys_read(reader, var, decl, node, out):
    f, q = reader.split("#")
    src = open(os.path.join(ROOT, f), encoding="utf-8").read(); m = mask(src)
    owner, name = q.rsplit(".", 1)
    tb = typeblock(m, owner)
    assert tb, reader
    found = False
    for mm in re.compile(r"\b" + re.escape(name) + r"\s*\(").finditer(m, tb[0], tb[1]):
        before = m[tb[0]:mm.start()]
        if before.count("{") != before.count("}") or before.rstrip().endswith("."): continue
        cl = close(m, mm.end(), "(", ")")
        bm = re.compile(r"\s*(?:throws\s+[\w.,\s<>]+?)?\s*\{").match(m, cl)
        if not bm: continue
        b0 = bm.end(); b1 = close(m, b0, "{", "}") - 1
        params = m[mm.end():cl - 1]
        dec = (re.search(r"\b" + re.escape(var) + r"\s*$|\b" + re.escape(var) + r"\s*,", params) or
               re.compile(r"(?:" + MAPLIKE + r")\s+" + re.escape(var) + r"\s*[=;]").search(m, b0, b1)) if decl == 0 else \
            any(s[0] == var for s in sites(m, b0, b1))
        if not dec: continue
        found = True
        reads(src, m, b0, b1, var, decl, node, out)
    assert found, (reader, var)

def at(doc, s, node):
    def res(x):
        while "$ref" in x: x = doc["components"]["schemas"][x["$ref"].split("/")[-1]]
        return x
    if not node: return s
    for seg in node.split("."):
        s = res(s)
        if seg == "[]": s = s.get("items")
        else: s = (s.get("properties") or {}).get(seg)
        if s is None: return None
    return res(s)

doc = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
bad = 0; n = 0
for name, s in sorted(doc["components"]["schemas"].items()):
    if "x-sangam-body-readers" not in s: continue
    n += 1
    exp = {}
    for r in s["x-sangam-body-readers"]:
        keys_read(r["method"], r["var"], r.get("declaration", 0), r["node"], exp)
    for r in s.get("x-sangam-body-records", []):
        sch = doc["components"]["schemas"][r["schema"]]
        exp.setdefault(r["node"], set()).update(sch["properties"].keys())
    for node, ks in sorted(exp.items()):
        a = at(doc, s, node)
        props = set((a or {}).get("properties", {}).keys())
        if props != ks:
            bad += 1; print(name, repr(node), "reads-only", sorted(ks - props), "contract-only", sorted(props - ks))
print("bodies", n, "mismatches", bad)
