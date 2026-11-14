# SPDX-License-Identifier: Apache-2.0
# M-060, owner decision 149 (W4Q-04): continue-as-new. REST shape over the W4-E lifecycle contract v1 (board CHANGE
# 2026-11-12T19:33:01Z): one engine command in one transaction (W4-E; chain table through W4-G). Executed inside
# gen_openapi.py.
#
# Lifecycle contract v1 (W4-E):
#   - allowed only on an active, not suspended root process instance of the caller's engine tenant (decision 145);
#     a sub-instance, an ended, suspended or already continued instance is 409 CONTINUATION_NOT_ALLOWED and nothing
#     changes (for an already continued instance the problem carries successorId);
#   - permission process.continue (named by W4-B); server-side identity only;
#   - one engine transaction: lock the root, check the state, insert the chain row, start the successor on the
#     predecessor's definition id (never the latest; another version only when the request names an authorised
#     processDefinitionId), carry the business key and the explicit carry list of variables (typed values copied,
#     exact decimals kept, decision 138), then delete the predecessor with delete reason continued-as-new:<successorId>
#     (history kept); any failure rolls everything back;
#   - idempotency: (tenant, continuationRequestId) is unique; a retry returns the committed successor unchanged;
#   - pending work: open user tasks, external tasks, jobs, timers and message subscriptions of the predecessor end
#     with it; the successor model recreates what it needs from carried variables; business deadlines are carried as
#     instants and never reset (an overdue deadline fires at once); stale workers and callbacks on the predecessor
#     fail as not found; late messages correlate by business key and tenant to the successor;
#   - record: chain row plus audit event PROCESS_CONTINUED_AS_NEW (actor, reason, request id, predecessor, successor,
#     generation) through the outbox; no claim of atomicity with external systems.

schema("ContinueAsNewRequest", "Close this instance and continue it as one new instance (decision 149).",
       "continuationRequestId reason processDefinitionId carryVariables:list:s variables:map",
       ["continuationRequestId", "reason"],
       descs={"continuationRequestId": "Stable id of this continuation chosen by the caller, 1..255 characters (a "
                                       "UUID is recommended), unique per tenant; a retry with the same id on the "
                                       "same instance returns the committed successor unchanged, the same id on "
                                       "another instance is 409 CONTINUATION_NOT_ALLOWED (REQUEST_ID_REUSED)",
              "reason": "Mandatory reason for the audit record (R42)",
              "processDefinitionId": "Definition id of the successor. Absent: the predecessor's own definition id "
                                     "(never the latest). Another version of the same definition key only, and only "
                                     "when the caller may start it (403 otherwise); another key is 400 "
                                     "VALIDATION_FAILED, an unknown id or one of another tenant 404",
              "carryVariables": "Names of predecessor variables copied to the successor with their exact types; a "
                                "name the predecessor does not have, a blank or a repeated name is 400 "
                                "VALIDATION_FAILED",
              "variables": "Further successor variables (R33 typed values; exact decimals); a name also in "
                           "carryVariables is 400 VALIDATION_FAILED"})
SCHEMAS["ContinueAsNewRequest"]["properties"]["continuationRequestId"]["minLength"] = 1
SCHEMAS["ContinueAsNewRequest"]["properties"]["continuationRequestId"]["maxLength"] = 255
SCHEMAS["ContinueAsNewRequest"]["properties"]["reason"]["minLength"] = 1
schema("ContinuationEnded", "Pending items of one kind that ended with the predecessor.",
       "kind ended:int", ["kind", "ended"],
       enums={"kind": ["USER_TASK", "EXTERNAL_TASK", "JOB", "TIMER", "MESSAGE_SUBSCRIPTION"]})
schema("ContinueAsNewResult", "The successor of a continued instance.",
       "requestId predecessorId successorId chainId generation:int businessKey processDefinitionId outcome actor "
       "reason continuedAt:dt ended:list:ContinuationEnded",
       ["requestId", "predecessorId", "successorId", "chainId", "generation", "processDefinitionId", "outcome",
        "actor", "reason", "continuedAt", "ended"],
       enums={"outcome": ["CREATED", "EXISTING"]},
       descs={"chainId": "Id of the continuation chain (the first instance of the chain)",
              "businessKey": "Business key carried to the successor; absent when the predecessor had none",
              "generation": "Position of the successor in the chain; the first instance is generation 0",
              "processDefinitionId": "Definition id the successor was started on",
              "outcome": "CREATED by this call, or EXISTING when the continuationRequestId was already committed",
              "actor": "Authenticated principal that continued the instance",
              "ended": "Pending items of the predecessor that ended with it, per kind; empty when outcome is "
                       "EXISTING"})
schema("ProcessContinuationLink", "One link of a continuation chain.",
       "predecessorId successorId generation:int requestId actor reason continuedAt:dt",
       ["predecessorId", "successorId", "generation", "requestId", "actor", "reason", "continuedAt"])
schema("ProcessContinuationChain", "The continuation chain an instance belongs to, oldest link first.",
       "chainId instanceIds:list:s links:list:ProcessContinuationLink", ["chainId", "instanceIds", "links"],
       descs={"instanceIds": "Every instance of the chain in order; the last one is the current instance"})

_CAN_REQ = ["M-060", "R41", "R42", "VI.5"]
op("post", "/process-instances/{id}/continue-as-new", "continueProcessInstanceAsNew", "Processes", "Continue as new",
   "Ends the active root instance and starts exactly one successor in one transaction, keeping the business key, the "
   "definition id and the named variables exactly. 409 CONTINUATION_NOT_ALLOWED, nothing changed, with extension "
   "reason SUB_INSTANCE, SUSPENDED, ENDED, ALREADY_CONTINUED (then the problem also carries successorId) or "
   "REQUEST_ID_REUSED (the continuationRequestId was used for another instance); an instance of another tenant is "
   "404 (decision 145). A retry with the same continuationRequestId answers 200 with outcome EXISTING and the "
   "committed successor (ended empty). Needs PROCESS_INSTANCE MODIFY on the instance and, for another named "
   "version, PROCESS_DEFINITION START. Open user tasks, external "
   "tasks, jobs, timers and message subscriptions end with the predecessor; stale workers and callbacks on it get "
   "404; late messages correlate by business key and tenant to the successor. Audited as PROCESS_CONTINUED_AS_NEW.",
   _CAN_REQ, params=[ID], body="ContinueAsNewRequest", resp=(200, "ContinueAsNewResult"),
   cmd="process.continue-as-new", perm="process.continue",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound"))
op("get", "/process-instances/{id}/continuations", "getProcessContinuationChain", "Processes", "Continuation chain",
   "The chain of the instance (predecessors and successors), with generation, actor, reason and request id of every "
   "link. An instance never continued has a chain of one instance and no links.", _CAN_REQ, params=[ID],
   resp=(200, "ProcessContinuationChain"), perm="process.read", errors=GET_E)

# Integrator ruling 2026-11-13T01:26:13Z: an absent optional module is 501 CAPABILITY_NOT_INSTALLED (not retryable).
# ProcessContinuationController.port answers it when the host has no continuation port bound, after the permission
# check (and, on the POST, after the body checks), so 401, 403 and 400 come first.
_CAN_OFF = {"description": "CAPABILITY_NOT_INSTALLED: this server has no process continuation engine (sangam-engine "
                           "continuation port not bound). Not retryable", "content": PROBLEM}
PATHS["/process-instances/{id}/continue-as-new"]["post"]["responses"]["501"] = _CAN_OFF
PATHS["/process-instances/{id}/continuations"]["get"]["responses"]["501"] = _CAN_OFF
