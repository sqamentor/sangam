# SPDX-License-Identifier: Apache-2.0
# M-025 (P2-028, R37 / E02): the step journal behind the SDK helper `context.step(name, fn)`. A worker records each
# side-effecting step of a leased work item under the work's stable operationId (VI.3 rule 4), so a retry after a crash
# or lease expiry replays the recorded result instead of calling the remote API again. Contract-first: the server side
# extends the operation ledger / CONNECTOR_CALL journal (sangam-integration ConnectorCallJournal, R37) and is written by
# the owning lane; the SDK helpers are W2-B's. Both calls are fenced like the other /work calls (VI.3 rule 2).
# NOT exactly-once (R37): a crash between the remote call and PUT leaves the step INTENT; the helper must then look the
# outcome up (capability class a/b) or report outcome-unknown (class c), never re-issue blindly.

schema("WorkStep", "One recorded step of a work item's operation (R37). Keyed by (operationId, name): stable across "
                   "retries, redelivery and lease expiry, so a later attempt sees what an earlier one recorded.",
       "operationId name state result:any providerRef error attemptId recordedAt:dt",
       enums={"state": ["INTENT", "SUCCEEDED", "FAILED"]},
       descs={"state": "INTENT: recorded before the remote call; SUCCEEDED/FAILED: the outcome. An INTENT found on replay "
                       "means the earlier attempt may or may not have had its effect (look the outcome up, R37).",
              "result": "The step's recorded result (R33 typed JSON); replayed as is by context.step",
              "attemptId": "The attempt that recorded the current state"})
schema("WorkStepRequest", "Record a step of the leased work (fenced by workerId, lockVersion, attemptId). INTENT first, "
                          "then the outcome; an identical repeat is a no-op, an outcome for a step already SUCCEEDED is "
                          "409 STEP_ALREADY_RECORDED.",
       "workerId lockVersion:long attemptId state result:any providerRef error", ("workerId", "lockVersion", "attemptId", "state"),
       enums={"state": ["INTENT", "SUCCEEDED", "FAILED"]})

_WS_REQ = ["R37", "E02", "VI.3", "A-2", "R36"]
_WS_P = [pp("id", "External work id"), pp("stepName", "Step name, unique within the work's operation (1-128 printable ASCII)")]
op("get", "/work/{id}/steps/{stepName}", "getWorkStep", "Work", "Replay a recorded step",
   "The step as recorded for this work's operationId; 404 when the step was never recorded (run it). The caller must hold "
   "the current lease: workerId, lockVersion and attemptId as query parameters (409 STALE_LEASE otherwise).",
   _WS_REQ, params=_WS_P + [q("workerId", required=True), q("lockVersion", "long", required=True), q("attemptId", required=True)],
   resp=(200, "WorkStep"), perm="work.execute", fenced=True,
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict", "Gone"))
op("get", "/work/{id}/steps", "listWorkSteps", "Work", "Steps recorded for a work item",
   "Every step recorded under the work's operationId, in recording order (for diagnosis and reconciliation; ops.read).",
   _WS_REQ, params=[pp("id", "External work id")], resp=(200, "array:WorkStep"), perm="ops.read",
   errors=("Unauthorized", "Forbidden", "NotFound"))
op("put", "/work/{id}/steps/{stepName}", "recordWorkStep", "Work", "Record a step",
   "Records the step's intent or outcome under the work's operationId, in the same transaction as the lease check. "
   "Fenced: a stale lease is 409 STALE_LEASE and records nothing; a deleted instance is 410 GONE_INSTANCE_DELETED.",
   _WS_REQ, params=_WS_P, body="WorkStepRequest", resp=(200, "WorkStep"), perm="work.execute", fenced=True,
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict", "Gone"))
