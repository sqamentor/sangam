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
# transaction that stores a template. W4-B named the permissions (ACK 2026-11-13T08:18:51Z): dlt.registration.read for
# the GET, dlt.registration.write for the four mutations, both tenant-scoped (tenant administration).

TAGS.append(("DltHooks", "Inbound India DLT SMS delivery reports (decision 304)"))
_DLT = "/hooks/sms-dlt/{tenant}/by-connector-id/delivery-reports"

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
   params=[pp("tenant"), q("connectorId", desc="The tenant's DLT SMS connector; may carry '/' or ':'", required=True),
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

# ---------------------------------------------------------------- decision 305: /admin/dlt-registration
TAGS.append(("DltRegistration", "The tenant's stored India DLT registration: principal entity, headers, content "
                                "templates and whitelisted URL prefixes (decision 305)"))
_DLTR = "/admin/dlt-registration"
_DLT_REQS = ["R60", "GOV-10", "decision 305"]
_DLT_W = _DLT_REQS + ["R41", "R42"]

schema("DltTemplate", "One DLT content template as registered with the operator.",
       "templateId header body", required=["templateId", "header", "body"],
       descs={"templateId": "The operator's template id: 1 to 64 letters, digits, '_' or '-'",
              "header": "The sender header the template is bound to",
              "body": "The template text with {#var#} placeholders, at most 2000 characters; a URL in it never "
                      "starts with a placeholder"})
schema("DltRegistration", "A tenant's stored DLT registration. A tenant with nothing registered has no entityId, "
                          "empty lists and revision 0.",
       "entityId headers:list:s templates:list:DltTemplate urlPrefixes:list:s revision:long",
       required=["headers", "templates", "urlPrefixes", "revision"],
       descs={"entityId": "The principal-entity id; absent until one is registered",
              "headers": "Registered sender headers",
              "templates": "Registered content templates",
              "urlPrefixes": "Whitelisted URL prefixes (stored in lower case)",
              "revision": "Moves by one with every change of the registration"})
schema("DltRegistrationChange", "Outcome of a registration change.", "changed:bool revision:long",
       required=["changed", "revision"],
       descs={"changed": "false when the registration already held exactly this entry (nothing was written, no audit "
                         "event)",
              "revision": "The tenant's revision after the call"})
schema("DltEntityRequest", "The principal-entity id to register.", "entityId", required=["entityId"],
       descs={"entityId": "1 to 64 letters or digits"})
schema("DltHeaderRequest", "A sender header to register.", "header", required=["header"],
       descs={"header": "6 letters or digits, with an optional TRAI suffix -P, -S, -T or -G"})
schema("DltUrlPrefixRequest", "A URL prefix to whitelist.", "prefix", required=["prefix"],
       descs={"prefix": "An http or https URL with a host"})

_DLT_422 = ("422 with the DLT reason as code (ENTITY_INVALID, HEADER_INVALID, TEMPLATE_INVALID or URL_INVALID) when "
            "the entry is refused; nothing is written. 501 CAPABILITY_NOT_INSTALLED without sangam-integration.")
op("get", _DLTR, "getDltRegistration", "DltRegistration", "The tenant's DLT registration",
   "The caller's tenant's stored registration: entity, headers, templates, URL prefixes and revision. Start-up "
   "configuration only seeds it; what is stored here is what the DLT SMS connector validates against. 501 "
   "CAPABILITY_NOT_INSTALLED without sangam-integration.", _DLT_REQS,
   resp=(200, "DltRegistration"), perm="dlt.registration.read", errors=("Unauthorized", "Forbidden", "NotImplemented"))
for _m, _sub, _opid, _sum, _body, _ctype, _what in [
        ("put", "/entity", "setDltEntity", "Set the principal entity", "DltEntityRequest", "dlt.registration.entity.set",
         "Sets the tenant's principal-entity id."),
        ("post", "/headers", "addDltHeader", "Register a sender header", "DltHeaderRequest",
         "dlt.registration.header.add", "Registers a sender header."),
        ("post", "/templates", "addDltTemplate", "Register a content template", "DltTemplate",
         "dlt.registration.template.add",
         "Registers a content template, or replaces the header and body of the template with the same id."),
        ("post", "/urls", "addDltUrlPrefix", "Whitelist a URL prefix", "DltUrlPrefixRequest",
         "dlt.registration.url.add", "Whitelists a URL prefix.")]:
    op(_m, _DLTR + _sub, _opid, "DltRegistration", _sum,
       _what + " The change, the tenant's new revision and the audit event DLT_TEMPLATE_REGISTERED (chain "
       "dlt:registry, with the actor) are written in one transaction; an entry that is already registered unchanged "
       "answers changed=false and writes nothing. " + _DLT_422, _DLT_W,
       body=_body, resp=(200, "DltRegistrationChange"), cmd=_ctype, perm="dlt.registration.write",
       errors=("BadRequest", "Unauthorized", "Forbidden", "NotImplemented"))
    PATHS[_DLTR + _sub][_m]["responses"]["422"] = {
        "description": "ENTITY_INVALID, HEADER_INVALID, TEMPLATE_INVALID or URL_INVALID: the entry is refused by the "
                       "DLT registration checks. Nothing is written",
        "content": PROBLEM}
