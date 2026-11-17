# SPDX-License-Identifier: Apache-2.0
# sangam-connectors-health: inbound FHIR subscription notifications. The CDS Hooks endpoints (GET /cds-services,
# POST /cds-services/{id}) are fixed by HL7 CDS Hooks 2.0 at the server root, outside /api/v1, so they are not in this
# contract. Executed inside gen_openapi.py.

TAGS.append(("HealthHooks", "Inbound health interoperability: FHIR topic-based subscription notifications "
                            "(sangam-connectors-health)"))

schema("HealthHookResult", "Outcome of a notification: inbox messages written and missed events.",
       "kind messages:int missedEvents:long")

op("post", "/hooks/fhir/by-channel", "receiveFhirNotification", "HealthHooks", "Receive a FHIR subscription notification",
   "Public at the Sangam layer: the channel's shared secret header is checked (constant time) and each focus resource "
   "is written to the integration inbox (deduplicated, tenant-bound by the channel). ClaimResponse becomes "
   "sangam.ushealth.pasUpdate.", ["R38", "FC-02"], params=[q("channel", desc="Configured FHIR subscription channel; may carry '/' or ':'", required=True)],
   body={"required": True, "content": {"application/fhir+json": {"schema": {"type": "object"}},
                                       "application/json": {"schema": {"type": "object"}}}},
   resp=(200, "HealthHookResult"), errors=("BadRequest", "Unauthorized", "NotFound", "Unprocessable"))
public("/hooks/fhir/by-channel", "post")
# Owner decision 187: sangam-connectors-health is optional and off by default. With the module off or not installed,
# these operations (and the CDS Hooks endpoints at the server root) answer 501 CAPABILITY_NOT_INSTALLED (capability
# connectors-health), never an unmapped 404 (W4-K REQUEST 2026-11-12T19:02:19Z). Integrator ruling
# 2026-11-13T01:26:13Z: an absent optional module is 501 (ApiProblem.notInstalled, not retryable); 503 stays for
# temporary unavailability only.
_CH_OFF = {"description": "CAPABILITY_NOT_INSTALLED: the optional health connectors module (capability "
                          "connectors-health) is not installed or is off; it is off by default (decision 187). Not "
                          "retryable", "content": PROBLEM}
PATHS["/hooks/fhir/by-channel"]["post"]["responses"]["501"] = _CH_OFF

op("post", "/connectors-health/x12/835", "receiveRemittance835", TAGS[-1][0], "Hand over a received 835", "Routes each claim to its waiting case, else starts the later-remittance process once per trace number. An unreadable file becomes a poison item (422).", body={"required": True, "content": {"text/plain": {"schema": {"type": "string"}}, "application/edi-x12": {"schema": {"type": "string"}}}}, resp=(202, "object"), cmd="connectorshealth.remittance.receive", perm="ops.write")
PATHS["/connectors-health/x12/835"]["post"]["responses"]["501"] = _CH_OFF
