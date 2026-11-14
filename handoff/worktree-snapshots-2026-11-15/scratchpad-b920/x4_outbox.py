import os, re, sys
# Port of OutboxDestinationPublishersTest (sangam-server, SL-146).
R = sys.argv[1]
CONFIGURED = {
    'IntegrationWiring.java|spec.substring(0, spec.length() - ":ordered".length())',
    'IntegrationWiring.java|spec',
    'PortsHistoryEventHandler.java|x',
    'CaseModuleNotifier.java|destination()',
    'CaseWiring.java|n.destination()',
}
DEST = re.compile(r'Outbox\.Destination\.(?:un)?ordered\(')
REG = re.compile(r'\b(pubs|publishers|registry|p)\.register\(')
CONST = re.compile(r'static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"\s*;')

def sources():
    out = []
    for m in sorted(os.listdir(R)):
        main = os.path.join(R, m, "src", "main", "java")
        if not os.path.isdir(os.path.join(R, m)) or not os.path.isdir(main):
            continue
        for d, ds, fs in os.walk(main):
            for f in fs:
                if f.endswith(".java"):
                    p = os.path.join(d, f)
                    out.append((p, f, open(p, encoding="utf-8").read(), m))
    return out

def code(t):
    out = []; i = 0; n = len(t)
    while i < n:
        ch = t[i]
        if t.startswith('"""', i):
            e = t.find('"""', i + 3); e = n if e < 0 else e + 3
            out.append(t[i:e]); i = e
        elif ch == '"' or ch == "'":
            j = i + 1
            while j < n and t[j] != ch:
                j += 2 if t[j] == '\\' else 1
            j = min(n, j + 1)
            out.append(t[i:j]); i = j
        elif t.startswith("//", i):
            e = t.find("\n", i); i = n if e < 0 else e
        elif t.startswith("/*", i):
            e = t.find("*/", i + 2); i = n if e < 0 else e + 2
            out.append(' ')
        else:
            out.append(ch); i += 1
    return "".join(out)

def first_arg(s, open_):
    depth = 0; q = False
    for i in range(open_, len(s)):
        ch = s[i]
        if ch == '"' and s[i - 1] != '\\':
            q = not q
        elif not q:
            if ch == '(':
                depth += 1
            elif ch == ')':
                if depth == 0:
                    return s[open_:i].strip(), "1"
                depth -= 1
            elif ch == ',' and depth == 0:
                return s[open_:i].strip(), "2"
    raise AssertionError("unbalanced at %d" % open_)

def operands(e):
    out = []; depth = 0; q = False; st = 0
    for i, ch in enumerate(e):
        if ch == '"' and (i == 0 or e[i - 1] != '\\'):
            q = not q
        elif not q:
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
            elif ch == '+' and depth == 0:
                out.append(e[st:i].strip()); st = i + 1
    out.append(e[st:].strip())
    return out

def value(op, name, consts):
    if re.fullmatch(r'"[^"]*"', op):
        return op[1:-1]
    if re.fullmatch(r'(?:[a-z]\w*\.)*[A-Z]\w*\.[A-Z][A-Z0-9_]*', op):
        parts = op.split(".")
        return consts.get(parts[-2] + "." + parts[-1])
    if re.fullmatch(r'[A-Z][A-Z0-9_]*', op):
        return consts.get(name.replace(".java", "") + "." + op)
    return None

def resolve(expr, name, consts):
    ops = operands(expr)
    head = value(ops[0], name, consts)
    if head is None:
        return None
    v = head; prefix = False
    for op in ops[1:]:
        part = value(op, name, consts)
        if part is None:
            prefix = True; break
        v += part
    if v.endswith("*"):
        return (v[:-1], True, name)
    return (v, prefix, name)

allsrc = sources()
consts = {}
for p, name, t, m in allsrc:
    for mm in CONST.finditer(t):
        consts[name.replace(".java", "") + "." + mm.group(1)] = mm.group(2)
dests = []; pubs = []; conf = set(); unres = []
for p, name, t, m in allsrc:
    c = code(t)
    for d in DEST.finditer(c):
        expr = first_arg(c, d.end())[0]
        n = resolve(expr, name, consts)
        if n is not None:
            dests.append(n + (os.path.relpath(p, R) + ":" + str(c[:d.start()].count("\n") + 1),))
        elif name + "|" + expr in CONFIGURED:
            conf.add(name + "|" + expr)
        else:
            unres.append("destination %s: %s" % (p, expr))
    if m not in ("sangam-server", "sangam-integration") or "PublisherRegistry" not in t:
        continue
    for r in REG.finditer(c):
        a = first_arg(c, r.end())
        if a[1] != "2":
            continue
        n = resolve(a[0], name, consts)
        if n is not None:
            pubs.append(n)
        elif name + "|" + a[0] in CONFIGURED:
            conf.add(name + "|" + a[0])
        else:
            unres.append("publisher %s: %s" % (p, a[0]))

def covered(d, ps):
    for pv, pp, _ in ps:
        if (d[0].startswith(pv) if pp else (not d[1] and d[0] == pv)):
            return True
    return False

print("unresolved", unres)
print("configured stale", sorted(CONFIGURED - conf), "extra", sorted(conf - CONFIGURED))
missing = [d for d in dests if not covered(d, pubs)]
print("missing", [(d[0] + ("*" if d[1] else ""), d[3]) for d in missing])
D = sorted({d[0] + ("*" if d[1] else "") for d in dests})
P = sorted({p[0] + ("*" if p[1] else "") for p in pubs})
print("destinations", D)
print("publishers", P)
for k in ["case-events", "history-projector", "channel:*", "ledger:gl", "pack-dispatch", "programme-events",
          "programme-dashboard", "sangam.registry.publish", "sangam.resources.notification"]:
    if k not in D: print("KNOWN DEST MISSING", k)
for k in ["channel:*", "xcell:*", "webhook:*", "rollup:national", "case-events", "history-projector"]:
    if k not in P: print("KNOWN PUB MISSING", k)
print("forbidden present", [k for k in ("notifications", "sangam.registry.events") if k in D])
