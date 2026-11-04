# SPDX-License-Identifier: Apache-2.0
# sangam-connectors-health: inbound FHIR subscription notifications. The CDS Hooks endpoints (GET /cds-services,
# POST /cds-services/{id}) are fixed by HL7 CDS Hooks 2.0 at the server root, outside /api/v1, so they are not in this
# contract. Executed inside gen_openapi.py.

TAGS.append(("HealthHooks", "Inbound health interoperability: FHIR topic-based subscription notifications "
                            "(sangam-connectors-health)"))

schema("HealthHookResult", "Outcome of a notification: inbox messages written and missed events.",
       "kind messages:int missedEvents:long")

op("post", "/hooks/fhir/{channel}", "receiveFhirNotification", "HealthHooks", "Receive a FHIR subscription notification",
   "Public at the Sangam layer: the channel's shared secret header is checked (constant time) and each focus resource "
   "is written to the integration inbox (deduplicated, tenant-bound by the channel). ClaimResponse becomes "
   "sangam.ushealth.pasUpdate.", ["R38", "FC-02"], params=[pp("channel")],
   body={"required": True, "content": {"application/fhir+json": {"schema": {"type": "object"}},
                                       "application/json": {"schema": {"type": "object"}}}},
   resp=(200, "HealthHookResult"), errors=("BadRequest", "Unauthorized", "NotFound", "Unprocessable"))
public("/hooks/fhir/{channel}", "post")
