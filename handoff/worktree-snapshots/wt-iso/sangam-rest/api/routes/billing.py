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

BL = pp("ledger", "receivable ledger id (e.g. DEMAND)")

op("get", "/billing/{ledger}/accounts", "listBillingAccounts", "Billing", "Accounts billed in a cycle",
   "Consumers holding demand accounts in the ledger.", BR, params=[BL, q("cycle")], resp=(200, "object"),
   perm="billing.read")
op("post", "/billing/{ledger}/invoices", "raiseBillingInvoice", "Billing", "Raise an invoice",
   "Computes the tariff (fixed, per unit, slab, percent, DMN) and raises the demand per head.", BR, params=[BL],
   body="BillingInvoiceRequest", resp=(200, "object"), cmd="billing.invoice", perm="billing.write")
op("post", "/billing/{ledger}/collections", "recordBillingCollection", "Billing", "Record a collection",
   "Confirms the payment with its channel, allocates it oldest-due first (excess as advance) and issues a receipt.",
   BR, params=[BL], body="BillingCollectionRequest", resp=(200, "object"), cmd="billing.collect",
   perm="billing.write")
op("get", "/billing/{ledger}/consumers/{consumer}/arrears", "getBillingArrears", "Billing", "Arrears of a consumer",
   "Outstanding and overdue demand.", BR, params=[BL, pp("consumer")], resp=(200, "object"), perm="billing.read")
op("post", "/billing/{ledger}/consumers/{consumer}/penalties", "levyBillingPenalties", "Billing", "Levy penalties",
   "Penalty / interest per the tariff (grace days, rate).", BR, params=[BL, pp("consumer")],
   body="BillingPenaltyRequest", resp=(200, "object"), cmd="billing.penalty", perm="billing.write")
op("post", "/billing/{ledger}/waivers", "recordBillingWaiver", "Billing", "Record a waiver",
   "Writes off outstanding demand under an approved order.", BR, params=[BL], body="BillingWaiverRequest",
   resp=(200, "object"), cmd="billing.waive", perm="billing.admin")
op("post", "/billing/{ledger}/refunds", "refundBillingCollection", "Billing", "Refund a collection",
   "Reverses consumption and calls the payment rail once per operation (the Idempotency-Key).", BR, params=[BL],
   body="BillingRefundRequest", resp=(200, "BillingRefundResult"), cmd="billing.refund", perm="billing.admin")
