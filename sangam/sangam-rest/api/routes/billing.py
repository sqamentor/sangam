# SPDX-License-Identifier: Apache-2.0
# sangam-billing: tariffs, invoices, collection, receipts, refunds, penalties on RECEIVABLE ledgers. Executed inside
# gen_openapi.py.

TAGS.append(("Billing", "Invoices, collections, arrears, penalties, waivers and refunds on receivable ledgers "
                        "(sangam-billing)"))
BR = ["R36", "R41", "GP-09"]

schema("BillingInvoiceRequest", "Raise the invoice of one consumer for one cycle (idempotent per consumer x cycle).",
       "consumer cycle tariffId period measures:smap units dueDays:int attributes:obj",
       ["consumer", "cycle", "tariffId"], descs={"measures": "component measure -> decimal string"})
schema("BillingCollectionRequest", "Record a payment confirmed by its channel.",
       "consumer channel paymentRef amount", ["consumer", "channel", "paymentRef", "amount"])
schema("BillingPenaltyRequest", "Levy penalties / interest on overdue demand.", "tariffId period", ["tariffId"])
schema("BillingWaiverRequest", "Record an approved waiver / write-off.", "consumer head cycle period amount orderRef",
       ["consumer", "amount", "orderRef"])
schema("BillingRefundRequest", "Refund a collection.", "consumer head cycle period channel paymentRef amount",
       ["consumer", "channel", "paymentRef", "amount"])
schema("BillingRefundResult", "The payment rail reference of a refund.", "railReference operationId")

BL = q("ledger", desc="receivable ledger id (e.g. DEMAND); may carry '/' or ':'", required=True)

op("get", "/billing/by-ledger/accounts", "listBillingAccounts", "Billing", "Accounts billed in a cycle",
   "Consumers holding demand accounts in the ledger.", BR, params=[BL, q("cycle")], resp=(200, "object"),
   perm="billing.read")
op("post", "/billing/by-ledger/invoices", "raiseBillingInvoice", "Billing", "Raise an invoice",
   "Computes the tariff (fixed, per unit, slab, percent, DMN) and raises the demand per head.", BR, params=[BL],
   body="BillingInvoiceRequest", resp=(200, "object"), cmd="billing.invoice", perm="billing.write")
op("post", "/billing/by-ledger/collections", "recordBillingCollection", "Billing", "Record a collection",
   "Confirms the payment with its channel, allocates it oldest-due first (excess as advance) and issues a receipt. A "
   "payment the channel cannot confirm is refused here; staff record it with acceptUnverifiedPayment (decision 141).",
   BR, params=[BL], body="BillingCollectionRequest", resp=(200, "object"), cmd="billing.collect",
   perm="billing.write")
op("get", "/billing/by-ledger/consumers/by-consumer/arrears", "getBillingArrears", "Billing", "Arrears of a consumer",
   "Outstanding and overdue demand.", BR, params=[BL, q("consumer", desc="consumer id; may carry '/' or ':'", required=True)], resp=(200, "object"), perm="billing.read")
op("post", "/billing/by-ledger/consumers/by-consumer/penalties", "levyBillingPenalties", "Billing", "Levy penalties",
   "Penalty / interest per the tariff (grace days, rate).", BR, params=[BL, q("consumer", desc="consumer id; may carry '/' or ':'", required=True)],
   body="BillingPenaltyRequest", resp=(200, "object"), cmd="billing.penalty", perm="billing.write")
op("post", "/billing/by-ledger/waivers", "recordBillingWaiver", "Billing", "Record a waiver",
   "Writes off outstanding demand under an approved order.", BR, params=[BL], body="BillingWaiverRequest",
   resp=(200, "object"), cmd="billing.waive", perm="billing.admin")
op("post", "/billing/by-ledger/refunds", "refundBillingCollection", "Billing", "Refund a collection",
   "Reverses consumption and calls the payment rail once per operation (the Idempotency-Key).", BR, params=[BL],
   body="BillingRefundRequest", resp=(200, "BillingRefundResult"), cmd="billing.refund", perm="billing.admin")

# ---------------------------------------------------------------- unverified payments (RM-03, owner decision 141)
# Bound to W4-A's code (sangam-billing UnverifiedPayments, sangam-rest UnverifiedPaymentController; board CHANGE
# 2026-11-12T19:05:00Z) and W4-B (the ACCEPT_UNVERIFIED
# action in the existing authorisation model). Binding rules of decision 141 carried here:
#   - an explicit action ACCEPT_UNVERIFIED scoped to payment channel, tenant and office/counter; a generic billing or
#     channel permission never implies it; enforced at the shared application-service boundary (REST, internal calls,
#     jobs and packs alike), REST checks are an extra layer; packs never approve the exception;
#   - the actor is the authenticated server-side identity only; no request field names an actor;
#   - only explicitly configured channels; refused where the operating policy is unresolved;
#   - reason, receipt/evidence reference, payment details and an audit record (PAYMENT_ACCEPTED_UNVERIFIED) are required;
#   - PAYMENT_ACCEPTED_UNVERIFIED is a pending-verification state, never VERIFIED, settled or reconciled; no automatic
#     release of goods or services, clearing of liabilities or disbursement while pending;
#   - controlled resolution to VERIFIED, REJECTED or REVERSED keeps the original record and its audit history;
#   - no duplicate acceptance: one record per (tenant, channel, paymentRef); authorisation and state are rechecked
#     atomically at every transition.
# Owner decisions of 2026-11-13: 194 maker-checker at resolution only (the resolver differs from the acceptor;
# acceptance posts no money); 195 penalty and interest accrual pauses from acceptedAt (VERIFIED counts the payment from
# acceptedAt; REJECTED or REVERSED recalculates the paused accrual, audited); 196 rail reconciliation is the normal path
# to VERIFIED, a manual VERIFIED needs an evidence reference, maker-checker and its own audit event; 201 only signed-in
# staff accept or resolve (API keys and service identities are refused), rail reconciliation runs as the system job.
_UP_STATES = ["PAYMENT_ACCEPTED_UNVERIFIED", "VERIFIED", "REJECTED", "REVERSED"]
schema("UnverifiedPaymentRequest", "Accept a payment that its channel cannot confirm now (decision 141).",
       "consumer channel office paymentRef amount:MoneyAmount currency paidAt:dt payerName instrument reason "
       "evidenceRef details:smap",
       ["consumer", "channel", "office", "paymentRef", "amount", "currency", "paidAt", "reason", "evidenceRef"],
       descs={"channel": "A payment channel configured for unverified acceptance; any other channel is 403",
              "office": "Office or counter where the payment was taken; part of the ACCEPT_UNVERIFIED scope",
              "paymentRef": "The channel's payment reference; a second acceptance of the same channel and reference "
                            "is 409 (one record per tenant, channel and reference)",
              "amount": "Greater than zero; exact decimal string (MoneyAmount)",
              "currency": "ISO 4217; must be the ledger currency",
              "instrument": "How the payer paid, for example CHEQUE, DEMAND_DRAFT, CASH, UPI (free text)",
              "reason": "Why the channel could not confirm the payment",
              "evidenceRef": "Receipt or evidence reference (document key or physical receipt number)",
              "details": "Further payment details as text (bank, instrument number, date on the instrument)"})
SCHEMAS["UnverifiedPaymentRequest"]["properties"]["reason"]["minLength"] = 1
SCHEMAS["UnverifiedPaymentRequest"]["properties"]["evidenceRef"]["minLength"] = 1
schema("UnverifiedPaymentResolution", "How a pending unverified payment was resolved.",
       "outcome reason verificationRef evidenceRef resolvedBy resolvedAt:dt",
       ["outcome", "reason", "resolvedBy", "resolvedAt"], enums={"outcome": _UP_STATES[1:]},
       descs={"verificationRef": "The channel's confirmation reference (required for VERIFIED)",
              "evidenceRef": "Evidence of a manual VERIFIED (document key or receipt number; required for it, "
                             "decision 196); absent for a rail reconciliation",
              "resolvedBy": "Authenticated principal that resolved it (server-side identity); system:reconciliation "
                            "when the rail reconciliation job verified it (the normal path to VERIFIED)"})
schema("UnverifiedPayment", "A payment accepted unverified, and its state.",
       "id ledger consumer channel office paymentRef amount:MoneyAmount currency paidAt:dt state reason evidenceRef "
       "acceptedBy acceptedAt:dt ageDays:long policyVersion downstreamPermitted:list:s "
       "resolution:UnverifiedPaymentResolution",
       ["id", "ledger", "consumer", "channel", "office", "paymentRef", "amount", "currency", "paidAt", "state", "reason",
        "evidenceRef", "acceptedBy", "acceptedAt", "ageDays", "policyVersion", "downstreamPermitted"],
       enums={"state": _UP_STATES},
       descs={"state": "PAYMENT_ACCEPTED_UNVERIFIED is pending verification: never verified, settled or reconciled. "
                       "Penalty accrual on the arrears the payment covers pauses from the business date of acceptance "
                       "(decision 195): VERIFIED forgives the suppressed amounts (the payment counts from acceptance); "
                       "REJECTED or REVERSED posts each suppressed amount to the penalty account (audit "
                       "PAYMENT_UNVERIFIED_ACCRUAL_RESUMED)",
              "acceptedBy": "Authenticated principal that accepted it (server-side identity, never from the request)",
              "ageDays": "Whole days since acceptedAt (ageing; alerts per the channel policy)",
              "policyVersion": "Version of the channel's unverified-acceptance policy applied",
              "downstreamPermitted": "Actions the applied policy permits while the payment is pending; empty means "
                                     "none. Goods or services are not released, liabilities are not cleared and "
                                     "nothing is disbursed while pending unless a verified policy lists it",
              "resolution": "Present once the payment left PAYMENT_ACCEPTED_UNVERIFIED"})
schema("UnverifiedPaymentResolveRequest", "Resolve a pending unverified payment.",
       "outcome reason verificationRef evidenceRef", ["outcome", "reason"], enums={"outcome": _UP_STATES[1:]},
       descs={"outcome": "VERIFIED (the channel confirmed it), REJECTED (it never arrived) or REVERSED (it was "
                         "returned); the original record and its audit history stay",
              "verificationRef": "Required for VERIFIED: the channel's confirmation reference",
              "evidenceRef": "Required for VERIFIED (a manual VERIFIED, decision 196): document key or receipt number "
                             "of the evidence"})
SCHEMAS["UnverifiedPaymentResolveRequest"]["properties"]["evidenceRef"]["minLength"] = 1
SCHEMAS["UnverifiedPaymentResolveRequest"]["properties"]["reason"]["minLength"] = 1
_UP = "/billing/by-ledger/unverified-payments"
_UP_REQ = BR + ["RM-03", "R42"]
op("post", _UP, "acceptUnverifiedPayment", "Billing", "Accept a payment unverified",
   "Records a payment its channel cannot confirm now, in state PAYMENT_ACCEPTED_UNVERIFIED, with an audit record of "
   "the same name. Requires the ACCEPT_UNVERIFIED action on this channel, tenant and office (permission "
   "billing.accept-unverified, scoped); a generic billing permission is not enough (403 NOT_PERMITTED). Only "
   "signed-in staff: an API key or a service identity is 403 NOT_PERMITTED (decision 201). Nothing is posted to the "
   "ledger. 403 "
   "CHANNEL_NOT_ENABLED when the channel is not configured for unverified acceptance or its operating policy is "
   "unresolved. 400 VALIDATION_FAILED when the currency is not the ledger currency or the amount is not a MoneyAmount "
   "of it. 409 DUPLICATE when the channel and paymentRef were already accepted in the tenant, with the existing id (a retry with the same Idempotency-Key "
   "returns the stored answer). 422 LIMIT_EXCEEDED when the amount exceeds the policy limit.", _UP_REQ, params=[BL], body="UnverifiedPaymentRequest",
   resp=(201, "UnverifiedPayment"), cmd="billing.accept-unverified", perm="billing.accept-unverified", created=True)
op("get", _UP, "listUnverifiedPayments", "Billing", "Unverified payments",
   "Payments accepted unverified, oldest first, for reconciliation and ageing. Every filter applies before paging "
   "(size default 50, at most 500), with X-Total-Count.", _UP_REQ, list_=True,
   params=[BL, q("state", enum=_UP_STATES), q("channel"), q("office"),
           q("minAgeDays", "int", "Only payments pending at least this many days")],
   resp=(200, "array:UnverifiedPayment"), perm="billing.read", errors=STD_R)
op("get", _UP + "/{id}", "getUnverifiedPayment", "Billing", "An unverified payment", "One record with its resolution.",
   _UP_REQ, params=[BL, ID], resp=(200, "UnverifiedPayment"), perm="billing.read", errors=GET_E)
op("post", _UP + "/{id}/resolution", "resolveUnverifiedPayment", "Billing", "Resolve an unverified payment",
   "Manual resolution of a pending payment to VERIFIED, REJECTED or REVERSED; rail reconciliation "
   "is the normal path to VERIFIED and runs as the system job, not through this operation. Only signed-in staff: an "
   "API key or a service identity is 403 NOT_PERMITTED (decision 201). Maker-checker: the resolver must be a "
   "different principal from the acceptor (403 NOT_PERMITTED otherwise, decision 194). The state and the caller's "
   "authorisation are rechecked atomically with the transition (403 NOT_PERMITTED). A payment already resolved is "
   "409 INVALID_TRANSITION and changes nothing. A manual VERIFIED needs verificationRef and evidenceRef (400 "
   "VALIDATION_FAILED otherwise) and is audited as its own event PAYMENT_UNVERIFIED_MANUALLY_VERIFIED (decision "
   "196); REJECTED and REVERSED are audited as PAYMENT_UNVERIFIED_RESOLVED and post the penalty accrual paused since "
   "acceptance (decision 195).", _UP_REQ, params=[BL, ID], body="UnverifiedPaymentResolveRequest",
   resp=(200, "UnverifiedPayment"), cmd="billing.resolve-unverified", perm="billing.resolve-unverified")