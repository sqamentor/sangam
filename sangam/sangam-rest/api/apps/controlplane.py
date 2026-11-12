# SPDX-License-Identifier: Apache-2.0
# M-017: sangam-control-plane REST API (org.sangam.controlplane.web.ControlPlaneController, SecurityConfig).
# Every schema mirrors a Java record field for field; ControlPlaneContractTest (sangam-control-plane) checks the
# mappings, parameters and record components against this contract.
app("sangam-control-plane-api.yaml", "Sangam control plane API",
    "Tenant registry, cell directory, placement, routing and tenant moves (SCALE-ARCHITECTURE sections 5 and 7)",
    "The control plane's own REST API (port 8090, base path /api/v1). Gateways, cells and the roll-up read routes; cells "
    "register and send heartbeats; administrators manage tenants, placement, cells and moves. Errors are RFC 9457 "
    "`application/problem+json`. Times are epoch milliseconds (`...Ms`).",
    {"ADMIN": "tenant and cell administration, moves, audit", "CELL": "register, heartbeat, route and cell reads",
     "GATEWAY": "route and cell reads", "ROLLUP": "route and cell reads"},
    [("Routing", "Tenant routes for gateways, cells and the roll-up (SCALE 5.4)"),
     ("Cells", "Cell registration, heartbeat and directory (SCALE 5.2)"),
     ("Tenants", "Tenant hierarchy and placement (SCALE 5.1, 5.3)"),
     ("Moves", "Tenant moves between cells (SCALE 7)"),
     ("CellAdmin", "Cell placement switch and retirement"),
     ("Audit", "Append-only control-plane audit (R42)")],
    servers=[{"url": "/api/v1", "description": "Sangam control plane"}])

READ_ROLES = ("ADMIN", "GATEWAY", "CELL", "ROLLUP")
ERR_READ = ("Unauthorized", "Forbidden", "NotFound")
ERR_ALL = ("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict")

# ---------------------------------------------------------------- records
schema("Route", "Routing answer for a tenant (org.sangam.controlplane.routing.Route). `routeEpoch` bumps on every change; "
       "a gateway drops cached routes with a lower epoch. `ttlSeconds` is the cache TTL hint.",
       "tenantCode path cellId apiBaseUrl state routeEpoch:long ttlSeconds:long retryAfterSeconds:int",
       enums={"state": ["ROUTABLE", "DRAINING", "MOVING", "CELL_DOWN", "UNPLACED"]},
       descs={"state": "ROUTABLE; DRAINING (writes answer 503 Retry-After); MOVING and CELL_DOWN (503); UNPLACED (no cell)",
              "path": "The /-joined chain of tenant codes from the root"})
schema("RouteSnapshot", "Every route changed after `sinceMs`, with the snapshot time to pass as the next `sinceMs`.",
       "asOfMs:long routes:list:Route")
schema("CellRegistration", "Cell registration (CellDirectory.Registration). Idempotent; residency is immutable. Capacity "
       "defaults: 500 starts/s, 2000 tenants.",
       "region jurisdiction residency apiBaseUrl adminBaseUrl dbVendor capacityStartsPerSec:int capacityTenants:int version",
       ["apiBaseUrl", "jurisdiction", "residency"])
schema("CellHeartbeat", "Cell heartbeat (CellDirectory.Heartbeat). Missing numbers count as 0.",
       "status startsPerSec:num p99CommandMs:num activeNodes:int dbPoolUsagePct:num outboxFillPct:num tenants:long version",
       descs={"status": "UP (default), DEGRADED or DRAINING, case-insensitive"})
schema("Cell", "Cell directory entry (org.sangam.controlplane.cells.Cell). `status` is the reported status (UP, DEGRADED, "
       "DRAINING), DOWN (no heartbeat within the timeout) or RETIRED; `placementOpen` is the administrative switch.",
       "cellId region jurisdiction residency apiBaseUrl adminBaseUrl dbVendor capacityStartsPerSec:int capacityTenants:int "
       "status placementOpen:bool startsPerSec:num p99CommandMs:num activeNodes:int dbPoolUsagePct:num outboxFillPct:num "
       "tenantsReported:long version lastHeartbeatMs:long registeredAtMs:long",
       enums={"status": ["UP", "DEGRADED", "DRAINING", "DOWN", "RETIRED"]})
schema("CreateTenantRequest", "A new tenant node (ControlPlaneController.CreateTenant). jurisdiction and residency are "
       "inherited from the parent when absent and required for a root; residency must lie within the parent's. "
       "`place` (default true) places the tenant on a cell right away.",
       "tenantCode parentCode levelKind name jurisdiction residency sector expectedStartsPerSec:num place:bool", ["tenantCode"],
       descs={"levelKind": "Hierarchy level name (data, ADR-D-20); default ORG"})
schema("Tenant", "A tenant node of the n-level hierarchy (org.sangam.controlplane.registry.Tenant). `cellId` is set for "
       "placed tenants.",
       "tenantCode parentCode path depth:int levelKind name jurisdiction residency sector expectedStartsPerSec:num cellId "
       "status routeEpoch:long createdAtMs:long updatedAtMs:long",
       enums={"status": ["ACTIVE", "DRAINING", "MOVING", "RETIRED"]})
schema("PlacementCandidate", "A cell that can host the tenant (PlacementService.Candidate), best first.",
       "cell:Cell affinity:int headroom:num reason")
schema("MoveRequest", "Start a tenant move (ControlPlaneController.MoveRequest). Without `targetCell` the placement "
       "service picks one; the reason is audited.", "targetCell reason", ["reason"])
schema("TenantMove", "A tenant move (org.sangam.controlplane.move.TenantMove).",
       "moveId tenantCode sourceCell targetCell state processInstanceId requestedBy reason rowsCopied:long checksumSource "
       "checksumTarget sourceWatermark startedAtMs:long finishedAtMs:long lastError",
       enums={"state": ["REQUESTED", "PREPARING", "DRAINING", "COPYING", "VERIFYING", "CUT_OVER", "RETAINING", "COMPLETED",
                        "ROLLING_BACK", "ROLLED_BACK", "FAILED"]})
SCHEMAS["AuditEntry"] = {
    "type": "object", "additionalProperties": True,
    "description": "One CP_AUDIT row as returned by JDBC: the columns audit_id, at_ms, actor, action, subject, detail and "
                   "content_hash, keyed by the database's column label (its letter case depends on the database).",
}

CODE = pp("code", "Tenant code")
CELL_ID = pp("cellId", "Cell id")

# ---------------------------------------------------------------- routing
aop("get", "/routes/tenants/{code}", "getTenantRoute", "Routing", "Route of one tenant",
    "The cell and state a request for this tenant goes to.", READ_ROLES, params=[CODE], resp=(200, "Route"),
    errors=ERR_READ)
aop("get", "/routes", "listChangedRoutes", "Routing", "Incremental route snapshot",
    "Every placed tenant's route changed after `sinceMs` (0 = all).", READ_ROLES,
    params=[q("sinceMs", "long", "Epoch ms of the previous snapshot (`asOfMs`); default 0")], resp=(200, "RouteSnapshot"),
    errors=("BadRequest", "Unauthorized", "Forbidden"))

# ---------------------------------------------------------------- cells
aop("put", "/cells/{cellId}", "registerCell", "Cells", "Register a cell",
    "Registers the cell or updates its registration (idempotent). 409 when the cell is retired (ids are never reused) or "
    "the residency would change.", ("CELL", "ADMIN"),
    params=[CELL_ID], body="CellRegistration", resp=(200, "Cell"), errors=ERR_ALL)
aop("post", "/cells/{cellId}/heartbeat", "cellHeartbeat", "Cells", "Cell heartbeat",
    "Reports load and status. A cell without a heartbeat within the timeout shows as DOWN.", ("CELL", "ADMIN"),
    params=[CELL_ID], body="CellHeartbeat", resp=(204, None), errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound"))
aop("get", "/cells", "listCells", "Cells", "List cells", "Every registered cell, by id.", READ_ROLES,
    resp=(200, "array:Cell"), errors=("Unauthorized", "Forbidden"))
aop("get", "/cells/{cellId}", "getCell", "Cells", "Get a cell", "One cell directory entry.", READ_ROLES,
    params=[CELL_ID], resp=(200, "Cell"), errors=ERR_READ)

# ---------------------------------------------------------------- tenants
aop("post", "/admin/tenants", "createTenant", "Tenants", "Create a tenant",
    "Creates the tenant node and, unless `place` is false, places it on a cell.", ("ADMIN",),
    body="CreateTenantRequest", resp=(201, "Tenant"), errors=ERR_ALL)
aop("get", "/admin/tenants/{code}", "getTenant", "Tenants", "Get a tenant", "One tenant node.", ("ADMIN",),
    params=[CODE], resp=(200, "Tenant"), errors=ERR_READ)
aop("get", "/admin/tenants/{code}/children", "listTenantChildren", "Tenants", "Children of a tenant",
    "The direct children, by tenant code (empty for an unknown code).", ("ADMIN",), params=[CODE],
    resp=(200, "array:Tenant"), errors=("Unauthorized", "Forbidden"))
aop("post", "/admin/tenants/{code}/place", "placeTenant", "Tenants", "Place a tenant",
    "Places the tenant on the best candidate cell; an already placed tenant is returned unchanged.", ("ADMIN",), params=[CODE], resp=(200, "Tenant"), errors=ERR_ALL)
aop("get", "/admin/tenants/{code}/placement-candidates", "listPlacementCandidates", "Tenants", "Placement candidates",
    "Cells that can host the tenant (residency, capacity, outbox and pool filters, hierarchy affinity), best first.",
    ("ADMIN",), params=[CODE], resp=(200, "array:PlacementCandidate"), errors=ERR_READ)

# ---------------------------------------------------------------- moves
aop("post", "/admin/tenants/{code}/moves", "startTenantMove", "Moves", "Start a tenant move",
    "Starts the move process (drain, copy, verify, cut-over, retain, purge). Answers 202 with the move.", ("ADMIN",),
    params=[CODE], body="MoveRequest", resp=(202, "TenantMove"), errors=ERR_ALL)
aop("get", "/admin/tenants/{code}/moves", "listTenantMoves", "Moves", "Moves of a tenant", "Every move of the tenant.",
    ("ADMIN",), params=[CODE], resp=(200, "array:TenantMove"), errors=ERR_READ)
aop("get", "/admin/moves/{moveId}", "getTenantMove", "Moves", "Get a move",
    "One move. An unknown id answers 400 (the repository raises IllegalArgumentException).", ("ADMIN",),
    params=[pp("moveId")], resp=(200, "TenantMove"), errors=("BadRequest", "Unauthorized", "Forbidden"))

# ---------------------------------------------------------------- cell administration
aop("post", "/admin/cells/{cellId}/placement", "setCellPlacement", "CellAdmin", "Open or close placement",
    "Switches new placements on the cell on or off; returns the cell.", ("ADMIN",),
    params=[CELL_ID, q("open", "bool", "true opens, false closes", required=True)], resp=(200, "Cell"),
    errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound"))
aop("post", "/admin/cells/{cellId}/retire", "retireCell", "CellAdmin", "Retire a cell",
    "Retires an empty cell (409 while it still hosts tenants); returns the cell.", ("ADMIN",), params=[CELL_ID],
    resp=(200, "Cell"), errors=ERR_ALL)

# ---------------------------------------------------------------- audit
aop("get", "/admin/audit", "listControlPlaneAudit", "Audit", "Audit of a subject",
    "Newest first; at most `limit` (1-1000) rows.", ("ADMIN",),
    params=[q("subject", desc="Audited subject, for example tenant:RJ or cell:c1", required=True),
            q("limit", "int", "Maximum rows; default 100")],
    resp=(200, "array:AuditEntry"), errors=("BadRequest", "Unauthorized", "Forbidden"))
