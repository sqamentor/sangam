import sys
api = sys.argv[1]


def edit(path, reps):
    s = open(path, encoding="utf-8", newline="").read()
    for old, new in reps:
        n = s.count(old)
        if n != 1:
            sys.exit("MISS %d in %s: %s" % (n, path, old[:70]))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8", newline="").write(s)


edit(api + "/gen_openapi.py", [
    ('"INVALID_BATCH_STATE"]}, {"type": "string"}]},',
     '"INVALID_BATCH_STATE", "TENANT_MOVED", "TENANT_NOT_ON_INSTANCE"]}, {"type": "string"}]},'),
    ('    "NotImplemented": {"description": "CAPABILITY_NOT_INSTALLED: the providing module is not installed in this server", "content": PROBLEM},\n}',
     '    "NotImplemented": {"description": "CAPABILITY_NOT_INSTALLED: the providing module is not installed in this server", "content": PROBLEM},\n'
     '    "MisdirectedRequest": {"description": "421 Misdirected Request: TENANT_MOVED (the tenant left this cell; details.targetCell '
     'names its cell, SCALE IR-4) or TENANT_NOT_ON_INSTANCE (M-016: this instance does not serve the tenant: a dedicated '
     'instance bound to another tenant, or a shared instance asked for a SCHEMA or DATABASE isolated tenant; details.tenant '
     'only, the bound tenant is never named). Fail-closed, no data is read.", "content": PROBLEM},\n}'),
    ('       "rootProcessInstanceId", enums={"state": ["ACTIVE", "SUSPENDED", "COMPLETED", "CANCELLED", "DELETED", "EXTERNALLY_TERMINATED"]})',
     '       "rootProcessInstanceId traceId", enums={"state": ["ACTIVE", "SUSPENDED", "COMPLETED", "CANCELLED", "DELETED", "EXTERNALLY_TERMINATED"]},\n'
     '       descs={"traceId": "M-044 (decision 57): W3C trace id (32 lowercase hex) of the command that started the instance; "\n'
     '              "absent when none was recorded. The UI links it through config traceUrlTemplate."})\n'
     'SCHEMAS["ProcessInstance"]["properties"]["traceId"]["pattern"] = "^[0-9a-f]{32}$"'),
    ('# M-051: the Map-backed request bodies declared by the route files get schemas from the keys their services read\n',
     '# M-016: every operation can answer 421 (TENANT_MOVED from CellTenantStateFilter, TENANT_NOT_ON_INSTANCE from the\n'
     '# dedicated-tenant and shared-isolation filters, order 39, on /api/* and /engine-rest/*), before any data access.\n'
     'for _item in PATHS.values():\n'
     '    for _o in _item.values():\n'
     '        _o["responses"].setdefault("421", RESP("MisdirectedRequest"))\n'
     '# M-051: the Map-backed request bodies declared by the route files get schemas from the keys their services read\n'),
])
edit(api + "/events/sangam_events.py", [
    ('AUDIT_EVENTS_PENDING = []',
     'AUDIT_EVENTS_PENDING = [("org.sangam.engine.clock.TestModeClock (M-021, test mode only)", "TEST_CLOCK_CHANGED")]'),
])
print("ok")
