# SPDX-License-Identifier: Apache-2.0
# M-059 (P1-008; SANGAM.md 3.3d L6, R19, R25, R42, R49, ADR-D-26): model-declared query and update handlers. A process
# model declares named handlers with the extension elements sangam:query (read-only FEEL over instance state) and
# sangam:update (a validated change); deployment validates them. Contract-first: the engine parse listener and FEEL
# evaluation are the engine lane's, the controllers the REST lane's, the SDK methods W2-B's.
# R25: these are reviewed, versioned handlers from the deployed model, not client expressions; arguments are values only.
# R49 / VI.4 rule 5: an update is a modification. It needs a reason, re-checks permissions in the command transaction,
# and is bound to the payload hash and case version the caller saw (409 APPROVAL_INVALIDATED / REVISION_CONFLICT), so it
# cannot slip past an approval.

schema("HandlerParameter", "A declared handler argument.", "name type required:bool description",
       enums={"type": ["string", "number", "integer", "boolean", "date", "date-time", "decimal", "object", "array"]})
schema("InstanceHandler", "A query or update handler declared by the process model (sangam:query / sangam:update).",
       "name kind description parameters:list:HandlerParameter permission resultType changes:list:s",
       enums={"kind": ["QUERY", "UPDATE"]},
       descs={"permission": "Permission the caller needs in addition to the endpoint permission (from the model), if any",
              "changes": "UPDATE only: the variables the handler may change"})
schema("InstanceQueryRequest", "Arguments of a named query (values only; no expressions, R25).", "arguments:map")
schema("InstanceQueryResult", "The value of a named query, evaluated read-only under the instance's package (FEEL strict "
                              "mode, ADR-D-26) with field masks applied (K11).",
       "processInstanceId name definitionId packageHash result:any evaluatedAt:dt")
schema("InstanceUpdateRequest", "Arguments of a named update, bound to what the caller saw (VI.4 rule 5).",
       "arguments:map reason reasonCode payloadHash caseVersion:long", ["reason"],
       descs={"payloadHash": "Hex SHA-256 of the material payload the caller saw; a mismatch is 409 APPROVAL_INVALIDATED",
              "caseVersion": "Case version the caller saw; stale is 409 REVISION_CONFLICT"})
schema("InstanceUpdateResult", "Outcome of a named update; recorded as a class-B audit event (R42).",
       "processInstanceId name changedVariables:list:s caseVersion:long payloadHash auditEventId",
       descs={"payloadHash": "The material payload hash after the change",
              "caseVersion": "The case version after the change"})

_IH_REQ = ["L6", "R19", "R25", "ADR-D-26"]
_IH_NAME = pp("name", "Handler name declared in the model")
op("get", "/process-definitions/{id}/handlers", "listInstanceHandlers", "Processes", "Declared query and update handlers",
   "The sangam:query and sangam:update handlers the deployed model declares.", _IH_REQ, params=[ID],
   resp=(200, "array:InstanceHandler"), perm="process.read", errors=GET_E)
op("post", "/process-instances/{id}/queries/{name}", "queryProcessInstance", "Processes", "Run a named query",
   "Evaluates the model's named query read-only over the instance state; nothing changes. 404 for an unknown handler; "
   "422 VALIDATION_FAILED for missing or "
   "mistyped arguments; evaluation is bounded in time and size (R19).", _IH_REQ, params=[ID, _IH_NAME],
   body="InstanceQueryRequest", body_required=False, resp=(200, "InstanceQueryResult"), perm="process.read",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Unprocessable"))
op("post", "/process-instances/{id}/updates/{name}", "updateProcessInstance", "Processes", "Run a named update",
   "Validates the arguments against the handler, applies the declared changes in one command transaction and records "
   "the change (R42). A modification under R49: reason mandatory, permission re-checked, bound to payloadHash and "
   "caseVersion; obligations the change affects are re-evaluated. 404 unknown handler; 409 APPROVAL_INVALIDATED, "
   "REVISION_CONFLICT; 422 VALIDATION_FAILED, SOD_VIOLATION.", _IH_REQ + ["R42", "R49", "VI.4", "R41"],
   params=[ID, _IH_NAME], body="InstanceUpdateRequest", resp=(200, "InstanceUpdateResult"), cmd="process.update",
   perm="process.modify", errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict", "Unprocessable"))
