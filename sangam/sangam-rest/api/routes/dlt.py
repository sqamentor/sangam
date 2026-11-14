# SPDX-License-Identifier: Apache-2.0
# Owner decisions 304 and 305 (India DLT SMS), contract-first for W4-H (sangam-integration connector/gov), agreed on the
# board (W4-H REQUEST 2026-11-13T07:40:04Z, W4-M ACK 2026-11-13T07:50:45Z). Executed inside gen_openapi.py.
#
# Decision 304: the SMS provider posts delivery reports to a signed webhook; a status lookup is the fallback (no REST
# surface). NOTIFICATION_DELIVERED is written once per message, whichever path confirms it. The signature is the
# authentication, as for /hooks/eventgrid: the route has no user security, and a delivery whose signature is missing,
# wrong or outside the tolerance is refused before the body is read as reports.
# Decision 305: the per-tenant DLT registration (entity, headers, templates, URL prefixes) is stored and administered
# through /admin/dlt-registration; start-up configuration only seeds it. DLT_TEMPLATE_REGISTERED is written in the
# transaction that stores a template. The admin routes are published once W4-B names their permission.

TAGS.append(("DltHooks", "Inbound India DLT SMS delivery reports (decision 304)"))
_DLT = "/hooks/sms-dlt/{tenant}/{connectorId}/delivery-reports"

schema("DltDeliveryReport", "One SMS delivery report from the provider.",
       "reportId clientRef messageId status reason reportedAt:dt", required=["status"],
       descs={"reportId": "The provider's report id; when absent the report is identified by the hash of its body. "
                          "A report id already received is acknowledged as a duplicate without a second write",
              "clientRef": "The operationId the message was sent with; clientRef or messageId must be present",
              "messageId": "The provider's message id returned at send; clientRef or messageId must be present",
              "status": "The provider's delivery status",
              "reason": "The provider's reason text for a failed or pending delivery",
              "reportedAt": "When the provider recorded the status"})
schema("DltDeliveryReportResult", "Outcome of a delivery-report post.",
       "accepted:int duplicates:int ignored:int",
       descs={"accepted": "Reports recorded",
              "duplicates": "Reports already received (same report id, or same body hash), acknowledged without a "
                            "second write",
              "ignored": "Reports that match no message sent through this connector, acknowledged and not written"})

op("post", _DLT, "receiveDltDeliveryReports", "DltHooks", "Receive SMS delivery reports",
   "Public at the Sangam layer: the X-Sangam-Signature header is the authentication and is checked first. A missing, "
   "wrong or stale signature (outside 5 minutes) is 401 WEBHOOK_SIGNATURE_INVALID; a tenant or connector that is not "
   "a configured DLT SMS connector is 404. The body is one report or an array of reports; a body that is not, or a "
   "report with neither clientRef nor messageId, is 422 DLT_DELIVERY_REPORT_INVALID and nothing is written. Each "
   "accepted report updates the message's delivery state; NOTIFICATION_DELIVERED is written once per delivered message "
   "(decision 304).", ["R60", "decision 304"],
   params=[pp("tenant"), pp("connectorId", "The tenant's DLT SMS connector"),
           {"name": "X-Sangam-Signature", "in": "header", "required": True, "schema": {"type": "string"},
            "description": "t=<epoch seconds>,v1=<hex HMAC-SHA256(secret, t + '.' + body)>; refused when t is more "
                           "than 5 minutes from the server clock"}],
   body={"required": True, "content": {"application/json": {"schema": {"oneOf": [
       {"$ref": "#/components/schemas/DltDeliveryReport"},
       {"type": "array", "items": {"$ref": "#/components/schemas/DltDeliveryReport"}}]}}}},
   resp=(200, "DltDeliveryReportResult"), errors=("Unauthorized", "NotFound", "Unprocessable"))
public(_DLT, "post")
# The shared 401 and 422 texts describe user tokens and business rules; this hook has neither, so it names its own.
PATHS[_DLT]["post"]["responses"]["401"] = {
    "description": "WEBHOOK_SIGNATURE_INVALID: X-Sangam-Signature is missing, does not verify with the connector's "
                   "secret, or its t is more than 5 minutes from the server clock. Nothing is read or written",
    "content": PROBLEM}
PATHS[_DLT]["post"]["responses"]["422"] = {
    "description": "DLT_DELIVERY_REPORT_INVALID: the body is not a report or an array of reports, or a report has "
                   "neither clientRef nor messageId, or no status. Nothing is written",
    "content": PROBLEM}
