# SPDX-License-Identifier: Apache-2.0
# sangam-ledger: budget / quantity / receivable ledgers (stages, holds, freezes, period close). Executed inside
# gen_openapi.py.

TAGS.append(("Ledger", "Limit, quantity and receivable ledgers: balances, idempotent operations, freeze orders, holds, "
                       "period close, reconciliation (sangam-ledger)"))
LR = ["R36", "R41", "GP-11"]

schema("LedgerAccountKey", "An account address. Fields left out are '-'.",
       "tenant ledger period line holder stage sub")
schema("LedgerAccountRef", "An account within the path's ledger.", "period line holder stage sub")
schema("LedgerAccount", "A balance; amounts are decimal strings (R33).",
       "accountId key:LedgerAccountKey currency total distributed committed encumbered consumed available state "
       "version:long attrs:obj")
schema("LedgerOperationRequest", "One ledger operation. reference defaults to the Idempotency-Key; one of the two is "
       "required (a retry must not post twice).",
       "operation account:LedgerAccountRef target:LedgerAccountRef amount currency rateType reference auditRef "
       "asOf:date fromCommitment:bool fromEncumbrance:bool holdIfShort:bool attrs:obj", ["operation", "account"],
       descs={"operation": "ALLOCATE, DISTRIBUTE, WITHDRAW, COMMIT, ENCUMBER, RELEASE, CONSUME, TRANSFER, ADJUST, "
                           "REVERSE, WRITE_OFF, ...",
              "amount": "decimal string (R33)"})
schema("LedgerAccountingEntry", "An accounting entry of the event mapping.",
       "reference event basis debit credit amount currency functionalAmount")
schema("LedgerResult", "Outcome of an operation; held = parked until funds arrive.",
       "applied:bool duplicate:bool held:bool reason findings:list:ConfigFinding balances:list:LedgerAccount "
       "accounting:list:LedgerAccountingEntry")
schema("LedgerFreezeRequest", "A freeze order over matching accounts (court / audit order).",
       "id period linePrefix holderPrefix stage reason", ["reason"])
schema("LedgerFreezeOrder", "A freeze order.", "tenant id ledger period linePrefix holderPrefix stage reason orderedBy at:dt")
schema("LedgerCloseRequest", "Period close: ANNUAL funds lapse; commitments carry forward when the definition says so.",
       "nextPeriod asOf:date")
schema("LedgerReconcileRequest", "Reconcile a period against the external system of record.", "period", ["period"])
schema("LedgerReconcileRow", "Mirror versus external balance of one account (decimal strings).",
       "accountId key:LedgerAccountKey values:list:s")
schema("LedgerHeldRequest", "A request parked for funds.",
       "operation account:LedgerAccountKey target:LedgerAccountKey amount currency rateType reference auditRef actor "
       "asOf:date fromCommitment:bool fromEncumbrance:bool holdIfShort:bool attrs:obj")

LG = pp("ledger", "ledger id from the tenant's profile (e.g. BUDGET, DEMAND)")
ACC_Q = [q("period"), q("line", desc="line (head of account) prefix"), q("holder", desc="holder prefix"), q("stage")]

op("get", "/ledger/{ledger}/accounts", "listLedgerAccounts", "Ledger", "List accounts",
   "Balances of the ledger, filtered by period, line and holder prefixes and stage.", LR, params=[LG] + ACC_Q,
   resp=(200, "array:LedgerAccount"), perm="ledger.read", list_=True)
op("get", "/ledger/accounts/{accountId}", "getLedgerAccount", "Ledger", "Get one account",
   "An account by its id (searches the tenant's profile ledgers, or ?ledger=).", LR,
   params=[pp("accountId"), q("ledger")], resp=(200, "LedgerAccount"), perm="ledger.read")
op("post", "/ledger/{ledger}/operations", "applyLedgerOperation", "Ledger", "Apply an operation",
   "Posts one operation idempotently by reference; insufficient availability refuses (422) or parks the request "
   "(holdIfShort).", LR, params=[LG], body="LedgerOperationRequest", resp=(200, "LedgerResult"),
   cmd="ledger.operation", perm="ledger.write")
op("post", "/ledger/{ledger}/freeze-orders", "createLedgerFreezeOrder", "Ledger", "Create a freeze order",
   "Freezes matching accounts until released.", LR, params=[LG], body="LedgerFreezeRequest",
   resp=(201, "LedgerFreezeOrder"), created=True, cmd="ledger.freeze", perm="ledger.admin")
op("get", "/ledger/{ledger}/freeze-orders", "listLedgerFreezeOrders", "Ledger", "List freeze orders",
   "Active freeze orders of the ledger.", LR, params=[LG], resp=(200, "array:LedgerFreezeOrder"), perm="ledger.read",
   list_=True)
op("delete", "/ledger/{ledger}/freeze-orders/{id}", "releaseLedgerFreezeOrder", "Ledger", "Release a freeze order",
   "Releases the order; frozen accounts reopen.", LR, params=[LG, pp("id")], resp=(204, None),
   cmd="ledger.freeze.release", perm="ledger.admin")
op("post", "/ledger/{ledger}/periods/{period}/close", "closeLedgerPeriod", "Ledger", "Close a period",
   "Year-end lapse and carry-forward.", LR, params=[LG, pp("period")], body="LedgerCloseRequest", body_required=False,
   resp=(200, "array:LedgerResult"), cmd="ledger.period.close", perm="ledger.admin")
op("get", "/ledger/{ledger}/holds", "listLedgerHolds", "Ledger", "List held requests",
   "Requests parked for funds on matching accounts.", LR, params=[LG] + ACC_Q, resp=(200, "array:LedgerHeldRequest"),
   perm="ledger.read", list_=True)
op("post", "/ledger/{ledger}/reconcile", "reconcileLedger", "Ledger", "Reconcile a period",
   "Mirror versus external balances (SHADOW ledgers).", LR, params=[LG], body="LedgerReconcileRequest",
   resp=(200, "array:LedgerReconcileRow"), cmd="ledger.reconcile", perm="ledger.admin")
