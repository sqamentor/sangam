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
