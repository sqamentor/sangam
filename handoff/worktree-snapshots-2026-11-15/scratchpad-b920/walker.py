# ---------------------------------------------------------------- map reads
_BK_READS = ("get", "getOrDefault", "containsKey", "remove", "path", "has", "hasNonNull", "putIfAbsent")
_BK_JSON_KINDS = {"asText": "string", "textValue": "string", "asInt": "integer", "intValue": "integer",
                  "asLong": "int64", "longValue": "int64", "asBoolean": "boolean", "booleanValue": "boolean",
                  "asDouble": "number", "doubleValue": "number", "decimalValue": "decimal"}
_BK_TRANSPARENT = ("valueToTree", "copyOf", "unmodifiableMap")
_BK_COPIES = ("LinkedHashMap", "HashMap", "TreeMap", "ArrayList")
_BK_MEMBERS_HARMLESS = {"isEmpty", "size", "put", "putAll", "equals", "hashCode", "isObject", "isArray", "isNull",
                        "isMissingNode", "isTextual"}
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
    if callee in _BK_KEYWORDS:
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
        if sig in self.seen:
            return
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
        _, m, _ = _bk_load(path)
        a, b = method["body"]
        vars_ = {var: (kind, list(nodes))}
        consumed = set()

        def add(x, k_, ns):
            cur = vars_.setdefault(x, (k_, []))
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
                rhs_a = a + d.end()
                rhs_b = rhs_a + _bk_stmt_end(m[rhs_a:b])
                for v, (vk, vn) in list(vars_.items()):
                    if v == x:
                        continue
                    for t in _bk_re.finditer(r"(?<![\w.])" + v + r"\b", m[rhs_a:rhs_b]):
                        p = rhs_a + t.start()
                        after = m[p + len(v):p + len(v) + 60]
                        call = _bk_enclosing_call(m, rhs_a, rhs_b, p)
                        fm = _bk_re.match(r"\s*,\s*\"", after)
                        gm = _bk_re.match(r"\s*\.\s*(?:get|getOrDefault|path)\(\s*\"", after)
                        if fm and call and call[2] == 0 and call[0] in ("map", "maps") and vk == "map":
                            if _bk_enclosing_call(m, rhs_a, rhs_b, _bk_call_start(call)) is not None:
                                continue
                            k = _bk_literal(path, p + len(v) + fm.end() - 1)
                            if call[0] == "map":
                                changed |= add(x, "map", [n + (k,) for n in vn])
                            else:
                                changed |= add(x, "list", [n + (k, "[]") for n in vn])
                        elif gm and vk == "map" and xt != "var":
                            if _bk_re.search(r"\bconvert(?:Value)?\s*\($", m[rhs_a:p].rstrip()):
                                continue
                            k = _bk_literal(path, p + len(v) + gm.end() - 1)
                            if _bk_re.match(r"(?:java\.util\.)?(?:List|Collection)\b", xt):
                                changed |= add(x, "list", [n + (k, "[]") for n in vn])
                            else:
                                changed |= add(x, "map", [n + (k,) for n in vn])
                        elif fm or _bk_re.match(r"\s*\.", after) or _bk_re.match(r"\s*[=!]=\s*null", after) or \
                                _bk_re.match(r"\s*instanceof\b", after):
                            continue
                        else:
                            c = call
                            while c is not None and _bk_is_copy(c[0]):
                                c = _bk_enclosing_call(m, rhs_a, rhs_b, _bk_call_start(c))
                            if c is None:     # an alias or a copy: x = v, x = v == null ? Map.of() : v, new LinkedHashMap<>(v)
                                consumed.add(p)
                                changed |= add(x, vk, vn)
            # for (Map<String, Object> x : <a list variable, a JSON array, maps(v, "k") or v.get("k")>)
            for d in _bk_re.finditer(r"for\s*\(\s*" + _BK_DECL_TYPE + r"\s+(\w+)\s*:\s*", m[a:b]):
                x = d.group(2)
                e0 = a + d.end()
                e1 = _bk_close(m, a + d.start() + m[a + d.start():b].index("(") + 1, "(", ")") - 1
                expr = m[e0:e1].strip()
                for v, (vk, vn) in list(vars_.items()):
                    if v == x:
                        continue
                    if expr == v:
                        consumed.add(e0 + m[e0:e1].index(v))
                        changed |= add(x, "map", list(vn) if vk == "list" else [n + ("[]",) for n in vn])
                        continue
                    mm = _bk_re.match(r"(?:[\w.]+\.)?maps\(\s*" + v + r"\s*,\s*\"", expr) or \
                        _bk_re.match(v + r"\s*\.\s*(?:get|path)\(\s*\"", expr)
                    if mm and vk == "map":
                        k = _bk_literal(path, e0 + m[e0:e1].index(expr) + mm.end() - 1)
                        changed |= add(x, "map", [n + (k, "[]") for n in vn])
        where = "%s#%s" % (_bk_rel(path), _bk_where(method))
        for v, (vk, vn) in vars_.items():
            for n in vn:
                r = {"method": where, "var": v, "node": ".".join(n)}
                if r not in self.readers:
                    self.readers.append(r)
        for v, (vk, vn) in list(vars_.items()):
            for t in _bk_re.finditer(r"(?<![\w.])" + v + r"\b", m[a:b]):
                p = a + t.start()
                if p not in consumed:
                    self.use(path, method, v, vk, vn, p, a, b)
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
        if fm and call and call[2] == 0:
            assert vk == "map", (self.opid, v)
            if call[0] not in _BK_KINDS:
                self.fail(path, p, "unknown map reader %s(%s, ...)" % (call[0], v))
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
            kind = self.get_kind(path, m, a, b, p, end, gm.group(1))
            for n in vn:
                self.key(n, k, kind, path, p)
            if gm.group(1) in ("get", "path", "getOrDefault") and kind in ("any", "object", "array"):
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


