# SPDX-License-Identifier: Apache-2.0
# Helper for route files whose controllers are Spring MVC classes: derives one OpenAPI operation per mapping from the
# controller source, so the contract and the controllers cannot drift (parity by construction). Runs inside
# gen_openapi.py (op, pp, q are in scope). Used by geo.py, registry.py and resources.py.
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


def v2_spring_routes(rel_java_dir, tag, opid_prefix, cmd_prefix, perm_read, perm_write, reqs, no_cmd=(),
                     public_paths=(), files=None):
    """Adds every @XxxMapping of the controllers in rel_java_dir (relative to sangam/). Returns the operation count."""
    base_dir = _v2_os.path.join(_V2_SANGAM, rel_java_dir)
    count = 0
    names = files or sorted(f for f in _v2_os.listdir(base_dir) if f.endswith("Controller.java"))
    for fname in names:
        src = open(_v2_os.path.join(base_dir, fname), encoding="utf-8").read()
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
            params, body, body_req = [], False, True
            for p in _v2_param_list(src[start:i - 1]):
                if "@RequestBody" in p:
                    body = True
                    body_req = "required = false" not in p
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
            elif "byte[]" in ret or "StreamingResponseBody" in ret or "Resource" in ret.split("<")[-1]:
                resp = (200, {"application/octet-stream": {"schema": {"type": "string", "contentMediaType": "application/octet-stream"}}})
            else:
                resp = (200, {"application/json": {"schema": _V2_OBJECT}})
            if body == "multipart":
                bspec = {"required": True, "content": {"multipart/form-data": {"schema": _V2_OBJECT}}}
            elif body:
                bspec = {"required": body_req, "content": {"application/json": {"schema": _V2_OBJECT}}}
            else:
                bspec = None
            opid = opid_prefix + cls.replace("Controller", "") + jname[0].upper() + jname[1:]
            op(method, path, opid, tag, _v2_camel_words(jname).capitalize(),
               f"{cls}.{jname} ({rel_java_dir.split('/')[0]}).", reqs, params=params, body=bspec, resp=resp, cmd=cmd,
               perm=None if path in public_paths else (perm_read if not mutation else perm_write))
            if path in public_paths:
                public(path, method)
            count += 1
    return count
