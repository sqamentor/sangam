# SPDX-License-Identifier: Apache-2.0
"""Generates the typed surface of the five Tier-1 SDKs from the contract (M-020; R16 "one canonical schema generates
the types"; ADR-D-4).

Input:  sangam/sangam-rest/api/sangam-api.yaml (written by gen_openapi.py; never edited here).
Output: one generated tree per SDK, owned entirely by this script (stale files in it are removed):
  java        sdks/java/src/main/java/org/sangam/sdk/generated/**     records + one API class per tag + SangamApis
  typescript  sdks/typescript/src/generated/**                        interfaces + API classes + SangamApis
  python      sdks/python/src/sangam_sdk/generated/**                 TypedDicts + API classes + SangamApis
  dotnet      sdks/dotnet/src/Sangam.Sdk/Generated/**                 records + API classes + SangamApis
  php         sdks/php/src/Generated/**                               readonly classes + API classes + SangamApis
  manifest    sdks/codegen/surface-manifest.json                      operationId -> method name in every SDK
The hand-written clients, models and worker runtimes are not touched; SangamClient.api() exposes the generated
surface on the same transport.

  python sdks/codegen/generate_sdk_surface.py            # write (PyYAML needed, as for gen_openapi.py)
  python sdks/codegen/generate_sdk_surface.py --check    # CI: exit 1 when any generated file is stale
  python sdks/codegen/generate_sdk_surface.py --lang=java  # one SDK only

The comparison ignores CR/LF differences, so a Windows checkout with autocrlf does not report drift.
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import yaml  # noqa: E402  (PyYAML, as gen_openapi.py)

import api_groups  # noqa: E402
import contract_ir  # noqa: E402
import emit_dotnet  # noqa: E402
import emit_java  # noqa: E402
import emit_php  # noqa: E402
import emit_python  # noqa: E402
import emit_typescript  # noqa: E402

SDKS = os.path.abspath(os.path.join(HERE, ".."))
CONTRACT = os.path.abspath(os.path.join(SDKS, "..", "sangam-rest", "api", "sangam-api.yaml"))
ROOTS = {
    "java": emit_java.BASE,
    "typescript": emit_typescript.BASE,
    "python": emit_python.BASE,
    "dotnet": emit_dotnet.BASE,
    "php": emit_php.BASE,
}
MANIFEST = "codegen/surface-manifest.json"


def load() -> contract_ir.Contract:
    with open(CONTRACT, encoding="utf-8") as fh:
        return contract_ir.build(yaml.safe_load(fh))


EMITTERS = {"java": emit_java, "typescript": emit_typescript, "python": emit_python, "dotnet": emit_dotnet,
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
           "models": list(c.models), "surface": rows,
           # operations the contract marks x-sangam-sdk: false (contract_ir.SDK_EXTENSION): no method in any SDK
           "notForSdk": c.not_for_sdk}
    return json.dumps(doc, indent=1, ensure_ascii=False) + "\n"


def render(c: contract_ir.Contract, langs: list[str]) -> dict[str, str]:
    out: dict[str, str] = {}
    groups = groups_of(c)
    for lang in langs:
        out.update(EMITTERS[lang].emit(c, groups))
    out[MANIFEST] = manifest(c, groups)
    return out


def existing(root: str) -> set[str]:
    found = set()
    base = os.path.join(SDKS, root)
    for d, dirs, files in os.walk(base):
        dirs[:] = [x for x in dirs if x != "__pycache__"]  # interpreter caches are not generated output
        for f in files:
            found.add(os.path.relpath(os.path.join(d, f), SDKS).replace(os.sep, "/"))
    return found


def norm(b: bytes) -> bytes:
    return b.replace(b"\r\n", b"\n")


def main(argv: list[str]) -> int:
    check = "--check" in argv
    langs = [a.split("=", 1)[1] for a in argv if a.startswith("--lang=")] or list(ROOTS)
    c = load()
    files = render(c, langs)
    stale, removed = [], []
    for rel, text in sorted(files.items()):
        path = os.path.join(SDKS, rel)
        data = text.encode("utf-8")
        old = open(path, "rb").read() if os.path.exists(path) else None
        if old is None or norm(old) != data:
            stale.append(rel)
            if not check:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "wb") as fh:
                    fh.write(data)
    for lang in langs:
        for rel in sorted(existing(ROOTS[lang]) - set(files)):
            removed.append(rel)
            if not check:
                os.remove(os.path.join(SDKS, rel))
    if check:
        if stale or removed:
            print("generated SDK surface is stale (run sdks/codegen/generate_sdk_surface.py):")
            for rel in stale:
                print("  changed:", rel)
            for rel in removed:
                print("  not generated any more:", rel)
            return 1
        print(f"SDK surface up to date: {len(c.operations)} operations, {c.path_count} paths, {len(c.models)} schemas,"
              f" {len(files)} files ({', '.join(langs)})")
        return 0
    print(f"{len(c.operations)} operations, {c.path_count} paths, {len(c.models)} schemas: {len(stale)} file(s) written,"
          f" {len(removed)} removed ({', '.join(langs)})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
