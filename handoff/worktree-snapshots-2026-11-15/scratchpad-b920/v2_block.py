

# ---------------------------------------------------------------- request records (M-051)
# A @RequestBody whose Java type is a record gets a typed schema derived from the record's source: one property per
# component, in declaration order. Typing is additive (0.1.0): no `required`, and additionalProperties stays true
# (Jackson ignores unknown members, and SL-40 custom fields travel as extra members). Map, String and other bodies stay
# open objects. RequestBodyContractTest checks every record body of the contract against the compiled record.
_V2_SCALAR = {"String": "s", "char": "s", "Character": "s", "int": "int", "Integer": "int", "short": "int",
              "Short": "int", "long": "long", "Long": "long", "double": "num", "Double": "num", "float": "num",
              "Float": "num", "boolean": "bool", "Boolean": "bool", "Instant": "dt", "OffsetDateTime": "dt",
              "ZonedDateTime": "dt", "LocalDate": "date", "Object": "any"}
# Jackson writes and reads these as JSON strings (decimals as strings is the SD-398 money convention)
_V2_STRINGS = {"BigDecimal": "Decimal number as a string", "BigInteger": "Integer as a string", "UUID": "UUID",
               "LocalTime": "Local time (HH:mm[:ss])", "LocalDateTime": "Local date-time (ISO-8601, no offset)",
               "Duration": "ISO-8601 duration"}
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


def _v2_type_schema(t, src_path, owner):
    raw, args = _v2_split_type(_v2_re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", t))
    simple = raw.split(".")[-1] if raw.startswith(("java.", "javax.")) else raw
    if simple in _V2_SCALAR:
        return typ(_V2_SCALAR[simple])
    if simple in _V2_STRINGS:
        return {"type": "string", "description": _V2_STRINGS[simple]}
    if simple == "DayOfWeek":
        return {"type": "string", "enum": list(_V2_DAYS)}
    if simple in ("List", "Set", "Collection", "SortedSet", "LinkedHashSet", "ArrayList", "[]"):
        return {"type": "array", "items": _v2_type_schema(args[0], src_path, owner) if args else {}}
    if simple in ("Map", "LinkedHashMap", "TreeMap", "SortedMap", "JsonNode", "ObjectNode"):
        return {"type": "object", "additionalProperties": True}
    found = _v2_find_type(src_path, raw)
    assert found, "%s: cannot resolve request type %s from %s" % (owner, raw, src_path)
    path, kind, body, display = found
    if kind == "enum":
        consts = [_v2_re.match(r"\s*(\w+)", c).group(1) for c in _v2_param_list(body.split(";")[0]) if c.strip()]
        return {"type": "string", "enum": consts}
    return R(_v2_record_schema(path, body, display, owner))


def _v2_record_schema(path, body, display, owner):
    """Registers the record's component schema (once) and returns its name."""
    name = display
    if name in SCHEMAS:
        assert SCHEMAS[name].get("x-sangam-record") == _v2_os.path.relpath(path, _V2_SANGAM).replace(_v2_os.sep, "/"), \
            "%s: schema name %s is taken" % (owner, name)
        return name
    rel = _v2_os.path.relpath(path, _V2_SANGAM).replace(_v2_os.sep, "/")
    SCHEMAS[name] = {"type": "object", "x-sangam-record": rel}   # placeholder first: records may nest
    props = {}
    for comp in _v2_param_list(body):
        comp = _v2_re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", comp).strip()
        cm = _v2_re.match(r"(.+?)\s+(\w+)$", comp)
        assert cm, "%s: cannot read record component '%s' in %s" % (owner, comp, rel)
        props[cm.group(2)] = _v2_type_schema(cm.group(1), path, owner)
    SCHEMAS[name] = {"type": "object",
                     "description": "Request record %s (%s), typed from its source." % (display, rel),
                     "properties": props, "additionalProperties": True, "x-sangam-record": rel}
    return name


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
    return R(_v2_record_schema(found[0], found[2], found[3], owner))
