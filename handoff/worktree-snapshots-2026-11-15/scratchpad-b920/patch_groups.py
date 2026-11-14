"""Refactors the five emitters from one class per tag to the api_groups plan (run from sdks/codegen)."""
import re


def patch(path, pairs):
    s = open(path, encoding="utf-8").read()
    for a, b in pairs:
        if s.count(a) != 1:
            raise SystemExit(f"{path}: {s.count(a)} matches for {a[:80]!r}")
        s = s.replace(a, b)
    open(path, "w", encoding="utf-8", newline="\n").write(s)
    print("patched", path)


def cut(path, start, end_marker, replacement):
    s = open(path, encoding="utf-8").read()
    a = s.index(start)
    b = s.index(end_marker, a) + len(end_marker)
    s = s[:a] + replacement + s[b:]
    open(path, "w", encoding="utf-8", newline="\n").write(s)


# ------------------------------------------------------------------ Java
patch("emit_java.py", [
    ('def api_file(c: Contract, tag: str, ops: list[Operation]) -> str:\n    cls = pascal(tag) + "Api"\n',
     'def api_file(c: Contract, g: Group) -> str:\n    cls, ops = g.cls, g.ops\n'),
    ('        f" * Typed operations of the contract tag {{@code {jdoc(tag)}}} ({len(ops)} operations), generated from",',
     '        f" * Typed operations of {{@code {jdoc(g.label)}}} ({len(ops)} operations), generated from",'),
    ('def facade(c: Contract, by_tag: dict[str, list[Operation]]) -> str:\n    fields, inits, accessors = [], [], []\n'
     '    for tag in c.tags:\n        cls = pascal(tag) + "Api"\n        f = ident(tag)\n',
     'def facade(c: Contract, groups: list[Group]) -> str:\n    fields, inits, accessors = [], [], []\n'
     '    for g in groups:\n        cls = g.cls\n        f = ident(g.stem)\n'),
    ('        accessors.append(f"    /** Tag {{@code {jdoc(tag)}}}: {len(by_tag[tag])} operations. */\\n"',
     '        accessors.append(f"    /** {{@code {jdoc(g.label)}}}: {len(g.ops)} operations. */\\n"'),
    ('    imports = [f"import {PKG}.api.{pascal(t)}Api;" for t in c.tags]',
     '    imports = [f"import {PKG}.api.{g.cls};" for g in groups]'),
    ('        " * one accessor per contract tag. Obtain it with',
     '        " * one accessor per API class (a tag, or a sub-path of a large tag: M-098 class-size gate). Obtain it with'),
    ('    by_tag: dict[str, list[Operation]] = {t: [] for t in c.tags}\n    for o in c.operations:\n'
     '        by_tag[o.tag].append(o)\n    for tag, ops in by_tag.items():\n'
     '        files[f"{BASE}/api/{pascal(tag)}Api.java"] = api_file(c, tag, ops)\n',
     '    for g in groups:\n        files[f"{BASE}/api/{g.cls}.java"] = api_file(c, g)\n'),
    ('    files[f"{BASE}/SangamApis.java"] = facade(c, by_tag)', '    files[f"{BASE}/SangamApis.java"] = facade(c, groups)'),
    ('def emit(c: Contract) -> dict[str, str]:',
     'def class_text(c: Contract, g: Group) -> str:\n    return api_file(c, g)\n\n\ndef emit(c: Contract, groups: list[Group]) -> dict[str, str]:'),
    ('from contract_ir import ', 'from api_groups import Group\nfrom contract_ir import '),
])

# ------------------------------------------------------------------ TypeScript
s = open("emit_typescript.py", encoding="utf-8").read()
a = s.index("def api(c: Contract, by_tag")
b = s.index("def emit(c: Contract)")
api_ts = '''def class_text(c: Contract, g: Group) -> str:
    out = doc_block(f"Typed operations of `{g.label}` ({len(g.ops)} operations).", "")
    out.append(f"export class {g.cls} {{")
    out.append("  constructor(private readonly calls: GeneratedCalls) {}")
    out.append("")
    out.append("\\n\\n".join(method(o) for o in g.ops))
    out.append("}")
    return "\\n".join(out) + "\\n"


def api(c: Contract, groups: list[Group]) -> str:
    out = [header(c.version), SUPPORT]
    for g in groups:
        out.append(class_text(c, g))
    out += doc_block(f"The generated typed surface of sangam-api.yaml {c.version}: {len(c.operations)} operations on "
                     f"{c.path_count} paths, one member per API class (a tag, or a sub-path of a large tag). "
                     "Use `client.api`.", "")
    out.append("export class SangamApis {")
    for g in groups:
        out.append(f"  readonly {g.member}: {g.cls};")
    out.append("")
    out.append("  constructor(http: HttpTransport) {")
    out.append("    const calls = new GeneratedCalls(http);")
    for g in groups:
        out.append(f"    this.{g.member} = new {g.cls}(calls);")
    out.append("  }")
    out.append("}")
    return "\\n".join(out) + "\\n"


def emit(c: Contract, groups: list[Group]) -> dict[str, str]:
    index = (header(c.version) + "export type * from './models.js';\\n"
             "export { SangamApis, GeneratedCalls, type RequestOptions } from './api.js';\\n"
             + "".join(f"export {{ {g.cls} }} from './api.js';\\n" for g in groups))
    return {f"{BASE}/models.ts": models(c), f"{BASE}/api.ts": api(c, groups), f"{BASE}/index.ts": index}
'''
s = s[:a] + api_ts
s = s.replace("from contract_ir import ", "from api_groups import Group\nfrom contract_ir import ", 1)
open("emit_typescript.py", "w", encoding="utf-8", newline="\n").write(s)
print("patched emit_typescript.py")

# ------------------------------------------------------------------ Python
s = open("emit_python.py", encoding="utf-8").read()
a = s.index("def api(c: Contract, by_tag")
api_py = '''def class_text(c: Contract, g: Group) -> str:
    out = ["", f"class {g.cls}:", f\'    """``{pdoc(g.label)}`` ({len(g.ops)} operations)."""\', "",
           "    def __init__(self, calls: _Calls) -> None:", "        self._c = calls"]
    for o in g.ops:
        out.append("")
        out.append(method(o))
    out.append("")
    return "\\n".join(out) + "\\n"


def api(c: Contract, groups: list[Group]) -> str:
    out = [header(c.version).rstrip("\\n"),
           \'"""Typed operations of sangam-api.yaml, one class per contract tag (large tags split by sub-path);\',
           \'``SangamClient.api`` returns the SangamApis facade. Bodies and results are the TypedDicts of\',
           \':mod:`sangam_sdk.generated.models`."""\',
           "from __future__ import annotations", SUPPORT]
    for g in groups:
        out.append(class_text(c, g).rstrip("\\n"))
    out.append("")
    out.append("")
    out.append("class SangamApis:")
    out.append(f\'    """The generated typed surface of sangam-api.yaml {c.version}: {len(c.operations)} operations on \'
               f\'{c.path_count} paths."""\')
    out.append("")
    out.append("    def __init__(self, http: HttpTransport) -> None:")
    out.append("        calls = _Calls(http)")
    for g in groups:
        out.append(f"        self.{ident(g.stem)} = {g.cls}(calls)")
    return "\\n".join(out) + "\\n"


def emit(c: Contract, groups: list[Group]) -> dict[str, str]:
    init = (header(c.version) + \'"""Generated typed surface of sangam-api.yaml (M-020). Use ``SangamClient.api``."""\\n\'
            "from . import models\\n"
            "from .api import RequestOptions, SangamApis\\n\\n"
            "__all__ = [\\"models\\", \\"RequestOptions\\", \\"SangamApis\\"]\\n")
    return {f"{BASE}/__init__.py": init, f"{BASE}/models.py": models(c), f"{BASE}/api.py": api(c, groups)}
'''
s = s[:a] + api_py
s = s.replace("from contract_ir import ", "from api_groups import Group\nfrom contract_ir import ", 1)
open("emit_python.py", "w", encoding="utf-8", newline="\n").write(s)
print("patched emit_python.py")

# ------------------------------------------------------------------ .NET
s = open("emit_dotnet.py", encoding="utf-8").read()
a = s.index("def apis(c: Contract, by_tag")
api_cs = '''def class_text(c: Contract, g: Group) -> str:
    out = summary(f"Typed operations of {g.label} ({len(g.ops)} operations).", "")
    out.append(f"public sealed class {g.cls}")
    out.append("{")
    out.append("    private readonly GeneratedCalls _calls;")
    out.append("")
    out.append(f"    public {g.cls}(GeneratedCalls calls) => _calls = calls;")
    out.append("")
    out.append("\\n\\n".join(method(o) for o in g.ops))
    out.append("}")
    return "\\n".join(out) + "\\n"


def apis(c: Contract, groups: list[Group]) -> str:
    out = [header(c.version), SUPPORT]
    for g in groups:
        out.append(class_text(c, g))
    out += summary(f"The generated typed surface of sangam-api.yaml {c.version}: {len(c.operations)} operations on "
                   f"{c.path_count} paths, one property per API class (a tag, or a sub-path of a large tag). "
                   "Use SangamClient.Api.", "")
    out.append("public sealed class SangamApis")
    out.append("{")
    out.append("    public SangamApis(HttpTransport http)")
    out.append("    {")
    out.append("        var calls = new GeneratedCalls(http);")
    for g in groups:
        out.append(f"        {g.stem} = new {g.cls}(calls);")
    out.append("    }")
    out.append("")
    for g in groups:
        out.append(f"    public {g.cls} {g.stem} {{ get; }}")
    out.append("}")
    return "\\n".join(out) + "\\n"


def emit(c: Contract, groups: list[Group]) -> dict[str, str]:
    return {f"{BASE}/Models.g.cs": models(c), f"{BASE}/Apis.g.cs": apis(c, groups)}
'''
s = s[:a] + api_cs
s = s.replace("from contract_ir import ", "from api_groups import Group\nfrom contract_ir import ", 1)
open("emit_dotnet.py", "w", encoding="utf-8", newline="\n").write(s)
print("patched emit_dotnet.py")

# ------------------------------------------------------------------ PHP
patch("emit_php.py", [
    ('def api_file(c: Contract, tag: str, ops: list[Operation]) -> str:\n    cls = pascal(tag) + "Api"\n',
     'def api_file(c: Contract, g: Group) -> str:\n    cls, ops = g.cls, g.ops\n'),
    ('           f"/** Typed operations of the contract tag {pdoc(tag)} ({len(ops)} operations). */",',
     '           f"/** Typed operations of {pdoc(g.label)} ({len(ops)} operations). */",'),
    ('def facade(c: Contract, by_tag: dict[str, list[Operation]]) -> str:', 'def facade(c: Contract, groups: list[Group]) -> str:'),
    ('    out += [f"use {NS}\\\\Api\\\\{pascal(t)}Api;" for t in c.tags]', '    out += [f"use {NS}\\\\Api\\\\{g.cls};" for g in groups]'),
    ('            " * one property per contract tag. Use SangamClient::api().", " */",',
     '            " * one property per API class (a tag, or a sub-path of a large tag). Use SangamClient::api().", " */",'),
    ('    by_tag: dict[str, list[Operation]] = {t: [] for t in c.tags}\n    for o in c.operations:\n'
     '        by_tag[o.tag].append(o)\n    for tag, ops in by_tag.items():\n'
     '        files[f"{BASE}/Api/{pascal(tag)}Api.php"] = api_file(c, tag, ops)\n',
     '    for g in groups:\n        files[f"{BASE}/Api/{g.cls}.php"] = api_file(c, g)\n'),
    ('    files[f"{BASE}/SangamApis.php"] = facade(c, by_tag)', '    files[f"{BASE}/SangamApis.php"] = facade(c, groups)'),
    ('def emit(c: Contract) -> dict[str, str]:',
     'def class_text(c: Contract, g: Group) -> str:\n    return api_file(c, g)\n\n\ndef emit(c: Contract, groups: list[Group]) -> dict[str, str]:'),
    ('from contract_ir import ', 'from api_groups import Group\nfrom contract_ir import '),
])
s = open("emit_php.py", encoding="utf-8").read()
a = s.index("    for t in c.tags:\n        out.append(f\"    public readonly {pascal(t)}Api")
seg_end = s.index("    out += [\"    }\", \"}\"]")
s = s[:a] + '''    for g in groups:
        out.append(f"    public readonly {g.cls} ${g.member};")
    out += ["", "    public function __construct(HttpTransport $http)", "    {", "        $calls = new Calls($http);"]
    for g in groups:
        out.append(f"        $this->{g.member} = new {g.cls}($calls);")
''' + s[seg_end:]
open("emit_php.py", "w", encoding="utf-8", newline="\n").write(s)
print("patched emit_php.py facade")
