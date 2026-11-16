# SPDX-License-Identifier: Apache-2.0
# Helper for route files whose controllers are Spring MVC classes: derives one OpenAPI operation per mapping from the
# controller source, so the contract and the controllers cannot drift (parity by construction). Runs inside
# gen_openapi.py (op, pp, q, typ, R and SCHEMAS are in scope). Used by geo.py, registry.py and resources.py. Request
# bodies bound to a record get a schema derived from the record's source (M-051, _v2_body_schema).
import os as _v2_os
import re as _v2_re

_V2_SANGAM = _v2_os.path.normpath(_v2_os.path.join(_v2_os.path.dirname(_v2_os.path.abspath(__file__)), "..", ".."))
_V2_JTYPES = {"int": "int", "Integer": "int", "long": "long", "Long": "long", "double": "num", "Double": "num",
              "boolean": "bool", "Boolean": "bool", "Instant": "dt", "LocalDate": "date"}
_V2_OBJECT = {"type": "object", "additionalProperties": True}


def _v2_camel_words(s):
    return _v2_re.sub(r"(?<!^)(?=[A-Z])", " ", s).lower()


def _v2_kebab(s):
    return _v2_re.sub(r"(?<!^)(?=[A-Z])", "-", s).lower()


def _v2_param_list(sig):
    """Splits a Java parameter list at top-level commas (generics and annotation parentheses respected)."""
    out, depth, cur = [], 0, ""
    for ch in sig:
        if ch in "(<":
            depth += 1
        elif ch in ")>":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur.strip())
    return out


# ---------------------------------------------------------------- request records (M-051)
# A @RequestBody whose Java type is a record gets a typed schema derived from the record's source: one property per
# component, in declaration order. In a @RequestBody record (and the records nested in it) a primitive component
# (boolean, int, long, ...) is `required`: the server's Jackson 3 mapper refuses a missing or null member for it (SL-81,
# FAIL_ON_NULL_FOR_PRIMITIVES), so the contract says so
# (PrimitiveRequestFieldContractTest). A record bound through a @JsonCreator factory (the SL-81 pattern: boxed
# parameters, the documented default applied once) is required by the creator's primitive parameters instead. Every
# other member stays optional, and additionalProperties stays true (Jackson ignores unknown members, and SL-40 custom
# fields travel as extra members). A Map body is typed from the keys
# its service reads (_v2_bodykeys.py, MapRequestBodyContractTest). RequestBodyContractTest checks every record body of
# the contract against the compiled record.
_V2_SCALAR = {"String": "s", "char": "s", "Character": "s", "int": "int", "Integer": "int", "short": "int",
              "Short": "int", "long": "long", "Long": "long", "double": "num", "Double": "num", "float": "num",
              "Float": "num", "boolean": "bool", "Boolean": "bool", "Instant": "dt", "OffsetDateTime": "dt",
              "ZonedDateTime": "dt", "LocalDate": "date", "Object": "any"}
# Jackson writes and reads these as JSON strings (decimals as strings is the SD-398 money convention)
_V2_STRINGS = {"BigDecimal": "Decimal number as a string", "BigInteger": "Integer as a string", "UUID": "UUID",
               "LocalTime": "Local time (HH:mm[:ss])", "LocalDateTime": "Local date-time (ISO-8601, no offset)",
               "Duration": "ISO-8601 duration"}
_V2_PRIMITIVES = {"boolean", "int", "long", "short", "byte", "double", "float", "char"}
_V2_DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]
_V2_SOURCES = {}


def _v2_source(path):
    if path not in _V2_SOURCES:
        with open(path, encoding="utf-8") as f:
            _V2_SOURCES[path] = _v2_re.sub(r"/\*.*?\*/|//[^\n]*", "", f.read(), flags=_v2_re.S)
    return _V2_SOURCES[path]


def _v2_class_file(src_path, simple):
    """The source file of a top-level type `simple` seen from src_path: an explicit import, else the same package."""
    src = _v2_source(src_path)
    imp = _v2_re.search(r"^import\s+([\w.]+)\." + simple + r"\s*;", src, _v2_re.M)
    pkg = imp.group(1) if imp else _v2_re.search(r"^package\s+([\w.]+)\s*;", src, _v2_re.M).group(1)
    rel = _v2_os.path.join(*pkg.split(".")) + _v2_os.sep + simple + ".java"
    for module in sorted(_v2_os.listdir(_V2_SANGAM)):
        candidate = _v2_os.path.join(_V2_SANGAM, module, "src", "main", "java", rel)
        if _v2_os.path.isfile(candidate):
            return candidate
    return None


def _v2_find_type(src_path, name):
    """(file, kind, body, display name) of the record or enum `name` (simple or Outer.Inner) seen from src_path."""
    parts = name.split(".")
    if len(parts) == 1:
        nested = _v2_re.search(r"\b(record|enum)\s+" + parts[0] + r"\b", _v2_source(src_path))
        if nested:
            return _v2_decl(src_path, parts[0], _v2_os.path.basename(src_path)[:-5])
        f = _v2_class_file(src_path, parts[0])
        return _v2_decl(f, parts[0], None) if f else None
    f = _v2_class_file(src_path, parts[0])
    return _v2_decl(f, parts[-1], parts[0]) if f else None


def _v2_decl(path, simple, outer):
    src = _v2_source(path)
    m = _v2_re.search(r"\b(record|enum)\s+" + simple + r"\b\s*(?:<[^>]*>)?\s*([({])", src)
    if not m:
        return None
    i, depth, start = m.end(), 1, m.end()
    close = ")" if m.group(2) == "(" else "}"
    while depth and i < len(src):
        depth += {m.group(2): 1, close: -1}.get(src[i], 0)
        i += 1
    display = (outer.replace("Controller", "") if outer and outer != simple else "") + simple
    return path, m.group(1), src[start:i - 1], display


def _v2_split_type(t):
    """'List<Map<String, Object>>' -> ('List', ['Map<String, Object>'])."""
    t = t.strip()
    if t.endswith("[]"):
        return "[]", [t[:-2]]
    if "<" not in t:
        return t, []
    return t[:t.index("<")].strip(), _v2_param_list(t[t.index("<") + 1:t.rindex(">")])


def _v2_type_schema(t, src_path, owner, strict=False):
    raw, args = _v2_split_type(_v2_re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", t))
    simple = raw.split(".")[-1] if raw.startswith(("java.", "javax.")) else raw
    if simple in _V2_SCALAR:
        return typ(_V2_SCALAR[simple])
    if simple in _V2_STRINGS:
        return {"type": "string", "description": _V2_STRINGS[simple]}
    if simple == "DayOfWeek":
        return {"type": "string", "enum": list(_V2_DAYS)}
    if simple in ("List", "Set", "Collection", "SortedSet", "LinkedHashSet", "ArrayList", "[]"):
        return {"type": "array", "items": _v2_type_schema(args[0], src_path, owner, strict) if args else {}}
    if simple in ("Map", "LinkedHashMap", "TreeMap", "SortedMap", "JsonNode", "ObjectNode"):
        return {"type": "object", "additionalProperties": True}
    found = _v2_find_type(src_path, raw)
    assert found, "%s: cannot resolve request type %s from %s" % (owner, raw, src_path)
    path, kind, body, display = found
    if kind == "enum":
        consts = [_v2_re.match(r"\s*(\w+)", c).group(1) for c in _v2_param_list(body.split(";")[0]) if c.strip()]
        return {"type": "string", "enum": consts}
    return R(_v2_record_schema(path, body, display, owner, strict))


def _v2_record_schema(path, body, display, owner, strict=False):
    """Registers the record's component schema (once) and returns its name. strict: the record is bound by the
    server's Jackson 3 mapper as (part of) a @RequestBody, so its primitive members are required (SL-81)."""
    name = display
    if name in SCHEMAS:
        assert SCHEMAS[name].get("x-sangam-record") == _v2_os.path.relpath(path, _V2_SANGAM).replace(_v2_os.sep, "/"), \
            "%s: schema name %s is taken" % (owner, name)
        return name
    rel = _v2_os.path.relpath(path, _V2_SANGAM).replace(_v2_os.sep, "/")
    SCHEMAS[name] = {"type": "object", "x-sangam-record": rel}   # placeholder first: records may nest
    props, required = {}, []
    for comp in _v2_param_list(body):
        comp = _v2_re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", comp).strip()
        cm = _v2_re.match(r"(.+?)\s+(\w+)$", comp)
        assert cm, "%s: cannot read record component '%s' in %s" % (owner, comp, rel)
        props[cm.group(2)] = _v2_type_schema(cm.group(1), path, owner, strict)
        if strict and cm.group(1).strip() in _V2_PRIMITIVES:
            required.append(cm.group(2))
    creator = _v2_creator_params(path, body) if strict else None
    if creator is not None:
        required = [n for n, t in creator if t in _V2_PRIMITIVES]
    SCHEMAS[name] = {"type": "object",
                     "description": "Request record %s (%s), typed from its source." % (display, rel),
                     "properties": props, "additionalProperties": True, "x-sangam-record": rel}
    if required:
        SCHEMAS[name]["required"] = required
    return name


def _v2_creator_params(path, components):
    """[(json name, Java type)] of the @JsonCreator of the record whose component list is `components`, or None."""
    src = _v2_source(path)
    at = src.find(components)
    assert at >= 0, path
    block = _v2_block(src, at + len(components))
    cm = _v2_re.search(r"@JsonCreator(?:\([^)]*\))?\s*(?:public\s+|static\s+|private\s+|protected\s+)*"
                       r"[\w<>.]+\s*\w*\s*\(", block)
    if not cm:
        return None
    k, depth = cm.end(), 1
    while depth:
        depth += {"(": 1, ")": -1}.get(block[k], 0)
        k += 1
    out = []
    for prm in _v2_param_list(block[cm.end():k - 1]):
        jp = _v2_re.search(r"@JsonProperty\(\s*\"([^\"]+)\"\s*\)", prm)
        assert jp, "%s: a @JsonCreator parameter without @JsonProperty: %s" % (path, prm)
        t = _v2_re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", prm).strip()
        out.append((jp.group(1), _v2_re.match(r"(?:final\s+)?(.+?)\s+\w+$", t).group(1).strip()))
    return out


def _v2_body_schema(param, src_path, owner):
    """Schema of a @RequestBody parameter: a record reference, or an open object."""
    p = _v2_re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", param).strip()
    t = _v2_re.match(r"(?:final\s+)?(.+?)\s+\w+$", p).group(1)
    raw = _v2_split_type(t)[0]
    if raw in _V2_SCALAR or raw in _V2_STRINGS or raw in ("Map", "List", "Set", "JsonNode", "byte[]", "[]"):
        return _V2_OBJECT
    found = _v2_find_type(src_path, raw)
    if not found or found[1] != "record":
        return _V2_OBJECT
    return R(_v2_record_schema(found[0], found[2], found[3], owner, strict=True))


def record_body(java_file, record, required=True):
    """A JSON request body typed from the request record `record` (simple or Outer.Inner) declared in or imported by
    java_file (relative to sangam/), for route files written by hand (M-051)."""
    path = _v2_os.path.join(_V2_SANGAM, *java_file.split("/"))
    found = _v2_find_type(path, record)
    assert found and found[1] == "record", "%s: no record %s" % (java_file, record)
    name = _v2_record_schema(found[0], found[2], found[3], java_file + ":" + record, strict=True)
    return {"required": required, "content": {"application/json": {"schema": R(name)}}}


# ---------------------------------------------------------------- permissions, status, headers (W2-C 2026-11-12)
# The contract takes these from the controller source too: the permission a method checks (directly, or through a
# helper such as admin(ctx) that calls permissions.require), 201 when it answers created, and its @RequestHeader
# parameters. A method that checks nothing keeps the route file's read/write default.
_V2_SKIP_HEADERS = {"Idempotency-Key", "X-Sangam-Seat", "X-Sangam-Acting-For-Seat"}


def _v2_block(src, i):
    """Text of the brace block that starts at or after i."""
    j = src.index("{", i)
    k, depth = j + 1, 1
    while depth and k < len(src):
        depth += {"{": 1, "}": -1}.get(src[k], 0)
        k += 1
    return src[j:k]


def _v2_constant(src_path, src, ref):
    """The string value of a constant: NAME in src, or Type.NAME in Type's source."""
    text, name = src, ref
    if "." in ref:
        cls_, name = ref.rsplit(".", 1)
        f = _v2_class_file(src_path, cls_)
        if not f:
            return None
        text = open(f, encoding="utf-8").read()
    m = _v2_re.search(r"\b" + name + r'\s*=\s*"([^"]*)"', text)
    return m.group(1) if m else None


def _v2_guards(src_path, src):
    """{helper method name: permission} for private helpers (RequestContext ctx) that call x.require(ctx, PERM)."""
    out = {}
    for h in _v2_re.finditer(r"(?:private|protected|static)[\w<>\s.,?]*?\s(\w+)\(\s*RequestContext\s+\w+\s*\)\s*\{", src):
        req = _v2_re.search(r"\.require\(\s*\w+\s*,\s*([\w.]+)\s*\)", _v2_block(src, h.end() - 1))
        if req:
            value = _v2_constant(src_path, src, req.group(1))
            if value:
                out[h.group(1)] = value
    return out


def _v2_method_permission(src_path, src, body, guards):
    direct = _v2_re.search(r"\.require\(\s*\w+\s*,\s*([\w.]+)\s*\)", body)
    found = []
    if direct:
        v = _v2_constant(src_path, src, direct.group(1))
        if v:
            found.append((direct.start(), v))
    for name, perm in guards.items():
        hm = _v2_re.search(r"(?<![\w.])" + name + r"\(\s*\w+\s*\)", body)
        if hm:
            found.append((hm.start(), perm))
    return min(found)[1] if found else None


def _v2_header_param(src_path, src, p):
    an = _v2_re.search(r"@RequestHeader(\(([^)]*)\))?", p)
    a = an.group(2) or ""
    lit = _v2_re.search(r'(?:value|name)\s*=\s*"([^"]+)"', a) or _v2_re.match(r'\s*"([^"]+)"', a)
    if lit:
        name = lit.group(1)
    else:
        ref = _v2_re.search(r"(?:value|name)\s*=\s*([\w.]+)", a) or _v2_re.match(r"\s*([A-Za-z_][\w.]*)\s*$", a)
        name = _v2_constant(src_path, src, ref.group(1)) if ref else None
    if not name or name in _V2_SKIP_HEADERS:
        return None
    h = {"name": name, "in": "header", "schema": {"type": "string"}}
    if "required = false" not in a and "defaultValue" not in a:
        h["required"] = True
    if name == "If-Match":
        h["description"] = "Revision the change is based on (409 on mismatch)"
    return h


def _v2_spring_io_resource(ret, src):
    """True when the return type is a Spring core.io resource (a byte stream). S9: a substring test on "Resource"
    also matched domain types (sangam-resources' Resource, ResourceType, ResourcesPorts.Quote,
    ResourcesRestPort.EntryView) and declared their JSON answers application/octet-stream."""
    inner = ret.split("<")[-1].replace(">", "").strip()
    if inner.startswith("org.springframework.core.io."):
        return True
    if not _v2_re.fullmatch(r"\w*Resource", inner):
        return False
    return _v2_re.search(r"import\s+org\.springframework\.core\.io\.(?:\*|%s)\s*;" % inner, src) is not None


def v2_spring_routes(rel_java_dir, tag, opid_prefix, cmd_prefix, perm_read, perm_write, reqs, no_cmd=(),
                     public_paths=(), files=None):
    """Adds every @XxxMapping of the controllers in rel_java_dir (relative to sangam/). Returns the operation count."""
    base_dir = _v2_os.path.join(_V2_SANGAM, rel_java_dir)
    count = 0
    names = files or sorted(f for f in _v2_os.listdir(base_dir) if f.endswith("Controller.java"))
    for fname in names:
        src_path = _v2_os.path.join(base_dir, fname)
        src = open(src_path, encoding="utf-8").read()
        guards = _v2_guards(src_path, src)
        cls = fname[:-5]
        head = src.split(" class ")[0]
        bm = _v2_re.search(r'@RequestMapping\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"', head)
        base = bm.group(1) if bm else ""
        for m in _v2_re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping(\(([^)]*)\))?\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*'
                                 r'public\s+([\w<>\[\], ?.]+?)\s+(\w+)\s*\(', src):
            method = m.group(1).lower()
            args = m.group(3) or ""
            pm = _v2_re.search(r'"([^"]*)"', args)
            sub = pm.group(1) if pm else ""
            path = (base + sub).replace("/api/v1", "", 1) or "/"
            ret, jname = m.group(4), m.group(5)
            # the parameter list: from the "(" after the method name to the matching ")"
            i, depth, start = m.end(), 1, m.end()
            while depth and i < len(src):
                depth += {"(": 1, ")": -1}.get(src[i], 0)
                i += 1
            params, body, body_req, body_param = [], False, True, None
            for p in _v2_param_list(src[start:i - 1]):
                if "@RequestBody" in p:
                    body = True
                    body_req = "required = false" not in p
                    body_param = p
                elif "@RequestParam" in p:
                    an = _v2_re.search(r'@RequestParam(\(([^)]*)\))?', p)
                    a = an.group(2) or ""
                    nm = _v2_re.search(r'(?:value|name)\s*=\s*"([^"]+)"', a) or _v2_re.match(r'\s*"([^"]+)"', a)
                    toks = p.split()
                    pname = nm.group(1) if nm else toks[-1]
                    jt = toks[-2].split(".")[-1] if len(toks) >= 2 else "String"
                    required = "required = false" not in a and "defaultValue" not in a
                    params.append(q(pname, _V2_JTYPES.get(jt, "s"), required=required))
                elif "@RequestPart" in p:
                    body = "multipart"
                elif "@RequestHeader" in p:
                    hp = _v2_header_param(src_path, src, p)
                    if hp:
                        params.append(hp)
            mbody = _v2_block(src, i)
            lead = src[max(0, src.rfind("\n\n", 0, m.start())):m.start()]     # annotations above the mapping
            created = "@ResponseStatus(HttpStatus.CREATED)" in m.group(0) + lead or _v2_re.search(
                r"Lists\.created\(|ResponseEntity\.created\(|HttpStatus\.CREATED|\.status\(\s*201\s*\)", mbody) is not None
            mixed = created and _v2_re.search(r"\.ok\(|okRef\(|ResponseEntity\.status\(\s*200", mbody) is not None
            assert not mixed, "%s.%s answers both 200 and 201: declare it by hand" % (cls, jname)
            checked = _v2_method_permission(src_path, src, mbody, guards)
            for v in _v2_re.findall(r"\{(\w+)(?::[^}]*)?\}", path):
                params.insert(0, pp(v))
            path = _v2_re.sub(r"\{(\w+):[^}]*\}", r"{\1}", path)
            mutation = method != "get"
            cmd = None
            if mutation and jname not in no_cmd and path not in public_paths:
                cmd = f"{cmd_prefix}.{_v2_kebab(cls.replace('Controller', ''))}.{_v2_kebab(jname)}"
            if "List<" in ret and "ResponseEntity<Map" not in ret:
                resp = (200, {"application/json": {"schema": {"type": "array", "items": _V2_OBJECT}}})
            elif "Void" in ret or ret.strip() == "void":
                resp = (204, None)
            elif "byte[]" in ret or "StreamingResponseBody" in ret or _v2_spring_io_resource(ret, src):
                resp = (200, {"application/octet-stream": {"schema": {"type": "string", "contentMediaType": "application/octet-stream"}}})
            else:
                resp = (200, {"application/json": {"schema": _V2_OBJECT}})
            if created and resp[0] == 200:
                resp = (201, resp[1])
            if body == "multipart":
                bspec = {"required": True, "content": {"multipart/form-data": {"schema": _V2_OBJECT}}}
            elif body:
                bspec = {"required": body_req, "content": {"application/json": {
                    "schema": _v2_body_schema(body_param, _v2_os.path.join(base_dir, fname), cls + "." + jname)}}}
            else:
                bspec = None
            opid = opid_prefix + cls.replace("Controller", "") + jname[0].upper() + jname[1:]
            if body is True:
                # a Map body (or a list of maps) is typed from the keys the service reads (_v2_bodykeys.py, M-051)
                bp = _v2_re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", body_param).replace("final ", "").strip()
                bm_ = _v2_re.match(r"(.+?)\s+(\w+)$", bp)
                # A List<Map> body is a JSON array of such objects (owner decision 2026-11-12: the 0.1.0 contract and
                # baseline said object for PartyController.saveIdentifierTypes; corrected, see the corrections section
                # of sangam-api-deprecations.yaml).
                if _v2_re.match(r"(?:java\.util\.)?Map\s*<", bm_.group(1)):
                    map_body(opid, rel_java_dir + "/" + fname, cls + "." + jname, bm_.group(2))
                elif _v2_re.match(r"(?:java\.util\.)?List\s*<\s*Map\s*<", bm_.group(1)):
                    map_body(opid, rel_java_dir + "/" + fname, cls + "." + jname, bm_.group(2), array=True)
            op(method, path, opid, tag, _v2_camel_words(jname).capitalize(),
               f"{cls}.{jname} ({rel_java_dir.split('/')[0]}).", reqs, params=params, body=bspec, resp=resp, cmd=cmd,
               perm=None if path in public_paths else (checked or (perm_read if not mutation else perm_write)))
            if path in public_paths:
                public(path, method)
            count += 1
    return count
