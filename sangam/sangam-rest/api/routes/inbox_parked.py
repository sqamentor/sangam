# SPDX-License-Identifier: Apache-2.0
# SL-142: operator routes for inbound messages parked because their tenant left this cell (MOVED or PURGED). A parked
# message is a POISON_INBOUND reconciliation item with details.parked = true, backed by
# org.sangam.integration.inbox.PoisonInbound: listOpen(tenant) (filtered to parked items), parked(itemId) and
# replayParked(itemId, transport, actor, reason), which hands the message to the tenant's new cell and closes the item
# (REPLAYED, audited) and its dead letter (REQUEUED). Authorisation follows the reconciliation workbench
# (OpsController / OpsConsoleController): ops.read to list and view, recon.resolve to act; the OPERATOR persona holds
# both. Contract only: the expected controller is org.sangam.server.web.ParkedInboundController (sangam-server, which
# owns the cross-cell transport), mapped under /api/v1/ops/reconciliation/parked-inbound.
_PI = "sangam-integration/src/main/java/org/sangam/integration/inbox/PoisonInbound.java"
_PI_PATH = _v2_os.path.join(_V2_SANGAM, *_PI.split("/"))


def _pi_rec(name):
    f = _v2_find_type(_PI_PATH, "PoisonInbound." + name)
    return _v2_record_schema(f[0], f[2], f[3], "inbox_parked.py PoisonInbound." + name)


_PI_ITEM = _pi_rec("Item")
_PI_PARKED = _pi_rec("Parked")
_PI_REQ = ["SL-142", "VI.3", "R38"]
_PI_ID = pp("itemId", "reconciliation item id of the parked message (POISON_INBOUND)")
op("get", "/ops/reconciliation/parked-inbound", "listParkedInbound", "Ops", "Parked inbound messages",
   "Open POISON_INBOUND items of the caller's tenant whose message was parked because the tenant moved or was purged "
   "(PoisonInbound.listOpen, parked = true).", _PI_REQ, resp=(200, "array:" + _PI_ITEM), perm="ops.read", errors=STD_R)
op("get", "/ops/reconciliation/parked-inbound/{itemId}", "getParkedInbound", "Ops", "View a parked inbound message",
   "The original message with the tenant state, target cell and move id recorded at parking (PoisonInbound.parked). "
   "409 NOT_PARKED for a poison message, 409 ALREADY_RESOLVED for a closed item.", _PI_REQ, params=[_PI_ID],
   resp=(200, _PI_PARKED), perm="ops.read", errors=GET_E + ("Conflict",))
op("post", "/ops/reconciliation/parked-inbound/{itemId}/replay", "replayParkedInbound", "Ops", "Replay a parked inbound message",
   "Hands the message, with its original id, type and key, to the tenant's new cell; then closes the item (REPLAYED, "
   "audited with the reason) and the dead letter (REQUEUED). A failed hand-over leaves the item open; a repeat is "
   "deduplicated by the target inbox (PoisonInbound.replayParked).", _PI_REQ + ["R41", "R42"], params=[_PI_ID],
   body="ReasonRequest", resp=(204, None), cmd="ops.reconciliation.replay-parked", perm="recon.resolve",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict"))
