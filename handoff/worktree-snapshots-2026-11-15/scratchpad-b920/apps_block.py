

# ---------------------------------------------------------------- per-app contracts (M-017)
# The SCALE apps are separate services with their own base URL, roles and error bodies, so each gets its own OpenAPI
# document instead of paths in sangam-api.yaml. api/apps/<name>.py runs in this namespace with fresh SCHEMAS, PATHS and
# OPIDS: it calls app() once, then schema() and aop() for its records and controller mappings. The document is written
# next to OUT as APP["file"] (sangam-rest/api/ in the repository), so one generator run writes every contract.
# The roll-up stream event (org.sangam.cell.rollup.RollupEnvelope.toJson/parse; RollupProjector.validate): the ingest
# body of sangam-rollup-api.yaml and the message of the AsyncAPI channel rollup:national.
ROLLUP_CLOUDEVENT = {
    "type": "object",
    "description": "CloudEvents 1.0, structured JSON (SCALE-ARCHITECTURE section 8). Consumers deduplicate on `id`. A "
                   "missing or too long id, type, sangamcell or sangamtenantpath rejects the event.",
    "required": ["id", "type", "sangamcell", "sangamtenantpath"],
    "properties": {
        "specversion": {"type": "string", "description": "1.0"},
        "id": {"type": "string", "maxLength": 64, "description": "The outbox event id"},
        "source": {"type": "string", "description": "sangam://cell/<cell id>"},
        "type": {"type": "string", "minLength": 1, "description": "The outbox event type, e.g. sangam.history.process.start"},
        "time": {"type": "string", "format": "date-time"},
        "subject": {"type": "string", "description": "The root id (same as sangamroot)"},
        "datacontenttype": {"type": "string", "description": "application/json"},
        "sangamcell": {"type": "string", "minLength": 1, "maxLength": 16, "description": "Publishing cell id"},
        "sangamtenant": {"type": "string", "description": "Tenant code; the last path segment when absent"},
        "sangamtenantpath": {"type": "string", "minLength": 1, "maxLength": 256,
                             "description": "Hierarchy path from the root, /-separated (e.g. IN/RJ/RJ-JPR); every prefix "
                                            "is projected"},
        "sangamroot": {"type": "string", "description": "Root id: per-root order (VI.2)"},
        "sangamrootseq": {"type": "integer", "format": "int64", "description": "Sequence within the root"},
        "sangamoperationid": {"type": "string", "description": "Operation id of a cross-cell business message (R36)"},
        "data": {"type": "object", "additionalProperties": True,
                 "description": "Only allow-listed payload fields leave the cell (RollupPublisher.DEFAULT_FIELDS: "
                                "eventType, processDefinitionKey, taskDefinitionKey, activityType, incidentType, durationMs, "
                                "outcome, amountMinor, currency, category, targetTenant, operationId); no personal data"},
    },
    "additionalProperties": True,
}

APP_ERRORS = {
    "BadRequest": "Invalid request: a body that does not bind, a missing or invalid parameter, or a rule the input breaks",
    "Unauthorized": "No bearer token, or a token the service does not know",
    "Forbidden": "The token's roles do not include one of the operation's `x-sangam-roles`",
    "NotFound": "The named resource does not exist",
    "Conflict": "The current state does not allow the operation",
    "Unprocessable": "The request was understood but rejected",
}


def app(file, title, summary, description, roles, tags, servers=None, problem=None, components=None, extensions=None,
        errors=None, bearer=None):
    """Starts an app contract. `roles` maps a role to its meaning; `problem` replaces the default Problem schema,
    `errors` the default error-response texts (APP_ERRORS) and `bearer` the bearer-token description."""
    global APP
    APP = {"file": file, "title": title, "summary": summary, "description": description, "roles": roles, "tags": tags,
           "servers": servers or [{"url": "/api/v1"}], "components": components or {}, "extensions": extensions or {},
           "errors": dict(APP_ERRORS, **(errors or {})),
           "bearer": bearer or ("Static service token from the app's token list (`<token>:<ROLE>[,<ROLE>]`), compared in "
                                "constant time. Roles: " + "; ".join("%s (%s)" % kv for kv in roles.items()) + ".")}
    SCHEMAS["Problem"] = problem or {
        "type": "object",
        "description": "RFC 9457 problem details (Spring ProblemDetail). `type` is about:blank unless stated; extra members "
                       "(for example `field`, the JSON path of a body field that does not bind) may follow.",
        "required": ["title", "status"],
        "properties": {"type": {"type": "string", "format": "uri-reference"}, "title": {"type": "string"},
                       "status": {"type": "integer", "format": "int32"}, "detail": {"type": "string"},
                       "instance": {"type": "string", "format": "uri-reference"}, "field": {"type": "string"}},
        "additionalProperties": True,
    }




def aop(method, path, opid, tag, summary, desc, roles, params=(), body=None, body_required=True, body_ct=("application/json",),
        resp=(200, None), errors=("BadRequest", "Unauthorized", "Forbidden"), more=None):
    """One app operation. `roles` are the Spring Security roles the app's SecurityConfig requires (x-sangam-roles);
    `more` adds responses by status code."""
    assert opid not in OPIDS, opid
    OPIDS.add(opid)
    for r in roles:
        assert r in APP["roles"], "%s: unknown role %s" % (opid, r)
    o = {"operationId": opid, "tags": [tag], "summary": summary, "description": desc, "x-sangam-roles": list(roles)}
    if params:
        o["parameters"] = list(params)
    if body is not None:
        schema_ = body if isinstance(body, dict) else R(body)
        o["requestBody"] = {"required": body_required, "content": {ct: {"schema": schema_} for ct in body_ct}}
    status, rs = resp
    r = {"description": "Success"}
    if isinstance(rs, dict):
        r["content"] = {"application/json": {"schema": rs}}
    elif isinstance(rs, str):
        r["content"] = {"application/json": {"schema": {"type": "array", "items": R(rs[6:])} if rs.startswith("array:")
                                             else R(rs)}}
    responses = {str(status): r}
    for e in errors:
        responses[{"BadRequest": "400", "Unauthorized": "401", "Forbidden": "403", "NotFound": "404", "Conflict": "409",
                   "Unprocessable": "422"}[e]] = RESP(e)
    responses.update(more or {})
    o["responses"] = responses
    PATHS.setdefault(path, {})[method] = o


def _write_app_contract():
    used = {e["$ref"] for item in PATHS.values() for o in item.values() for e in o["responses"].values() if "$ref" in e}
    responses = {}
    for name, text in APP["errors"].items():
        if RESP(name)["$ref"] in used:
            responses[name] = {"description": text, "content": {"application/problem+json": {"schema": R("Problem")}}}
    comps = {"securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer", "description": APP["bearer"]}}}
    if responses:
        comps["responses"] = responses
    comps["schemas"] = SCHEMAS
    for k, v in APP["components"].items():
        comps.setdefault(k, {}).update(v)
    d = {"openapi": "3.1.0",
         "info": {"title": APP["title"], "version": "0.1.0", "summary": APP["summary"], "description": APP["description"],
                  "license": {"name": "Apache-2.0", "identifier": "Apache-2.0"}},
         "servers": APP["servers"], "security": [{"bearerAuth": []}],
         "tags": [{"name": n, "description": t} for n, t in APP["tags"]], "paths": PATHS, "components": comps}
    d.update(APP["extensions"])
    t = json.dumps(d)
    for ref in set(re.findall(r'"#/components/(\w+)/([\w-]+)"', t)):
        assert ref[1] in d["components"].get(ref[0], {}), (APP["file"], ref)
    with open(_os.path.join(_os.path.dirname(_os.path.abspath(OUT)), APP["file"]), "w", encoding="utf-8", newline="\n") as f:
        f.write("# SPDX-License-Identifier: Apache-2.0\n")
        f.write("# %s contract (OpenAPI 3.1). Generated by sangam-rest/api/gen_openapi.py from api/apps/%s; do not edit.\n"
                % (APP["title"], _app_source))
        yaml.dump(d, f, Dumper=D, sort_keys=False, allow_unicode=True, width=140)
    print(APP["file"], "paths", len(PATHS), "operations", sum(len(v) for v in PATHS.values()), "schemas", len(SCHEMAS))


for _app_file in sorted(_glob.glob(_os.path.join(_os.path.dirname(_os.path.abspath(__file__)), "apps", "*.py"))):
    SCHEMAS, PATHS, OPIDS, APP = {}, {}, set(), None
    _app_source = _os.path.basename(_app_file)
    with open(_app_file, encoding="utf-8") as _f:
        exec(compile(_f.read(), _app_file, "exec"), globals())
    assert APP is not None, _app_file + " must call app()"
    _write_app_contract()
