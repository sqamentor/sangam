# SPDX-License-Identifier: Apache-2.0
# SL-142 / owner decision 61: cell-operator routes for inbound messages parked because their tenant left this cell
# (MOVED or PURGED). A parked message is a POISON_INBOUND reconciliation item with details.parked = true, backed by
# org.sangam.integration.inbox.PoisonInbound: listParked() (every tenant of the cell), parked(itemId) and
# replayParked(itemId, transport, actor, reason), which hands the message to the tenant's current cell and closes the
# item (REPLAYED, audited on sec:<tenant>) and its dead letter (REQUEUED). The routes live under /admin/cell, like the
# other cell tenant-admin routes (service role CELL_ADMIN): the tenant itself answers 421 TENANT_MOVED on every tenant
# route of this cell (CellTenantStateFilter), so only the cell operator can reach its parked messages.
# Controller: org.sangam.server.web.CellParkedInboundController. Transport: CellParkedReplayTransport posts through
# sangam.cell.gateway-url to the target cell's POST /api/v1/messages/replay (CellParkedReplayInboxFilter, role XCELL,
# a cell-to-cell receive path like the cross-cell X-Sangam-Source-Cell variant of POST /messages, not a public route).
_PI = "sangam-integration/src/main/java/org/sangam/integration/inbox/PoisonInbound.java"
_PI_PATH = _v2_os.path.join(_V2_SANGAM, *_PI.split("/"))


def _pi_rec(name):
    f = _v2_find_type(_PI_PATH, "PoisonInbound." + name)
    return _v2_record_schema(f[0], f[2], f[3], "inbox_parked.py PoisonInbound." + name)


_PI_ITEM = _pi_rec("Item")
_PI_PARKED = _pi_rec("Parked")
_PI_REQ = ["SL-142", "VI.3", "R38", "SCALE IR-4"]
_PI_BASE = "/admin/cell/parked-inbound"
_PI_ID = pp("itemId", "reconciliation item id of the parked message (POISON_INBOUND)")
op("get", _PI_BASE, "listParkedInbound", "Cell", "Parked inbound messages of this cell",
   "Open POISON_INBOUND items of every tenant of this cell whose message was parked because the tenant moved or was "
   "purged, oldest first (PoisonInbound.listParked); `tenant` narrows the list to one tenant.", _PI_REQ,
   params=[q("tenant", desc="Tenant code (optional filter)")], resp=(200, "array:" + _PI_ITEM), perm="role CELL_ADMIN",
   errors=("Unauthorized", "Forbidden", "NotImplemented"))
op("get", _PI_BASE + "/{itemId}", "getParkedInbound", "Cell", "View a parked inbound message",
   "The original message with the tenant state, target cell and move id recorded at parking (PoisonInbound.parked). "
   "409 NOT_PARKED for a poison message, 409 ALREADY_RESOLVED for a closed item.", _PI_REQ, params=[_PI_ID],
   resp=(200, _PI_PARKED), perm="role CELL_ADMIN", errors=GET_E + ("Conflict", "NotImplemented"))
op("post", _PI_BASE + "/{itemId}/replay", "replayParkedInbound", "Cell", "Replay a parked inbound message",
   "Hands the message, with its original source, message id, event type and correlation key, to the tenant's current "
   "cell through the gateway (sangam.cell.gateway-url; the target inbox deduplicates on tenant, source and message id); "
   "then closes the item (REPLAYED, audited with the reason) and the dead letter (REQUEUED). A failed hand-over, or a "
   "target where the tenant is not ACTIVE, leaves the item open: 503 REPLAY_FAILED; 503 REPLAY_NOT_CONFIGURED without "
   "a gateway URL (PoisonInbound.replayParked).", _PI_REQ + ["R41", "R42"], params=[_PI_ID],
   body="ReasonRequest", resp=(204, None), cmd="cell.parked-inbound.replay", perm="role CELL_ADMIN",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict", "ServiceUnavailable", "NotImplemented"))
