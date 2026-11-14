# SPDX-License-Identifier: Apache-2.0
# M-051 (SL-145): contract types for request bodies that bind to Map<String, Object> (or to a JSON string the service
# parses into a map). Runs inside gen_openapi.py before _v2_spring.py; the functions run later, from map_body() calls in
# the route files and from the final _bk_apply() pass in gen_openapi.py.
#
# The schema of such a body is derived from the Java source, so it cannot drift from what the service reads:
#   - start at the controller method and its body parameter (the "anchor");
#   - every read of the map in that method is a property: Maps.str(body, "k"), str(b, "k"), b.get("k"), ...; the reader
#     function gives the JSON type (_BK_KINDS);
#   - a local copy or alias of the map (Map<String, Object> b = body == null ? Map.of() : body) is read the same way;
#     a nested map (Map<String, Object> d = Maps.map(body, "definition")) or list element (for (Map<String, Object> l :
#     Maps.maps(body, "lines"))) types the nested object;
#   - when the whole map is passed to another method, that method is analysed too: the receiver's declared type gives
#     the class, an interface method is followed to its implementations, and the parameter at the same position is the
#     new variable;
#   - a map converted to a record (FieldJson.convert(body, X.class), MAPPER.convertValue(...)) is typed as the record;
#   - any other use of the whole map (stored, hashed, iterated) must be declared with map_body(opaque=...) and a reason.
# Typing is additive (0.1.0, ADR-D-5): no `required`, and additionalProperties stays true, because the runtime still
# binds a map and accepts unknown members (SL-40 custom fields). Each body schema lists the methods that read it
# (x-sangam-body-readers) and the records it is converted to (x-sangam-body-records); MapRequestBodyContractTest
# re-reads those methods and fails when the keys a service reads and the schema's properties differ. Set the environment
# variable SANGAM_BODYKEYS_EVIDENCE to a file path to get every key with the file:line that reads it.
import os as _bk_os
import re as _bk_re

_BK_SANGAM = _bk_os.path.normpath(_bk_os.path.join(_bk_os.path.dirname(_bk_os.path.abspath(__file__)), "..", ".."))
_BK_FILES = {}
_BK_DECLS = {}
_BK_IMPLS = None
_BK_BODIES = {}       # operationId -> spec (filled by map_body and by _v2_spring for Map bodies)
_BK_EVIDENCE = []     # (operationId, node, key, kind, file:line)

# Reader function (simple name) -> JSON kind of the key it reads. Every function a body is read with must be listed:
# an unknown reader stops the generator so the new reader is looked at (and added here) before the contract changes.
_BK_KINDS = {
    "str": "string", "required": "string", "req": "string", "text": "string", "string": "string",
    "integer": "integer", "intOrNull": "integer", "intOr": "integer", "intVal": "integer",
    "lng": "int64",
    "decimal": "decimal", "minor": "decimal",
    "num": "number", "dbl": "number",
    "bool": "boolean", "flag": "boolean", "optBool": "boolean", "boolVal": "boolean",
    "date": "date", "instant": "date-time",
    "map": "object", "maps": "objects", "strings": "strings", "asMap": "object", "asList": "array",
}
# Wrappers around map.get("k") that fix its kind (the reader functions above also apply as wrappers)
_BK_WRAPPERS = {"String.valueOf": "string", "Boolean.TRUE.equals": "boolean", "Long.parseLong": "string"}
_BK_KEYWORDS = {"if", "for", "while", "switch", "catch", "synchronized", "return", "new", "super", "this", "throw",
                "else", "case", "yield", "assert", "try", "do", "record", "class", "interface", "enum"}
_BK_CONTROL = {"if", "for", "while", "switch", "catch", "synchronized", "return", "throw", "else", "case", "yield",
               "assert", "try", "do"}


# ---------------------------------------------------------------- Java source model
def _bk_rel(path):
    return _bk_os.path.relpath(path, _BK_SANGAM).replace(_bk_os.sep, "/")


def _bk_load(path):
    """(original, masked, line starts): masked has comments and string/char contents blanked, same length."""
    if path not in _BK_FILES:
        with open(path, encoding="utf-8") as f:
            s = f.read()
        out, i, n = list(s), 0, len(s)
        while i < n:
            c = s[i]
            if s.startswith("//", i):
                while i < n and s[i] != "\n":
                    out[i] = " "
                    i += 1
            elif s.startswith("/*", i):
                j = s.index("*/", i + 2) + 2
                for k in range(i, j):
                    if s[k] != "\n":
                        out[k] = " "
                i = j
            elif s.startswith('"""', i):
                j = s.index('"""', i + 3)
                while s[j - 1] == "\\":
                    j = s.index('"""', j + 1)
                for k in range(i + 3, j):
                    if s[k] != "\n":
                        out[k] = " "
                i = j + 3
            elif c in "\"'":
                j = i + 1
                while s[j] != c:
                    j += 2 if s[j] == "\\" else 1
                for k in range(i + 1, j):
                    out[k] = " "
                i = j + 1
            else:
                i += 1
        masked = "".join(out)
        starts = [0] + [m.end() for m in _bk_re.finditer("\n", s)]
        _BK_FILES[path] = (s, masked, starts)
    return _BK_FILES[path]


def _bk_line(path, pos):
    starts = _bk_load(path)[2]
    lo, hi = 0, len(starts)
    while lo + 1 < hi:
        mid = (lo + hi) // 2
        if starts[mid] <= pos:
            lo = mid
        else:
            hi = mid
    return lo + 1


def _bk_close(masked, i, open_, close):
    """Index just past the bracket that closes the one opened just before i."""
    depth = 1
    while depth:
        ch = masked[i]
        if ch == open_:
            depth += 1
        elif ch == close:
            depth -= 1
        i += 1
    return i


def _bk_decls(path):
    """Types and methods declared in a file: {"types": [(qualified name, kind, body start, body end)], "methods": [...]}"""
    if path in _BK_DECLS:
        return _BK_DECLS[path]
    _, m, _ = _bk_load(path)
    types = []
    for t in _bk_re.finditer(r"\b(class|interface|record|enum)\s+(\w+)", m):
        if m[:t.start()].rstrip().endswith("."):
            continue
        i = t.end()
        depth = 0
        while i < len(m) and not (m[i] == "{" and depth == 0):
            if m[i] == ";" and depth == 0:
                break
            depth += {"(": 1, ")": -1, "<": 1, ">": -1}.get(m[i], 0)
            i += 1
        if i >= len(m) or m[i] != "{":
            continue
        types.append([t.group(2), t.group(1), i + 1, _bk_close(m, i + 1, "{", "}") - 1])
    for t in types:   # qualify nested names: Outer.Inner
        outers = [o for o in types if o is not t and o[2] <= t[2] and t[3] <= o[3]]
        outers.sort(key=lambda o: o[2])
        t.append(".".join([o[0] for o in outers] + [t[0]]))
    methods = []
    for mm in _bk_re.finditer(r"\b(\w+)\s*\(", m):
        name = mm.group(1)
        if name in _BK_KEYWORDS:
            continue
        before = m[:mm.start()].rstrip()
        if not before or before[-1] in ".@" or not (before[-1].isalnum() or before[-1] in "_>]"):
            continue
        prev = _bk_re.search(r"(\w+)$", before)
        if prev and prev.group(1) in _BK_KEYWORDS:
            continue
        owner = [t for t in types if t[2] <= mm.start() < t[3]]
        if not owner:
            continue
        owner = max(owner, key=lambda t: t[2])
        # declared directly in the type body: no unclosed brace between the type's "{" and the name
        seg = m[owner[2]:mm.start()]
        if seg.count("{") != seg.count("}"):
            continue
        pend = _bk_close(m, mm.end(), "(", ")")
        rest = _bk_re.match(r"\s*(?:throws\s+[\w.,\s<>]+?)?\s*([{;])", m[pend:])
        if not rest:
            continue
        params = []
        for p in _v2_param_list(m[mm.end():pend - 1]) if m[mm.end():pend - 1].strip() else []:
            p = _bk_re.sub(r"@[\w.]+(\s*\([^)]*\))?\s*", "", p).replace("final ", "").strip()
            pm = _bk_re.match(r"(.+?)\s+(\w+)$", p)
            if pm:
                params.append((pm.group(1).strip(), pm.group(2)))
        body = None
        if rest.group(1) == "{":
            b0 = pend + rest.end()
            body = (b0, _bk_close(m, b0, "{", "}") - 1)
        ret = _bk_re.search(r"([\w.]+(?:<[^;{}()]*>)?(?:\[\])?)\s*$", before)
        methods.append({"name": name, "owner": owner[4], "params": params, "body": body,
                        "ret": ret.group(1) if ret else "", "pos": mm.start()})
    _BK_DECLS[path] = {"types": [(t[4], t[1], t[2], t[3]) for t in types], "methods": methods}
    return _BK_DECLS[path]


def _bk_simple(t):
    t = _bk_re.sub(r"<.*>", "", t).replace("[]", "").strip()
    return t


def _bk_type_file(path, name):
    """(file, qualified name in that file) of the type `name` (simple, Outer.Inner or dotted FQN) seen from path."""
    name = _bk_simple(name)
    if name.startswith(("java.", "javax.", "jakarta.", "com.fasterxml.")):
        return None
    if _bk_re.match(r"(?:[a-z]\w*\.)+[A-Z]", name):    # fully qualified
        parts = name.split(".")
        k = next(i for i, p in enumerate(parts) if p[:1].isupper())
        f = _bk_find_class_file(".".join(parts[:k]), parts[k])
        return (f, ".".join(parts[k:])) if f else None
    parts = name.split(".")
    decls = _bk_decls(path)
    for q, _, _, _ in decls["types"]:
        if q == name or q.endswith("." + name):
            return path, q
    src = _bk_load(path)[0]
    imp = _bk_re.search(r"^import\s+([\w.]+)\." + parts[0] + r"\s*;", src, _bk_re.M)
    if imp:
        pkg = imp.group(1)
        f = _bk_find_class_file(pkg, parts[0])
        if not f:    # an import of a nested type: import a.b.Outer.Inner
            outer = pkg.split(".")[-1]
            f = _bk_find_class_file(".".join(pkg.split(".")[:-1]), outer)
            return (f, outer + "." + name) if f else None
        return f, name
    pkg = _bk_re.search(r"^package\s+([\w.]+)\s*;", src, _bk_re.M).group(1)
    f = _bk_find_class_file(pkg, parts[0])
    if f:
        return f, name
    for wild in _bk_re.findall(r"^import\s+([\w.]+)\.\*\s*;", src, _bk_re.M):
        f = _bk_find_class_file(wild, parts[0])
        if f:
            return f, name
    return None


def _bk_find_class_file(pkg, simple):
    rel = _bk_os.path.join(*pkg.split(".")) + _bk_os.sep + simple + ".java"
    for module in sorted(_bk_os.listdir(_BK_SANGAM)):
        candidate = _bk_os.path.join(_BK_SANGAM, module, "src", "main", "java", rel)
        if _bk_os.path.isfile(candidate):
            return candidate
    return None


def _bk_implementations(path, qual):
    """Classes that implement the interface `qual` of path (direct implements clause, any module)."""
    global _BK_IMPLS
    if _BK_IMPLS is None:
        _BK_IMPLS = []
        for module in sorted(_bk_os.listdir(_BK_SANGAM)):
            root = _bk_os.path.join(_BK_SANGAM, module, "src", "main", "java")
            for d, _, fs in sorted(_bk_os.walk(root)):
                for f in sorted(fs):
                    if f.endswith(".java"):
                        p = _bk_os.path.join(d, f)
                        with open(p, encoding="utf-8") as fh:
                            if " implements " in fh.read():
                                _BK_IMPLS.append(p)
    simple = qual.split(".")[-1]
    out = []
    for p in _BK_IMPLS:
        m = _bk_load(p)[1]
        for c in _bk_re.finditer(r"\bclass\s+(\w+)[^{]*?\bimplements\s+([^{]+)\{", m):
            names = [_bk_simple(x).split(".")[-1] for x in _v2_param_list(c.group(2))]
            if simple in names and _bk_type_file(p, simple) == (path, qual):
                q = [t[0] for t in _bk_decls(p)["types"] if t[0].split(".")[-1] == c.group(1)]
                out.append((p, q[0]))
    return out


def _bk_methods(path, owner, name):
    return [x for x in _bk_decls(path)["methods"] if x["name"] == name and
            (owner is None or x["owner"] == owner or x["owner"].endswith("." + owner))]


def _bk_var_type(path, method, var):
    """Declared type of a parameter, local or field `var` visible in method."""
    for t, n in method["params"]:
        if n == var:
            return t
    _, m, _ = _bk_load(path)
    if method["body"]:
        body = m[method["body"][0]:method["body"][1]]
        d = _bk_re.search(r"([\w.]+(?:<[^;=(){}]*>)?)\s+" + var + r"\s*[=;:]", body)
        if d and d.group(1) not in ("return", "new", "else"):
            return d.group(1)
    owner = [t for t in _bk_decls(path)["types"] if t[0] == method["owner"]]
    for q, _, a, b in owner + _bk_decls(path)["types"]:
        seg = m[a:b]
        d = _bk_re.search(r"([\w.]+(?:<[^;=(){}]*>)?)\s+" + var + r"\s*[;=]", seg)
        if d:
            return d.group(1)
        rc = None
        for t in _bk_decls(path)["types"]:
            if t[0] == q and t[1] == "record":
                rc = t
        if rc:   # record component
            hdr = m[:a]
            comps = hdr[hdr.rindex("(", 0, hdr.rindex(")")) + 1:hdr.rindex(")")]
            for p in _v2_param_list(comps):
                pm = _bk_re.match(r"(.+?)\s+(\w+)$", _bk_re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", p).strip())
                if pm and pm.group(2) == var:
                    return pm.group(1)
    return None


def _bk_resolve_receiver(path, method, recv):
    """(file, qualified type) of a receiver expression: a.b.c, a().b, Type, Outer.Inner, this.x."""
    recv = _bk_re.sub(r"^this\.", "", recv.strip())
    if recv in ("", "this"):
        return path, method["owner"]
    segs = [s for s in _bk_re.split(r"\.(?![^(]*\))", recv)]
    first = segs[0]
    if first[:1].isupper() or _bk_re.match(r"[a-z]\w*\.[a-z]\w*\.[A-Z]", recv):
        # a type: take the longest prefix that names a type
        for k in range(len(segs), 0, -1):
            cand = ".".join(segs[:k])
            if all(s[:1].isupper() for s in segs[:k]) or k == len(segs):
                found = _bk_type_file(path, cand)
                if found:
                    return _bk_walk(found, segs[k:])
        return None
    fm = _bk_re.match(r"(\w+)\(\)$", first)
    if fm:
        here = _bk_methods(path, method["owner"], fm.group(1))
        if not here:
            return None
        found = _bk_type_file(path, here[0]["ret"])
    else:
        t = _bk_var_type(path, method, first)
        if t is None:
            return None
        found = _bk_type_file(path, t)
    return _bk_walk(found, segs[1:]) if found else None


def _bk_walk(found, segs):
    """Follows .field and .method() segments from a (file, type)."""
    for s in segs:
        if found is None:
            return None
        f, q = found
        fm = _bk_re.match(r"(\w+)\(\)$", s)
        if fm:
            ms = _bk_methods(f, q, fm.group(1))
            if not ms:
                return None
            found = _bk_type_file(f, ms[0]["ret"])
        else:
            pseudo = {"params": [], "body": None, "owner": q}
            t = _bk_var_type(f, pseudo, s)
            found = _bk_type_file(f, t) if t else None
    return found


# ---------------------------------------------------------------- map reads
_BK_READS = ("get", "getOrDefault", "containsKey", "remove", "path", "has", "hasNonNull", "putIfAbsent")
_BK_JSON_KINDS = {"asText": "string", "textValue": "string", "asInt": "integer", "intValue": "integer",
                  "asLong": "int64", "longValue": "int64", "asBoolean": "boolean", "booleanValue": "boolean",
                  "asDouble": "number", "doubleValue": "number", "decimalValue": "decimal"}
_BK_TRANSPARENT = ("valueToTree", "copyOf", "unmodifiableMap")
_BK_COPIES = ("LinkedHashMap", "HashMap", "TreeMap", "ArrayList")
_BK_MEMBERS_HARMLESS = {"isEmpty", "size", "put", "putAll", "equals", "hashCode", "isObject", "isArray", "isNull",
                        "isMissingNode", "isTextual", "toString"} | set(_BK_JSON_KINDS)
_BK_MAPLIKE = r"(?:java\.util\.)?(?:Map|LinkedHashMap|HashMap|TreeMap|SortedMap)\s*<[^;=(){}]*>|JsonNode|ObjectNode"
_BK_DECL_TYPE = (r"(?:final\s+)?(" + _BK_MAPLIKE + r"|(?:java\.util\.)?(?:List|Collection)\s*<\s*(?:Map\s*<[^;=(){}]*>|"
                 r"JsonNode)\s*>|var)")
_BK_OPAQUE = {}           # ("Type.method", use) -> reason; use = callee name, "var.member" or "var"
_BK_OPAQUE_TARGETS = {}   # "Type.method" a map may be passed to without its keys being read -> reason
_BK_FOLLOW = {}           # ("Type.method", callee) -> [("file", "Type.method", "param")]


def bk_opaque(rules):
    """Declares uses of a whole map that read no key: {("Type.method", use): reason}."""
    _BK_OPAQUE.update(rules)


def bk_opaque_target(rules):
    """Declares methods that take a map without reading its keys (they store, hash or forward it): {"Type.method": reason}."""
    _BK_OPAQUE_TARGETS.update(rules)


def bk_follow(rules):
    """Names the reader of a call the analysis cannot resolve: {("Type.method", callee): [("file", "Type.method", "param")]}."""
    _BK_FOLLOW.update(rules)


def _bk_args(m, k):
    """Spans (start, end) of the arguments of the call whose "(" is at k, and the index of its ")". Angle brackets count
    as nesting only when they look like type arguments (Name<T, U>), so comparisons do not shift the index."""
    out, depth, start, i, gen = [], 0, k + 1, k + 1, 0
    while True:
        ch = m[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif ch == "<" and (m[i - 1].isalnum() or m[i - 1] == "_") and _bk_re.match(r"<\s*[\w?>]", m[i:]):
            gen += 1
        elif ch == ">" and gen:
            gen -= 1
        elif ch == "," and depth == 0 and gen == 0:
            out.append((start, i))
            start = i + 1
        i += 1
    if m[start:i].strip():
        out.append((start, i))
    return out, i


def _bk_enclosing_call(m, a, b, p):
    """For position p inside m[a:b]: (callee, receiver, argument index, "(" index, ")" index, argument span) of the
    innermost call whose argument list holds it, or None when it is not inside a call's parentheses."""
    depth, k = 0, p - 1
    while k >= a:
        ch = m[k]
        if ch in ")]":
            depth += 1
        elif ch in "([":
            if depth == 0:
                break
            depth -= 1
        elif ch in ";{}" and depth == 0:
            return None
        k -= 1
    if k < a or m[k] != "(":
        return None
    head = m[a:k].rstrip()
    cm = _bk_re.search(r"(new\s+[\w.]+\s*(?:<[^()]*>)?|\w+)$", head)
    if not cm:
        return None    # a parenthesised expression or a cast
    callee = _bk_re.sub(r"\s+", " ", cm.group(1))
    if callee in _BK_CONTROL:
        return None    # if (...), switch (...), synchronized (...): a condition, not a call
    recv = ""
    pre = head[:cm.start()]
    if pre.endswith("."):
        j, depth = len(pre) - 2, 0
        while j >= 0:
            ch = pre[j]
            if ch == ")":
                depth += 1
            elif ch == "(":
                if depth == 0:
                    break
                depth -= 1
            elif depth == 0 and not (ch.isalnum() or ch in "_."):
                break
            j -= 1
        recv = pre[j + 1:-1].strip()
    args, close = _bk_args(m, k)
    idx = next((n for n, (s0, e0) in enumerate(args) if s0 <= p < e0), 0)
    span = args[idx] if args else (k + 1, close)
    return callee, recv, idx, k, close, span


def _bk_call_start(call):
    """Start of the call expression (receiver included) of an _bk_enclosing_call result."""
    return call[3] - len(call[0]) - (len(call[1]) + 1 if call[1] else 0)


def _bk_whole_arg(m, a, b, s, e):
    """The enclosing call when m[s:e] is a whole argument of it, else None."""
    call = _bk_enclosing_call(m, a, b, s)
    if call is None:
        return None
    s0, e0 = call[5]
    return call if m[s0:e0].strip() == m[s:e].strip() else None


def _bk_is_copy(callee):
    return callee in _BK_TRANSPARENT or (callee.startswith("new ") and
                                         _bk_re.sub(r"\s*<.*", "", callee[4:]).split(".")[-1] in _BK_COPIES)


def _bk_literal(path, pos):
    """The string literal starting at pos (a quote in the masked text)."""
    s, m, _ = _bk_load(path)
    end = m.index('"', pos + 1)
    return s[pos + 1:end]


def _bk_where(method):
    return method["owner"] + "." + method["name"]


def _bk_match(where, rule):
    return where == rule or where.endswith("." + rule)


class _BkWalk:
    def __init__(self, opid, spec):
        self.opid, self.spec = opid, spec
        self.nodes = {(): {"keys": {}, "records": []}}
        self.readers, self.records, self.opaque_used = [], [], []
        self.seen = set()
        self.active = set()
        self.stack = []

    def node(self, n):
        return self.nodes.setdefault(n, {"keys": {}, "records": []})

    def key(self, node, k, kind, path, pos):
        assert "." not in k and k != "[]" and k, (self.opid, k)
        keys = self.node(node)["keys"].setdefault(k, [])
        if kind not in keys:
            keys.append(kind)
        _BK_EVIDENCE.append((self.opid, ".".join(node), k, kind if isinstance(kind, str) else kind[0] + ":" + kind[2],
                             "%s:%d" % (_bk_rel(path), _bk_line(path, pos))))

    def fail(self, path, p, text):
        raise AssertionError("%s: %s at %s:%d" % (self.opid, text, _bk_rel(path), _bk_line(path, p)))

    def note_opaque(self, path, method, use, reason):
        e = {"method": "%s#%s" % (_bk_rel(path), _bk_where(method)), "use": use, "reason": reason}
        if e not in self.opaque_used:
            self.opaque_used.append(e)

    def opaque(self, path, method, use):
        where = _bk_where(method)
        for (rule, u), reason in sorted(list(_BK_OPAQUE.items()) + list((self.spec.get("opaque") or {}).items())):
            if u == use and _bk_match(where, rule):
                self.note_opaque(path, method, use, reason)
                return True
        return False

    def run(self, path, method, var, kind, nodes):
        sig = (path, method["pos"], var, kind, tuple(nodes))
        if sig in self.seen or (path, method["pos"], var) in self.active:
            return     # done already, or a recursive descent into nested values of the same shape
        self.seen.add(sig)
        if method["body"] is None:     # an interface method: its implementations read the map
            impls = _bk_implementations(path, method["owner"])
            assert impls, "%s: no implementation of %s in %s" % (self.opid, _bk_where(method), _bk_rel(path))
            idx = [n for _, n in method["params"]].index(var)
            for ip, iq in impls:
                for im in _bk_methods(ip, iq, method["name"]):
                    if len(im["params"]) == len(method["params"]) and im["body"]:
                        self.run(ip, im, im["params"][idx][1], kind, nodes)
            return
        self.stack.append("%s(%s)" % (_bk_where(method), var))
        self.active.add((path, method["pos"], var))
        _, m, _ = _bk_load(path)
        a, b = method["body"]
        # bindings: (name, declaration ordinal) -> [kind, nodes, scope]; ordinal 0 is the parameter, n the n-th
        # declaration of that name in the body (_bk_decl_sites), whose scope is the rest of its block or its for loop
        sites = _bk_decl_sites(m, a, b)
        vars_ = {(var, 0): [kind, list(nodes), (a, b)]}
        consumed = set()

        def binding_at(v, p):
            for (n, o), bnd in vars_.items():
                if n == v and bnd[2][0] <= p < bnd[2][1]:
                    return (n, o), bnd
            return None, None

        def add(x, pos, k_, ns):
            o, scope = next((o_, sc) for (n_, o_, p_, sc) in sites if n_ == x and p_ == pos)
            cur = vars_.setdefault((x, o), [k_, [], scope])
            grew = False
            for n in ns:
                if n not in cur[1]:
                    cur[1].append(n)
                    grew = True
            return grew

        changed = True
        while changed:
            changed = False
            # declarations: Map<String, Object> x = <alias, copy or nested map of a known variable>;
            for d in _bk_re.finditer(_BK_DECL_TYPE + r"\s+(\w+)\s*=\s*", m[a:b]):
                x, xt = d.group(2), d.group(1)
                dpos = a + d.start(2)
                rhs_a = a + d.end()
                rhs_b = rhs_a + _bk_stmt_end(m[rhs_a:b])
                for (v, _o), (vk, vn, _sc) in list(vars_.items()):
                    if v == x:
                        continue
                    for t in _bk_re.finditer(r"(?<![\w.])" + v + r"\b", m[rhs_a:rhs_b]):
                        p = rhs_a + t.start()
                        if binding_at(v, p)[0] != (v, _o):
                            continue
                        after = m[p + len(v):p + len(v) + 60]
                        call = _bk_enclosing_call(m, rhs_a, rhs_b, p)
                        fm = _bk_re.match(r"\s*,\s*\"", after)
                        gm = _bk_re.match(r"\s*\.\s*(?:get|getOrDefault|path)\(\s*\"", after)
                        if fm and call and call[2] == 0 and call[0] in ("map", "maps") and vk == "map":
                            if _bk_enclosing_call(m, rhs_a, rhs_b, _bk_call_start(call)) is not None:
                                continue
                            k = _bk_literal(path, p + len(v) + fm.end() - 1)
                            if call[0] == "map":
                                changed |= add(x, dpos, "map", [n + (k,) for n in vn])
                            else:
                                changed |= add(x, dpos, "list", [n + (k, "[]") for n in vn])
                        elif gm and vk == "map" and xt != "var":
                            if _bk_re.search(r"\bconvert(?:Value)?\s*\($", m[rhs_a:p].rstrip()):
                                continue
                            ks = (_bk_literal(path, p + len(v) + gm.end() - 1),)
                            end = _bk_close(m, p + len(v) + _bk_re.match(r"\s*\.\s*\w+\s*\(", after).end(), "(", ")")
                            dynamic = False
                            while True:     # x = v.path("a").path("b"): x is b inside a
                                seg = _bk_re.match(r"\s*\.\s*(?:path|get)\(\s*", m[end:end + 60])
                                if not seg:
                                    break
                                if m[end + seg.end()] != '"':
                                    dynamic = True
                                    break
                                ks += (_bk_literal(path, end + seg.end()),)
                                end = _bk_close(m, end + seg.end(), "(", ")")
                            if dynamic or _bk_re.match(r"\s*\.", m[end:end + 5]):
                                continue    # a member named at run time, or a scalar taken from the value
                            if _bk_re.match(r"(?:java\.util\.)?(?:List|Collection)\b", xt):
                                changed |= add(x, dpos, "list", [n + ks + ("[]",) for n in vn])
                            else:
                                changed |= add(x, dpos, "map", [n + ks for n in vn])
                        elif fm or _bk_re.match(r"\s*\.", after) or _bk_re.match(r"\s*[=!]=\s*null", after) or \
                                _bk_re.match(r"\s*instanceof\b", after):
                            continue
                        else:
                            c = call
                            while c is not None and _bk_is_copy(c[0]):
                                c = _bk_enclosing_call(m, rhs_a, rhs_b, _bk_call_start(c))
                            if c is None:     # an alias or a copy: x = v, x = v == null ? Map.of() : v, new LinkedHashMap<>(v)
                                consumed.add(p)
                                changed |= add(x, dpos, vk, vn)
            # for (Map<String, Object> x : <a list variable, a JSON array, maps(v, "k") or v.get("k")>)
            for d in _bk_re.finditer(r"for\s*\(\s*" + _BK_DECL_TYPE + r"\s+(\w+)\s*:\s*", m[a:b]):
                x = d.group(2)
                dpos = a + d.start(2)
                e0 = a + d.end()
                e1 = _bk_close(m, a + d.start() + m[a + d.start():b].index("(") + 1, "(", ")") - 1
                expr = m[e0:e1].strip()
                for (v, _o), (vk, vn, _sc) in list(vars_.items()):
                    if v == x or binding_at(v, e0)[0] != (v, _o):
                        continue
                    if expr == v:
                        consumed.add(e0 + m[e0:e1].index(v))
                        changed |= add(x, dpos, "map", list(vn) if vk == "list" else [n + ("[]",) for n in vn])
                        continue
                    mm = _bk_re.match(r"(?:[\w.]+\.)?maps\(\s*" + v + r"\s*,\s*\"", expr) or \
                        _bk_re.match(v + r"\s*\.\s*(?:get|path)\(\s*\"", expr)
                    if mm and vk == "map":
                        k = _bk_literal(path, e0 + m[e0:e1].index(expr) + mm.end() - 1)
                        changed |= add(x, dpos, "map", [n + (k, "[]") for n in vn])
        where = "%s#%s" % (_bk_rel(path), _bk_where(method))
        for (v, o), (vk, vn, sc) in vars_.items():
            for n in vn:
                r = {"method": where, "var": v, "node": ".".join(n)}
                if o:
                    r["declaration"] = o
                if r not in self.readers:
                    self.readers.append(r)
        for (v, o), (vk, vn, sc) in list(vars_.items()):
            for t in _bk_re.finditer(r"(?<![\w.])" + v + r"\b", m[a:b]):
                p = a + t.start()
                if p not in consumed and binding_at(v, p)[0] == (v, o):
                    self.use(path, method, v, vk, vn, p, a, b)
        self.active.discard((path, method["pos"], var))
        self.stack.pop()

    def use(self, path, method, v, vk, vn, p, a, b):
        _, m, _ = _bk_load(path)
        after = m[p + len(v):p + len(v) + 80]
        before = m[max(a, p - 120):p]
        if _bk_re.search(r"(?:" + _BK_MAPLIKE + r"|(?:List|Collection)\s*<[^;=(){}]*>|var|Object)\s+$", before):
            return     # the declaration of v
        if _bk_re.match(r"\s*(?:->|=[^=])", after) or _bk_re.search(r"[(,]\s*$", before) and _bk_re.match(r"\s*[,)]\s*->", after):
            return     # a lambda parameter or an assignment to v
        fm = _bk_re.match(r"\s*,\s*\"", after)
        call = _bk_enclosing_call(m, a, b, p)
        if fm and call and call[2] == 0 and call[0] in _BK_KINDS:
            assert vk == "map", (self.opid, v)
            k = _bk_literal(path, p + len(v) + fm.end() - 1)
            for n in vn:
                self.key(n, k, _BK_KINDS[call[0]], path, p)
            if call[0] in ("map", "maps"):
                ns = [n + (k,) for n in vn] if call[0] == "map" else [n + (k, "[]") for n in vn]
                self.nested(path, method, m, a, b, _bk_call_start(call), call[4] + 1,
                            "map" if call[0] == "map" else "list", ns)
            return
        gm = _bk_re.match(r"\s*\.\s*(" + "|".join(_BK_READS) + r")\(\s*\"", after)
        if gm:
            assert vk == "map", (self.opid, v)
            k = _bk_literal(path, p + len(v) + gm.end() - 1)
            end = _bk_close(m, p + len(v) + _bk_re.match(r"\s*\.\s*\w+\s*\(", after).end(), "(", ")")
            how = gm.group(1)
            while True:     # a JSON path chain: v.path("a").path("b") reads b inside a
                cm_ = _bk_re.match(r"\s*\.\s*(path|get|has|hasNonNull)\(\s*\"", m[end:end + 60])
                if not cm_ or how not in ("path", "get"):
                    break
                for n in vn:
                    self.key(n, k, "object", path, p)
                vn = [n + (k,) for n in vn]
                k = _bk_literal(path, end + cm_.end() - 1)
                how = cm_.group(1)
                end = _bk_close(m, end + _bk_re.match(r"\s*\.\s*\w+\s*\(", m[end:]).end(), "(", ")")
            kind = self.get_kind(path, m, a, b, p, end, how)
            for n in vn:
                self.key(n, k, kind, path, p)
            if how in ("get", "path", "getOrDefault") and kind in ("any", "object", "array"):
                self.nested(path, method, m, a, b, p, end, None, [n + (k,) for n in vn])
            return
        mm = _bk_re.match(r"\s*\.\s*(\w+)\s*\(", after)
        if mm:
            if mm.group(1) not in _BK_MEMBERS_HARMLESS and not self.opaque(path, method, v + "." + mm.group(1)):
                self.fail(path, p, "the whole map is used (%s.%s): declare it with bk_opaque and a reason" % (v, mm.group(1)))
            return
        if _bk_re.match(r"\s*\.", after):
            return
        if _bk_re.match(r"\s*[=!]=\s*null", after) or _bk_re.search(r"null\s*[=!]=\s*$", before) or \
                _bk_re.match(r"\s*instanceof\b", after):
            return
        self.passed(path, method, m, a, b, p, v, vk, vn)

    def passed(self, path, method, m, a, b, s, v, vk, vn):
        """The variable v (a map or a list of maps from the body) is used as a whole at s."""
        call = _bk_enclosing_call(m, a, b, s)
        while call is not None and _bk_is_copy(call[0]):
            call = _bk_enclosing_call(m, a, b, _bk_call_start(call))
        if call is None:
            if not self.opaque(path, method, v):
                self.fail(path, s, "the whole map %s is used outside a call: declare it with bk_opaque and a reason" % v)
            return
        callee, recv, idx, k0, k1, span = call
        if callee in ("convert", "convertValue", "treeToValue"):
            cls = _bk_re.search(r"<\s*(?:List|Collection)\s*<\s*([\w.]+)\s*>\s*>", m[k0:k1]) or \
                _bk_re.search(r"([\w.]+)\.class\b", m[k0:k1])
            if not cls or cls.group(1).split(".")[-1] in ("Map", "Object"):
                if not self.opaque(path, method, callee):
                    self.fail(path, s, "%s(%s, ...) without a record target: declare it with bk_opaque" % (callee, v))
                return
            for n in vn:
                self.record(path, cls.group(1), n)
            return
        if self.opaque(path, method, callee):
            return
        targets = self.resolve(path, method, callee, recv, idx, vk)
        if targets is None:     # an opaque target
            return
        if not targets:
            self.fail(path, s, "cannot follow %s%s(...) that receives %s: declare bk_follow or bk_opaque" % (
                recv + "." if recv else "", callee, v))
        for tp, tm, tv, tk in targets:
            self.run(tp, tm, tv, tk, vn)

    def nested(self, path, method, m, a, b, s, e, kind, nodes):
        """A nested map (Maps.map(v, "k"), v.get("k")) passed straight to a method: follow it when the method takes a
        map, a JSON node or a list of maps; anything else leaves the member typed by its reader."""
        call = _bk_whole_arg(m, a, b, s, e)
        if call is None or call[0] in _BK_KINDS or call[0] in _BK_JSON_KINDS or _bk_is_copy(call[0]):
            return
        if ((call[1] + "." if call[1] else "") + call[0]) in _BK_WRAPPERS:
            return
        callee, recv, idx, k0, k1, span = call
        if callee in ("convert", "convertValue", "treeToValue"):
            cls = _bk_re.search(r"([\w.]+)\.class\b", m[k0:k1])
            if cls and cls.group(1).split(".")[-1] not in ("Map", "List", "Object"):
                for n in nodes:
                    self.record(path, cls.group(1), n)
            return
        if self.opaque(path, method, callee):
            return
        targets = self.resolve(path, method, callee, recv, idx, kind, strict=True)
        for tp, tm, tv, tk in targets or []:
            ns = nodes
            if tk == "list" and kind is None:
                ns = [n + ("[]",) for n in nodes]
            self.run(tp, tm, tv, tk, ns)

    def get_kind(self, path, m, a, b, p, end, how):
        if how in ("containsKey", "has", "hasNonNull", "remove", "putIfAbsent"):
            return "any"
        jm = _bk_re.match(r"\s*\.\s*(\w+)\s*\(", m[end:end + 40])
        if jm and jm.group(1) in _BK_JSON_KINDS:
            return _BK_JSON_KINDS[jm.group(1)]
        im = _bk_re.match(r"\s*instanceof\s+([\w.]+)", m[end:end + 60])
        if im:
            return {"Map": "object", "List": "array", "Collection": "array", "Number": "number", "String": "string",
                    "Boolean": "boolean", "Integer": "integer", "Long": "int64"}.get(im.group(1).split(".")[-1], "any")
        call = _bk_whole_arg(m, a, b, p, end)
        if call:
            name = (call[1] + "." if call[1] else "") + call[0]
            if name in _BK_WRAPPERS:
                return _BK_WRAPPERS[name]
            if call[0] in _BK_KINDS and call[2] == 0:
                return _BK_KINDS[call[0]]
            if call[0] in ("convert", "convertValue"):
                cls = _bk_re.search(r"<\s*(?:List|Collection)\s*<\s*([\w.]+)\s*>\s*>", m[call[3]:call[4]])
                if cls:
                    return ("records", path, cls.group(1))
                cls = _bk_re.search(r"([\w.]+)\.class\b", m[call[3]:call[4]])
                if cls and cls.group(1).split(".")[-1] not in ("Map", "List", "Object"):
                    return ("record", path, cls.group(1))
                if cls:
                    return {"Map": "object", "List": "array"}.get(cls.group(1).split(".")[-1], "any")
        cast = _bk_re.search(r"\(\s*(String|Number|Boolean|Integer|Long|(?:java\.util\.)?Map\s*<[^()]*>|"
                             r"(?:java\.util\.)?List\s*<[^()]*>|Map|List)\s*\)\s*$", m[a:p])
        if cast:
            c = cast.group(1)
            return {"String": "string", "Number": "number", "Boolean": "boolean", "Integer": "integer",
                    "Long": "int64"}.get(c, "object" if "Map" in c else "array")
        return "any"

    def record(self, path, cls, node):
        found = _bk_type_file(path, cls)
        assert found, "%s: cannot resolve record %s from %s" % (self.opid, cls, _bk_rel(path))
        f, q = found
        decl = _v2_find_type(f, q)
        assert decl and decl[1] == "record", "%s: %s is not a record" % (self.opid, cls)
        name = _v2_record_schema(decl[0], decl[2], decl[3], self.opid)
        pkg = _bk_re.search(r"^package\s+([\w.]+)\s*;", _bk_load(f)[0], _bk_re.M).group(1)
        entry = {"class": pkg + "." + q.replace(".", "$"), "schema": name, "node": ".".join(node)}
        if entry not in self.records:
            self.records.append(entry)
        if name not in self.node(node)["records"]:
            self.node(node)["records"].append(name)

    def resolve(self, path, method, callee, recv, idx, vk, strict=False):
        """[(file, method, parameter, kind)] that receive argument idx of the call, or None for an opaque target."""
        where = _bk_where(method)
        for (rule, c), targets in sorted(_BK_FOLLOW.items()):
            if c == callee and _bk_match(where, rule):
                out = []
                for tf, tq, tv in targets:
                    tpath = _bk_os.path.join(_BK_SANGAM, *tf.split("/"))
                    owner, _, name = tq.rpartition(".")
                    for x in _bk_methods(tpath, owner or None, name):
                        for t, n in x["params"]:
                            if n == tv:
                                out.append((tpath, x, tv, "list" if _bk_re.match(r"(?:java\.util\.)?(?:List|Collection)\s*<", t) else "map"))
                return out
        if callee.startswith("new "):
            return []
        found = _bk_resolve_receiver(path, method, recv)
        if not found:
            return []
        f, q = found
        cands = _bk_methods(f, q, callee)
        if not cands and not recv:     # a method of an enclosing type
            cands = [x for x in _bk_decls(f)["methods"] if x["name"] == callee]
        out = []
        for x in cands:
            for rule, reason in sorted(_BK_OPAQUE_TARGETS.items()):
                if _bk_match(_bk_where(x), rule):
                    self.note_opaque(path, method, callee, reason)
                    return None
            if idx < len(x["params"]):
                t = x["params"][idx][0]
                if _bk_re.match(r"(?:java\.util\.)?(?:List|Collection)\s*<", t):
                    out.append((f, x, x["params"][idx][1], "list"))
                elif _bk_re.match(_BK_MAPLIKE, t) or (not strict and t in ("Object", "T")):
                    out.append((f, x, x["params"][idx][1], "map"))
        return out


def _bk_decl_sites(m, a, b):
    """Declarations of map, JSON-node and list-of-map locals in m[a:b], in order: (name, ordinal among the declarations
    of that name, position of the name, scope). The scope of a for-each variable is its loop; of any other local, the
    rest of the enclosing block. MapRequestBodyContractTest numbers declarations the same way."""
    out, count = [], {}
    for d in _bk_re.finditer(r"(for\s*\(\s*)?" + _BK_DECL_TYPE + r"\s+(\w+)\s*([=:])", m[a:b]):
        name, pos = d.group(3), a + d.start(3)
        count[name] = count.get(name, 0) + 1
        if d.group(1) and d.group(4) == ":":
            close = _bk_close(m, a + d.start() + d.group(0).index("(") + 1, "(", ")")
            nxt = _bk_re.match(r"\s*", m[close:]).end() + close
            end = _bk_close(m, nxt + 1, "{", "}") if m[nxt] == "{" else nxt + _bk_stmt_end(m[nxt:b]) + 1
        else:
            depth, k = 0, pos
            while k > a:
                k -= 1
                if m[k] == "}":
                    depth += 1
                elif m[k] == "{":
                    if depth == 0:
                        break
                    depth -= 1
            end = _bk_close(m, k + 1, "{", "}") - 1 if k > a else b
        out.append((name, count[name], pos, (pos, min(end, b))))
    return out


def _bk_stmt_end(s):
    depth = 0
    for i, ch in enumerate(s):
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            if depth == 0:
                return i
            depth -= 1
        elif ch == ";" and depth == 0:
            return i
    return len(s)


# ---------------------------------------------------------------- schemas
def _bk_kind_schema(kinds):
    ks = [k for k in kinds if k != "any"]
    recs = [k for k in ks if isinstance(k, tuple)]
    if recs:
        how, path, cls = recs[0]
        found = _bk_type_file(path, cls)
        decl = _v2_find_type(found[0], found[1])
        ref = R(_v2_record_schema(decl[0], decl[2], decl[3], cls))
        return {"type": "array", "items": ref} if how == "records" else ref
    ks = sorted(set(ks))
    if not ks:
        return {}
    strings = {"string": 0, "decimal": 1, "date": 2, "date-time": 2}
    if all(k in strings for k in ks):
        k = max(ks, key=lambda x: (strings[x], x))
        return {"string": {"type": "string"}, "decimal": {"type": "string", "description": _V2_STRINGS["BigDecimal"]},
                "date": typ("date"), "date-time": typ("dt")}[k]
    nums = {"integer": 0, "int64": 1, "number": 2}
    if all(k in nums for k in ks):
        return {"integer": typ("int"), "int64": typ("long"), "number": typ("num")}[max(ks, key=lambda x: nums[x])]
    if ks == ["array", "objects"] or ks == ["objects"]:
        return {"type": "array", "items": {"type": "object", "additionalProperties": True}}
    if ks == ["array", "strings"] or ks == ["strings"]:
        return {"type": "array", "items": {"type": "string"},
                "description": "A JSON array of strings; a comma-separated string is also read"}
    if len(ks) == 1:
        return {"boolean": typ("bool"), "object": {"type": "object", "additionalProperties": True},
                "array": {"type": "array", "items": {}}}[ks[0]]
    return {"anyOf": [_bk_kind_schema([k]) for k in ks]}


def _bk_node_schema(w, node):
    n = w.nodes.get(node, {"keys": {}, "records": []})
    props = {}
    for rec in n["records"]:
        for k, v in SCHEMAS[rec]["properties"].items():
            props.setdefault(k, v)
    for k, kinds in n["keys"].items():
        if node + (k, "[]") in w.nodes:
            props[k] = {"type": "array", "items": _bk_obj(w, node + (k, "[]"))}
        elif node + (k,) in w.nodes:
            props[k] = _bk_obj(w, node + (k,))
        elif k in props and all(x == "any" for x in kinds):
            continue
        else:
            props[k] = _bk_kind_schema(kinds)
    return props


def _bk_obj(w, node):
    return {"type": "object", "properties": _bk_node_schema(w, node), "additionalProperties": True}


def map_body(opid, java_file=None, method=None, var=None, follow=None, opaque=None, array=False, also=(), via=None):
    """Declares the Map-backed request body of `opid`: the controller method ("Class.method") of java_file (relative to
    sangam/) and its body parameter var. follow={("Class.method", "callee"): ("file", "Class.method", "param")} names a
    call the analysis cannot resolve; opaque={("Class.method", "callee" or "var.member" or "var"): "reason"} declares a
    use of the whole map that reads no key. array=True: the body is a JSON array of such objects. For a body bound as a
    JSON string that the service parses, `var` is the local variable holding the parsed object, `via` names the
    controller method that receives the string, and `also` lists further (file, "Class.method", var) that parse the
    same string."""
    spec = _BK_BODIES.setdefault(opid, {})
    if java_file:
        spec.update({"file": java_file, "method": method, "var": var, "array": array, "also": list(also), "via": via})
    if follow:
        spec.setdefault("follow", {}).update(follow)
    if opaque:
        spec.setdefault("opaque", {}).update(opaque)


def _bk_declares(path, method, var):
    """True when var is a parameter of method, or a local map / JSON node declared in its body."""
    if var in [n for _, n in method["params"]]:
        return True
    if method["body"] is None:
        return False
    _, m, _ = _bk_load(path)
    return _bk_re.search(r"(?:" + _BK_MAPLIKE + r")\s+" + var + r"\s*[=;]", m[method["body"][0]:method["body"][1]]) is not None


def _bk_apply():
    """Types every declared Map body of the contract (gen_openapi.py calls this after the route files)."""
    ops = {o["operationId"]: o for item in PATHS.values() for o in item.values()}
    for opid in sorted(_BK_BODIES):
        spec = _BK_BODIES[opid]
        assert opid in ops, "map_body: unknown operation " + opid
        assert "file" in spec, "map_body: %s has follow/opaque but no anchor" % opid
        anchors = []
        for jf, jm, jv in [(spec["file"], spec["method"], spec["var"])] + list(spec.get("also") or []):
            path = _bk_os.path.join(_BK_SANGAM, *jf.split("/"))
            owner, _, name = jm.rpartition(".")
            ms = [x for x in _bk_methods(path, owner or None, name) if _bk_declares(path, x, jv)]
            assert len(ms) == 1, "map_body %s: %d methods %s(%s) in %s" % (opid, len(ms), jm, jv, jf)
            anchors.append((path, ms[0], jv))
        w = _BkWalk(opid, spec)
        try:
            for path, mt, jv in anchors:
                w.run(path, mt, jv, "list" if spec.get("array") else "map", [()])
        except AssertionError as e:
            if not _bk_os.environ.get("SANGAM_BODYKEYS_SURVEY"):
                raise
            print("SURVEY", e, "\n    via", " > ".join(w.stack))
            continue
        sname = opid[0].upper() + opid[1:] + "Body"
        assert sname not in SCHEMAS, "map_body: schema name %s is taken" % sname
        s = {"type": "object",
             "description": ("Request body of %s, read by the service as a JSON object; typed from the keys the service "
                             "reads (M-051). Members it does not read are accepted and ignored." % (spec.get("via") or spec["method"])),
             "properties": _bk_node_schema(w, ()), "additionalProperties": True,
             "x-sangam-body-readers": w.readers}
        if w.records:
            s["x-sangam-body-records"] = w.records
        if w.opaque_used:
            s["x-sangam-body-opaque"] = w.opaque_used
        assert s["properties"] or w.opaque_used, "map_body %s: the service reads no key of the body" % opid
        SCHEMAS[sname] = s
        rb = ops[opid]["requestBody"]
        ct = "application/json"
        assert ct in rb["content"], opid
        ref = R(sname)
        rb["content"][ct]["schema"] = {"type": "array", "items": ref} if spec.get("array") else ref
    out = _bk_os.environ.get("SANGAM_BODYKEYS_EVIDENCE")
    if out:
        with open(out, "w", encoding="utf-8", newline="\n") as f:
            f.write("operationId\tnode\tkey\tkind\tread at\n")
            for row in _BK_EVIDENCE:
                f.write("\t".join(str(x) for x in row) + "\n")
