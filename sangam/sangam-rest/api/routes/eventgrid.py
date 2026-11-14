# SPDX-License-Identifier: Apache-2.0
# SL-170, owner decision 189: Azure Event Grid inbound (webhook delivery) into the integration inbox. Off by default and
# failing closed: a tenant/channel pair that is not configured and enabled answers 404, and a delivery that does not
# authenticate answers 401 before the body is read as events. Contract-first for W4-H (sangam-integration
# publish/cloud AzureChannels; the inbound side is new). Service Bus binary mode (the other half of decision 189) is a
# channel setting with no REST surface. Executed inside gen_openapi.py.
#
# Authentication is per channel, and a channel must configure at least one method (none configured: the channel
# does not start, so its hook answers 404):
#   - a Microsoft Entra ID bearer token (Authorization: Bearer) whose issuer, audience and signature are validated
#     against the channel's configured tenant and application;
#   - a shared secret sent by Event Grid as a delivery-property header (the channel names the header), compared in
#     constant time. The secret never appears in a URL, log or error.
# Owner decision 219: both methods are supported; the shared secret stays an alternative to the Entra token, and each
# fails closed (a delivery that satisfies no configured method is refused).
# Both handshakes are supported: the CloudEvents 1.0 webhook abuse-protection OPTIONS request, and the Event Grid
# schema SubscriptionValidationEvent answered with {validationResponse}. Accepted events are written to the integration
# inbox, deduplicated on the event id per channel, and tenant-bound by the channel.

TAGS.append(("EventGridHooks", "Inbound Azure Event Grid deliveries (SL-170, decision 189); off by default"))
_EG = "/hooks/eventgrid/{tenant}/{channel}"
_EG_P = [pp("tenant"), pp("channel", "Configured Event Grid inbound channel of the tenant")]
_EG_REQ = ["SL-170", "R38"]
_EG_E = ("BadRequest", "Unauthorized", "NotFound")

schema("EventGridHookResult", "Outcome of an Event Grid delivery.",
       "validationResponse accepted:int duplicates:int",
       descs={"validationResponse": "Only for a SubscriptionValidationEvent: the event's validationCode; the body "
                                    "then has this member alone",
              "accepted": "Events written to the inbox",
              "duplicates": "Events already received (same id on this channel), acknowledged without a second write"})

op("options", _EG, "eventGridHookHandshake", "EventGridHooks", "CloudEvents webhook validation",
   "CloudEvents 1.0 webhook abuse protection. Answers 200 with WebHook-Allowed-Origin only when the channel is "
   "configured and enabled and WebHook-Request-Origin equals the channel's allowed origin; otherwise 403 (origin) or "
   "404 (channel). The channel's authentication is checked as for a delivery.", _EG_REQ,
   params=_EG_P + [
                   {"name": "WebHook-Request-Origin", "in": "header", "required": True, "schema": {"type": "string"},
                    "description": "Origin of the delivering service, for example eventgrid.azure.net"},
                   {"name": "WebHook-Request-Rate", "in": "header", "required": False,
                    "schema": {"type": "string"}, "description": "Requested deliveries per minute"}],
   resp=(200, None), errors=("Unauthorized", "Forbidden", "NotFound"))
PATHS[_EG]["options"]["responses"]["200"]["headers"] = {
    "WebHook-Allowed-Origin": {"description": "The accepted origin", "schema": {"type": "string"}},
    "WebHook-Allowed-Rate": {"description": "Accepted deliveries per minute, or *", "schema": {"type": "string"}}}
public(_EG, "options")
op("post", _EG, "receiveEventGridDelivery", "EventGridHooks", "Receive an Event Grid delivery",
   "Public at the Sangam layer; the channel's authentication is checked first: a Microsoft Entra ID delivery token "
   "(Authorization: Bearer; issuer, audience and signature validated against the channel's configured tenant and "
   "application) or the delivery-property secret header, whichever the channel configures (decision 219). A delivery "
   "that satisfies no configured method is 401 WEBHOOK_SIGNATURE_INVALID (fail closed), 404 when the channel is not configured or not "
   "enabled. CloudEvents structured single or batch, or the Event Grid schema (an array). A "
   "Microsoft.EventGrid.SubscriptionValidationEvent is answered with {validationResponse} and writes nothing; other "
   "events are written to the inbox (deduplicated by id) and acknowledged. A content type the channel does not accept "
   "is 400.", _EG_REQ, params=_EG_P,
   body={"required": True, "content": {
       "application/cloudevents+json": {"schema": {"type": "object", "additionalProperties": True}},
       "application/cloudevents-batch+json": {"schema": {"type": "array", "items": {"type": "object"}}},
       "application/json": {"schema": {"type": "array", "items": {"type": "object"}}}}},
   resp=(200, "EventGridHookResult"), errors=_EG_E)
public(_EG, "post")
