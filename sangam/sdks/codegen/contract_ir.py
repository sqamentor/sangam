# SPDX-License-Identifier: Apache-2.0
"""Language-neutral model of sangam-rest/api/sangam-api.yaml for the SDK surface generator (M-020, R16).

Only what the contract states is modelled: every component schema, every inline object schema (hoisted under a
deterministic name), and every operation with its parameters, request body and success response. Nothing is invented:
a name, field or type that is not in the contract never appears in the IR.

Type mapping (the same for every language):
  string                      -> string;  format date-time -> datetime; date -> date; binary/contentMediaType -> binary
  integer int32 / int64 / none -> int32 / int64 / int64
  number double / other       -> double / decimal (money is never a binary float, R33)
  boolean                     -> boolean
  array                       -> array(item)
  object with properties      -> a model (component or hoisted)
  object without properties   -> map(value)  (additionalProperties schema, or any)
  allOf [one $ref]            -> that ref;  anyOf of string branches -> string;  other anyOf / no type -> any
  a component that is not an object with properties (MoneyAmount, VariableMap, I18nBundles) is an alias: its uses are
  replaced by the aliased type.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any, Optional

COMMON_HEADERS = {"X-Sangam-Seat": "seat", "X-Sangam-Acting-For-Seat": "actingForSeat",
                  "Idempotency-Key": "idempotencyKey"}
JSON_MEDIA = ("application/json", "application/cloudevents+json", "application/fhir+json")


@dataclass(frozen=True)
class T:
    """A wire type. kind: ref|string|datetime|date|binary|int32|int64|double|decimal|boolean|any|array|map."""
    kind: str
    name: str = ""                      # ref target
    item: Optional["T"] = None          # array item / map value
    enum: tuple[str, ...] = ()          # allowed string values (documentation and literal types)

    def refs(self) -> set[str]:
        if self.kind == "ref":
            return {self.name}
        if self.item is not None:
            return self.item.refs()
        return set()


ANY = T("any")


@dataclass
class Prop:
    wire: str
    type: T
    required: bool
    description: str = ""


@dataclass
class Model:
    name: str
    props: list[Prop]
    extras: Optional[T]                 # additionalProperties next to typed properties (None = not declared)
    description: str = ""
    origin: str = ""                    # "#/components/schemas/X" or the JSON pointer of the hoisted inline schema


@dataclass
class Param:
    wire: str
    location: str                       # path|query|header
    type: T
    required: bool
    description: str = ""


@dataclass
class Body:
    kind: str                           # json|raw|multipart
    media: str
    required: bool
    type: T = ANY                       # json
    parts: list[Prop] = field(default_factory=list)   # multipart


@dataclass
class Response:
    kind: str                           # none|json|raw|sse
    status: str
    media: str = ""
    type: T = ANY
    paged: bool = False                 # bare JSON array with page/size query parameters and A-16 count headers


@dataclass
class Operation:
    op_id: str
    tag: str
    method: str
    path: str
    summary: str
    description: str
    path_params: list[Param]
    query_params: list[Param]
    header_params: list[Param]          # contract headers other than COMMON_HEADERS
    common_headers: list[str]           # the COMMON_HEADERS this operation declares
    body: Optional[Body]
    response: Response
    fenced: bool
    idempotent: bool
    permission: str
    deprecated: bool

    @property
    def required_params(self) -> list[Param]:
        return [p for p in self.query_params + self.header_params if p.required]

    @property
    def optional_params(self) -> list[Param]:
        return [p for p in self.query_params + self.header_params if not p.required]


@dataclass
class Contract:
    version: str
    models: dict[str, Model]
    operations: list[Operation]
    tags: list[str]
    path_count: int


def pascal(s: str) -> str:
    parts = re.split(r"[^A-Za-z0-9]+", s)
    out = "".join(p[:1].upper() + p[1:] for p in parts if p)
    return out if out and not out[0].isdigit() else "N" + out


def camel(s: str) -> str:
    p = pascal(s)
    return p[:1].lower() + p[1:]


def snake(s: str) -> str:
    s = re.sub(r"[^A-Za-z0-9]+", "_", s)
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.strip("_").lower()


class _Builder:
    def __init__(self, doc: dict[str, Any]) -> None:
        self.doc = doc
        self.schemas: dict[str, Any] = doc["components"]["schemas"]
        self.models: dict[str, Model] = {}
        self.aliases: dict[str, T] = {}
        self._resolving: set[str] = set()

    # ---------------------------------------------------------------- schemas

    @staticmethod
    def _is_model(s: dict[str, Any]) -> bool:
        return isinstance(s, dict) and bool(s.get("properties"))

    def component(self, name: str) -> T:
        if name not in self.schemas:
            raise ValueError(f"$ref to unknown schema {name}")
        s = self.schemas[name]
        if self._is_model(s):
            if name not in self.models and name not in self._resolving:
                self._resolving.add(name)
                self.models[name] = self._model(name, s, f"#/components/schemas/{name}")
                self._resolving.discard(name)
            return T("ref", name)
        if name not in self.aliases:
            self.aliases[name] = self.type_of(s, name)
        return self.aliases[name]

    def _model(self, name: str, s: dict[str, Any], origin: str) -> Model:
        req = set(s.get("required") or [])
        props = []
        for wire, ps in (s.get("properties") or {}).items():
            props.append(Prop(wire, self.type_of(ps, name + pascal(wire)), wire in req, _desc(ps)))
        extras = None
        ap = s.get("additionalProperties")
        if ap is True or isinstance(ap, dict):
            extras = ANY if ap is True or ap == {} else self.type_of(ap, name + "Value")
        return Model(name, props, extras, _desc(s), origin)

    def hoist(self, hint: str, s: dict[str, Any], origin: str) -> T:
        name = hint
        n = 2
        while name in self.schemas or (name in self.models and self.models[name].origin != origin):
            name = f"{hint}{n}"
            n += 1
        if name not in self.models:
            self.models[name] = Model(name, [], None, "", origin)  # placeholder against recursion
            self.models[name] = self._model(name, s, origin)
        return T("ref", name)

    def type_of(self, s: Any, hint: str) -> T:
        if not isinstance(s, dict) or not s:
            return ANY
        if "$ref" in s:
            return self.component(s["$ref"].split("/")[-1])
        if "allOf" in s:
            branches = s["allOf"]
            if len(branches) == 1:
                return self.type_of(branches[0], hint)
            return ANY
        for key in ("anyOf", "oneOf"):
            if key in s:
                kinds = [self.type_of(b, hint) for b in s[key]]
                if kinds and all(k.kind == "string" for k in kinds):
                    return T("string")
                return ANY
        t = s.get("type")
        if isinstance(t, list):  # OpenAPI 3.1 nullable form; the non-null member decides
            non_null = [x for x in t if x != "null"]
            t = non_null[0] if len(non_null) == 1 else None
        if t == "string":
            f = s.get("format")
            if f == "date-time":
                return T("datetime")
            if f == "date":
                return T("date")
            if f == "binary" or "contentMediaType" in s:
                return T("binary")
            return T("string", enum=tuple(str(v) for v in s.get("enum") or ()))
        if t == "integer":
            return T("int32") if s.get("format") == "int32" else T("int64")
        if t == "number":
            return T("double") if s.get("format") in ("double", "float") else T("decimal")
        if t == "boolean":
            return T("boolean")
        if t == "array":
            return T("array", item=self.type_of(s.get("items") or {}, hint + "Item"))
        if t == "object" or "properties" in s or "additionalProperties" in s:
            if self._is_model(s):
                return self.hoist(hint, s, hint + "|" + json.dumps(s, sort_keys=True, default=str))
            ap = s.get("additionalProperties")
            value = self.type_of(ap, hint + "Value") if isinstance(ap, dict) and ap else ANY
            return T("map", item=value)
        return ANY

    # ------------------------------------------------------------- operations

    def param(self, p: dict[str, Any]) -> dict[str, Any]:
        if "$ref" in p:
            return self.doc["components"]["parameters"][p["$ref"].split("/")[-1]]
        return p

    def response(self, op_id: str, op: dict[str, Any], has_paging: bool) -> Response:
        codes = sorted(c for c in op["responses"] if c.startswith("2"))
        if not codes:
            return Response("none", "")
        code = codes[0]
        r = op["responses"][code]
        if "$ref" in r:
            r = self.doc["components"]["responses"][r["$ref"].split("/")[-1]]
        content = r.get("content") or {}
        if not content:
            return Response("none", code)
        if "text/event-stream" in content:
            return Response("sse", code, "text/event-stream",
                            self.type_of(content["text/event-stream"].get("schema") or {}, pascal(op_id) + "Event"))
        for media in JSON_MEDIA:
            if media in content:
                t = self.type_of(content[media].get("schema") or {}, pascal(op_id) + "Response")
                return Response("json", code, media, t, paged=has_paging and t.kind == "array")
        media = next(iter(content))
        return Response("raw", code, media, T("binary"))

    def body(self, op_id: str, op: dict[str, Any]) -> Optional[Body]:
        rb = op.get("requestBody")
        if not rb:
            return None
        content = rb.get("content") or {}
        required = bool(rb.get("required"))
        for media in JSON_MEDIA:
            if media in content:
                s = content[media].get("schema") or {}
                t = self.type_of(s, pascal(op_id) + "Request")
                if t.kind in ("string", "binary"):
                    break  # a JSON-typed text document (config profile): sent as raw text like its siblings
                return Body("json", media, required, t)
        if "multipart/form-data" in content:
            s = content["multipart/form-data"].get("schema") or {}
            req = set(s.get("required") or [])
            parts = [Prop(w, self.type_of(ps, pascal(op_id) + pascal(w)), w in req, _desc(ps))
                     for w, ps in (s.get("properties") or {}).items()]
            return Body("multipart", "multipart/form-data", required, ANY, parts)
        media = next(iter(content))
        return Body("raw", media, required, T("binary"))

    def operations(self) -> list[Operation]:
        ops = []
        for path, item in self.doc["paths"].items():
            shared = item.get("parameters") or []
            for method, op in item.items():
                if method not in ("get", "post", "put", "patch", "delete"):
                    continue
                op_id = op["operationId"]
                params = [self.param(p) for p in shared + (op.get("parameters") or [])]
                path_params, query, headers, common = [], [], [], []
                for p in params:
                    t = self.type_of(p.get("schema") or {}, pascal(op_id) + pascal(p["name"]))
                    prm = Param(p["name"], p["in"], t, bool(p.get("required")) or p["in"] == "path", _desc(p))
                    if p["in"] == "path":
                        path_params.append(prm)
                    elif p["in"] == "query":
                        query.append(prm)
                    elif p["in"] == "header":
                        if p["name"] in COMMON_HEADERS:
                            common.append(p["name"])
                        else:
                            headers.append(prm)
                    else:
                        raise ValueError(f"{op_id}: unsupported parameter location {p['in']}")
                ordered = re.findall(r"{([^}]+)}", path)
                by_name = {p.wire: p for p in path_params}
                if set(ordered) != set(by_name):
                    raise ValueError(f"{op_id}: path template {path} does not match its path parameters")
                path_params = [by_name[n] for n in ordered]
                names = {p.wire for p in query}
                tags = op.get("tags") or ["Default"]
                ops.append(Operation(
                    op_id=op_id, tag=tags[0], method=method.upper(), path=path,
                    summary=uncite((op.get("summary") or "").strip()),
                    description=uncite((op.get("description") or "").strip()),
                    path_params=path_params, query_params=query, header_params=headers, common_headers=common,
                    body=self.body(op_id, op),
                    response=self.response(op_id, op, {"page", "size"} <= names),
                    fenced=bool(op.get("x-sangam-fenced")), idempotent=bool(op.get("x-sangam-idempotent")),
                    permission=str(op.get("x-sangam-permission") or ""), deprecated=bool(op.get("deprecated"))))
        return ops


def _desc(s: Any) -> str:
    return uncite((s.get("description") or "").strip()) if isinstance(s, dict) else ""


# Decision and finding ids (D-n, FC-n, ADR-D-n, FLOWABLE-INTO-OPERATON D-n) in contract prose. The contract text does not
# say which register a bare id belongs to, so the generated SDK docs carry no citation at all (audit X2-03, owner:
# "qualified citations, or none"); the contract keeps them. Requirement ids (R13, VI.3, SL-16, ...) are not touched.
_CITE = re.compile(r"(?<![A-Za-z0-9-])(?:ADR-|FLOWABLE-INTO-OPERATON )?(?:D|FC)-\d+\b")


def uncite(text: str) -> str:
    if not text:
        return text
    t = _CITE.sub("", text)
    for _ in range(3):
        t = re.sub(r"\(\s*[,;/]\s*", "(", t)
        t = re.sub(r"\s*[,;/]\s*\)", ")", t)
        t = re.sub(r"([,;/])\s*[,;/]", r"\1", t)
        t = re.sub(r"\(\s*\)", "", t)
    t = re.sub(r"[ \t]{2,}", " ", t)
    t = re.sub(r" +([,.;:)])", r"\1", t)
    return t.strip()


def build(doc: dict[str, Any]) -> Contract:
    b = _Builder(doc)
    for name in doc["components"]["schemas"]:
        b.component(name)
    ops = b.operations()
    ids = [o.op_id for o in ops]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate operationId in the contract")
    # every ref must name a model (aliases are already resolved)
    for m in b.models.values():
        for p in m.props:
            for r in p.type.refs():
                if r not in b.models:
                    raise ValueError(f"{m.name}.{p.wire}: unresolved ref {r}")
    tags = sorted({o.tag for o in ops})
    return Contract(str(doc["info"]["version"]), dict(sorted(b.models.items())), ops, tags, len(doc["paths"]))
