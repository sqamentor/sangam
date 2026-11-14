p = "generate_sdk_surface.py"
s = open(p, encoding="utf-8").read()


def rep(a, b):
    global s
    if s.count(a) != 1:
        raise SystemExit(f"{s.count(a)} matches: {a[:70]!r}")
    s = s.replace(a, b)


rep("import contract_ir  # noqa: E402\n", "import api_groups  # noqa: E402\nimport contract_ir  # noqa: E402\n")
rep('''def manifest(c: contract_ir.Contract) -> str:
    rows = []
    for o in c.operations:
        rows.append({
            "operationId": o.op_id, "method": o.method, "path": o.path, "tag": o.tag,
            "java": f"{contract_ir.pascal(o.tag)}Api#{emit_java.ident(o.op_id)}",
            "typescript": f"{emit_typescript.member(o.tag)}.{emit_typescript.member(o.op_id)}",
            "python": f"{emit_python.ident(o.tag)}.{emit_python.ident(o.op_id)}",
            "dotnet": f"{contract_ir.pascal(o.tag)}Api#{emit_dotnet.method_name(o.op_id)}",
            "php": f"{contract_ir.pascal(o.tag)}Api#{emit_php.ident(o.op_id)}",
        })
    doc = {"contractVersion": c.version, "paths": c.path_count, "operations": len(c.operations),
           "schemas": len(c.models), "tags": c.tags, "models": list(c.models), "surface": rows}''',
    '''EMITTERS = {"java": emit_java, "typescript": emit_typescript, "python": emit_python, "dotnet": emit_dotnet,
            "php": emit_php}


def groups_of(c: contract_ir.Contract) -> list[api_groups.Group]:
    """One plan for every SDK: a class is split until it fits the 800-line limit in ALL five languages (X2-04)."""
    def measure(cc: contract_ir.Contract, g: api_groups.Group) -> int:
        return max(api_groups.line_count(e.class_text(cc, g)) for e in EMITTERS.values())
    return api_groups.plan(c, measure)


def manifest(c: contract_ir.Contract, groups: list[api_groups.Group]) -> str:
    owner = {o.op_id: g for g in groups for o in g.ops}
    rows = []
    for o in c.operations:
        g = owner[o.op_id]
        rows.append({
            "operationId": o.op_id, "method": o.method, "path": o.path, "tag": o.tag, "apiClass": g.cls,
            "java": f"{g.cls}#{emit_java.ident(o.op_id)}",
            "typescript": f"{g.member}.{emit_typescript.member(o.op_id)}",
            "python": f"{emit_python.ident(g.stem)}.{emit_python.ident(o.op_id)}",
            "dotnet": f"{g.cls}#{emit_dotnet.method_name(o.op_id)}",
            "php": f"{g.cls}#{emit_php.ident(o.op_id)}",
        })
    classes = [{"class": g.cls, "tag": g.tag, "pathPrefix": "/".join(g.prefix), "operations": len(g.ops),
                "maxLines": max(api_groups.line_count(e.class_text(c, g)) for e in EMITTERS.values())}
               for g in groups]
    doc = {"contractVersion": c.version, "paths": c.path_count, "operations": len(c.operations),
           "schemas": len(c.models), "tags": c.tags, "lineLimit": api_groups.LIMIT, "apiClasses": classes,
           "models": list(c.models), "surface": rows}''')
rep('''    out: dict[str, str] = {}
    emitters = {"java": emit_java, "typescript": emit_typescript, "python": emit_python, "dotnet": emit_dotnet,
                "php": emit_php}
    for lang in langs:
        out.update(emitters[lang].emit(c))
    out[MANIFEST] = manifest(c)''', '''    out: dict[str, str] = {}
    groups = groups_of(c)
    for lang in langs:
        out.update(EMITTERS[lang].emit(c, groups))
    out[MANIFEST] = manifest(c, groups)''')
open(p, "w", encoding="utf-8", newline="\n").write(s)
print("patched", p)
