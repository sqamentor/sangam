# SPDX-License-Identifier: Apache-2.0
"""P1 bill/payment example worker (IX.4 P1, AC-01, AC-03) for the ``payment`` topic (``wf1_payment``).

Posts a payment instruction to the treasury/payment gateway with ``Idempotency-Key = operationId`` (R36), so a
retried attempt can never pay twice. A connect failure is safe to retry (nothing was sent); a timeout after
sending is reported as outcome-unknown (AC-03: payment success with lost response -> UNKNOWN_OUTCOME ->
reconciliation). A gateway rejection is the BPMN error ``PAYMENT_REJECTED``.

Environment: ``SANGAM_URL`` (default http://localhost:8080), either ``SANGAM_TOKEN`` or
``SANGAM_OIDC_TOKEN_URL`` + ``SANGAM_CLIENT_ID`` + ``SANGAM_CLIENT_SECRET`` (+ ``SANGAM_SCOPE``), optional
``SANGAM_TOPIC`` (default ``payment``), ``SANGAM_WORKER_ID``, ``SANGAM_SEAT`` and ``TREASURY_URL`` (unset = built-in simulator).
"""
from __future__ import annotations

import json
import logging
import os
import socket
import urllib.error
import urllib.request
from typing import Any

from sangam_sdk import (BpmnError, ClientCredentialsTokenProvider, NoAuth, OutcomeUnknown, SangamClient,
                        StaticTokenProvider, TaskContext, TaskFailed, TokenProvider, TypedValue, Worker)


def token_provider() -> TokenProvider:
    url = os.environ.get("SANGAM_OIDC_TOKEN_URL")
    if url:
        return ClientCredentialsTokenProvider(url, os.environ["SANGAM_CLIENT_ID"], os.environ["SANGAM_CLIENT_SECRET"],
                                              scope=os.environ.get("SANGAM_SCOPE"))
    token = os.environ.get("SANGAM_TOKEN")
    return StaticTokenProvider(token) if token else NoAuth()


TREASURY_URL = os.environ.get("TREASURY_URL")


def pay(ctx: TaskContext) -> dict[str, Any]:
    amount = ctx.decimal("amount")  # unwraps {"type":"Decimal","value":"..."} (R33)
    beneficiary = ctx.variable("beneficiaryAccount")
    bill_no = ctx.business_key
    if amount is None or amount <= 0:
        raise BpmnError("PAYMENT_REJECTED", f"bill {bill_no} has no positive amount")
    if not beneficiary:
        raise BpmnError("PAYMENT_REJECTED", f"bill {bill_no} has no beneficiary account")
    ctx.throw_if_cancelled()
    ctx.logger.info("p1.payment.instruct", "sending payment instruction", {"amount": str(amount)})

    if not TREASURY_URL:
        # simulator: deterministic reference derived from the operationId (same op -> same reference)
        return {"paymentStatus": "ACKNOWLEDGED", "paymentReference": "SIM-" + ctx.operation_id[:8],
                "paidAmount": TypedValue.decimal(amount)}

    body = json.dumps({"billNumber": bill_no, "amount": str(amount), "beneficiaryAccount": beneficiary,
                       "operationId": ctx.operation_id}).encode("utf-8")
    req = urllib.request.Request(TREASURY_URL + "/payment-instructions", data=body, method="POST",
                                 headers={"Content-Type": "application/json",
                                          "Idempotency-Key": ctx.idempotency_key})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            ack = json.loads(resp.read().decode("utf-8") or "{}")
            return {"paymentStatus": "ACKNOWLEDGED", "paymentReference": str(ack.get("reference", "")),
                    "paidAmount": TypedValue.decimal(amount)}
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        if e.code in (400, 422):
            raise BpmnError("PAYMENT_REJECTED", f"treasury rejected the instruction: {text}",
                            {"paymentStatus": "REJECTED"}) from e
        if e.code >= 500:
            raise OutcomeUnknown(f"treasury answered {e.code}; outcome unknown", text) from e
        raise TaskFailed(f"unexpected treasury status {e.code}") from e
    except (socket.timeout, TimeoutError) as e:
        raise OutcomeUnknown("treasury did not answer in time; payment may have been made",
                             f"POST {TREASURY_URL}/payment-instructions", {"billNumber": bill_no}) from e
    except urllib.error.URLError as e:
        if isinstance(e.reason, (socket.timeout, TimeoutError)):
            raise OutcomeUnknown("treasury did not answer in time; payment may have been made") from e
        raise TaskFailed("treasury unreachable (nothing sent)") from e


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    client = SangamClient(os.environ.get("SANGAM_URL", "http://localhost:8080"), token_provider=token_provider(),
                          seat=os.environ.get("SANGAM_SEAT"))
    worker = Worker(client, worker_id=os.environ.get("SANGAM_WORKER_ID"), max_concurrency=4)
    # a payment is irreversible: unexpected errors become outcome-unknown, never a blind retry
    worker.topic(os.environ.get("SANGAM_TOPIC", "payment"), lock_duration_ms=120_000, retries=3,
                 irreversible=True)(pay)
    worker.run()


if __name__ == "__main__":
    main()
