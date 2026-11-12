# SPDX-License-Identifier: Apache-2.0
# M-017: sangam-gateway (org.sangam.gateway.CellProxy, TenantResolver, GatewayRoutes). The gateway has no API of its own:
# one catch-all route (everything but /actuator/**) proxies to the tenant's cell, so its paths are those of
# sangam-api.yaml. This contract states what the gateway adds: tenant resolution, the headers it reads and sets, and
# the problem answers it gives itself. AppContractDriftTest (sangam-rest) checks the problem types and statuses
# against CellProxy.
GATEWAY_PROBLEMS = [
    # (type, status, when) -- one row per CellProxy.problem(...) call
    ("tenant-unresolved", 400, "No tenant in the token claim, the host name or the tenant header"),
    ("tenant-unknown", 404, "The tenant is not registered in the control plane"),
    ("tenant-unplaced", 404, "The tenant has no cell (route state UNPLACED)"),
    ("tenant-overloaded", 429, "More concurrent requests for the tenant than this gateway node allows (Retry-After: 1)"),
    ("routing-unavailable", 503, "The route is not cached and the control plane is unreachable"),
    ("tenant-draining", 503, "A write while the tenant is being moved (route state DRAINING); reads still pass"),
    ("tenant-moving", 503, "The tenant is being copied to another cell (route state MOVING)"),
    ("cell-down", 503, "The tenant's cell is down (route state CELL_DOWN)"),
    ("cell-unreachable", 503, "The cell did not answer (connect error or timeout)"),
    ("tenant-moved", 503, "The cell answered 421: the tenant moved away; the cached route is dropped (Retry-After: 1)"),
]
app("sangam-gateway-api.yaml", "Sangam gateway",
    "Routing edge in front of the cells (SCALE-ARCHITECTURE sections 4 and 5.5)",
    "The gateway (port 8000) proxies every request except `/actuator/**` to the tenant's cell, streaming bodies and "
    "keeping the path and query unchanged, so the operations are those of the cell API (`x-sangam-proxied-contract`). "
    "The tenant comes, in this order, from the JWT claim (`sangam.gateway.tenant-claim`, default `tenant`; the signature "
    "is not verified, the cell verifies it), the host name `<tenant><host-suffix>`, or the tenant header (default "
    "`X-Sangam-Tenant`); codes are upper-cased and must match `[A-Z0-9][A-Z0-9_-]{0,63}`. The gateway authorizes "
    "nothing: the cell does (R19). Its own answers are `application/problem+json` with a `requestId` member; see "
    "`x-sangam-gateway-problems` and `components.responses`.",
    {},
    [],
    bearer="The caller's cell API token, forwarded unchanged. The gateway reads only its tenant claim for routing and "
           "checks no signature or role; the cell authorizes the call.",
    servers=[{"url": "/api/v1", "description": "Sangam gateway (same paths as the cell)"}],
    problem={"type": "object", "description": "Problem written by the gateway itself (CellProxy.problem).",
             "required": ["type", "title", "status", "detail", "requestId"],
             "properties": {"type": {"type": "string", "format": "uri",
                                     "description": "https://sangam.org/problems/<x-sangam-gateway-problems type>"},
                            "title": {"type": "string"}, "status": {"type": "integer", "format": "int32"},
                            "detail": {"type": "string"}, "requestId": {"type": "string"}}},
    components={
        "headers": {
            "X-Request-Id": {"description": "Kept when it matches `[A-Za-z0-9._:-]{8,128}`, otherwise a new UUIDv7; set on "
                                            "every answer and forwarded to the cell", "schema": {"type": "string"}},
            "Retry-After": {"description": "Seconds to wait (the route's retry hint, else `sangam.gateway."
                                           "retry-after-seconds`, default 30)", "schema": {"type": "integer"}},
        },
        "parameters": {
            "TenantHeader": {"name": "X-Sangam-Tenant", "in": "header", "required": False, "schema": {"type": "string"},
                             "description": "Tenant code, used when neither the token claim nor the host name gives one "
                                            "(header name: `sangam.gateway.tenant-header`)"},
            "RequestId": {"name": "X-Request-Id", "in": "header", "required": False, "schema": {"type": "string"},
                          "description": "Caller's request id; `traceparent` and `tracestate` pass through untouched"},
        },
        "responses": {
            "Gateway%d" % s: {
                "description": "; ".join("%s: %s" % (t, w) for t, st, w in GATEWAY_PROBLEMS if st == s),
                "headers": dict({"X-Request-Id": {"$ref": "#/components/headers/X-Request-Id"}},
                                **({"Retry-After": {"$ref": "#/components/headers/Retry-After"}} if s in (429, 503) else {})),
                "content": {"application/problem+json": {"schema": {"$ref": "#/components/schemas/Problem"}}}}
            for s in sorted({st for _, st, _ in GATEWAY_PROBLEMS})},
    },
    extensions={
        "x-sangam-proxied-contract": "sangam-api.yaml",
        "x-sangam-gateway-problems": [{"type": t, "status": s, "when": w} for t, s, w in GATEWAY_PROBLEMS],
        "x-sangam-upstream-headers": {
            "X-Request-Id": "the request id above", "X-Sangam-Cell": "the target cell id",
            "X-Sangam-Route-Epoch": "the route epoch used", "X-Forwarded-Host": "the original Host",
            "X-Forwarded-Proto": "http or https", "X-Forwarded-For": "the caller's address appended",
        },
        "x-sangam-dropped-headers": ["connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te",
                                     "trailer", "transfer-encoding", "upgrade", "host", "content-length"],
    })
