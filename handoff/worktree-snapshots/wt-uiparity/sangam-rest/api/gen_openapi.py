# SPDX-License-Identifier: Apache-2.0
"""Generates sangam-rest/api/sangam-api.yaml (OpenAPI 3.1) from the WS-REST route table and port records."""
import sys
import yaml

OUT = sys.argv[1]

# ---------------------------------------------------------------- schemas
T = {
    "s": {"type": "string"}, "int": {"type": "integer", "format": "int32"}, "long": {"type": "integer", "format": "int64"},
    "bool": {"type": "boolean"}, "num": {"type": "number", "format": "double"},
    "dt": {"type": "string", "format": "date-time"}, "date": {"type": "string", "format": "date"},
    "any": {}, "map": {"$ref": "#/components/schemas/VariableMap"},
    "obj": {"type": "object", "additionalProperties": True},
    "smap": {"type": "object", "additionalProperties": {"type": "string"}},
    "lmap": {"type": "object", "additionalProperties": {"type": "integer", "format": "int64"}},
}
SCHEMAS = {}


def typ(t):
    nullable = False
    if t.startswith("list:"):
        return {"type": "array", "items": typ(t[5:])}
    if t in T:
        return dict(T[t])
    return {"$ref": "#/components/schemas/" + t}


def schema(name, desc, fields, required=(), enums=None, descs=None):
    props = {}
    for f in fields.split():
        n, _, t = f.partition(":")
        p = typ(t or "s")
        if enums and n in enums:
            p["enum"] = enums[n]
        if descs and n in descs:
            if "$ref" in p:
                p = {"allOf": [p], "description": descs[n]}
            else:
                p["description"] = descs[n]
        props[n] = p
    s = {"type": "object", "description": desc, "properties": props}
    if required:
        s["required"] = list(required)
    SCHEMAS[name] = s


SCHEMAS["Problem"] = {
    "type": "object",
    "description": "RFC 9457 problem details. `type` is derived from `code` (https://sangam.dev/problems/<code-in-kebab-case>).",
    "required": ["type", "title", "status"],
    "properties": {
        "type": {"type": "string", "format": "uri", "examples": ["https://sangam.dev/problems/stale-lease"]},
        "title": {"type": "string"},
        "status": {"type": "integer", "format": "int32"},
        "detail": {"type": "string"},
        "instance": {"type": "string", "format": "uri-reference"},
        "code": {"description": "Stable machine code (X.4.0.4, X.2.2.6). Clients must accept unknown codes.",
                 "anyOf": [{"type": "string", "enum": [
                     "STALE_LEASE", "GONE_INSTANCE_DELETED", "CONFLICT_RETRY_EXHAUSTED", "BACKPRESSURE", "API_SUNSET",
                     "ACTION_NOT_PERMITTED", "FORM_VERSION_STALE", "FORM_VERSION_OUTDATED", "TASK_ALREADY_CLAIMED",
                     "APPROVAL_INVALIDATED", "SOD_VIOLATION", "POWER_LIMIT_EXCEEDED", "IDEMPOTENCY_KEY_REQUIRED",
                     "IDEMPOTENCY_KEY_INVALID", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH",
                     "IDEMPOTENCY_IN_PROGRESS", "DRAFT_CONFLICT", "REVISION_CONFLICT", "NOT_FOUND",
                     "VALIDATION_FAILED", "MODEL_INVALID", "ENGINE_REJECTED", "CAPABILITY_NOT_INSTALLED",
                     "SESSION_EXPIRED", "INVALID_TRANSITION", "SEGREGATION_OF_DUTIES", "UNAUTHENTICATED",
                     "SEAT_NOT_HELD", "REASON_REQUIRED", "EVIDENCE_REQUIRED", "DEBUGGER_DISABLED", "LEGAL_HOLD",
                     "BULK_NOT_ALLOWED", "DOCUMENT_REJECTED", "FORMAT_NOT_AVAILABLE", "PRECONDITION_REQUIRED",
                     "PAYLOAD_TOO_LARGE", "INTERNAL_ERROR", "EVENT_IN_FLIGHT", "NOT_RETIRED",
                     "INVALID_BATCH_STATE"]}, {"type": "string"}]},
        "traceId": {"type": "string", "description": "W3C trace id of the request (R13)"},
    },
    "additionalProperties": True,
}
SCHEMAS["TypedValue"] = {
    "type": "object",
    "description": "R33 typed JSON value. Money is never a binary float; decimals and large integers are strings (E18, T18).",
    "required": ["type", "value"],
    "properties": {
        "type": {"type": "string", "enum": ["Decimal", "BigInteger", "Long", "Integer", "Boolean", "String", "Date",
                                             "LocalDate", "Json", "Bytes"]},
        "value": {"description": "String form for Decimal/BigInteger/Long/Date/LocalDate/Bytes(base64); any JSON for Json"},
    },
    "examples": [{"type": "Decimal", "value": "1234.50"}],
}
SCHEMAS["MoneyAmount"] = {
    "type": "string",
    "pattern": "^-?[0-9]+([.][0-9]+)?$",
    "description": ("Money convention for EVERY endpoint (SD-398, SG-20): an amount is a plain decimal string in MAJOR units "
                    "of the ISO 4217 currency given next to it (field `currency`, or the ledger / tariff currency), e.g. "
                    "\"100000.00\" INR. It must not have more fraction digits than the currency's minor unit (INR/USD 2, "
                    "JPY 0, KWD 3): \"0.001\" INR is rejected with VALIDATION_FAILED, never rounded. No exponent, no JSON "
                    "number, no binary float. The server stores integer minor units (org.sangam.core.money.Money) and "
                    "returns amounts with exactly the currency's fraction digits. Directional operations (commit, "
                    "encumber, pay, allocate) take amounts > 0; reversals are separate operations. Computed documents "
                    "(bills, penalties) are rounded once per document total and the lines sum to the total exactly."),
    "examples": ["100000.00", "0.01", "1.230"],
}
SCHEMAS["VariableMap"] = {
    "type": "object",
    "description": ("Variables by name. Plain JSON values pass through (a JSON number with a fraction is read as an exact "
                    "decimal). Typed values use the TypedValue form, e.g. {\"amount\":{\"type\":\"Decimal\",\"value\":\"1234.50\"}} (R33)."),
    "additionalProperties": {"anyOf": [{"$ref": "#/components/schemas/TypedValue"}, {}]},
}
SCHEMAS["ReasonRequest"] = {"type": "object", "description": "A mandatory reason for an audited action (R42, R63).",
                            "required": ["reason"], "properties": {"reason": {"type": "string"}}}

LOCK_REQ = ("workerId", "lockVersion")
schema("SeatHolding", "A seat held by the caller on the action date (IX.5 rules 1, 4).",
       "seatId seatName designation orgUnitName kind actingForPersonName validFrom:date validTo:date pendingApproval:bool",
       enums={"kind": ["SUBSTANTIVE", "ADDITIONAL_CHARGE", "LEAVE_SUBSTITUTE"]})
schema("Me", "The authenticated caller: identity, tenant, personas (roles), seats held, current seat context and permissions (K1, S02).",
       "tenantId tenantName principalId userId displayName groups:list:s roles:list:s seats:list:SeatHolding seatId actingForSeatId "
       "authMethod permissions:list:s preferredLanguage",
       descs={"roles": "UI personas: TASK_USER, OPERATOR, PLATFORM_OPERATOR, ADMIN, PLATFORM_ADMIN, MODELLER, REVIEWER, ANALYST, AUDITOR, INTEGRATION, CITIZEN"})
# Work
schema("TopicRequest", "Topic subscription for fetch-and-lock. Lease default 5 min, maximum 24 h (VI.3 rule 3).",
       "name lockDurationMs:long variables:list:s localVariables:bool processDefinitionKey businessKey", ["name"])
schema("FetchAndLockRequest", "Long-poll fetch-and-lock (VI.3, FC-36).",
       "workerId topics:list:TopicRequest maxTasks:int asyncResponseTimeoutMs:long usePriority:bool", ["workerId", "topics"])
schema("ExternalWork", "A locked unit of external work. operationId = UUIDv5(tenant, root, activityInstanceId, loop) is stable across retries, redelivery and lease expiry; forward it downstream as the idempotency key (VI.3 rule 4, R36).",
       "id topic tenantId processInstanceId processDefinitionKey activityId activityInstanceId businessKey variables:map "
       "lockVersion:long attemptId operationId lockExpiresAt:dt retries:int priority:long errorMessage operation:WorkOperation",
       descs={"operation": "Ledger state of operationId, recorded by the server when it leased the work (intent before dispatch, R37). mayDispatch=false with state SUCCEEDED: complete from storedOutcome without calling the downstream again (SL-16)."})
schema("WorkOperation", "Operation-ledger entry of a leased work item (VI.3 rules 4-5, R37, A-8). attempts > 1 is a repeat attempt (class B: look the outcome up first).",
       "state attempts:int capabilityClass mayDispatch:bool storedOutcome:obj",
       enums={"state": ["INTENT", "SUCCEEDED", "FAILED", "UNKNOWN_OUTCOME"],
              "capabilityClass": ["A_IDEMPOTENT_WITH_LOOKUP", "B_LOOKUP_ONLY", "C_NEITHER"]},
       descs={"storedOutcome": "Recorded outcome when settled: {output, providerRef, duplicate}; null while INTENT."})
schema("WorkOutcome", "The downstream result of the work, recorded as the ledger outcome (SL-16).", "reference details:obj",
       descs={"reference": "Downstream reference, e.g. the payment reference (stored as providerRef)."})
schema("ExtendLockRequest", "Heartbeat at lease/3 (VI.3 rule 3).", "workerId lockVersion:long attemptId newDurationMs:long",
       LOCK_REQ + ("newDurationMs",))
schema("LockState", "Heartbeat response; cancelRequested tells the worker to stop and acknowledge (VI.3).",
       "id lockVersion:long lockExpiresAt:dt cancelRequested:bool")
schema("WorkCompleteRequest", "Complete locked work (fenced). The ledger outcome SUCCEEDED (providerRef = outcome.reference, output = variables) commits with the engine completion; an identical repeat for the same lease is an idempotent 204.",
       "workerId lockVersion:long attemptId variables:map localVariables:map outcome:WorkOutcome", LOCK_REQ)
schema("WorkFailRequest", "Report a failure; retries=0 raises an incident and records the ledger operation FAILED; with retries left the ledger stays INTENT and the next attempt reuses the operationId (VI.3).",
       "workerId lockVersion:long attemptId errorMessage errorDetails retries:int retryTimeoutMs:long", LOCK_REQ)
schema("WorkBpmnErrorRequest", "Throw a BPMN error routed by the model (COMPLETED_WITH_ERROR).",
       "workerId lockVersion:long attemptId errorCode errorMessage variables:map", LOCK_REQ + ("errorCode",))
schema("WorkUnlockRequest", "Explicit release (LOCKED → AVAILABLE).", "workerId lockVersion:long attemptId", LOCK_REQ)
schema("OutcomeUnknownRequest", "The downstream effect may or may not have happened (CX-5).",
       "workerId lockVersion:long attemptId detail evidence:map", LOCK_REQ)
schema("OutcomeUnknownResult", "The work and its ledger operation (operationId, always set) moved to UNKNOWN_OUTCOME and a reconciliation item was opened.",
       "workId operationId reconciliationItemId state")
schema("CancelAckRequest", "Acknowledge CANCEL_REQUESTED (VI.5).", "workerId lockVersion:long attemptId", LOCK_REQ)
# Tasks
schema("Task", "A BPMN user task or CMMN human task (FC-52 lifecycle fields included).",
       "id name description taskDefinitionKey processInstanceId processDefinitionKey caseInstanceId businessKey assignee owner "
       "seatId tenantId created:dt due:dt followUp:dt lastUpdated:dt claimTime:dt state priority:int formKey delegationState")
schema("TaskDetail", "Task plus the material payload hash the caller must send back on completion (VI.4 rule 5).",
       "task:Task variables:map payloadHash caseVersion:long formVersion materialFields:list:s allowedActions:list:s "
       "candidateUsers:list:s candidateGroups:list:s returnTargets:list:ReturnTarget sod:SodCheck candidates:list:CandidateSeat "
       "rejectReasons:list:CodedReason returnReasons:list:CodedReason changedSinceLastSeen:list:FieldChange "
       "ruleEvidence:list:RuleEvidence delegatedFrom")
schema("CompleteTaskRequest", "Decision on a task, bound to the payload hash and case version shown (K4, K9).",
       "payloadHash caseVersion:long decision remarks formVersion variables:map reasonCode nextSeatId nextSeatReason", ["payloadHash"],
       descs={"decision": "APPROVED | REJECTED | FORWARDED | ... (stored as variable sangamDecision)",
              "payloadHash": "Hex SHA-256 of the material payload; mismatch gives 409 APPROVAL_INVALIDATED"})
schema("CompleteTaskResult", "Result of complete/return.", "taskId processInstanceId decision payloadHash instanceEnded:bool nextTaskIds:list:s")
schema("ReturnTaskRequest", "Return the case to an earlier level (CASE_RETURNED, R43).",
       "reason reasonCode targetActivityId variables:map targetSeatId targetLevel:int payloadHash caseVersion:long", ["reason"])
schema("DelegateTaskRequest", "Delegate to a person or seat (TASK_DELEGATED).", "toUserId toSeatId reason", ["reason"])
schema("Comment", "Task or case note.", "id taskId processInstanceId userId time:dt message")
schema("AddCommentRequest", "New note.", "message", ["message"])
# Processes
schema("StartProcessRequest", "Start a process by definition key. The tenant comes from the token (VI.4 rule 6).",
       "key businessKey variables:map version:int packageHash formVersion startBeforeActivityIds:list:s", ["key"])
schema("ProcessInstance", "A process instance (runtime or historic).",
       "id processDefinitionId processDefinitionKey processDefinitionVersion:int businessKey tenantId state ended:bool "
       "startTime:dt endTime:dt activeActivityIds:list:s caseVersion:long startedBy businessStatus superProcessInstanceId "
       "rootProcessInstanceId", enums={"state": ["ACTIVE", "SUSPENDED", "COMPLETED", "CANCELLED", "DELETED", "EXTERNALLY_TERMINATED"]})
schema("TerminateRequest", "Reason for cancel/delete (VI.5).", "reason reasonCode", ["reason"])
schema("TerminationResult", "Outcome of cancel/delete; open external work goes to CANCEL_REQUESTED and is tombstoned on delete (VI.5).",
       "processInstanceId state openOperationIds:list:s cancelRequestedWork:int tombstones:int")
schema("ResubmitRequest", "Resubmit a returned case with changes; raises the case version (R43).",
       "variables:map reason expectedVersion:long", ["variables"])
schema("CaseVersion", "One entry of the case version history.",
       "version:long kind payloadHash diffHash changedFields:list:s actor seatId reason at:dt",
       enums={"kind": ["SUBMITTED", "RETURNED", "RESUBMITTED"]})
schema("ModificationInstruction", "Instance modification instruction (O03, R49; FC-17 when targetProcessInstanceId is set).",
       "type activityId activityInstanceId transitionInstanceId targetProcessInstanceId variables:map", ["type"],
       enums={"type": ["startBeforeActivity", "startAfterActivity", "startTransition", "cancelActivity",
                       "cancelActivityInstance", "cancelTransitionInstance"]})
schema("ModifyRequest", "Modify a running instance; a reason is mandatory (R63).",
       "instructions:list:ModificationInstruction reason skipCustomListeners:bool skipIoMappings:bool", ["instructions", "reason"])
schema("TriggerRequest", "Signal one waiting execution (FC-12 triggerable tasks, receive tasks).",
       "executionId activityId variables:map reason")
schema("ActivityInstance", "Activity instance tree.",
       "id activityId activityName activityType parentId executionIds:list:s children:list:ActivityInstance")
schema("ProcessDefinition", "Deployed BPMN process definition.",
       "id key name version:int deploymentId tenantId versionTag suspended:bool resourceName startableInTasklist:bool")
schema("MessageRequest", "Correlate a message (exactly one instance unless all=true).",
       "messageName businessKey processInstanceId correlationKeys:map variables:map all:bool", ["messageName"])
schema("MessageResult", "Correlation result.", "messageName processInstanceIds:list:s executionIds:list:s")
schema("SignalRequest", "Throw a signal.", "name executionId variables:map", ["name"])
# Cases
schema("StartCaseRequest", "Start a CMMN case.", "key businessKey variables:map", ["key"])
schema("CaseExecution", "Plan item instance.", "id activityId activityName activityType state required:bool repeatable:bool parentId")
schema("CaseInstance", "CMMN case instance.",
       "id caseDefinitionId caseDefinitionKey businessKey tenantId state created:dt executions:list:CaseExecution reactivatedFrom")
schema("CaseDefinition", "Deployed CMMN case definition.", "id key name version:int deploymentId tenantId")
schema("CaseExecutionCommand", "Command on one plan item.", "action variables:map reason", ["action"],
       enums={"action": ["start", "complete", "disable", "reenable", "terminate", "manualStart"]})
schema("CaseInstanceCommand", "Command on the case (reactivate = linked new case, FC-59).", "action variables:map reason", ["action"],
       enums={"action": ["complete", "close", "terminate", "reactivate"]})
# Decisions
schema("DecisionEvaluateRequest", "Evaluate a DMN decision. ROUTING/AUTHORITY with a businessKey writes a class-B event.",
       "variables:map version:int purpose businessKey", enums={"purpose": ["ROUTING", "AUTHORITY", "OTHER"]})
schema("DecisionEvaluateResult", "Decision result rows.",
       "decisionDefinitionId decisionKey version:int result:list:map outputDigest auditEventId")
schema("DecisionDefinition", "Deployed DMN decision.",
       "id key name version:int deploymentId tenantId decisionRequirementsDefinitionKey versionTag")
# Deployments
schema("DeployedDefinition", "A definition created by a deployment.", "id key kind version:int name",
       enums={"kind": ["bpmn", "dmn", "cmmn", "form", "other"]})
schema("Deployment", "Engine deployment.", "id name source tenantId deploymentTime:dt resources:list:s definitions:list:DeployedDefinition")
# Packages
schema("PackageEvent", "Lifecycle transition record.", "transition fromState toState actor seatId reason at:dt")
schema("Package", "Executable package (R43, FC-41 app bundle). ETag = revision.",
       "id key name revision:long state packageHash resourceHashes:smap author reviewer approver deployer environment "
       "activateAt:dt deploymentId createdAt:dt updatedAt:dt history:list:PackageEvent resources:smap",
       descs={"resources": "Resource name → content: UTF-8 text for .bpmn/.dmn/.cmmn/.form/.json/.xml/.yaml, base64 otherwise"},
       enums={"state": ["DRAFT", "IN_REVIEW", "APPROVED", "DEPLOYED", "ACTIVE", "SUSPENDED", "RETIRED", "ARCHIVED", "WITHDRAWN"]})
schema("CreatePackageRequest", "New DRAFT package; resources maps resource name to content (UTF-8 text for model/form/JSON/XML/YAML files, base64 otherwise).", "key name resources:smap", ["key", "resources"])
schema("UpdatePackageRequest", "Edit a DRAFT (If-Match revision).", "name resources:smap")
schema("PackageTransitionRequest", "Lifecycle transition (X.2.4.4); reviewer/approver/deployer differ from the author by person and seat.",
       "action reason environment activateAt:dt", ["action"],
       enums={"action": ["submit", "approve", "reject", "deploy", "activate", "suspend", "resume", "withdraw", "retire"]})
schema("PackageDiffEntry", "Semantic diff entry against the ACTIVE version.", "resource change oldHash newHash",
       enums={"change": ["ADDED", "REMOVED", "CHANGED", "UNCHANGED"]})
# Documents / links / dynamic
schema("Document", "Stored document version (FC-40, R74).",
       "id tenantId name contentType size:long sha256 version:int taskId processInstanceId caseInstanceId scanStatus createdBy "
       "createdAt:dt metadata:map", enums={"scanStatus": ["PENDING", "CLEAN", "INFECTED", "NOT_SCANNED"]})
schema("EntityLink", "Entity link (FC-31, FC-32, FC-59).",
       "id tenantId linkType sourceScopeType sourceId targetScopeType targetId referenceType createdAt:dt createdBy",
       enums={"linkType": ["PARENT", "CHILD", "ROOT", "REFERENCE", "SIBLING", "REACTIVATED_FROM"]})
schema("CreateLinkRequest", "New explicit link.", "linkType sourceScopeType sourceId targetScopeType targetId referenceType",
       ["linkType", "sourceScopeType", "sourceId", "targetScopeType", "targetId"],
       enums={"linkType": ["REFERENCE", "SIBLING", "PARENT", "CHILD"], "sourceScopeType": ["bpmn", "cmmn", "task"],
              "targetScopeType": ["bpmn", "cmmn", "task"]})
schema("AdHocActivity", "Activity inside an ad-hoc sub-process (FC-01).", "activityId name type enabled:bool")
schema("AdHocState", "State of an ad-hoc sub-process scope.",
       "processInstanceId adHocExecutionId adHocActivityId enabledActivities:list:AdHocActivity activeActivityIds:list:s completable:bool")
schema("StartAdHocActivityRequest", "Start one enabled activity.", "activityId variables:map reason", ["activityId"])
schema("InjectionRequest", "Inject a task or sub-process into one instance as a reviewed model variant (FC-15, R43).",
       "kind name assignee candidateGroup formKey afterActivityId subProcessBpmn variables:map reason", ["kind", "reason"],
       enums={"kind": ["USER_TASK", "EMBEDDED_SUBPROCESS", "PARALLEL_USER_TASK"]})
schema("InjectionResult", "Injection result.", "processInstanceId derivedDefinitionId packageId injectedActivityIds:list:s state")
# History
schema("HistoricProcessInstance", "Class-A history (not evidence, D-0).",
       "id processDefinitionId processDefinitionKey businessKey state startTime:dt endTime:dt durationMs:long startUserId deleteReason tenantId superProcessInstanceId")
schema("HistoricActivityInstance", "Historic activity instance.",
       "id activityId activityName activityType processInstanceId executionId taskId assignee startTime:dt endTime:dt durationMs:long canceled:bool")
schema("HistoricTask", "Historic task.",
       "id name taskDefinitionKey processInstanceId caseInstanceId assignee owner startTime:dt endTime:dt durationMs:long deleteReason tenantId")
schema("HistoricVariable", "Historic variable.", "id name type value:any processInstanceId activityInstanceId createTime:dt state")
schema("HistoricDecision", "Historic decision evaluation.",
       "id decisionDefinitionKey decisionDefinitionId processInstanceId activityId evaluationTime:dt inputs:map outputs:map")
schema("HistoricIncident", "Historic incident.", "id type message processInstanceId activityId createTime:dt endTime:dt state")
schema("HistoricJobLog", "Job log entry.", "id jobId processInstanceId activityId timestamp:dt state exceptionMessage retries:int")
schema("UserOperation", "User operation log entry.",
       "id operationType entityType property orgValue newValue userId timestamp:dt processInstanceId annotation")
# Audit
schema("AuditEntry", ("Timeline entry. eventClass B = hash-chained evidence; A = operational history (evidence=false, no hashes). "
                     "Client-side linkage check for class B (X.2.3.4): entryHash = hex(SHA-256(hexdecode(prevHash) || UTF8(envelope))); "
                     "prevHash(seq n) == entryHash(seq n-1) within one chainId; genesis prevHash(seq 1) = hex(SHA-256(UTF8(\"sangam-audit-v1:\" + tenantId + \":\" + chainId))). "
                     "hash equals entryHash. The envelope covers the salted payload digest, so an erased payload (erased=true) still verifies."),
       "eventId eventClass chainId seq:long type occurredAt:dt actor seatId actedForSeatId processInstanceId activityId taskId "
       "payload:map erased:bool hash evidence:bool prevHash entryHash envelope", enums={"eventClass": ["A", "B"]},
       descs={"prevHash": "Hex SHA-256 of the previous entry (genesis value for seq 1)", "entryHash": "Hex SHA-256 of this entry",
              "envelope": "Canonical JSON envelope exactly as stored and hashed"})
schema("ChainVerification", "Verification of one hash chain.", "chainId ok:bool eventsChecked:long firstBadSeq:long reason")
schema("AuditVerifyResult", "Chain verification result; itself recorded as a class-B event (X.2.3.4 rule 5).",
       "tenantId ok:bool verifiedAt:dt chains:list:ChainVerification")
# Admin
schema("OrgUnit", "Organisation unit (IX.5 rule 2).",
       "id tenantId parentId code name names:smap level active:bool attributes:map revision:long", ["code", "name"],
       enums={"level": ["MINISTRY", "DEPARTMENT", "DIRECTORATE", "REGION", "DISTRICT", "OFFICE", "SECTION", "OTHER"]})
schema("Seat", "Seat (post) ≠ person (IX.5 rule 1).",
       "id tenantId orgUnitId code designation name hierarchyLevel:int reportsToSeatId roles:list:s active:bool attributes:map revision:long",
       ["orgUnitId", "code", "designation"])
schema("Assignment", "Effective-dated seat holding (IX.5 rules 1, 4).",
       "id tenantId seatId personId kind validFrom:date validTo:date actedForSeatId orderRef createdBy createdAt:dt ended:bool",
       ["seatId", "personId", "kind", "validFrom"],
       enums={"kind": ["SUBSTANTIVE", "ADDITIONAL_CHARGE", "LEAVE_SUBSTITUTE", "ACTING"]})
schema("EndAssignmentRequest", "End an assignment.", "validTo:date reason", ["validTo", "reason"])
schema("Delegation", "Delegation of powers (POWER, versioned DMN table, IX.5 rule 3) or person-to-person task delegation (TASK). amountLimit is a decimal string (R33).",
       "id tenantId kind seatId toSeatId fromPersonId toPersonId decisionKey decisionVersion:int caseType amountLimit currency "
       "validFrom:date validTo:date orderRef revoked:bool createdBy createdAt:dt", ["kind", "validFrom"],
       enums={"kind": ["POWER", "TASK"]})
schema("Policy", "Grant or deny; any matching deny wins at any scope (A-13). resourceId * = all resources of the type.",
       "id tenantId effect principalType principalId resourceType resourceId actions:list:s conditionId policyVersion:long createdBy createdAt:dt",
       ["effect", "principalType", "principalId", "resourceType", "resourceId", "actions"],
       enums={"effect": ["GRANT", "DENY"], "principalType": ["USER", "GROUP", "SEAT"]})
schema("PolicyVersion", "Current policy version of the tenant (VI.4 rule 5).", "tenantId policyVersion:long")
schema("Tenant", "Tenant (EC-17).", "id name status profile isolation defaultLanguage languages:list:s attributes:map createdAt:dt revision:long",
       ["id", "name"], enums={"status": ["DRAFT", "ACTIVE", "SUSPENDED"], "profile": ["GOVERNMENT", "STANDARD"],
                              "isolation": ["SHARED", "SCHEMA", "DATABASE"]})
# Ops
schema("Incident", "Open incident.",
       "id type message processInstanceId processDefinitionId activityId jobId externalWorkId causeIncidentId rootCauseIncidentId createdAt:dt annotation tenantId")
schema("IncidentAction", "Retry or resolve an incident; reason mandatory.", "reason retries:int annotation", ["reason"])
schema("ReconciliationItem", "Reconciliation workbench item (VI.3 rule 4, VI.5).",
       "id kind operationId rootId taskId state details:any action resolvedBy resolution:any createdAt:dt resolvedAt:dt",
       enums={"kind": ["UNKNOWN_OUTCOME", "LATE_RESULT_AFTER_DELETE"], "state": ["OPEN", "RESOLVED"]})
schema("ReconciliationResolveRequest", "Resolve with evidence (OJ-02).", "action evidence result:map reason", ["action", "evidence"],
       enums={"action": ["CONFIRM_SUCCESS", "RETRY_SAME_OPERATION", "MARK_FAILED"]})
schema("OutboxDelivery", "Outbox delivery row (VI.2).",
       "outboxId destination rootId rootSeq:long eventType state attempts:int nextAttemptAt:dt lastError createdAt:dt")
schema("OutboxStats", "Outbox budget and back-pressure (VI.2.4).",
       "pending:long inFlight:long failed:long dead:long budget:long usedPercent:num backpressure",
       enums={"backpressure": ["NONE", "WARN", "REFUSE_NEW"]})
schema("OutboxRepairRequest", "Requeue or skip a delivery (audited OUTBOX_REQUEUED / OUTBOX_SKIPPED).", "destination reason", ["reason"])
schema("Job", "Engine job.", "id type processInstanceId processDefinitionKey activityId dueDate:dt retries:int exceptionMessage suspended:bool priority:long tenantId")
schema("JobRetriesRequest", "Set retries.", "retries:int reason", ["retries", "reason"])
schema("JobSuspensionRequest", "Suspend or activate a job.", "suspended:bool reason", ["suspended", "reason"])
schema("BatchFilter", "Batch target selection; counted with the same authorization predicate (R64).",
       "processDefinitionKey processDefinitionId processInstanceIds:list:s businessKeys:list:s activityId withIncidents:bool")
schema("BatchRequest", "Asynchronous batch (R64). Items get derived keys <Idempotency-Key>:<index> (X.2.2.7 rule 3).",
       "type filter:BatchFilter params:map reason", ["type", "filter", "reason"],
       enums={"type": ["RETRY", "CANCEL", "DELETE", "SUSPEND", "RESUME", "MIGRATE", "MODIFY", "SET_VARIABLES", "RESTART"]})
schema("BatchPreview", "Pre-execution count.", "type count:long capped:bool sample:list:s fourEyesRequired:bool")
schema("Batch", "Batch state.",
       "id type state total:long succeeded:long failed:long createdBy approvedBy reason createdAt:dt completedAt:dt filter:BatchFilter params:map fourEyesRequired:bool",
       enums={"state": ["AWAITING_APPROVAL", "RUNNING", "PAUSED", "COMPLETED", "CANCELLED", "FAILED"]})
schema("BatchItem", "Per-item result.", "index:int rootId state error itemKey finishedAt:dt",
       enums={"state": ["PENDING", "SUCCEEDED", "FAILED"]})
schema("BatchControlRequest", "Pause/resume/cancel/approve (four-eyes) a batch.", "action reason", ["action", "reason"],
       enums={"action": ["PAUSE", "RESUME", "CANCEL", "APPROVE"]})
schema("MigrationInstruction", "Activity mapping; 1:N and N:1 allowed (FC-18).",
       "sourceActivityIds:list:s targetActivityIds:list:s updateEventTrigger:bool", ["sourceActivityIds", "targetActivityIds"])
schema("MigrationPlanRequest", "Migration plan input (R48).",
       "sourceDefinitionId targetDefinitionId mapEqualActivities:bool instructions:list:MigrationInstruction formMappings:map variables:map",
       ["sourceDefinitionId", "targetDefinitionId"])
schema("MigrationPlan", "Validated plan with dry-run count.",
       "sourceDefinitionId targetDefinitionId instructions:list:MigrationInstruction valid:bool validationErrors:list:s affectedInstances:long")
schema("MigrationExecuteRequest", "Execute a migration as a batch.",
       "plan:MigrationPlanRequest processInstanceIds:list:s filter:BatchFilter skipCustomListeners:bool skipIoMappings:bool reason",
       ["plan", "reason"])
schema("RestartRequest", "Restart completed or cancelled instances (R63, PF-05).",
       "processDefinitionId processInstanceIds:list:s startBeforeActivityIds:list:s initialVariables:bool skipCustomListeners:bool reason",
       ["processDefinitionId", "processInstanceIds", "reason"])
schema("CleanupStatus", "History cleanup status (R29, PF-10).", "jobId dueDate:dt retries:int exceptionMessage removable:lmap")
# Events
schema("EventChannel", "Event channel (FC-03).",
       "key tenantId name direction transport destination eventDefinitionKeys:list:s keyDetector tenantDetector dedupWindowSeconds:int "
       "config:map active:bool revision:long", ["key", "direction", "transport"],
       enums={"direction": ["INBOUND", "OUTBOUND"], "transport": ["REST", "WEBHOOK", "KAFKA", "JMS", "AMQP"]})
schema("EventDefinition", "Event definition (FC-02, FC-04): starts a process (uniqueStart on the business key) or correlates a message.",
       "key tenantId name version:int payloadSchema:obj correlationParameters:list:s businessKeyPointer startProcessKey messageName uniqueStart:bool revision:long",
       ["key"])
schema("CloudEvent", "CloudEvents 1.0 structured JSON (R38 inbox dedupe by tenant, source, id).",
       "id source type specversion subject time:dt datacontenttype dataschema data:any extensions:obj", ["id", "source", "type", "specversion"])
schema("PublishResult", "Publication result.", "eventId status eventDefinitionKey startedInstanceIds:list:s correlatedInstanceIds:list:s",
       enums={"status": ["ACCEPTED", "DUPLICATE", "BUFFERED"]})
# Forms
SCHEMAS["I18nBundles"] = {"type": "object", "description": "language → (key → text); en and hi required in the government profile",
                          "additionalProperties": {"type": "object", "additionalProperties": {"type": "string"}}}
schema("FormVersion", "Form version = hash triple of data schema, layout and field policy (R47 X.2.5.1).",
       "key versionHash dataSchemaHash layoutHash fieldPolicyHash dataSchema:any layout:any fieldPolicy:any i18n:I18nBundles deploymentId state")
schema("FormValidateRequest", "Server-side authoritative validation (X.2.5.2).", "data:map taskId formVersion", ["data"])
schema("FieldError", "Validation error on a data-schema path.", "path code messageKey")
schema("FormValidationResult", "Validation result.", "valid:bool formVersion errors:list:FieldError")
schema("FormDraft", "Server-side draft (X.2.5.8). ETag = revision.",
       "draftId tenantId taskId formKey formVersion ownerKind ownerPrincipalId ownerSeatId revision:long data:map updatedAt:dt expiresAt:dt lastEditorPrincipalId",
       enums={"ownerKind": ["PERSON", "SEAT"]})
schema("SaveDraftRequest", "Draft content (size/type checks only; never becomes variables).",
       "taskId formKey formVersion ownerKind data:map", ["data"], enums={"ownerKind": ["PERSON", "SEAT"]})
# Analytics
schema("AnalyticsSummary", "Process KPIs (R65).",
       "processDefinitionKey from:dt to:dt started:long completed:long active:long cancelled:long openIncidents:long avgDurationMs:long p50DurationMs:long p95DurationMs:long")
schema("ActivityStat", "Per-activity statistics (heatmap, R63).",
       "activityId activityName activityType count:long active:long avgDurationMs:long maxDurationMs:long incidents:long")
schema("TaskStat", "User-task statistics.", "taskDefinitionKey name completed:long open:long avgDurationMs:long p95DurationMs:long")
schema("Variant", "Process variant (R66, UP-07).", "activities:list:s count:long share:num avgDurationMs:long")

# ---------------------------------------------------------------- schemas, round 2 (UI proposals, ROUTES2.md)
# Task detail extras
schema("ReturnTarget", "Where a return can go (by hierarchy level and seat); activityId feeds ReturnTaskRequest.targetActivityId.",
       "level:int activityId seatId seatName holderName")
schema("SodCheck", "Segregation-of-duties pre-check for the caller (K5, IX.5 rule 5).",
       "allowed:bool reasons:list:s initiatorName initiatorSeatName exceptionId",
       descs={"reasons": "SAME_PERSON_AS_INITIATOR, SAME_SEAT_AS_CHECKER, SELF_APPROVAL_VIA_DELEGATION, ..."})
schema("CodedReason", "Coded reason with labels (en, hi).", "code label labelHi")
schema("RuleEvidence", "DMN rule that routed or authorised the step (X.2.3.2 decision_ref).", "rule version result")
# Workspace
schema("Notification", "In-app notification (R60).", "id at:dt title body read:bool category link",
       enums={"category": ["GOVERNMENT_MESSAGE", "TASK", "SYSTEM"]})
schema("LeaveRequest", "Leave with a substitute (IX.5 rule 4); creates a LEAVE_SUBSTITUTE assignment pending approval.",
       "seatId substituteSeatId substitutePersonId from:date to:date reason", ["seatId", "from", "to", "reason"])
schema("BulkFailure", "One failed item of a bulk action.", "id reason")
schema("BulkResult", "Per-item outcome of a bulk action.", "succeeded:list:s failed:list:BulkFailure")
schema("BulkClaimRequest", "Claim several tasks; each claim is its own transaction.", "ids:list:s", ["ids"])
schema("BulkCompleteRequest", "Complete several tasks as a batch (D-34); each task is still bound to its current payload hash.",
       "ids:list:s action remarks", ["ids", "action"])
schema("CandidateSeat", "Eligible seat for the next step, computed on the server and re-validated at commit (S07, R82).",
       "seatId seatName holderName workload:int onLeave:bool outOfHierarchy:bool proposed:bool")
schema("FieldChange", "Field-level change of the material payload between versions (CX-2, S09).",
       "field label from:any to:any changedBy changedAt:dt")
schema("LifecycleEvent", "Task lifecycle event (FC-52).", "at:dt event by seatName actedFor")
schema("CatalogueEntry", "Startable process or case (R76 start catalogue).", "key name kind formKey description",
       enums={"kind": ["PROCESS", "CASE"]})
schema("CaseComment", "Comment on a case.", "id author seatName actedFor createdAt:dt text version:int")
schema("AddCaseCommentRequest", "New case comment.", "text", ["text"])
schema("Participant", "Case participant, including external parties (UP-09, UP-22).", "id name role external:bool")
schema("ChecklistItem", "Inspection checklist item.", "id label")
schema("FieldVisit", "Field inspection visit (S15, EC-16).",
       "id taskId businessKey address scheduledAt:dt checklist:list:ChecklistItem")
schema("VisitRecord", "Recorded visit; answers are boolean or string; recordedAt is the device time.",
       "answers:obj notes recordedAt:dt", ["answers"])
# Instance control
schema("OpenTaskCount", "Open external work by lease state.", "state count:long", enums={"state": ["OFFERED", "LOCKED", "UNKNOWN"]})
schema("ConsequencePreview", "What an action would do, shown before it is confirmed (O03, O08, R63).",
       "summary:list:s openTasks:list:OpenTaskCount openOperationIds:list:s tombstones:int blocked:bool blockedReason "
       "requiresFourEyes:bool affectedCount:long limitExceeded:bool")
schema("SuspensionRequest", "Suspend or resume; redateDeadlines shifts SLA due dates by the suspended time on resume (D-32).",
       "suspended:bool reason redateDeadlines:bool includeInstances:bool", ["suspended", "reason"])
schema("ModifyPreviewRequest", "Instructions to preview.", "instructions:list:ModificationInstruction", ["instructions"])
schema("CrossBoundaryMoveRequest", "Move a token across a call-activity boundary in one command (FC-17, R49).",
       "fromActivityInstanceId targetInstanceId targetActivityId reason",
       ["fromActivityInstanceId", "targetInstanceId", "targetActivityId", "reason"])
schema("ExternalTaskLogEntry", "Historic external-task log entry (PF-01).",
       "id externalTaskId topicName workerId state processInstanceId activityId timestamp:dt retries:int errorMessage")
# Portal
schema("PortalService", "Citizen service (C01). fee is a decimal string (R33).",
       "key name nameHi description descriptionHi slaDays:int fee formKey documentsRequired:list:s")
schema("Clarification", "Clarification sought from the applicant (C04).", "id question askedAt:dt askedBySeat dueAt:dt reply repliedAt:dt")
schema("PortalMessage", "Message shown to the applicant.", "at:dt text")
schema("Application", "The applicant's own application (C03). UNKNOWN_OUTCOME is never shown as failed.",
       "id reference serviceKey serviceName submittedAt:dt stage stageLabel stageLabelHi slaDate:dt status "
       "clarifications:list:Clarification messages:list:PortalMessage outcomeDocumentId appealDeadline:dt paymentStatus",
       enums={"status": ["DRAFT", "SUBMITTED", "IN_PROGRESS", "CLARIFICATION_SOUGHT", "APPROVED", "REJECTED", "WITHDRAWN"],
              "paymentStatus": ["NOT_REQUIRED", "DUE", "PAID", "UNKNOWN_OUTCOME"]})
schema("ApplyRequest", "New application; submit=false keeps it as a draft.", "serviceKey variables:map submit:bool", ["serviceKey"])
schema("WithdrawRequest", "Withdraw an application.", "reason", ["reason"])
schema("AppealRequest", "Appeal a decision.", "grounds", ["grounds"])
schema("Grievance", "Grievance or DPDP data-rights request.", "id reference subject status filedAt:dt dueAt:dt")
schema("GrievanceRequest", "File a grievance.", "subject text kind", ["subject", "text", "kind"],
       enums={"kind": ["GRIEVANCE", "DATA_RIGHTS"]})
schema("GrievanceReceipt", "Receipt of a filed grievance.", "reference")
# Ops console
schema("NodeStatus", "Engine node status.", "id status jobExecutorActive:bool lastHeartbeat:dt", enums={"status": ["UP", "DEGRADED", "DOWN"]})
schema("TopicBacklog", "Open work per topic.", "topic count:long oldestAgeSec:long")
schema("OutboxUsage", "Outbox budget use (VI.2.4).", "used:long budget:long drainTimeSec:long")
schema("AgeBucket", "Count per age bucket.", "bucket count:long")
schema("ClockItem", "Running deadline (e.g. CERT-In 6 h reporting clock).", "id title deadline:dt")
schema("OpsMetric", "Named metric value.", "name value:num unit")
schema("OpsDashboard", "Operations dashboard (O01).",
       "nodes:list:NodeStatus backlogByTopic:list:TopicBacklog outbox:OutboxUsage openIncidents:long "
       "openReconciliationByAge:list:AgeBucket slaBreaches:long notificationFailureRate:num "
       "securityIncidentClocks:list:ClockItem metrics:list:OpsMetric")
schema("PartitionCount", "Jobs per executor partition (R39).", "partition:int owner count:long")
schema("JobSummary", "Job counts.", "executable:long failed:long timersDue:long locked:long byPartition:list:PartitionCount")
schema("InboxEntry", "Inbound message row (R38).", "id source messageId status receivedAt:dt correlationKey",
       enums={"status": ["RECEIVED", "BUFFERED", "CORRELATED", "EXPIRED", "DUPLICATE"]})
schema("WorkerInfo", "Worker seen by the server (R26).",
       "workerId sdk sdkVersion language topics:list:s activeLeases:long heartbeatAgeSec:long errorRate:num staleLeaseCount:long")
schema("TopicInfo", "Worker topic (capability class a/b/c per R36).", "name capabilityClass paused:bool backlog:long lockDurationMs:long",
       enums={"capabilityClass": ["a", "b", "c"]})
schema("NoteRequest", "Note on an incident.", "note reason", ["note"])
schema("LookupResult", "Downstream lookup by operationId (capability class b, A-2).", "result at:dt")
schema("Evidence", "Evidence attached to a reconciliation item.", "id kind name addedBy addedAt:dt sha256")
schema("TtlRequest", "History time-to-live for a definition key (R29).", "ttlDays:int reason", ["ttlDays", "reason"])
schema("NotificationFailure", "Failed notification (R60).", "id at:dt channel reason templateId businessKey recipientMasked")
# Debugger
schema("DebugStatus", "Debugger availability; never enabled on a production profile (R80).", "enabled:bool profile sandboxTenant")
schema("BreakpointsRequest", "Breakpoints for one instance.", "activityIds:list:s", ["activityIds"])
schema("DebugStepResult", "Result of one debugger step.", "executed jobId activeActivityIds:list:s")
# Insights
schema("TimePoint", "Throughput bucket.", "t:dt started:long completed:long")
schema("SlaRow", "SLA compliance per key (R79).", "key name total:long withinSla:long warning:long breached:long avgBusinessDays:num")
schema("Deviation", "Conformance deviation.", "activityId kind count:long", enums={"kind": ["SKIPPED", "INSERTED", "ORDER"]})
schema("ConformanceResult", "Conformance of traces against the deployed BPMN (R66).",
       "fitness:num precision:num deviations:list:Deviation fixtureId")
schema("DurationReportRow", "Instance duration report row (PF-07).", "period count:long avgMs:long minMs:long maxMs:long")
schema("AlertRule", "KPI threshold alert (R65).", "id name kpi operator threshold:num channel recipients:list:s active:bool",
       ["name", "kpi", "operator", "threshold", "channel"],
       enums={"operator": [">", "<", ">=", "<="], "channel": ["IN_APP", "EMAIL", "WEBHOOK"]})
schema("AgentUsage", "Agent usage and budgets (R72).",
       "agent runs:long tokens:long costUnits:num budget:num guardrailBlocks:long humanReviews:long")
# Audit exports
schema("AuditExportJob", "Asynchronous audit export (U02).", "id status rowCount:long requestedAt:dt format scope:obj error",
       enums={"status": ["QUEUED", "RUNNING", "READY", "FAILED"], "format": ["CSV", "JSON", "PDFA"]})
schema("AuditExportRequest", "Scope keys: businessKey, chainId, type, actor, from, to.", "format scope:obj", ["format"],
       enums={"format": ["CSV", "JSON", "PDFA"]})
# Document extras
schema("DocumentTemplate", "Output document template (R74).", "id name languages:list:s format version:int",
       enums={"format": ["PDFA", "DOCX", "HTML"]})
schema("LegalHoldRequest", "Set or release a legal hold (R61).", "hold:bool reason", ["hold", "reason"])
schema("DocumentGenerateRequest", "Generate a document from a template for a case.", "templateId businessKey language",
       ["templateId", "businessKey"])
# Power tables
schema("PowerTableTestCase", "Test case for a delegation-of-powers table.", "id inputs:obj expected:obj")
schema("PowerTable", "Delegation-of-powers DMN table (IX.5 rule 3, K6). ACTIVE tables are deployed and evaluated by decision key.",
       "id key name version:int state effectiveFrom:dt author approver dmnXml testCases:list:PowerTableTestCase deploymentId revision:long",
       ["key", "name", "dmnXml"],
       enums={"state": ["DRAFT", "IN_REVIEW", "APPROVED", "ACTIVE", "SUPERSEDED", "REJECTED"]})
schema("PowerTableTestOutcome", "Result of one test case.", "id passed:bool actual:obj")
schema("PowerTableCheckResult", "Static check and test run of a power table.",
       "gaps:list:s overlaps:list:s hitPolicy tests:list:PowerTableTestOutcome")
schema("PowerTableTransitionRequest", "Four-eyes lifecycle step (approver ≠ author).", "reason effectiveFrom:dt", ["reason"])
# Identity admin
schema("SodConflict", "SoD conflict an assignment would create.", "taskId reason")
schema("AssignmentImpact", "Impact of an assignment before it is saved.", "openTasks:long sodConflicts:list:SodConflict summary:list:s")
schema("ExplainRequest", "Explain an authorization decision (VI.4).", "principalId action resourceType resourceId",
       ["principalId", "action", "resourceType", "resourceId"])
schema("PolicyMatch", "Policy that matched (deny wins, A-13).", "policyId effect scope", enums={"effect": ["ALLOW", "DENY"]})
schema("ExplainResult", "Authorization explanation.", "allowed:bool matched:list:PolicyMatch policyVersion:long")
schema("SodException", "Controlled SoD exception granted by a higher authority with a reason (IX.5 rule 5).",
       "id rule personId personName reason expiresAt:dt status approvedBy requestedBy", ["rule", "personId", "reason", "expiresAt"],
       enums={"status": ["PENDING", "APPROVED", "REJECTED", "EXPIRED"]})
schema("User", "Identity (R67).", "id username displayName email active:bool groups:list:s source lastLogin:dt",
       enums={"source": ["LOCAL", "OIDC", "LDAP", "SCIM"]})
schema("Group", "Group (R67).", "id name type members:int source", enums={"source": ["LOCAL", "OIDC", "LDAP", "SCIM"]})
schema("IdentityProvider", "Identity provider (R50, R67).",
       "id name protocol issuer metadataUrl attributeMapping:smap mfaRequired:bool sessionTimeoutMin:int status lastTestAt:dt",
       ["name", "protocol"], enums={"protocol": ["OIDC", "SAML2", "LDAP"], "status": ["DRAFT", "TESTED", "ACTIVE"]})
schema("IdentityTestResult", "Connection test result.", "ok:bool message")
schema("IdentitySyncResult", "Directory sync result.", "users:long groups:long")
schema("ServicePrincipal", "Service principal bound to tenant and topics (R26, K14).",
       "id name scopes:list:s topics:list:s expiresAt:dt lastUsedAt:dt status", ["name"],
       enums={"status": ["PENDING", "ACTIVE", "REVOKED"]})
schema("ServicePrincipalSecret", "Principal plus its secret, shown once (only a hash is stored).", "principal:ServicePrincipal secret")
schema("OrgUnitImportResult", "Bulk org-unit import result (CSV: id,parentId,code,name,nameHi,level).",
       "created:int updated:int errors:list:s")
# Registries
SCHEMAS["RegistryEntry"] = {
    "type": "object", "additionalProperties": True,
    "description": ("Registry entry (service registry R71, SLA model R79, routing strategy R82, document template R74, conversation "
                    "channel R81, agent R72, retention class R29/R61). Free-form JSON document; the server sets id, kind, revision, "
                    "createdBy and updatedAt. Send the current revision on update (409 REVISION_CONFLICT when stale)."),
    "properties": {"id": {"type": "string", "readOnly": True}, "kind": {"type": "string", "readOnly": True},
                   "key": {"type": "string"}, "name": {"type": "string"},
                   "revision": {"type": "integer", "format": "int64"}, "createdBy": {"type": "string", "readOnly": True},
                   "updatedAt": {"type": "string", "format": "date-time", "readOnly": True}},
}
# Low-code
schema("PageBlock", "Page block (R77).", "id kind title text query columns:list:s dataObject processKey",
       enums={"kind": ["heading", "text", "list", "detail", "kpi", "action"]})
schema("PageModel", "Versioned low-code page (R77, EC-03), authorized on the server.", "id key title version:int state blocks:list:PageBlock",
       ["key", "title"], enums={"state": ["DRAFT", "ACTIVE", "RETIRED"]})
schema("PageSummary", "Page list entry.", "id key title version:int state")
schema("DataObjectField", "Data-object field (R78).", "name type required:bool reference sensitive:bool", ["name", "type"],
       enums={"type": ["string", "number", "boolean", "date", "reference", "json"]})
schema("DataObjectDef", "Data object definition (R78, EC-06).", "id key name backing endpointRef version:int fields:list:DataObjectField",
       ["key", "name", "backing"], enums={"backing": ["DB", "REST"]})
# Package tools
schema("ValidationIssue", "Deployment validation issue (R44 profile, X.2.4.6, FC-46).", "severity artifact elementId message code",
       enums={"severity": ["ERROR", "WARNING", "INFO"]})
schema("TestRun", "Model test result (R80).", "name passed:bool message durationMs:long")
schema("GitStatus", "Git sync status (R69).", "remote branch ahead:int behind:int lastSync:dt conflicts:list:s")
schema("GitConfig", "Git remote; credentialRef names a secret, never inline.", "remote branch credentialRef provider",
       ["remote", "branch"], enums={"provider": ["github", "gitlab", "gitea", "generic"]})
schema("GitSyncRequest", "Commit message for push.", "message")
schema("GitSyncResult", "Sync result.", "ahead:int behind:int conflicts:list:s")
schema("PackageComment", "Review comment on a package element (R69).", "id author seatName actedFor createdAt:dt text elementId")
schema("PackageAddCommentRequest", "New review comment.", "text elementId", ["text"])
schema("AssessmentRow", "Foreign-model construct classification (EC-21, R48).", "file element construct classification note",
       enums={"classification": ["SUPPORTED", "MAPPED", "PATTERN", "REJECTED"]})

# ---------------------------------------------------------------- components
R = lambda n: {"$ref": "#/components/schemas/" + n}
P = lambda n: {"$ref": "#/components/parameters/" + n}
H = lambda n: {"$ref": "#/components/headers/" + n}
RESP = lambda n: {"$ref": "#/components/responses/" + n}

PARAMS = {
    "page": {"name": "page", "in": "query", "description": "0-based page index", "schema": {"type": "integer", "minimum": 0, "default": 0}},
    "size": {"name": "size", "in": "query", "description": "Page size", "schema": {"type": "integer", "minimum": 1, "maximum": 500, "default": 50}},
    "IdempotencyKey": {"name": "Idempotency-Key", "in": "header", "required": False,
                       "description": ("R41 idempotency key (IETF httpapi draft), 1–128 printable ASCII; UUIDv4/v7 recommended. "
                                       "Scope (tenant, command type, key). Required for start, correlate, task decisions, cancel, delete "
                                       "and deployment commands in the government profile (400 IDEMPOTENCY_KEY_REQUIRED)."),
                       "schema": {"type": "string", "minLength": 1, "maxLength": 128, "pattern": "^[\\x20-\\x7E]{1,128}$"}},
    "SeatHeader": {"name": "X-Sangam-Seat", "in": "header", "description": "Seat context selected by the caller (IX.5 rule 1)", "schema": {"type": "string"}},
    "ActingForSeat": {"name": "X-Sangam-Acting-For-Seat", "in": "header",
                      "description": "Acting authority for a substituted seat (IX.5 rule 4); part of the R41 fingerprint", "schema": {"type": "string"}},
    "IfMatch": {"name": "If-Match", "in": "header", "required": True, "description": "Revision the edit is based on (409 on mismatch)",
                "schema": {"type": "string"}},
    "reason": {"name": "reason", "in": "query", "description": "Mandatory reason (audited, R42)", "schema": {"type": "string"}},
}
HEADERS = {
    "X-Total-Count": {"description": "Total matching items, capped (A-16)", "schema": {"type": "integer", "format": "int64"}},
    "X-Total-Count-Capped": {"description": "true when the real total is more than X-Total-Count", "schema": {"type": "boolean"}},
    "Idempotent-Replayed": {"description": "Present on a replay: true (full body) or compacted (no body)", "schema": {"type": "string", "enum": ["true", "compacted"]}},
    "Idempotency-Key-Expires": {"description": "When the stored response expires (response_expires_at)", "schema": {"type": "string", "format": "date-time"}},
    "Retry-After": {"description": "Seconds to wait before retrying", "schema": {"type": "integer"}},
    "ETag": {"description": "Current revision", "schema": {"type": "string"}},
    "Location": {"description": "URI of the created resource", "schema": {"type": "string", "format": "uri-reference"}},
}
PROBLEM = {"application/problem+json": {"schema": R("Problem")}}
RESPONSES = {
    "BadRequest": {"description": "Invalid request (VALIDATION_FAILED, IDEMPOTENCY_KEY_REQUIRED, IDEMPOTENCY_KEY_INVALID ...)", "content": PROBLEM},
    "Unauthorized": {"description": "Missing or invalid token (SESSION_EXPIRED)", "content": PROBLEM},
    "Forbidden": {"description": "ACTION_NOT_PERMITTED (permission re-checked in the command transaction, VI.4 rule 5)", "content": PROBLEM},
    "NotFound": {"description": "Not found or not visible to the caller (no existence leak)", "content": PROBLEM},
    "Conflict": {"description": "Conflict: STALE_LEASE, CONFLICT_RETRY_EXHAUSTED, APPROVAL_INVALIDATED, FORM_VERSION_STALE, TASK_ALREADY_CLAIMED, IDEMPOTENCY_IN_PROGRESS, DRAFT_CONFLICT, REVISION_CONFLICT, INVALID_TRANSITION",
                 "headers": {"Retry-After": H("Retry-After")}, "content": PROBLEM},
    "Gone": {"description": "GONE_INSTANCE_DELETED: the instance was deleted; a late result is audited and may open LATE_RESULT_AFTER_DELETE (VI.5 rule 3)", "content": PROBLEM},
    "Unprocessable": {"description": "Business rule: SOD_VIOLATION, POWER_LIMIT_EXCEEDED, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH, MODEL_INVALID, ENGINE_REJECTED, VALIDATION_FAILED", "content": PROBLEM},
    "ServiceUnavailable": {"description": "BACKPRESSURE (outbox ≥ 80 %, VI.2.4) or capability not installed", "headers": {"Retry-After": H("Retry-After")}, "content": PROBLEM},
    "NotImplemented": {"description": "CAPABILITY_NOT_INSTALLED: the providing module is not installed in this server", "content": PROBLEM},
}

# ---------------------------------------------------------------- operations
PATHS = {}
OPIDS = set()
LIST_HEADERS = {"X-Total-Count": H("X-Total-Count"), "X-Total-Count-Capped": H("X-Total-Count-Capped")}


def q(name, t="s", desc=None, required=False, enum=None):
    s = typ(t)
    if enum:
        s["enum"] = enum
    p = {"name": name, "in": "query", "schema": s}
    if desc:
        p["description"] = desc
    if required:
        p["required"] = True
    return p


def pp(name, desc=None):
    p = {"name": name, "in": "path", "required": True, "schema": {"type": "string"}}
    if desc:
        p["description"] = desc
    return p


def op(method, path, opid, tag, summary, desc, reqs=(), params=(), body=None, resp=(200, None), cmd=None,
       perm=None, errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound"), list_=False, fenced=False,
       body_required=True, created=False, etag=False, if_match=False, content_type="application/json", resp_ct=None):
    assert opid not in OPIDS, opid
    OPIDS.add(opid)
    o = {"operationId": opid, "tags": [tag], "summary": summary, "description": desc}
    prms = []
    for x in params:
        prms.append(x)
    if list_:
        prms += [P("page"), P("size")]
    if if_match:
        prms.append(P("IfMatch"))
    if cmd:
        prms.append(P("IdempotencyKey"))
        o["x-sangam-command-type"] = cmd
        o["x-sangam-idempotent"] = True
    if fenced:
        o["x-sangam-idempotent"] = False
        o["x-sangam-fenced"] = True
    if method in ("post", "put", "delete", "patch"):
        prms += [P("SeatHeader"), P("ActingForSeat")]
    if prms:
        o["parameters"] = prms
    if perm:
        o["x-sangam-permission"] = perm
    if reqs:
        o["x-sangam-requirements"] = list(reqs)
    if body is not None:
        if isinstance(body, dict):
            o["requestBody"] = body
        else:
            o["requestBody"] = {"required": body_required, "content": {content_type: {"schema": R(body)}}}
    status, rs = resp
    r = {"description": "Success"}
    headers = {}
    if rs is not None:
        if isinstance(rs, dict):
            r["content"] = rs
        elif rs.startswith("array:"):
            r["content"] = {resp_ct or "application/json": {"schema": {"type": "array", "items": R(rs[6:])}}}
        elif rs == "object":
            r["content"] = {"application/json": {"schema": R("VariableMap")}}
        else:
            r["content"] = {resp_ct or "application/json": {"schema": R(rs)}}
    if list_:
        headers.update(LIST_HEADERS)
    if cmd:
        headers["Idempotent-Replayed"] = H("Idempotent-Replayed")
        headers["Idempotency-Key-Expires"] = H("Idempotency-Key-Expires")
    if created:
        headers["Location"] = H("Location")
    if etag:
        headers["ETag"] = H("ETag")
    if headers:
        r["headers"] = headers
    responses = {str(status): r}
    errs = list(errors)
    if cmd:
        for e in ("Conflict", "Unprocessable"):
            if e not in errs:
                errs.append(e)
    for e in errs:
        code = {"BadRequest": 400, "Unauthorized": 401, "Forbidden": 403, "NotFound": 404, "Conflict": 409, "Gone": 410,
                "Unprocessable": 422, "ServiceUnavailable": 503, "NotImplemented": 501}[e]
        responses[str(code)] = RESP(e)
    o["responses"] = responses
    PATHS.setdefault(path, {})[method] = o


ID = pp("id")
STD_R = ("BadRequest", "Unauthorized", "Forbidden")
GET_E = ("Unauthorized", "Forbidden", "NotFound")

# Me
# /me is defined with the workspace operations below (round 2).

# Work
WERR = ("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict", "Gone")
WD = " A stale lockVersion/attemptId returns 409 STALE_LEASE and changes nothing (VI.3 rule 2); a result for a deleted instance returns 410 GONE_INSTANCE_DELETED (VI.5 rule 3)."
op("post", "/work/fetch-and-lock", "fetchAndLock", "Work", "Fetch and lock external work",
   "Long-poll fetch-and-lock (up to asyncResponseTimeoutMs). Each item carries lockVersion, attemptId and the stable operationId. The worker principal is bound to its tenant and topics (K14, R26).",
   ["VI.3", "R9", "R36", "FC-36", "FC-11", "K14"], body="FetchAndLockRequest", resp=(200, "array:ExternalWork"),
   perm="work.execute", fenced=True, errors=("BadRequest", "Unauthorized", "Forbidden", "ServiceUnavailable"))
for verb, opid, s, bodyn, rs, st, d in [
    ("extend-lock", "extendLock", "Extend the lease (heartbeat)", "ExtendLockRequest", "LockState", 200, "Heartbeat; the response carries cancelRequested (VI.3 rule 3)."),
    ("complete", "completeWork", "Complete external work", "WorkCompleteRequest", None, 204, "Completes the work. A repeat of an identical complete after success is answered from the stored result (implicit key attemptId:complete, X.2.2.1)."),
    ("fail", "failWork", "Report a failure", "WorkFailRequest", None, 204, "retries>0 → AVAILABLE after back-off (lockVersion+1); retries=0 → INCIDENT."),
    ("bpmn-error", "bpmnErrorWork", "Throw a BPMN error", "WorkBpmnErrorRequest", None, 204, "Routes the BPMN error through the model."),
    ("unlock", "unlockWork", "Release the lease", "WorkUnlockRequest", None, 204, "Explicit release (LOCKED → AVAILABLE)."),
    ("outcome-unknown", "reportOutcomeUnknown", "Report an unknown outcome", "OutcomeUnknownRequest", "OutcomeUnknownResult", 202, "Moves the work to UNKNOWN_OUTCOME and opens a reconciliation item; never retried automatically (VI.3 rules 4–5, CX-5)."),
    ("cancel-ack", "acknowledgeCancel", "Acknowledge a cancel request", "CancelAckRequest", None, 204, "Acknowledges CANCEL_REQUESTED → CANCELLED (VI.3, VI.5)."),
]:
    op("post", "/work/{id}/" + verb, opid, "Work", s, d + WD, ["VI.3", "VI.5", "R9", "R36", "FC-36"], params=[pp("id", "External work id")],
       body=bodyn, resp=(st, rs), perm="work.execute", fenced=True, errors=WERR)

# Tasks
op("get", "/tasks", "listTasks", "Tasks", "List tasks",
   "Tasks visible to the caller through the one authorization predicate (VI.4 rules 1–3, K2); BPMN user tasks and CMMN human tasks.",
   ["VI.4", "A-16", "R76"], list_=True, resp=(200, "array:Task"), perm="task.read", errors=STD_R,
   params=[q("assignee"), q("mine", "bool", "Assigned to me or I am a candidate"), q("unassigned", "bool"), q("candidateGroup"),
           q("seatId"), q("processInstanceId"), q("caseInstanceId"), q("businessKey"), q("processDefinitionKey"),
           q("taskDefinitionKey"), q("state"), q("sortBy", desc="created, due, priority or name; a comma-separated list sorts by several keys"), q("sortOrder", desc="asc or desc per key, comma-separated")])
op("get", "/tasks/{id}", "getTask", "Tasks", "Get a task", "Task with variables (field masks applied, K11), the material payload hash and case version (VI.4 rule 5).",
   ["VI.4", "R47"], params=[ID], resp=(200, "TaskDetail"), perm="task.read", errors=GET_E)
op("get", "/tasks/{id}/variables", "getTaskVariables", "Tasks", "Task variables", "Variables visible on the task (K11).", ["VI.4"],
   params=[ID], resp=(200, "object"), perm="task.read", errors=GET_E)
op("post", "/tasks/{id}/claim", "claimTask", "Tasks", "Claim a task", "Single winner under the root lock; the loser gets 409 TASK_ALREADY_CLAIMED (CX-3).",
   ["R41", "CX-3", "K9"], params=[ID], resp=(204, None), cmd="task.claim", perm="task.write")
op("post", "/tasks/{id}/unclaim", "unclaimTask", "Tasks", "Unclaim a task", "Returns the task to the seat/candidate pool (TASK_UNCLAIMED).",
   ["R41"], params=[ID], resp=(204, None), cmd="task.unclaim", perm="task.write")
op("post", "/tasks/{id}/complete", "completeTask", "Tasks", "Complete a task with a decision",
   "Bound to the payload hash and case version shown (409 APPROVAL_INVALIDATED / FORM_VERSION_STALE); SoD by person and seat (422 SOD_VIOLATION); delegated power (422 POWER_LIMIT_EXCEEDED); permission re-checked in the command transaction (VI.4 rule 5, K3–K6).",
   ["VI.4", "R41", "R43", "R47", "K3", "K4", "K5", "K6", "K9", "T16", "T17"], params=[ID], body="CompleteTaskRequest",
   resp=(200, "CompleteTaskResult"), cmd="task.complete", perm="task.write")
op("post", "/tasks/{id}/return", "returnTask", "Tasks", "Return a task to an earlier level",
   "Returns the case (CASE_RETURNED) with a reason; resubmission raises the case version (R43 return/resubmit with version history).",
   ["R43", "IX.3", "R41"], params=[ID], body="ReturnTaskRequest", resp=(200, "CompleteTaskResult"), cmd="task.return", perm="task.write")
op("post", "/tasks/{id}/delegate", "delegateTask", "Tasks", "Delegate a task", "Delegates to a person or seat (TASK_DELEGATED, IX.5 rule 4).",
   ["IX.5", "R41"], params=[ID], body="DelegateTaskRequest", resp=(204, None), cmd="task.delegate", perm="task.write")
op("get", "/tasks/{id}/comments", "listTaskComments", "Tasks", "Task comments", "Notes on the task.", [], params=[ID],
   resp=(200, "array:Comment"), perm="task.read", errors=GET_E)
op("post", "/tasks/{id}/comments", "addTaskComment", "Tasks", "Add a comment", "Adds a note (non-authoritative).", ["R41"], params=[ID],
   body="AddCommentRequest", resp=(201, "Comment"), cmd="task.comment", perm="task.write", created=True)
MULTI_DOC = {"required": True, "content": {"multipart/form-data": {"schema": {"type": "object", "required": ["file"], "properties": {
    "file": {"type": "string", "contentMediaType": "application/octet-stream"}, "name": {"type": "string"},
    "taskId": {"type": "string"}, "processInstanceId": {"type": "string"}, "caseInstanceId": {"type": "string"}}}}}}
MULTI_FILE = {"required": True, "content": {"multipart/form-data": {"schema": {"type": "object", "required": ["file"], "properties": {
    "file": {"type": "string", "contentMediaType": "application/octet-stream"}, "name": {"type": "string"}}}}}}
op("get", "/tasks/{id}/attachments", "listTaskAttachments", "Tasks", "Task attachments", "Documents attached to the task (FC-40, R74).",
   ["FC-40", "R74"], params=[ID], resp=(200, "array:Document"), perm="document.read", errors=GET_E)
op("post", "/tasks/{id}/attachments", "addTaskAttachment", "Tasks", "Attach a document",
   "Uploads an attachment (type allow-list, size limit, scan hook; K12).", ["FC-40", "R74", "K12", "R41"], params=[ID],
   body=MULTI_FILE, resp=(201, "Document"), cmd="document.upload", perm="document.write", created=True)

# Processes
op("post", "/process-instances", "startProcessInstance", "Processes", "Start a process instance",
   "Starts by definition key; the package version is bound at creation (R43, K10). Refused with 503 BACKPRESSURE at ≥ 80 % outbox budget (CX-7).",
   ["R41", "R43", "K8", "K10", "VI.2.4"], body="StartProcessRequest", resp=(201, "ProcessInstance"), cmd="process.start",
   perm="process.start", created=True, errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "ServiceUnavailable"))
op("get", "/process-instances", "listProcessInstances", "Processes", "List process instances", "Authorized list (K2, A-16).",
   ["VI.4", "A-16"], list_=True, resp=(200, "array:ProcessInstance"), perm="process.read", errors=STD_R,
   params=[q("key"), q("businessKey"), q("state", enum=["ACTIVE", "SUSPENDED", "COMPLETED", "CANCELLED"]), q("startedAfter", "dt"),
           q("startedBefore", "dt"), q("withIncidents", "bool"), q("superProcessInstanceId")])
op("get", "/process-instances/{id}", "getProcessInstance", "Processes", "Get a process instance", "Runtime or historic instance.", ["VI.4"],
   params=[ID], resp=(200, "ProcessInstance"), perm="process.read", errors=GET_E)
op("get", "/process-instances/{id}/activity-tree", "getActivityTree", "Processes", "Activity instance tree", "Current activity instances (FC-20 equivalent).",
   ["FC-20", "R63"], params=[ID], resp=(200, "ActivityInstance"), perm="process.read", errors=GET_E)
op("get", "/process-instances/{id}/variables", "getProcessVariables", "Processes", "Instance variables", "Variables with field masks (K11).",
   ["VI.4"], params=[ID], resp=(200, "object"), perm="process.read", errors=GET_E)
op("put", "/process-instances/{id}/variables", "setProcessVariables", "Processes", "Update instance variables",
   "Sets variables (R33 typed values); audited.", ["R41", "R33", "R42"], params=[ID],
   body={"required": True, "content": {"application/json": {"schema": R("VariableMap")}}}, resp=(200, "object"),
   cmd="process.variables.update", perm="process.write")
op("post", "/process-instances/{id}/cancel", "cancelProcessInstance", "Processes", "Business cancel",
   "Runs the model's cancel/compensation paths; open external work → CANCEL_REQUESTED (VI.5). INSTANCE_CANCELLED records the open operation ids.",
   ["VI.5", "R23", "R41", "R42"], params=[ID], body="TerminateRequest", resp=(200, "TerminationResult"), cmd="process.cancel",
   perm="process.cancel")
op("delete", "/process-instances/{id}", "deleteProcessInstance", "Processes", "Technical delete",
   "Administrator delete with reason: open work → CANCEL_REQUESTED, tombstones written, internal jobs dropped (VI.5 rules 1–2, 5).",
   ["VI.5", "R23", "R41", "A-4"], params=[ID, q("reason", required=True), q("reasonCode")], resp=(200, "TerminationResult"),
   cmd="process.delete", perm="process.delete")
op("post", "/process-instances/{id}/resubmit", "resubmitProcessInstance", "Processes", "Resubmit a returned case",
   "Raises the case version n→n+1 with the diff hash (CASE_RESUBMITTED); earlier approvals on changed material fields are invalidated (R43, T16).",
   ["R43", "IX.3", "R41", "T16", "T19"], params=[ID], body="ResubmitRequest", resp=(200, "CaseVersion"), cmd="process.resubmit",
   perm="process.write")
op("get", "/process-instances/{id}/versions", "listCaseVersions", "Processes", "Case version history", "Submitted/returned/resubmitted versions (R43).",
   ["R43", "IX.3"], params=[ID], resp=(200, "array:CaseVersion"), perm="process.read", errors=GET_E)
op("post", "/process-instances/{id}/modify", "modifyProcessInstance", "Processes", "Modify a running instance",
   "Move, skip or cancel tokens, including across call-activity boundaries (O03, R49, FC-17); reason mandatory, audited INSTANCE_MODIFIED.",
   ["R49", "R63", "FC-17", "PF-03", "R41"], params=[ID], body="ModifyRequest", resp=(200, "ProcessInstance"), cmd="process.modify",
   perm="process.modify")
op("post", "/process-instances/{id}/trigger", "triggerExecution", "Processes", "Trigger a waiting execution",
   "Signals a receive task or triggerable service task (FC-12).", ["FC-12", "R41"], params=[ID], body="TriggerRequest",
   resp=(200, "ProcessInstance"), cmd="process.trigger", perm="process.write")
op("get", "/process-instances/{id}/links", "listProcessInstanceLinks", "Processes", "Entity links of an instance",
   "Parent/child/root and explicit links (FC-31, FC-32).", ["FC-31", "FC-32"], params=[ID, q("linkType")],
   resp=(200, "array:EntityLink"), perm="process.read", errors=GET_E)
op("get", "/process-instances/{id}/attachments", "listProcessInstanceAttachments", "Processes", "Instance documents",
   "Documents attached to the instance (FC-40).", ["FC-40", "R74"], params=[ID], resp=(200, "array:Document"), perm="document.read", errors=GET_E)
ADHOC = [ID, pp("adHocActivityId", "Id of the ad-hoc sub-process activity")]
op("get", "/process-instances/{id}/ad-hoc/{adHocActivityId}", "getAdHocState", "Processes", "Ad-hoc sub-process state",
   "Enabled and active activities of an ad-hoc scope (FC-01).", ["FC-01"], params=ADHOC, resp=(200, "AdHocState"), perm="process.read",
   errors=GET_E + ("NotImplemented",))
op("post", "/process-instances/{id}/ad-hoc/{adHocActivityId}/start", "startAdHocActivity", "Processes", "Start an ad-hoc activity",
   "Starts an enabled activity in the ad-hoc scope (FC-01).", ["FC-01", "R41"], params=ADHOC, body="StartAdHocActivityRequest",
   resp=(200, "AdHocState"), cmd="process.adhoc.start", perm="process.write",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "NotImplemented"))
op("post", "/process-instances/{id}/ad-hoc/{adHocActivityId}/complete", "completeAdHoc", "Processes", "Complete an ad-hoc scope",
   "Completes the ad-hoc sub-process when its completion condition allows (FC-01).", ["FC-01", "R41"], params=ADHOC + [P("reason")],
   resp=(200, "AdHocState"), cmd="process.adhoc.complete", perm="process.write",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "NotImplemented"))
op("post", "/process-instances/{id}/inject", "injectIntoProcessInstance", "Processes", "Dynamic injection",
   "Injects a user task or sub-process into one instance as a reviewed model variant plus single-instance migration (FC-15; no silent runtime patching, FC-16).",
   ["FC-15", "FC-16", "R43", "R41"], params=[ID], body="InjectionRequest", resp=(200, "InjectionResult"), cmd="process.inject",
   perm="process.modify", errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "NotImplemented"))
op("get", "/process-definitions", "listProcessDefinitions", "Processes", "List process definitions", "Deployed BPMN definitions of the tenant (and shared).",
   [], list_=True, params=[q("key"), q("latest", "bool"), q("deploymentId")], resp=(200, "array:ProcessDefinition"), perm="process.read", errors=STD_R)
op("get", "/process-definitions/{id}", "getProcessDefinition", "Processes", "Get a process definition", "One definition.", [],
   params=[ID], resp=(200, "ProcessDefinition"), perm="process.read", errors=GET_E)
XML = {"application/xml": {"schema": {"type": "string"}}}
op("get", "/process-definitions/{id}/xml", "getProcessDefinitionXml", "Processes", "BPMN XML", "The deployed BPMN 2.0 XML.", [],
   params=[ID], resp=(200, XML), perm="process.read", errors=GET_E)
op("post", "/messages", "correlateMessage", "Processes", "Correlate a message",
   "Correlates to exactly one waiting instance (or all); refused at back-pressure for starts (VI.2.4).", ["R41", "R38"],
   body="MessageRequest", resp=(200, "MessageResult"), cmd="message.correlate", perm="message.send",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "ServiceUnavailable"))
op("post", "/signals", "throwSignal", "Processes", "Throw a signal", "Broadcast or execution-targeted signal.", ["R41"], body="SignalRequest",
   resp=(204, None), cmd="signal.throw", perm="message.send")

# Cases
op("post", "/case-instances", "startCaseInstance", "Cases", "Start a case", "Starts a CMMN 1.1 case (R45).", ["R45", "R41"],
   body="StartCaseRequest", resp=(201, "CaseInstance"), cmd="case.start", perm="case.write", created=True)
op("get", "/case-instances", "listCaseInstances", "Cases", "List cases", "Authorized list.", ["R45", "A-16"], list_=True,
   params=[q("key"), q("businessKey"), q("active", "bool")], resp=(200, "array:CaseInstance"), perm="case.read", errors=STD_R)
op("get", "/case-instances/{id}", "getCaseInstance", "Cases", "Get a case", "Case with plan item instances.", ["R45"], params=[ID],
   resp=(200, "CaseInstance"), perm="case.read", errors=GET_E)
op("get", "/case-instances/{id}/variables", "getCaseVariables", "Cases", "Case variables", "Case variables (K11).", ["R45"], params=[ID],
   resp=(200, "object"), perm="case.read", errors=GET_E)
op("post", "/case-instances/{id}/commands", "commandCaseInstance", "Cases", "Case command",
   "complete | close | terminate | reactivate (FC-59, linked new case).", ["R45", "FC-59", "R41"], params=[ID], body="CaseInstanceCommand",
   resp=(200, "CaseInstance"), cmd="case.command", perm="case.write")
op("post", "/case-instances/{id}/executions/{executionId}/commands", "commandCaseExecution", "Cases", "Plan item command",
   "start | complete | disable | reenable | terminate | manualStart (discretionary items; FC-55, FC-56).", ["R45", "FC-55", "FC-56", "R41"],
   params=[ID, pp("executionId")], body="CaseExecutionCommand", resp=(200, "CaseInstance"), cmd="case.execution.command", perm="case.write")
op("get", "/case-definitions", "listCaseDefinitions", "Cases", "List case definitions", "Deployed CMMN definitions.", ["R45"], list_=True,
   params=[q("key"), q("latest", "bool")], resp=(200, "array:CaseDefinition"), perm="case.read", errors=STD_R)

# Decisions
op("post", "/decisions/{key}/evaluate", "evaluateDecision", "Decisions", "Evaluate a decision",
   "Evaluates the latest (or given) version; ROUTING/AUTHORITY evaluations with a businessKey are recorded as class-B events (X.2.3.2).",
   ["R8", "R42", "IX.5"], params=[pp("key")], body="DecisionEvaluateRequest", resp=(200, "DecisionEvaluateResult"), perm="decision.evaluate",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Unprocessable"))
op("get", "/decision-definitions", "listDecisionDefinitions", "Decisions", "List decision definitions", "Deployed DMN decisions.", [],
   list_=True, params=[q("key"), q("latest", "bool")], resp=(200, "array:DecisionDefinition"), perm="process.read", errors=STD_R)
op("get", "/decision-definitions/{id}/xml", "getDecisionDefinitionXml", "Decisions", "DMN XML", "The deployed DMN XML.", [], params=[ID],
   resp=(200, XML), perm="process.read", errors=GET_E)

# Deployments
op("post", "/deployments", "createDeployment", "Deployments", "Deploy resources",
   "Raw engine deployment (development); governed releases use /packages (R43). Validation per R44/FC-46.", ["R43", "R44", "FC-46", "R41"],
   body={"required": True, "content": {"multipart/form-data": {"schema": {"type": "object", "required": ["files"], "properties": {
       "name": {"type": "string"}, "source": {"type": "string"}, "duplicateFiltering": {"type": "boolean"},
       "files": {"type": "array", "items": {"type": "string", "contentMediaType": "application/octet-stream"}}}}}}},
   resp=(201, "Deployment"), cmd="deployment.create", perm="deployment.write", created=True)
op("get", "/deployments", "listDeployments", "Deployments", "List deployments", "Deployments of the tenant.", [], list_=True,
   params=[q("name")], resp=(200, "array:Deployment"), perm="deployment.read", errors=STD_R)
op("get", "/deployments/{id}", "getDeployment", "Deployments", "Get a deployment", "Deployment with its definitions.", [], params=[ID],
   resp=(200, "Deployment"), perm="deployment.read", errors=GET_E)
op("get", "/deployments/{id}/resources/{resourceName}", "getDeploymentResource", "Deployments", "Deployment resource", "Raw resource bytes.",
   [], params=[ID, pp("resourceName")], resp=(200, {"application/octet-stream": {"schema": {"type": "string", "contentMediaType": "application/octet-stream"}}}),
   perm="deployment.read", errors=GET_E)
op("delete", "/deployments/{id}", "deleteDeployment", "Deployments", "Delete a deployment", "Deletes a deployment (cascade deletes instances).",
   ["R41", "R42"], params=[ID, q("cascade", "bool"), P("reason")], resp=(204, None), cmd="deployment.delete", perm="deployment.write")

# Packages
op("post", "/packages", "createPackage", "Packages", "Create a package draft", "New DRAFT executable package (R43, FC-41).",
   ["R43", "FC-41", "R41"], body="CreatePackageRequest", resp=(201, "Package"), cmd="package.create", perm="package.author", created=True, etag=True)
op("get", "/packages", "listPackages", "Packages", "List packages", "Packages of the tenant.", ["R43"], list_=True,
   params=[q("key"), q("state")], resp=(200, "array:Package"), perm="package.read", errors=STD_R)
op("get", "/packages/{id}", "getPackage", "Packages", "Get a package", "Package with lifecycle history; ETag = revision.", ["R43"],
   params=[ID], resp=(200, "Package"), perm="package.read", errors=GET_E, etag=True)
op("put", "/packages/{id}", "updatePackage", "Packages", "Edit a draft", "DRAFT only; If-Match revision, 409 REVISION_CONFLICT when stale (X.2.4.4).",
   ["R43", "R41"], params=[ID], if_match=True, body="UpdatePackageRequest", resp=(200, "Package"), cmd="package.update",
   perm="package.author", etag=True)
op("post", "/packages/{id}/transitions", "transitionPackage", "Packages", "Lifecycle transition",
   "submit (hash frozen) | approve | reject | deploy | activate | suspend | resume | withdraw | retire; four-eyes by person and seat; 409 INVALID_TRANSITION, 422 SEGREGATION_OF_DUTIES.",
   ["R43", "X.2.4", "R41"], params=[ID], body="PackageTransitionRequest", resp=(200, "Package"), cmd="package.transition", perm="package.read")
op("get", "/packages/{id}/diff", "diffPackage", "Packages", "Semantic diff", "Diff against the ACTIVE version of the same key (viewed before approval).",
   ["R43"], params=[ID], resp=(200, "array:PackageDiffEntry"), perm="package.read", errors=GET_E)

# Documents
op("post", "/documents", "uploadDocument", "Documents", "Upload a document", "Stores an immutable version with SHA-256 checksum and scan hook (FC-40, R74, K12).",
   ["FC-40", "R74", "K12", "R41"], body=MULTI_DOC, resp=(201, "Document"), cmd="document.upload", perm="document.write", created=True)
op("get", "/documents", "listDocuments", "Documents", "List documents", "Authorized document list.", ["FC-40", "R74"], list_=True,
   params=[q("taskId"), q("processInstanceId"), q("caseInstanceId"), q("name")], resp=(200, "array:Document"), perm="document.read", errors=STD_R)
op("get", "/documents/{id}", "getDocument", "Documents", "Document metadata", "Latest version metadata.", ["R74"], params=[ID],
   resp=(200, "Document"), perm="document.read", errors=GET_E)
op("get", "/documents/{id}/content", "getDocumentContent", "Documents", "Document content", "Content of the latest or given version.", ["R74"],
   params=[ID, q("version", "int")], resp=(200, {"application/octet-stream": {"schema": {"type": "string", "contentMediaType": "application/octet-stream"}}}),
   perm="document.read", errors=GET_E)
op("post", "/documents/{id}/versions", "addDocumentVersion", "Documents", "New document version", "Adds an immutable version (R74).",
   ["R74", "R41"], params=[ID], body=MULTI_FILE, resp=(201, "Document"), cmd="document.version", perm="document.write", created=True)
op("delete", "/documents/{id}", "deleteDocument", "Documents", "Delete a document", "Deletes (subject to retention and legal hold, R29, R61).",
   ["R74", "R61", "R41"], params=[ID, P("reason")], resp=(204, None), cmd="document.delete", perm="document.write")

# Links
op("get", "/links", "listLinks", "Links", "List entity links", "Links of one scope (FC-31, FC-32).", ["FC-31", "FC-32"],
   params=[q("scopeType", required=True, enum=["bpmn", "cmmn", "task"]), q("scopeId", required=True), q("linkType")],
   resp=(200, "array:EntityLink"), perm="process.read", errors=STD_R)
op("post", "/links", "createLink", "Links", "Create a link", "Explicit reference/sibling link (FC-31).", ["FC-31", "R41"], body="CreateLinkRequest",
   resp=(201, "EntityLink"), cmd="link.create", perm="process.write", created=True)
op("delete", "/links/{id}", "deleteLink", "Links", "Delete a link", "Removes an explicit link.", ["FC-31", "R41"], params=[ID, P("reason")],
   resp=(204, None), cmd="link.delete", perm="process.write")

# History
HQ = [q("processDefinitionKey"), q("processInstanceId"), q("businessKey"), q("caseInstanceId"), q("activityId"), q("taskDefinitionKey"),
      q("assignee"), q("finished", "bool"), q("from", "dt"), q("to", "dt")]
for path, opid, s, sch in [
    ("process-instances", "listHistoricProcessInstances", "Historic process instances", "HistoricProcessInstance"),
    ("activity-instances", "listHistoricActivityInstances", "Historic activity instances", "HistoricActivityInstance"),
    ("tasks", "listHistoricTasks", "Historic tasks", "HistoricTask"),
    ("variables", "listHistoricVariables", "Historic variables", "HistoricVariable"),
    ("decisions", "listHistoricDecisions", "Historic decision evaluations", "HistoricDecision"),
    ("incidents", "listHistoricIncidents", "Historic incidents", "HistoricIncident"),
    ("job-log", "listHistoricJobLog", "Job log", "HistoricJobLog"),
    ("operation-log", "listUserOperationLog", "User operation log", "UserOperation"),
]:
    op("get", "/history/" + path, opid, "History", s, "Class-A operational history (READ_HISTORY; not evidence, rule D-0; R63 history views, PF-01).",
       ["R63", "PF-01", "VI.4"], list_=True, params=HQ, resp=(200, "array:" + sch), perm="history.read", errors=STD_R)

# Audit
op("get", "/audit/{businessKey}/timeline", "getAuditTimeline", "Audit", "Audit timeline of a case",
   "Class-B chain events of every root with this business key, optionally merged with class-A history (evidence=false). The read is audited (AUDIT_VIEWED).",
   ["R42", "X.2.3", "T30"], list_=True, params=[pp("businessKey"), q("includeHistory", "bool", "Merge class-A history (default true)")],
   resp=(200, "array:AuditEntry"), perm="audit.read", errors=GET_E)
op("get", "/audit/events", "searchAuditEvents", "Audit", "Search audit events", "Auditor search (U01).", ["R42"], list_=True,
   params=[q("chainId"), q("type"), q("actor"), q("from", "dt"), q("to", "dt")], resp=(200, "array:AuditEntry"), perm="audit.read", errors=STD_R)
op("get", "/audit/verify", "verifyAudit", "Audit", "Verify hash chains",
   "Recomputes chains (gapless seq, prev_hash links, hashes, payload digests, chain head) and reports the first divergence (X.2.3.4 rule 5).",
   ["R42", "X.2.3.4"], params=[q("chainId"), q("businessKey")], resp=(200, "AuditVerifyResult"), perm="audit.read", errors=STD_R)
op("get", "/audit/export", "exportAudit", "Audit", "Export the audit trail", "Bounded export; writes AUDIT_EXPORTED (R42).", ["R42"],
   params=[q("businessKey", required=True), q("format", enum=["json", "csv"])],
   resp=(200, {"application/json": {"schema": {"type": "array", "items": R("AuditEntry")}}, "text/csv": {"schema": {"type": "string"}}}),
   perm="audit.export", errors=STD_R)

# Admin
AQ = [q("q", desc="Free-text search"), q("parentId"), q("orgUnitId"), q("seatId"), q("personId"), q("kind"), q("activeOn", "date"),
      q("includeInactive", "bool")]


def crud(base, sch, name, plural, cmdbase, perm_r, perm_w, reqs, update=True, delete=True, get=True):
    tag = "Admin"
    op("get", base, "list" + plural, tag, "List " + name.lower() + "s", "Tenant-scoped admin list.", reqs, list_=True, params=AQ,
       resp=(200, "array:" + sch), perm=perm_r, errors=STD_R)
    op("post", base, "create" + name, tag, "Create " + name.lower(), "Creates and audits on the tenant security chain.", reqs + ["R41", "R42"],
       body=sch, resp=(201, sch), cmd=cmdbase + ".create", perm=perm_w, created=True)
    if get:
        op("get", base + "/{id}", "get" + name, tag, "Get " + name.lower(), "One item.", reqs, params=[ID], resp=(200, sch), perm=perm_r, errors=GET_E)
    if update:
        op("put", base + "/{id}", "update" + name, tag, "Update " + name.lower(), "Updates and audits.", reqs + ["R41", "R42"], params=[ID],
           body=sch, resp=(200, sch), cmd=cmdbase + ".update", perm=perm_w)
    if delete:
        op("delete", base + "/{id}", "delete" + name, tag, "Delete " + name.lower(), "Deletes (reason audited).", reqs + ["R41", "R42"],
           params=[ID, P("reason")], resp=(204, None), cmd=cmdbase + ".delete", perm=perm_w)


crud("/admin/org-units", "OrgUnit", "OrgUnit", "OrgUnits", "admin.orgunit", "admin.read", "admin.write", ["IX.5", "R19"])
crud("/admin/seats", "Seat", "Seat", "Seats", "admin.seat", "admin.read", "admin.write", ["IX.5", "R19"])
crud("/admin/assignments", "Assignment", "Assignment", "Assignments", "admin.assignment", "admin.read", "admin.write", ["IX.5"],
     update=False, delete=False)
op("post", "/admin/assignments/{id}/end", "endAssignment", "Admin", "End an assignment", "Ends an effective-dated holding (SEAT_HOLDER_CHANGED).",
   ["IX.5", "R41", "R42"], params=[ID], body="EndAssignmentRequest", resp=(200, "Assignment"), cmd="admin.assignment.end", perm="admin.write")
crud("/admin/delegations", "Delegation", "Delegation", "Delegations", "admin.delegation", "admin.read", "admin.write", ["IX.5"],
     update=False, delete=False)
op("post", "/admin/delegations/{id}/revoke", "revokeDelegation", "Admin", "Revoke a delegation", "Revokes (DELEGATION_EXPIRED).",
   ["IX.5", "R41", "R42"], params=[ID], body="ReasonRequest", resp=(200, "Delegation"), cmd="admin.delegation.revoke", perm="admin.write")
crud("/admin/policies", "Policy", "Policy", "Policies", "admin.policy", "policy.admin", "policy.admin", ["VI.4", "A-13", "R19"],
     update=False, get=False)
op("get", "/admin/policies/version", "getPolicyVersion", "Admin", "Policy version", "Current policy_version of the tenant (VI.4 rule 5).",
   ["VI.4"], resp=(200, "PolicyVersion"), perm="policy.admin", errors=STD_R)
crud("/admin/tenants", "Tenant", "Tenant", "Tenants", "admin.tenant", "tenant.admin", "tenant.admin", ["EC-17", "R19"], delete=False)

# Ops
op("get", "/ops/incidents", "listIncidents", "Ops", "List incidents", "Open incidents (O01).", ["R63"], list_=True,
   params=[q("type"), q("processInstanceId"), q("processDefinitionKey"), q("activityId")], resp=(200, "array:Incident"), perm="ops.read", errors=STD_R)
op("get", "/ops/incidents/{id}", "getIncident", "Ops", "Get an incident", "One incident.", ["R63"], params=[ID], resp=(200, "Incident"),
   perm="ops.read", errors=GET_E)
op("post", "/ops/incidents/{id}/retry", "retryIncident", "Ops", "Retry an incident", "Sets retries on the failed job/work (INCIDENT_RETRIED).",
   ["R63", "R41", "R42"], params=[ID], body="IncidentAction", resp=(200, "Incident"), cmd="ops.incident.retry", perm="ops.write")
op("post", "/ops/incidents/{id}/resolve", "resolveIncident", "Ops", "Resolve an incident", "Resolves with a reason (INCIDENT_RESOLVED).",
   ["R63", "R41", "R42"], params=[ID], body="IncidentAction", resp=(200, "Incident"), cmd="ops.incident.resolve", perm="ops.write")
op("get", "/ops/reconciliation", "listReconciliationItems", "Ops", "Reconciliation workbench", "UNKNOWN_OUTCOME and LATE_RESULT_AFTER_DELETE items (O04, O05).",
   ["VI.3", "VI.5", "A-2"], list_=True, params=[q("state", enum=["OPEN", "RESOLVED"]), q("kind", enum=["UNKNOWN_OUTCOME", "LATE_RESULT_AFTER_DELETE"])],
   resp=(200, "array:ReconciliationItem"), perm="ops.read", errors=STD_R)
op("get", "/ops/reconciliation/{id}", "getReconciliationItem", "Ops", "Get a reconciliation item", "One item.", ["VI.3"], params=[ID],
   resp=(200, "ReconciliationItem"), perm="ops.read", errors=GET_E)
op("post", "/ops/reconciliation/{id}/resolve", "resolveReconciliationItem", "Ops", "Resolve a reconciliation item",
   "CONFIRM_SUCCESS | RETRY_SAME_OPERATION (same operationId) | MARK_FAILED, with evidence; a money item cannot be resolved by its initiator (OJ-02, RECON_ITEM_RESOLVED).",
   ["VI.3", "A-2", "R41", "R42", "T27"], params=[ID], body="ReconciliationResolveRequest", resp=(200, "ReconciliationItem"),
   cmd="ops.reconciliation.resolve", perm="recon.resolve")
op("get", "/ops/outbox", "listOutboxDeliveries", "Ops", "Outbox deliveries", "Delivery rows (VI.2).", ["VI.2"], list_=True,
   params=[q("state"), q("destination")], resp=(200, "array:OutboxDelivery"), perm="ops.read", errors=STD_R)
op("get", "/ops/outbox/stats", "getOutboxStats", "Ops", "Outbox stats", "Budget use and back-pressure state (VI.2.4, K15).", ["VI.2.4", "R40"],
   resp=(200, "OutboxStats"), perm="ops.read", errors=STD_R)
op("post", "/ops/outbox/{outboxId}/requeue", "requeueOutboxDelivery", "Ops", "Requeue a delivery", "Repair: requeue (OUTBOX_REQUEUED).",
   ["VI.2.3", "R41", "R42"], params=[pp("outboxId")], body="OutboxRepairRequest", resp=(200, "OutboxDelivery"), cmd="ops.outbox.requeue", perm="ops.write")
op("post", "/ops/outbox/{outboxId}/skip", "skipOutboxDelivery", "Ops", "Skip a delivery", "Repair: skip (OUTBOX_SKIPPED).",
   ["VI.2.3", "R41", "R42"], params=[pp("outboxId")], body="OutboxRepairRequest", resp=(200, "OutboxDelivery"), cmd="ops.outbox.skip", perm="ops.write")
op("get", "/ops/jobs", "listJobs", "Ops", "List jobs", "Engine jobs (timers, async continuations).", ["R63"], list_=True,
   params=[q("processInstanceId"), q("processDefinitionKey"), q("withException", "bool"), q("timers", "bool"), q("executable", "bool")],
   resp=(200, "array:Job"), perm="ops.read", errors=STD_R)
op("post", "/ops/jobs/{id}/retries", "setJobRetries", "Ops", "Set job retries", "Sets retries.", ["R63", "R41"], params=[ID],
   body="JobRetriesRequest", resp=(200, "Job"), cmd="ops.job.retries", perm="ops.write")
op("post", "/ops/jobs/{id}/execute", "executeJob", "Ops", "Execute a job now", "Executes the job synchronously.", ["R63", "R41"],
   params=[ID, P("reason")], resp=(200, "Job"), cmd="ops.job.execute", perm="ops.write")
op("post", "/ops/jobs/{id}/suspension", "setJobSuspension", "Ops", "Suspend or activate a job", "Suspension state.", ["R63", "R41"],
   params=[ID], body="JobSuspensionRequest", resp=(200, "Job"), cmd="ops.job.suspension", perm="ops.write")
op("post", "/ops/batches/preview", "previewBatch", "Ops", "Preview a batch", "Count from the same authorization predicate before execution (R64).",
   ["R64"], body="BatchRequest", resp=(200, "BatchPreview"), perm="batch.admin", errors=STD_R)
op("post", "/ops/batches", "createBatch", "Ops", "Create a batch",
   "Asynchronous batch, one root per transaction (VI.1 rule 3); four-eyes above the threshold (AWAITING_APPROVAL).",
   ["R64", "PF-06", "GP-08", "R41"], body="BatchRequest", resp=(201, "Batch"), cmd="ops.batch.create", perm="batch.admin", created=True)
op("get", "/ops/batches", "listBatches", "Ops", "List batches", "Batches of the tenant.", ["R64"], list_=True, params=[q("state")],
   resp=(200, "array:Batch"), perm="ops.read", errors=STD_R)
op("get", "/ops/batches/{id}", "getBatch", "Ops", "Get a batch", "Progress.", ["R64"], params=[ID], resp=(200, "Batch"), perm="ops.read", errors=GET_E)
op("get", "/ops/batches/{id}/items", "listBatchItems", "Ops", "Batch items", "Per-item results and failures.", ["R64"], list_=True,
   params=[ID, q("state", enum=["PENDING", "SUCCEEDED", "FAILED"])], resp=(200, "array:BatchItem"), perm="ops.read", errors=GET_E)
op("post", "/ops/batches/{id}/control", "controlBatch", "Ops", "Control a batch", "PAUSE | RESUME | CANCEL | APPROVE (four-eyes: approver ≠ creator).",
   ["R64", "R41"], params=[ID], body="BatchControlRequest", resp=(200, "Batch"), cmd="ops.batch.control", perm="batch.admin")
op("post", "/ops/migrations/plan", "planMigration", "Ops", "Plan and validate a migration",
   "Generates and validates the plan, including 1:N/N:1 mappings and form mappings, with a dry-run count (R48, FC-18, T21).",
   ["R48", "FC-18", "GP-20", "PF-04"], body="MigrationPlanRequest", resp=(200, "MigrationPlan"), perm="batch.admin",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Unprocessable"))
op("post", "/ops/migrations/execute", "executeMigration", "Ops", "Execute a migration", "Runs the migration as a batch.",
   ["R48", "R64", "FC-18", "R41"], body="MigrationExecuteRequest", resp=(202, "Batch"), cmd="ops.migration.execute", perm="batch.admin")
op("post", "/ops/restarts", "restartProcessInstances", "Ops", "Restart instances", "Restarts completed/cancelled instances from chosen activities (R63, PF-05).",
   ["R63", "PF-05", "R41"], body="RestartRequest", resp=(202, "Batch"), cmd="ops.restart", perm="batch.admin")
op("get", "/ops/cleanup", "getCleanupStatus", "Ops", "History cleanup status", "Cleanup job state and removable counts (R29, PF-10).",
   ["R29", "PF-10"], resp=(200, "CleanupStatus"), perm="ops.read", errors=STD_R)
op("post", "/ops/cleanup", "triggerCleanup", "Ops", "Trigger history cleanup", "Schedules history cleanup now.", ["R29", "PF-10", "R41"],
   params=[P("reason")], resp=(202, "CleanupStatus"), cmd="ops.cleanup", perm="ops.write")

# Events
op("get", "/events/channels", "listEventChannels", "Events", "List channels", "Event channels (FC-03).", ["FC-03"], list_=True,
   resp=(200, "array:EventChannel"), perm="event.admin", errors=STD_R)
op("post", "/events/channels", "createEventChannel", "Events", "Create a channel", "Registers a channel; keys 'channels' and 'definitions' are reserved.",
   ["FC-03", "R41"], body="EventChannel", resp=(201, "EventChannel"), cmd="event.channel.create", perm="event.admin", created=True)
KEY = pp("key")
op("get", "/events/channels/{key}", "getEventChannel", "Events", "Get a channel", "One channel.", ["FC-03"], params=[KEY],
   resp=(200, "EventChannel"), perm="event.admin", errors=GET_E)
op("put", "/events/channels/{key}", "updateEventChannel", "Events", "Update a channel", "Updates a channel.", ["FC-03", "R41"], params=[KEY],
   body="EventChannel", resp=(200, "EventChannel"), cmd="event.channel.update", perm="event.admin")
EVT_CONFLICT = ("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict")
op("delete", "/events/channels/{key}", "deleteEventChannel", "Events", "Delete a channel",
   "Retires every active version and removes the retired versions. Refused with 409 EVENT_IN_FLIGHT while events on the channel are buffered in the inbox or undelivered in the outbox.",
   ["FC-03", "R41"], params=[KEY, P("reason")], resp=(204, None), cmd="event.channel.delete", perm="event.admin",
   errors=EVT_CONFLICT)
op("post", "/events/channels/{key}/retire", "retireEventChannel", "Events", "Retire a channel",
   "Marks every active version RETIRED: the channel no longer resolves and stops receiving; an older active version, if any, becomes current. Refused with 409 EVENT_IN_FLIGHT while events are in flight. Returns the channel with active=false (or the version that became current).",
   ["FC-03", "R41", "R42"], params=[KEY], body="ReasonRequest", resp=(200, "EventChannel"), cmd="event.channel.retire",
   perm="event.admin", errors=EVT_CONFLICT)
op("post", "/events/channels/{key}/restore", "restoreEventChannel", "Events", "Restore a channel",
   "Re-activates the newest retired version (undo of a retire); 409 NOT_RETIRED when there is none.",
   ["FC-03", "R41", "R42"], params=[KEY], body="ReasonRequest", resp=(200, "EventChannel"), cmd="event.channel.restore",
   perm="event.admin", errors=EVT_CONFLICT)
op("get", "/events/definitions", "listEventDefinitions", "Events", "List event definitions", "Event definitions (FC-02, FC-04).", ["FC-02", "FC-04"],
   list_=True, resp=(200, "array:EventDefinition"), perm="event.admin", errors=STD_R)
op("post", "/events/definitions", "createEventDefinition", "Events", "Create an event definition", "Registers an event definition.",
   ["FC-02", "FC-04", "R41"], body="EventDefinition", resp=(201, "EventDefinition"), cmd="event.definition.create", perm="event.admin", created=True)
op("get", "/events/definitions/{key}", "getEventDefinition", "Events", "Get an event definition", "One definition.", ["FC-02"], params=[KEY],
   resp=(200, "EventDefinition"), perm="event.admin", errors=GET_E)
op("delete", "/events/definitions/{key}", "deleteEventDefinition", "Events", "Delete an event definition",
   "Retires every active version and removes the retired versions. Refused with 409 EVENT_IN_FLIGHT while events of the key are buffered or undelivered.",
   ["FC-02", "R41"], params=[KEY, P("reason")], resp=(204, None), cmd="event.definition.delete", perm="event.admin",
   errors=EVT_CONFLICT)
op("post", "/events/definitions/{key}/retire", "retireEventDefinition", "Events", "Retire an event definition",
   "Marks every active version RETIRED; an older active version, if any, becomes current. Refused with 409 EVENT_IN_FLIGHT while events of the key are in flight.",
   ["FC-02", "R41", "R42"], params=[KEY], body="ReasonRequest", resp=(200, "EventDefinition"),
   cmd="event.definition.retire", perm="event.admin", errors=EVT_CONFLICT)
op("post", "/events/definitions/{key}/restore", "restoreEventDefinition", "Events", "Restore an event definition",
   "Re-activates the newest retired version (undo of a retire); 409 NOT_RETIRED when there is none.",
   ["FC-02", "R41", "R42"], params=[KEY], body="ReasonRequest", resp=(200, "EventDefinition"),
   cmd="event.definition.restore", perm="event.admin", errors=EVT_CONFLICT)
op("post", "/events/{channel}", "publishEvent", "Events", "Publish an event",
   "Accepts a CloudEvent on an inbound channel; de-duplicated through the inbox by (tenant, source, id) (R38), then starts (uniqueStart) or correlates (FC-02, FC-04, R83).",
   ["FC-02", "FC-03", "FC-04", "R38", "R83", "R41"], params=[pp("channel")],
   body={"required": True, "content": {"application/cloudevents+json": {"schema": R("CloudEvent")}, "application/json": {"schema": R("CloudEvent")}}},
   resp=(202, "PublishResult"), cmd="event.publish", perm="event.publish",
   errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "ServiceUnavailable"))

# Forms
op("get", "/forms/{key}", "getForm", "Forms", "Get a form version", "Data schema, layout, field policy and i18n bundles by hash version (R47, FC-39); the key 'drafts' is reserved.",
   ["R47", "FC-39", "X.2.5"], params=[KEY, q("version", desc="Form version hash; default the task-bound or ACTIVE version")],
   resp=(200, "FormVersion"), perm="form.read", errors=GET_E)
op("post", "/forms/{key}/validate", "validateForm", "Forms", "Validate form data", "Authoritative server-side validation (X.2.5.2).",
   ["R47", "X.2.5.2"], params=[KEY], body="FormValidateRequest", resp=(200, "FormValidationResult"), perm="form.read", errors=GET_E)
op("get", "/forms/drafts", "listFormDrafts", "Forms", "List drafts", "The caller's drafts (person or seat owned, X.2.5.8).", ["R47"], list_=True,
   params=[q("taskId")], resp=(200, "array:FormDraft"), perm="form.draft", errors=STD_R)
op("post", "/forms/drafts", "createFormDraft", "Forms", "Create a draft", "Server-side encrypted draft (not a submission).", ["R47", "R41"],
   body="SaveDraftRequest", resp=(201, "FormDraft"), cmd="form.draft.create", perm="form.draft", created=True, etag=True)
DID = pp("draftId")
op("get", "/forms/drafts/{draftId}", "getFormDraft", "Forms", "Get a draft", "ETag = revision.", ["R47"], params=[DID],
   resp=(200, "FormDraft"), perm="form.draft", errors=GET_E, etag=True)
op("put", "/forms/drafts/{draftId}", "saveFormDraft", "Forms", "Save a draft", "If-Match revision; 409 DRAFT_CONFLICT with both versions (X.2.5.8 rule 1).",
   ["R47", "R41"], params=[DID], if_match=True, body="SaveDraftRequest", resp=(200, "FormDraft"), cmd="form.draft.save", perm="form.draft", etag=True)
op("delete", "/forms/drafts/{draftId}", "deleteFormDraft", "Forms", "Delete a draft", "Discards the draft.", ["R47", "R41"], params=[DID],
   resp=(204, None), cmd="form.draft.delete", perm="form.draft")

# Analytics
RQ = [q("processDefinitionKey"), q("from", "dt"), q("to", "dt")]
op("get", "/analytics/summary", "getAnalyticsSummary", "Analytics", "Process KPIs", "Counts and duration percentiles (R65); hidden records never counted.",
   ["R65", "VI.4"], params=RQ, resp=(200, "AnalyticsSummary"), perm="analytics.read", errors=STD_R)
op("get", "/analytics/activities", "getActivityStats", "Analytics", "Activity statistics", "Heatmap and duration overlay (R63).", ["R63", "R65"],
   params=RQ, resp=(200, "array:ActivityStat"), perm="analytics.read", errors=STD_R)
op("get", "/analytics/tasks", "getTaskStats", "Analytics", "User-task statistics", "Task durations (R65).", ["R65"], params=RQ,
   resp=(200, "array:TaskStat"), perm="analytics.read", errors=STD_R)
op("get", "/analytics/variants", "getVariants", "Analytics", "Process variants", "Activity-sequence variants (R66, UP-07).", ["R66", "UP-07"],
   params=RQ + [q("limit", "int", "Maximum variants (default 20)")], resp=(200, "array:Variant"), perm="analytics.read", errors=STD_R)
op("get", "/analytics/export/xes", "exportXes", "Analytics", "Export XES", "IEEE 1849 XES event log (R15).", ["R15", "R66"],
   params=RQ + [q("maxTraces", "int", "Default 10000")], resp=(200, XML), perm="analytics.read", errors=STD_R)
op("get", "/analytics/export/ocel", "exportOcel", "Analytics", "Export OCEL 2.0", "OCEL 2.0 JSON object-centric event log (R15).", ["R15", "R66"],
   params=RQ + [q("maxTraces", "int", "Default 10000")], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}),
   perm="analytics.read", errors=STD_R)

# ---------------------------------------------------------------- operations, round 2 (UI proposals, ROUTES2.md)
BIN = lambda ct: {ct: {"schema": {"type": "string", "contentMediaType": ct}}}
WS_E = ("Unauthorized", "Forbidden")
FPR = lambda ct="application/octet-stream": {"type": "string", "contentMediaType": ct}

# Me / workspace (WorkspacePort)
op("get", "/me", "getMe", "Me", "Current caller",
   "Identity, tenant, personas (roles), seats held on today's date with kind and validity, the selected seat context and the permissions the server grants (K1, VI.4 rule 6, IX.5 rules 1 and 4, S02).",
   ["VI.4", "IX.5", "R76"], resp=(200, "Me"), errors=("Unauthorized",))
op("get", "/me/notifications", "listMyNotifications", "Me", "My notifications", "In-app notifications of the caller (R60).",
   ["R60", "R76"], list_=True, resp=(200, "array:Notification"), errors=WS_E)
op("post", "/me/notifications/{id}/read", "markNotificationRead", "Me", "Mark a notification read", "Marks one notification read.",
   ["R60", "R41"], params=[ID], resp=(204, None), cmd="me.notification.read", errors=("Unauthorized", "Forbidden", "NotFound"))
op("get", "/me/delegations", "listMyDelegations", "Me", "My delegations and acting charges",
   "Assignments where the caller acts for another seat or person: additional charge, leave substitute (IX.5 rule 4).",
   ["IX.5"], resp=(200, "array:Assignment"), errors=WS_E)
op("post", "/me/leave-requests", "requestLeave", "Me", "Request leave with a substitute",
   "Creates a LEAVE_SUBSTITUTE assignment pending approval; the substitute acts for the seat only within the date range (IX.5 rule 4, PER-4).",
   ["IX.5", "R41"], body="LeaveRequest", resp=(201, "Assignment"), cmd="me.leave.request", created=True)

TQ = [ID]
op("get", "/tasks/counts", "getTaskCounts", "Workspace", "Inbox counts",
   "Count per inbox section (MY_SEAT, ACTING_FOR, POOL, ESCALATED, RETURNED, ALL) through the same authorization predicate as the list; a capped count is the string \"N+\" (A-16, K2).",
   ["VI.4", "A-16", "R76"], resp=(200, {"application/json": {"schema": {"type": "object", "additionalProperties": {
       "anyOf": [{"type": "integer", "format": "int64"}, {"type": "string", "pattern": "^[0-9]+\\+$"}]}}}}),
   perm="task.read", errors=WS_E)
op("post", "/tasks/bulk-claim", "bulkClaimTasks", "Workspace", "Claim several tasks",
   "Claims each task in its own transaction; per-item results (CX-3 for each loser).", ["R41", "CX-3", "R64"],
   body="BulkClaimRequest", resp=(200, "BulkResult"), cmd="task.bulk-claim", perm="task.write")
op("post", "/tasks/bulk-complete", "bulkCompleteTasks", "Workspace", "Complete several tasks as a batch",
   "Runs as a batch, one task per transaction; each completion is bound to the task's current payload hash, SoD and power checks apply per task (D-34, VI.4 rule 5, R64).",
   ["R64", "VI.4", "R41", "D-34"], body="BulkCompleteRequest", resp=(202, "Batch"), cmd="task.bulk-complete", perm="task.write")
op("get", "/tasks/{id}/candidates", "listTaskCandidates", "Workspace", "Eligible seats for the next step",
   "Candidates computed on the server and re-validated at commit (S07, R82, K5–K7).", ["R82", "IX.5", "K5", "K7"],
   params=TQ + [q("action", enum=["APPROVE", "FORWARD", "RETURN", "SEEK_CLARIFICATION", "REJECT", "REASSIGN", "DELEGATE", "RESUBMIT", "COMPLETE"])],
   resp=(200, "array:CandidateSeat"), perm="task.read", errors=GET_E)
op("get", "/tasks/{id}/diff", "getTaskDiff", "Workspace", "Field diff between case versions",
   "Material-payload field changes between two case versions (CX-2, S09, R43).", ["R43", "CX-2"],
   params=TQ + [q("from", "long", "From case version", required=True), q("to", "long", "To case version", required=True)],
   resp=(200, "array:FieldChange"), perm="task.read", errors=("BadRequest",) + GET_E)
op("get", "/tasks/{id}/lifecycle", "getTaskLifecycle", "Workspace", "Task lifecycle",
   "Created, assigned, claimed, delegated, acted-for, completed events of the task (FC-52, X.4.0.3).", ["FC-52", "R42"],
   params=TQ, resp=(200, "array:LifecycleEvent"), perm="task.read", errors=GET_E)
op("get", "/start-catalogue", "getStartCatalogue", "Workspace", "Start catalogue",
   "Processes and cases the caller may start (R76).", ["R76"], resp=(200, "array:CatalogueEntry"), perm="process.read", errors=WS_E)
op("get", "/case-instances/{id}/comments", "listCaseComments", "Cases", "Case comments", "Comments on the case (R76 case workspace).",
   ["R76", "R45"], params=[ID], resp=(200, "array:CaseComment"), perm="case.read", errors=GET_E)
op("post", "/case-instances/{id}/comments", "addCaseComment", "Cases", "Add a case comment", "Adds a comment.", ["R76", "R41"],
   params=[ID], body="AddCaseCommentRequest", resp=(201, "CaseComment"), cmd="case.comment", perm="case.write", created=True)
op("get", "/case-instances/{id}/participants", "listCaseParticipants", "Cases", "Case participants",
   "Participants, including external experts and parties (UP-09, UP-22).", ["R76", "UP-09", "UP-22"], params=[ID],
   resp=(200, "array:Participant"), perm="case.read", errors=GET_E)
op("post", "/case-instances/{id}/reactivation", "reactivateCase", "Cases", "Reactivate a case",
   "Starts a linked new case of the same definition with the old variables (FC-59); audited on both chains.",
   ["FC-59", "R45", "R41"], params=[ID], body="ReasonRequest", resp=(201, "CaseInstance"), cmd="case.reactivate", perm="case.write", created=True)
op("get", "/field/visits", "listFieldVisits", "Field", "My field visits", "Inspection visits assigned to the caller (S15, EC-16).",
   ["EC-16", "R76"], resp=(200, "array:FieldVisit"), perm="task.read", errors=WS_E)
op("post", "/field/visits/{id}/record", "recordFieldVisit", "Field", "Record a visit",
   "Records answers and notes captured on the device (offline-first; the Idempotency-Key makes a resend safe).", ["EC-16", "R41"],
   params=[ID], body="VisitRecord", resp=(204, None), cmd="field.visit.record", perm="task.write")

# Instance control (InstanceControlPort)
op("get", "/process-instances/{id}/cancel-preview", "previewCancel", "Processes", "Consequence preview of cancel/delete",
   "Open external work by lease state, open operation ids and tombstones that a business cancel or technical delete would create (O08, VI.5).",
   ["VI.5", "R63", "O08"], params=[ID, q("mode", enum=["BUSINESS", "TECHNICAL"], desc="Default BUSINESS")],
   resp=(200, "ConsequencePreview"), perm="process.read", errors=GET_E)
op("post", "/process-instances/{id}/suspension", "setProcessInstanceSuspension", "Processes", "Suspend or resume an instance",
   "Suspension with a mandatory reason; redateDeadlines shifts SLA due dates on resume (D-32, R63).", ["R63", "D-32", "R41", "R42"],
   params=[ID], body="SuspensionRequest", resp=(200, "ProcessInstance"), cmd="process.suspension", perm="process.write")
op("post", "/process-definitions/{id}/suspension", "setProcessDefinitionSuspension", "Processes", "Suspend or resume a definition",
   "No new starts while suspended; includeInstances suspends running instances too (R63).", ["R63", "R41", "R42"],
   params=[ID], body="SuspensionRequest", resp=(200, "ProcessDefinition"), cmd="definition.suspension", perm="process.modify")
op("post", "/process-instances/{id}/modify/preview", "previewModification", "Processes", "Consequence preview of a modification",
   "What a modification would do, with R49 limits (O03).", ["R49", "R63", "O03"], params=[ID], body="ModifyPreviewRequest",
   resp=(200, "ConsequencePreview"), perm="process.read", errors=("BadRequest",) + GET_E)
op("post", "/process-instances/{id}/cross-boundary-move", "moveAcrossBoundary", "Processes", "Move a token across a call-activity boundary",
   "Cancels an activity instance and starts the target activity in another instance of the same root, in one command (FC-17, R49).",
   ["FC-17", "R49", "R41", "R42"], params=[ID], body="CrossBoundaryMoveRequest", resp=(200, "ProcessInstance"),
   cmd="process.cross-boundary-move", perm="process.modify")
op("get", "/history/external-task-log", "listExternalTaskLog", "History", "External-task log",
   "Historic external-task log (class A, PF-01).", ["R63", "PF-01"], list_=True, params=HQ,
   resp=(200, "array:ExternalTaskLogEntry"), perm="history.read", errors=STD_R)

# Portal (PortalPort)
PE = ("BadRequest", "Unauthorized", "Forbidden", "NotFound")
op("get", "/portal/services", "listPortalServices", "Portal", "Citizen services", "Services the applicant can apply for (C01).",
   ["UP-22", "R54"], resp=(200, "array:PortalService"), perm="portal.use", errors=WS_E)
op("post", "/portal/applications", "applyForService", "Portal", "Apply for a service",
   "Creates an application (draft or submitted); tenant bound by the portal channel (VI.4 rule 6).", ["UP-22", "R41", "C02"],
   body="ApplyRequest", resp=(201, "Application"), cmd="portal.apply", perm="portal.use", created=True)
op("get", "/portal/applications", "listMyApplications", "Portal", "My applications", "Only the applicant's own applications (C03).",
   ["UP-22"], list_=True, resp=(200, "array:Application"), perm="portal.use", errors=WS_E)
op("get", "/portal/applications/{id}", "getMyApplication", "Portal", "Application status",
   "Status, stage, clarifications and messages; officers appear only as seat designations (PER-8).", ["UP-22"], params=[ID],
   resp=(200, "Application"), perm="portal.use", errors=GET_E)
op("post", "/portal/applications/{id}/clarifications/{clarificationId}/reply", "replyToClarification", "Portal", "Reply to a clarification",
   "Text plus files (CLARIFICATION_RECEIVED, C04).", ["UP-22", "R41", "K12"], params=[ID, pp("clarificationId")],
   body={"required": True, "content": {"multipart/form-data": {"schema": {"type": "object", "required": ["text"], "properties": {
       "text": {"type": "string"}, "files": {"type": "array", "items": FPR()}}}}}},
   resp=(200, "Application"), cmd="portal.reply", perm="portal.use")
op("post", "/portal/applications/{id}/withdraw", "withdrawApplication", "Portal", "Withdraw an application", "CASE_WITHDRAWN.",
   ["UP-22", "R41"], params=[ID], body="WithdrawRequest", resp=(200, "Application"), cmd="portal.withdraw", perm="portal.use")
op("post", "/portal/applications/{id}/appeal", "appealApplication", "Portal", "Appeal a decision", "APPEAL_FILED.",
   ["UP-22", "R41"], params=[ID], body="AppealRequest", resp=(200, "Application"), cmd="portal.appeal", perm="portal.use")
op("get", "/portal/applications/{id}/outcome", "downloadOutcome", "Portal", "Outcome document", "Certificate, sanction or rejection order (C05).",
   ["UP-22", "R74"], params=[ID], resp=(200, BIN("application/octet-stream")), perm="portal.use", errors=GET_E)
op("get", "/portal/grievances", "listMyGrievances", "Portal", "My grievances", "Grievances and DPDP data-rights requests (C06, R53).",
   ["R53", "UP-22"], resp=(200, "array:Grievance"), perm="portal.use", errors=WS_E)
op("post", "/portal/grievances", "fileGrievance", "Portal", "File a grievance", "Files a grievance or a data-rights request.",
   ["R53", "R41"], body="GrievanceRequest", resp=(201, "GrievanceReceipt"), cmd="portal.grievance", perm="portal.use", created=True)

# Ops console (OpsConsolePort)
op("get", "/ops/dashboard", "getOpsDashboard", "Ops", "Operations dashboard",
   "Nodes, backlog per topic, outbox budget, open incidents, reconciliation by age, SLA breaches (O01, R63).", ["R63", "VI.2.4"],
   resp=(200, "OpsDashboard"), perm="ops.read", errors=WS_E)
op("get", "/ops/jobs/summary", "getJobSummary", "Ops", "Job summary", "Job counts, by executor partition (R39).", ["R63", "R39"],
   resp=(200, "JobSummary"), perm="ops.read", errors=WS_E)
op("get", "/ops/jobs/{id}/stacktrace", "getJobStacktrace", "Ops", "Job stack trace", "Exception stack trace of a failed job.", ["R63"],
   params=[ID], resp=(200, {"text/plain": {"schema": {"type": "string"}}}), perm="ops.read", errors=GET_E)
op("get", "/ops/inbox", "listInbox", "Ops", "Inbox", "Inbound messages (R38).", ["R38"], list_=True,
   params=[q("status", enum=["RECEIVED", "BUFFERED", "CORRELATED", "EXPIRED", "DUPLICATE"])], resp=(200, "array:InboxEntry"),
   perm="ops.read", errors=STD_R)
op("get", "/ops/workers", "listWorkers", "Ops", "Workers", "Workers seen recently, with leases and heartbeat age (R26, VI.3).",
   ["R26", "VI.3"], resp=(200, "array:WorkerInfo"), perm="ops.read", errors=WS_E)
op("get", "/ops/topics", "listTopics", "Ops", "Worker topics", "Topics with backlog, capability class and pause state.", ["R26", "R36"],
   resp=(200, "array:TopicInfo"), perm="ops.read", errors=WS_E)
TOPIC = pp("name", "Topic name")
op("post", "/ops/topics/{name}/pause", "pauseTopic", "Ops", "Pause a topic", "Fetch-and-lock stops offering the topic; leases already held continue.",
   ["R26", "R41", "R42"], params=[TOPIC], body="ReasonRequest", resp=(200, "TopicInfo"), cmd="ops.topic.pause", perm="ops.write")
op("post", "/ops/topics/{name}/resume", "resumeTopic", "Ops", "Resume a topic", "Offers the topic again.",
   ["R26", "R41", "R42"], params=[TOPIC], body="ReasonRequest", resp=(200, "TopicInfo"), cmd="ops.topic.resume", perm="ops.write")
op("get", "/ops/incidents/{id}/preview", "previewIncidentAction", "Ops", "Consequence preview of an incident action",
   "What retry, skip or cancel would do (O02).", ["R63", "O02"], params=[ID, q("action", required=True, enum=["retry", "skip", "cancel"])],
   resp=(200, "ConsequencePreview"), perm="ops.read", errors=GET_E)
op("post", "/ops/incidents/{id}/claim", "claimIncident", "Ops", "Claim an incident", "Takes ownership of an incident (OJ-01).",
   ["R63", "R41"], params=[ID], resp=(200, "Incident"), cmd="ops.incident.claim", perm="ops.write")
op("post", "/ops/incidents/{id}/note", "noteIncident", "Ops", "Annotate an incident", "Adds a note (annotation).", ["R63", "R41"],
   params=[ID], body="NoteRequest", resp=(200, "Incident"), cmd="ops.incident.note", perm="ops.write")
op("post", "/ops/reconciliation/{id}/claim", "claimReconciliationItem", "Ops", "Claim a reconciliation item", "RECON_ITEM_CLAIMED (OJ-02).",
   ["VI.3", "R41", "R42"], params=[ID], resp=(200, "ReconciliationItem"), cmd="ops.reconciliation.claim", perm="recon.resolve")
op("post", "/ops/reconciliation/{id}/lookup", "lookupReconciliationItem", "Ops", "Look up the downstream outcome",
   "Queries the downstream system by operationId (capability class b, A-2).", ["A-2", "VI.3", "R41"], params=[ID],
   resp=(200, "LookupResult"), cmd="ops.reconciliation.lookup", perm="recon.resolve")
op("post", "/ops/reconciliation/{id}/evidence", "addReconciliationEvidence", "Ops", "Attach evidence",
   "Bank statement, treasury advice or similar evidence for resolution (OJ-02).", ["VI.3", "R41", "K12"], params=[ID],
   body={"required": True, "content": {"multipart/form-data": {"schema": {"type": "object", "required": ["kind", "file"], "properties": {
       "kind": {"type": "string"}, "file": FPR()}}}}},
   resp=(201, "Evidence"), cmd="ops.reconciliation.evidence", perm="recon.resolve", created=True)
op("put", "/ops/cleanup/ttl/{key}", "setHistoryTtl", "Ops", "Set history time-to-live", "History TTL for a definition key (R29).",
   ["R29", "R41", "R42"], params=[pp("key", "Process definition key")], body="TtlRequest", resp=(200, "CleanupStatus"),
   cmd="ops.cleanup.ttl", perm="ops.write")
op("get", "/ops/notification-failures", "listNotificationFailures", "Ops", "Notification failures",
   "Failed SMS/e-mail/DLT notifications, recipients masked (R60).", ["R60"], list_=True, resp=(200, "array:NotificationFailure"),
   perm="ops.read", errors=STD_R)
op("post", "/ops/notification-failures/{id}/resend", "resendNotification", "Ops", "Resend a notification", "Resends with a reason.",
   ["R60", "R41"], params=[ID], body="ReasonRequest", resp=(204, None), cmd="ops.notification.resend", perm="ops.write")

# Debugger (DebugPort)
DE = ("Unauthorized", "Forbidden", "NotFound")
op("get", "/debug/status", "getDebugStatus", "Debug", "Debugger status", "Whether the debugger is enabled; never on a production profile (R80).",
   ["R80", "FC-44"], resp=(200, "DebugStatus"), perm="debug.use", errors=WS_E)
op("get", "/debug/instances/{id}/breakpoints", "getBreakpoints", "Debug", "Breakpoints", "Activity ids with breakpoints.", ["R80"],
   params=[ID], resp=(200, {"application/json": {"schema": {"type": "array", "items": {"type": "string"}}}}), perm="debug.use", errors=DE)
op("put", "/debug/instances/{id}/breakpoints", "setBreakpoints", "Debug", "Set breakpoints", "Replaces the breakpoints (sandbox tenants only).",
   ["R80", "R41"], params=[ID], body="BreakpointsRequest",
   resp=(200, {"application/json": {"schema": {"type": "array", "items": {"type": "string"}}}}), cmd="debug.breakpoints", perm="debug.use")
op("post", "/debug/instances/{id}/step", "stepInstance", "Debug", "Step", "Executes the next job of the instance.", ["R80", "R41"],
   params=[ID], resp=(200, "DebugStepResult"), cmd="debug.step", perm="debug.use")

# Insights (InsightsPort)
op("get", "/analytics/throughput", "getThroughput", "Analytics", "Throughput", "Started and completed per interval (R65).", ["R65"],
   params=RQ + [q("interval", enum=["hour", "day", "week", "month"], desc="Default day")], resp=(200, "array:TimePoint"),
   perm="analytics.read", errors=STD_R)
op("get", "/analytics/sla", "getSlaReport", "Analytics", "SLA compliance", "Within, warning and breached per key (R79).", ["R79", "R65"],
   params=RQ, resp=(200, "array:SlaRow"), perm="analytics.read", errors=STD_R)
op("get", "/analytics/conformance", "getConformance", "Analytics", "Conformance", "Traces replayed against the deployed BPMN (R66).",
   ["R66", "UP-07"], params=[q("processDefinitionKey", required=True), q("from", "dt"), q("to", "dt")],
   resp=(200, "ConformanceResult"), perm="analytics.read", errors=STD_R)
op("get", "/analytics/reports/duration", "getDurationReport", "Analytics", "Duration report", "Instance durations per period (PF-07).",
   ["R65", "PF-07"], params=RQ + [q("period", enum=["day", "week", "month"])], resp=(200, "array:DurationReportRow"),
   perm="analytics.read", errors=STD_R)
op("get", "/analytics/alerts", "listAlertRules", "Analytics", "Alert rules", "KPI threshold alerts (R65).", ["R65"],
   resp=(200, "array:AlertRule"), perm="analytics.read", errors=WS_E)
op("post", "/analytics/alerts", "createAlertRule", "Analytics", "Create an alert rule", "New alert rule.", ["R65", "R41"],
   body="AlertRule", resp=(201, "AlertRule"), cmd="analytics.alert.create", perm="analytics.admin", created=True)
op("put", "/analytics/alerts/{id}", "updateAlertRule", "Analytics", "Update an alert rule", "Updates a rule.", ["R65", "R41"],
   params=[ID], body="AlertRule", resp=(200, "AlertRule"), cmd="analytics.alert.update", perm="analytics.admin")
op("delete", "/analytics/alerts/{id}", "deleteAlertRule", "Analytics", "Delete an alert rule", "Removes a rule.", ["R65", "R41"],
   params=[ID], resp=(204, None), cmd="analytics.alert.delete", perm="analytics.admin")
op("get", "/analytics/agents", "getAgentUsage", "Analytics", "Agent usage", "Runs, tokens, cost, guardrail blocks and human reviews (R72).",
   ["R72"], params=[q("from", "dt"), q("to", "dt")], resp=(200, "array:AgentUsage"), perm="analytics.read", errors=STD_R)

# Audit exports (AuditExportPort)
op("post", "/audit/exports", "createAuditExport", "Audit", "Start an audit export", "Asynchronous bounded export; AUDIT_EXPORTED (R42, U02).",
   ["R42", "R41"], body="AuditExportRequest", resp=(202, "AuditExportJob"), cmd="audit.export.create", perm="audit.export")
op("get", "/audit/exports", "listAuditExports", "Audit", "Audit exports", "The caller's export jobs.", ["R42"],
   resp=(200, "array:AuditExportJob"), perm="audit.export", errors=WS_E)
op("get", "/audit/exports/{id}/content", "getAuditExportContent", "Audit", "Download an audit export", "Content of a READY export.", ["R42"],
   params=[ID], resp=(200, BIN("application/octet-stream")), perm="audit.export", errors=GET_E)
op("get", "/audit/{businessKey}/evidence-pack", "getEvidencePack", "Audit", "Evidence pack",
   "ZIP of the class-B timeline, chain verification, model and form versions and the document list, with a SHA-256 manifest (U03, R42).",
   ["R42", "X.2.3"], params=[pp("businessKey")], resp=(200, BIN("application/zip")), perm="audit.export", errors=GET_E)

# Document extras (DocumentExtrasPort)
op("get", "/documents/{id}/versions", "listDocumentVersions", "Documents", "Document versions", "Every immutable version (R74).", ["R74"],
   params=[ID], resp=(200, "array:Document"), perm="document.read", errors=GET_E)
op("post", "/documents/{id}/legal-hold", "setDocumentLegalHold", "Documents", "Set or release a legal hold",
   "A held document cannot be deleted or disposed of (R61, R29).", ["R61", "R41", "R42"], params=[ID], body="LegalHoldRequest",
   resp=(200, "Document"), cmd="document.legal-hold", perm="document.legal-hold")
op("get", "/documents/templates", "listDocumentTemplates", "Documents", "Document templates",
   "Output templates (R74, S12); the id 'templates' is reserved for /documents/{id}.", ["R74"], resp=(200, "array:DocumentTemplate"),
   perm="document.read", errors=WS_E)
op("post", "/documents/generate", "generateDocument", "Documents", "Generate a document",
   "Renders a template for a case and stores it as a document (R74, EC-18).", ["R74", "EC-18", "R41"], body="DocumentGenerateRequest",
   resp=(201, "Document"), cmd="document.generate", perm="document.write", created=True)

# Power tables (PowerTablePort)
op("get", "/admin/power-tables", "listPowerTables", "Admin", "Delegation-of-powers tables",
   "Versioned DMN tables of financial and administrative powers per seat (IX.5 rule 3, A04); distinct from /admin/delegations.",
   ["IX.5", "K6"], list_=True, params=[q("q")], resp=(200, "array:PowerTable"), perm="admin.read", errors=STD_R)
op("post", "/admin/power-tables", "createPowerTable", "Admin", "Create a power table", "New DRAFT table.", ["IX.5", "R41"],
   body="PowerTable", resp=(201, "PowerTable"), cmd="power-table.create", perm="admin.write", created=True)
op("get", "/admin/power-tables/{id}", "getPowerTable", "Admin", "Get a power table", "One table.", ["IX.5"], params=[ID],
   resp=(200, "PowerTable"), perm="admin.read", errors=GET_E)
op("put", "/admin/power-tables/{id}", "updatePowerTable", "Admin", "Update a power table", "DRAFT only; revision checked.", ["IX.5", "R41"],
   params=[ID], body="PowerTable", resp=(200, "PowerTable"), cmd="power-table.update", perm="admin.write")
op("post", "/admin/power-tables/{id}/check", "checkPowerTable", "Admin", "Check a power table",
   "Gaps, overlaps, hit policy and test cases.", ["IX.5", "K6"], params=[ID], resp=(200, "PowerTableCheckResult"), perm="admin.read",
   errors=GET_E + ("Unprocessable",))
for act, s, perm in [("submit", "Submit for approval", "admin.write"), ("approve", "Approve (four-eyes)", "power-table.approve"),
                     ("reject", "Reject", "power-table.approve")]:
    op("post", "/admin/power-tables/{id}/" + act, act + "PowerTable", "Admin", s + " a power table",
       "Lifecycle DRAFT → IN_REVIEW → APPROVED → ACTIVE on effectiveFrom; approver ≠ author by person and seat (422 SEGREGATION_OF_DUTIES).",
       ["IX.5", "R43", "R41", "R42"], params=[ID], body="PowerTableTransitionRequest", resp=(200, "PowerTable"),
       cmd="power-table.transition", perm=perm)

# Identity admin (IdentityAdminPort)
op("post", "/admin/assignments/impact", "previewAssignmentImpact", "Admin", "Impact of an assignment",
   "Open tasks and SoD conflicts an assignment would create, before saving (A03).", ["IX.5", "K5"], body="Assignment",
   resp=(200, "AssignmentImpact"), perm="admin.read", errors=STD_R)
for act, approve in [("approve", True), ("reject", False)]:
    op("post", "/admin/assignments/{id}/" + act, act + "Assignment", "Admin", act.capitalize() + " an assignment",
       "Four-eyes decision on a pending assignment (leave, additional charge).", ["IX.5", "R41", "R42"], params=[ID],
       body="ReasonRequest", resp=(200, "Assignment"), cmd="admin.assignment.decide", perm="admin.write")
op("post", "/admin/policies/explain", "explainPolicy", "Admin", "Explain an authorization decision",
   "Which grants and denies match (deny wins, A-13) at the current policy version (VI.4).", ["VI.4", "A-13"], body="ExplainRequest",
   resp=(200, "ExplainResult"), perm="policy.admin", errors=STD_R)
op("get", "/admin/sod-exceptions", "listSodExceptions", "Admin", "SoD exceptions", "Controlled exceptions (IX.5 rule 5).", ["IX.5", "R19"],
   list_=True, params=[q("status", enum=["PENDING", "APPROVED", "REJECTED", "EXPIRED"])], resp=(200, "array:SodException"),
   perm="admin.read", errors=STD_R)
op("post", "/admin/sod-exceptions", "createSodException", "Admin", "Request an SoD exception", "Needs a higher authority's approval.",
   ["IX.5", "R41"], body="SodException", resp=(201, "SodException"), cmd="admin.sod-exception.create", perm="admin.write", created=True)
for act in ("approve", "reject"):
    op("post", "/admin/sod-exceptions/{id}/" + act, act + "SodException", "Admin", act.capitalize() + " an SoD exception",
       "SOD_EXCEPTION_GRANTED on approval; the requester cannot decide.", ["IX.5", "R41", "R42"], params=[ID], body="ReasonRequest",
       resp=(200, "SodException"), cmd="admin.sod-exception.decide", perm="policy.admin")
op("get", "/admin/users", "listUsers", "Admin", "Users", "Users known to the tenant (R67).", ["R67"], list_=True, params=[q("q")],
   resp=(200, "array:User"), perm="admin.read", errors=STD_R)
op("get", "/admin/groups", "listGroups", "Admin", "Groups", "Groups of the tenant (R67).", ["R67"], list_=True, params=[q("q")],
   resp=(200, "array:Group"), perm="admin.read", errors=STD_R)
op("get", "/admin/groups/{id}/members", "listGroupMembers", "Admin", "Group members", "Members of a group.", ["R67"], params=[ID],
   resp=(200, "array:User"), perm="admin.read", errors=GET_E)
GM = [ID, pp("userId")]
op("post", "/admin/groups/{id}/members/{userId}", "addGroupMember", "Admin", "Add a group member", "MEMBERSHIP_CHANGED; policy_version+1 (VI.4 rule 5).",
   ["R67", "VI.4", "R41"], params=GM, resp=(204, None), cmd="admin.group.member.add", perm="policy.admin")
op("delete", "/admin/groups/{id}/members/{userId}", "removeGroupMember", "Admin", "Remove a group member", "MEMBERSHIP_CHANGED; policy_version+1.",
   ["R67", "VI.4", "R41"], params=GM, resp=(204, None), cmd="admin.group.member.remove", perm="policy.admin")
op("get", "/admin/identity-providers", "listIdentityProviders", "Admin", "Identity providers", "OIDC, SAML 2.0 and LDAP providers (R50, R67).",
   ["R50", "R67"], resp=(200, "array:IdentityProvider"), perm="tenant.admin", errors=WS_E)
op("post", "/admin/identity-providers", "createIdentityProvider", "Admin", "Add an identity provider", "IDP_CONFIGURED.", ["R50", "R67", "R41"],
   body="IdentityProvider", resp=(201, "IdentityProvider"), cmd="admin.idp.create", perm="tenant.admin", created=True)
op("put", "/admin/identity-providers/{id}", "updateIdentityProvider", "Admin", "Update an identity provider", "IDP_CONFIGURED.",
   ["R50", "R67", "R41"], params=[ID], body="IdentityProvider", resp=(200, "IdentityProvider"), cmd="admin.idp.update", perm="tenant.admin")
op("post", "/admin/identity-providers/{id}/test", "testIdentityProvider", "Admin", "Test an identity provider", "Connection and metadata test.",
   ["R50"], params=[ID], resp=(200, "IdentityTestResult"), perm="tenant.admin", errors=GET_E)
op("post", "/admin/identity-providers/{id}/sync", "syncIdentityProvider", "Admin", "Sync users and groups", "Directory sync (R67).",
   ["R67", "R41"], params=[ID], resp=(200, "IdentitySyncResult"), cmd="admin.idp.sync", perm="tenant.admin")
op("get", "/admin/service-principals", "listServicePrincipals", "Admin", "Service principals", "Worker and integration principals (R26).",
   ["R26", "K14"], resp=(200, "array:ServicePrincipal"), perm="admin.read", errors=WS_E)
op("post", "/admin/service-principals", "createServicePrincipal", "Admin", "Create a service principal",
   "Returns the secret once; only its hash is stored (R26).", ["R26", "R41"], body="ServicePrincipal", resp=(201, "ServicePrincipalSecret"),
   cmd="admin.service-principal.create", perm="tenant.admin", created=True)
op("post", "/admin/service-principals/{id}/rotate", "rotateServicePrincipal", "Admin", "Rotate a secret", "Returns the new secret once.",
   ["R26", "R41"], params=[ID], resp=(200, "ServicePrincipalSecret"), cmd="admin.service-principal.rotate", perm="tenant.admin")
op("post", "/admin/service-principals/{id}/revoke", "revokeServicePrincipal", "Admin", "Revoke a service principal", "Revocation with a reason.",
   ["R26", "R41", "R42"], params=[ID], body="ReasonRequest", resp=(200, "ServicePrincipal"), cmd="admin.service-principal.revoke",
   perm="tenant.admin")
op("post", "/admin/org-units/import", "importOrgUnits", "Admin", "Import org units",
   "Bulk CSV import of the hierarchy (id,parentId,code,name,nameHi,level), with per-row errors (PER-5).", ["IX.5", "R41"],
   body={"required": True, "content": {"multipart/form-data": {"schema": {"type": "object", "required": ["file"], "properties": {
       "file": FPR("text/csv")}}}}}, resp=(200, "OrgUnitImportResult"), cmd="admin.orgunit.import", perm="admin.write")

# Registries (RegistryPort), concrete paths per kind
REG = [("services", "Service", "Services", "Service registry: typed operations, auth reference, capability class a/b/c (R71, EC-07)", ["R71", "EC-07", "R36"]),
       ("sla-models", "SlaModel", "SlaModels", "SLA models: targets, warning and breach thresholds, calendar, pause rules, escalations (R79, EC-08)", ["R79", "EC-08"]),
       ("routing-strategies", "RoutingStrategy", "RoutingStrategies", "Assignment strategies: round-robin, least-loaded, skills, SLA priority (R82)", ["R82"]),
       ("templates", "Template", "Templates", "Document and notification templates (R74, R60)", ["R74", "R60"]),
       ("conversation-channels", "ConversationChannel", "ConversationChannels", "Chat and messaging channels (R81)", ["R81"]),
       ("agents", "Agent", "Agents", "AI agents with model provider, tools, guardrails and budgets (R72)", ["R72", "R14"]),
       ("retention-classes", "RetentionClass", "RetentionClasses", "Retention and records categories (R29, R61)", ["R29", "R61"])]
for kind, one, many, d, reqs in REG:
    base = "/admin/" + kind
    ck = "registry." + kind
    op("get", base, "list" + many, "Registries", "List " + kind, d + ".", reqs, list_=True, params=[q("q")],
       resp=(200, "array:RegistryEntry"), perm="admin.read", errors=STD_R)
    op("post", base, "create" + one, "Registries", "Create an entry", d + ".", reqs + ["R41"],
       body="RegistryEntry", resp=(201, "RegistryEntry"), cmd=ck + ".create", perm="admin.write", created=True)
    op("get", base + "/{id}", "get" + one, "Registries", "Get one entry", d + ".", reqs, params=[ID], resp=(200, "RegistryEntry"),
       perm="admin.read", errors=GET_E)
    op("put", base + "/{id}", "update" + one, "Registries", "Update an entry", "Send the current revision (409 REVISION_CONFLICT when stale).",
       reqs + ["R41"], params=[ID], body="RegistryEntry", resp=(200, "RegistryEntry"), cmd=ck + ".update", perm="admin.write")
    op("delete", base + "/{id}", "delete" + one, "Registries", "Delete an entry", "Deletes with a reason (audited).", reqs + ["R41", "R42"],
       params=[ID, P("reason")], resp=(204, None), cmd=ck + ".delete", perm="admin.write")
    op("post", base + "/{id}/{action}", "act" + one, "Registries", "Entry action", "activate | retire | test.", reqs + ["R41"],
       params=[ID, {"name": "action", "in": "path", "required": True, "schema": {"type": "string", "enum": ["activate", "retire", "test"]}}],
       body={"required": False, "content": {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}},
       resp=(200, "RegistryEntry"), cmd=ck + ".action", perm="admin.write")

# Low-code (LowCodePort)
op("get", "/pages", "listPages", "LowCode", "Pages", "Low-code pages (R77, EC-03).", ["R77"], resp=(200, "array:PageSummary"),
   perm="lowcode.read", errors=WS_E)
op("post", "/pages", "createPage", "LowCode", "Create a page", "New page version.", ["R77", "R41"], body="PageModel", resp=(201, "PageModel"),
   cmd="page.create", perm="lowcode.admin", created=True)
op("get", "/pages/{key}", "getPage", "LowCode", "Get a page", "Page model; authorized on the server.", ["R77"], params=[KEY],
   resp=(200, "PageModel"), perm="lowcode.read", errors=GET_E)
op("put", "/pages/{key}", "updatePage", "LowCode", "Update a page", "New version of the page.", ["R77", "R41"], params=[KEY],
   body="PageModel", resp=(200, "PageModel"), cmd="page.update", perm="lowcode.admin")
op("get", "/queries/{name}", "runNamedQuery", "LowCode", "Run a named query",
   "Named query (R25); further query parameters are passed to the query. Uses the same authorization as the underlying data.",
   ["R25", "R77", "VI.4"], list_=True, params=[pp("name")], resp=(200, "array:VariableMap"), perm="lowcode.read", errors=GET_E)
op("get", "/data-objects", "listDataObjects", "LowCode", "Data objects", "Data-object definitions (R78, EC-06).", ["R78"],
   resp=(200, "array:DataObjectDef"), perm="lowcode.read", errors=WS_E)
op("post", "/data-objects", "createDataObject", "LowCode", "Create a data object", "New definition.", ["R78", "R41"], body="DataObjectDef",
   resp=(201, "DataObjectDef"), cmd="data-object.create", perm="lowcode.admin", created=True)
op("put", "/data-objects/{id}", "updateDataObject", "LowCode", "Update a data object", "New schema version.", ["R78", "R41"], params=[ID],
   body="DataObjectDef", resp=(200, "DataObjectDef"), cmd="data-object.update", perm="lowcode.admin")
op("get", "/data-objects/{key}/records", "listDataObjectRecords", "LowCode", "Data-object records", "Records; sensitive fields masked (R78).",
   ["R78", "VI.4"], list_=True, params=[KEY, q("q")], resp=(200, "array:VariableMap"), perm="lowcode.read", errors=GET_E)
op("post", "/data-objects/{key}/records", "createDataObjectRecord", "LowCode", "Create a record", "Validated against the definition.",
   ["R78", "R41"], params=[KEY], body={"required": True, "content": {"application/json": {"schema": R("VariableMap")}}},
   resp=(201, "object"), cmd="data-object.record.create", perm="lowcode.read", created=True)

# Package tools (PackageToolsPort)
op("post", "/packages/{id}/validate", "validatePackage", "Packages", "Validate a package",
   "R44 profile and X.2.4.6 closure check (FC-46).", ["R44", "X.2.4.6", "FC-46"], params=[ID], resp=(200, "array:ValidationIssue"),
   perm="package.read", errors=GET_E)
op("post", "/packages/{id}/tests/run", "runPackageTests", "Packages", "Run model tests", "Model unit tests (R80).", ["R80"], params=[ID],
   resp=(200, "array:TestRun"), perm="package.author", errors=GET_E)
op("get", "/packages/{id}/git", "getPackageGitStatus", "Packages", "Git status", "Git sync status (R69).", ["R69"], params=[ID],
   resp=(200, "GitStatus"), perm="package.read", errors=GET_E)
op("put", "/packages/{id}/git", "configurePackageGit", "Packages", "Configure Git", "Remote, branch and credential reference (R69).",
   ["R69", "R41"], params=[ID], body="GitConfig", resp=(200, "GitStatus"), cmd="package.git.configure", perm="package.author")
for d in ("pull", "push"):
    op("post", "/packages/{id}/git/" + d, d + "PackageGit", "Packages", "Git " + d, "Sync with the remote (R69); deployment still needs R43 approval.",
       ["R69", "R43", "R41"], params=[ID], body="GitSyncRequest", body_required=False, resp=(200, "GitSyncResult"), cmd="package.git.sync",
       perm="package.author", errors=("BadRequest", "Unauthorized", "Forbidden", "NotFound", "NotImplemented"))
op("get", "/packages/{id}/comments", "listPackageComments", "Packages", "Review comments", "Comments on package elements (R69).", ["R69"],
   params=[ID], resp=(200, "array:PackageComment"), perm="package.read", errors=GET_E)
op("post", "/packages/{id}/comments", "addPackageComment", "Packages", "Add a review comment", "Comment on an element.", ["R69", "R41"],
   params=[ID], body="PackageAddCommentRequest", resp=(201, "PackageComment"), cmd="package.comment", perm="package.read", created=True)
op("post", "/packages/assessment", "assessModels", "Packages", "Assess foreign models",
   "Classifies BPMN/CMMN/DMN constructs of models from other engines: SUPPORTED, MAPPED, PATTERN, REJECTED (EC-21, R48).",
   ["EC-21", "R48"], body={"required": True, "content": {"multipart/form-data": {"schema": {"type": "object", "required": ["files"],
       "properties": {"files": {"type": "array", "items": FPR()}}}}}},
   resp=(200, "array:AssessmentRow"), perm="package.author", errors=STD_R)

# ---------------------------------------------------------------- Compliance (sangam-compliance; DPDP Act 2023 / Rules 2025,
# CERT-In directions; GOV-4, R53). Colon actions of ComplianceApi are sub-paths (/notices/{key}:publish -> /publish).
CR = ["GOV-4", "R53"]
schema("ComplianceRenderedNotice", "A consent notice or privacy policy rendered in one language (DPDP s.5, Rules r.3).",
       "noticeKey version:int language:obj purposeCodes:list:s text html contentHash fallback:bool")
schema("CompliancePublishNoticeRequest", "Purposes the notice covers.", "purposes:list:s")
schema("CompliancePublishResult", "Published languages and the missing translations.",
       "noticeKey version:int published:list:s missing:smap")
schema("CompliancePurpose", "A processing purpose with its legal basis, data items and retention (DPDP s.4, s.7, s.8(7)). Durations are ISO-8601.",
       "code names:smap descriptions:smap dataItems:list:obj basis legalRef subGround registerRef standardsAttested:bool "
       "exemptions:list:s exemptionRef childExemptions:list:s flags:list:s retentionTrigger retention retentionClass "
       "recordClass recordRetention recordRetentionRef version:int active:bool", ["basis"])
schema("ComplianceGiveConsentRequest", "Consent by the data principal (or a verified guardian, DPDP s.9). `language` is a code of the tenant's jurisdiction pack; principalRef defaults to the caller.",
       "principalRef noticeKey noticeVersion:int language purposeCodes:list:s affirmativeAction:bool channel "
       "guardianVerificationId consentManagerId cmArtefactId", ["noticeKey", "purposeCodes", "affirmativeAction"])
schema("ComplianceConsent", "A consent record with its active purposes and evidence hash (DPDP s.6).",
       "consentId principalRef noticeKey noticeVersion:int language status givenAt:dt withdrawnAt:dt consentManagerId "
       "cmArtefactId legacy:bool activePurposes:list:s evidenceHash", enums={"status": ["ACTIVE", "PARTIALLY_WITHDRAWN", "WITHDRAWN"]})
schema("ComplianceWithdrawConsentRequest", "Purposes to withdraw; empty = all (DPDP s.6(4)).", "purposes:list:s")
schema("ComplianceConsentArtefact", "A consent artefact from a registered consent manager (DPDP s.6(7), Rules r.4).",
       "consentManagerId artefactId principalRef fiduciaryId noticeKey noticeVersion:int languageCode purposeCodes:list:s "
       "issuedAt:dt expiresAt:dt signature")
schema("ComplianceRightsSubmission", "A data-principal rights request (DPDP s.11-14), authenticated by the published identifier.",
       "principalRef right identifierType identifierValue proof:smap nomineeRef channel details:obj", ["right"])
schema("ComplianceRightsRequest", "A rights request with its statutory due date.",
       "requestId principalRef right category status receivedAt:dt dueAt:dt closedAt:dt processInstanceId escalated:bool "
       "late:bool outcome responseText")
schema("ComplianceBreachRequest", "A personal-data breach (DPDP s.8(6), Rules r.7).",
       "facts:smap awareAt:dt subjects:list:s roots:list:s incidentType")
schema("ComplianceBreach", "A breach with its authority deadline (72 h detailed report).",
       "breachId status detectedAt:dt awareAt:dt reportDueAt:dt extensionUntil:dt authorityNotifiedAt:dt reportedAt:dt "
       "reportLate:bool subjectsAffected:long subjectsNotified:long incidentIds:list:s")
schema("ComplianceIncidentRequest", "A cyber-security incident for the authority (CERT-In 6-hour reporting).",
       "authority type occurredAt:dt noticedAt:dt systems:list:s description", ["authority", "type", "occurredAt"])
schema("ComplianceIncident", "A reportable incident.",
       "incidentId authority type occurredAt:dt noticedAt:dt dueAt:dt status reportedAt:dt late:bool breachId followUps:int")
schema("ComplianceSubmitIncidentRequest", "Actions taken, sent with the report.", "actionsTaken")
schema("ComplianceLawfulRequestBody", "A request from a lawful authority (maker-checker: verify, approve, respond).",
       "authority officerName officerDesignation basis reference scope dueAt:dt", ["authority", "basis", "reference"])
schema("ComplianceLawfulRequest", "A lawful-authority request and its maker-checker state.",
       "requestId authority officerName officerDesignation basis reference scope status dueAt:dt late:bool")
schema("ComplianceVerifyLawfulRequest", "Whether the requesting officer's rank is adequate.", "rankAdequate:bool")
schema("ComplianceRespondLawfulRequest", "The response and the disclosed principals (disclosure register).",
       "responseRef principalRefs:list:s dataCategories:list:s", ["responseRef"])
schema("ComplianceReasonBody", "A reason.", "reason", ["reason"])
schema("ComplianceContact", "A published contact (DPO / grievance officer, DPDP s.8(9), s.10(2)).",
       "role name designation organisation email phone mobile address resident:bool validFrom:dt", ["name", "email"])
schema("ComplianceAuditEvidenceRequest", "Independent data audit evidence (Significant Data Fiduciary, DPDP s.10).",
       "kind periodFrom:dt periodTo:dt completedAt:dt auditor accreditationRef independent:bool findings:list:s reportHash authorityReportRef",
       ["kind", "auditor"])
schema("ComplianceAuditEvidence", "Recorded audit evidence.",
       "auditId kind periodFrom:dt periodTo:dt auditor accreditationRef independent:bool completedAt:dt findings:list:s "
       "reportHash nextDueAt:dt authorityReportRef")
schema("ComplianceImpactAssessmentRequest", "Data protection impact assessment (DPDP s.10(2)(c)).",
       "activityId scope risks:list:s measures:list:s residualRisk assessor independent:bool assessedAt:dt authorityReportRef",
       ["scope"])
schema("ComplianceImpactAssessment", "Recorded impact assessment.",
       "assessmentId activityId scope risks:list:s measures:list:s residualRisk assessor independent:bool assessedAt:dt "
       "nextDueAt:dt authorityReportRef evidenceHash")
schema("ComplianceTransferRequest", "A cross-border transfer to evaluate (DPDP s.16, localisation rules).",
       "destinationCountry recipient purposeCode dataCategories:list:s approvalRef recipientSameProtectionAttested:bool",
       ["destinationCountry"])
schema("ComplianceTransferDecision", "The recorded transfer decision.", "decisionId allowed:bool rule reason")
schema("ComplianceSealLogsRequest", "Seal the log segment [from, to) (CERT-In 180-day logs, hash-chained).", "from:dt to:dt", ["from"])
schema("ComplianceLogSegment", "A sealed, hash-chained log segment in the home-country archive.",
       "segmentId from:dt to:dt events:long sha256 prevSha256 uri")
schema("ComplianceAuditVerification", "Audit-chain and log-segment integrity of the tenant.",
       "ok:bool chains:obj latestSegment:obj latestSegmentVerified:bool verifiedAt:dt")
schema("ComplianceReport", "Compliance evidence report (JSON; `?format=html` returns the HTML rendering). X-Sangam-Report-Hash carries its hash.",
       "reportId hash content:obj json html")
schema("ComplianceJurisdiction", "The tenant's active jurisdiction pack: languages, rights, authorities, contact roles, limits.",
       "code name homeCountry timeZone defaultLanguage languages:list:obj rights:list:obj authorities:list:obj "
       "contactRoles:list:obj limits:obj instruments:list:obj organisation rightsPortalUrl privacyPolicyUrl")

TENANT_PUBLIC = [{"name": "X-Sangam-Tenant", "in": "header", "required": False, "schema": {"type": "string"},
                  "description": "Tenant of an unauthenticated call (else ?tenant=)"},
                 q("tenant", desc="Tenant of an unauthenticated call (else X-Sangam-Tenant)")]


def public(path, method):
    PATHS[path][method]["security"] = []


C = "/compliance"
op("get", C + "/notices/{key}", "getComplianceNotice", "Compliance", "Render a consent notice (public)",
   "The notice in the requested language; falls back to the pack default only where the pack allows (DPDP Rules r.3).",
   CR + ["DPDP s.5"], params=[KEY, q("version", "int"), q("lang")] + TENANT_PUBLIC, resp=(200, "ComplianceRenderedNotice"),
   errors=("BadRequest", "NotFound"))
public(C + "/notices/{key}", "get")
op("post", C + "/notices/{key}/publish", "publishComplianceNotice", "Compliance", "Publish a notice version",
   "Publishes a new version for every enabled language; reports missing translations.", CR + ["DPDP s.5", "R41"],
   params=[KEY], body="CompliancePublishNoticeRequest", resp=(200, "CompliancePublishResult"),
   cmd="compliance.notice.publish", perm="compliance.admin")
op("get", C + "/privacy-policy", "getPrivacyPolicy", "Compliance", "Render the privacy policy (public)",
   "The published privacy policy in the requested language.", CR, params=[q("lang")] + TENANT_PUBLIC,
   resp=(200, "ComplianceRenderedNotice"), errors=("BadRequest", "NotFound"))
public(C + "/privacy-policy", "get")
op("get", C + "/purposes", "listCompliancePurposes", "Compliance", "List purposes", "Registered processing purposes.",
   CR + ["DPDP s.4"], resp=(200, "array:CompliancePurpose"), perm="compliance.read", errors=STD_R)
op("put", C + "/purposes/{code}", "defineCompliancePurpose", "Compliance", "Define a purpose",
   "Defines or versions a purpose; validated against the jurisdiction pack (legal basis, legitimate uses, retention).",
   CR + ["DPDP s.4", "DPDP s.7", "R41"], params=[pp("code")], body="CompliancePurpose", resp=(200, "CompliancePurpose"),
   cmd="compliance.purpose.define", perm="compliance.admin")
op("post", C + "/consents", "giveConsent", "Compliance", "Give consent",
   "The data principal (or verified guardian) consents to purposes of a notice version. Recording consent for another principal needs compliance.modify.",
   CR + ["DPDP s.6", "DPDP s.9", "R41"], body="ComplianceGiveConsentRequest", resp=(201, "ComplianceConsent"),
   cmd="compliance.consent.give", perm="authenticated (own) / compliance.modify", created=True)
op("post", C + "/consents/{id}/withdraw", "withdrawConsent", "Compliance", "Withdraw consent",
   "Withdraws some or all purposes, as easily as consent was given (DPDP s.6(4)); processing stops within the pack limit.",
   CR + ["DPDP s.6(4)", "R41"], params=[ID], body="ComplianceWithdrawConsentRequest", resp=(200, "ComplianceConsent"),
   cmd="compliance.consent.withdraw", perm="owner / compliance.modify", body_required=False)
op("get", C + "/consents", "listConsents", "Compliance", "Consent history",
   "Consents of a principal (default: the caller). Another principal's history needs compliance.read.",
   CR + ["DPDP s.6"], params=[q("subject")], resp=(200, "array:ComplianceConsent"), perm="owner / compliance.read",
   errors=STD_R)
op("post", C + "/consent-managers/{cm}/artefacts", "ingestConsentArtefact", "Compliance", "Ingest a consent-manager artefact",
   "Called by a registered consent manager (mTLS at the gateway; public at the API).", CR + ["DPDP s.6(7)", "R41"],
   params=[pp("cm")] + TENANT_PUBLIC, body="ComplianceConsentArtefact", resp=(201, "ComplianceConsent"),
   cmd="compliance.consent-artefact.ingest", created=True)
public(C + "/consent-managers/{cm}/artefacts", "post")
op("post", C + "/rights-requests", "submitRightsRequest", "Compliance", "Submit a rights request (public)",
   "Access, correction, erasure, grievance or nomination (DPDP s.11-14); authenticated by the published identifier; due dates from the pack.",
   CR + ["DPDP s.11", "DPDP s.12", "DPDP s.13", "DPDP s.14", "R41"], params=TENANT_PUBLIC, body="ComplianceRightsSubmission",
   resp=(201, "ComplianceRightsRequest"), cmd="compliance.rights-request.submit", created=True)
public(C + "/rights-requests", "post")
op("get", C + "/rights-requests", "listRightsRequests", "Compliance", "Rights requests of a principal",
   "Default: the caller's own; another principal's needs compliance.read.", CR, params=[q("subject")],
   resp=(200, "array:ComplianceRightsRequest"), perm="owner / compliance.read", errors=STD_R)
op("get", C + "/rights-requests/{id}", "getRightsRequest", "Compliance", "Get a rights request",
   "Visible to its principal or with compliance.read (otherwise 404).", CR, params=[ID], resp=(200, "ComplianceRightsRequest"),
   perm="owner / compliance.read", errors=GET_E)
op("post", C + "/breaches", "registerBreach", "Compliance", "Register a personal-data breach",
   "Starts the authority and data-principal notification clocks (DPDP s.8(6), Rules r.7).", CR + ["DPDP s.8(6)", "R41"],
   body="ComplianceBreachRequest", resp=(201, "ComplianceBreach"), cmd="compliance.breach.register",
   perm="compliance.modify", created=True)
op("get", C + "/breaches/{id}", "getBreach", "Compliance", "Get a breach", "Breach with deadlines and notification counts.",
   CR, params=[ID], resp=(200, "ComplianceBreach"), perm="compliance.read", errors=GET_E)
op("post", C + "/incidents", "registerComplianceIncident", "Compliance", "Register a reportable incident",
   "A cyber-security incident for the configured authority (CERT-In: report within 6 hours of noticing).",
   CR + ["CERT-In 2022", "R51", "R41"], body="ComplianceIncidentRequest", resp=(201, "ComplianceIncident"),
   cmd="compliance.incident.register", perm="compliance.modify", created=True)
op("get", C + "/incidents/{id}", "getComplianceIncident", "Compliance", "Get an incident", "Incident with its due time.",
   CR, params=[ID], resp=(200, "ComplianceIncident"), perm="compliance.read", errors=GET_E)
op("post", C + "/incidents/{id}/submit", "submitComplianceIncident", "Compliance", "Submit the incident report",
   "Sends the report to the authority recipients; returns the delivery references.", CR + ["CERT-In 2022", "R41"],
   params=[ID], body="ComplianceSubmitIncidentRequest",
   resp=(200, {"application/json": {"schema": {"type": "array", "items": {"type": "string"}}}}), cmd="compliance.incident.submit",
   perm="compliance.modify")
op("post", C + "/lawful-requests", "receiveLawfulRequest", "Compliance", "Receive a lawful-authority request",
   "Records a request from a lawful authority with its statutory due time (maker).", CR + ["DPDP s.36", "R41"],
   body="ComplianceLawfulRequestBody", resp=(201, "ComplianceLawfulRequest"), cmd="compliance.lawful-request.receive",
   perm="compliance.modify", created=True)
op("get", C + "/lawful-requests/{id}", "getLawfulRequest", "Compliance", "Get a lawful request", "The request and its state.",
   CR, params=[ID], resp=(200, "ComplianceLawfulRequest"), perm="compliance.read", errors=GET_E)
for verb, body, cmdv, desc in [
    ("verify", "ComplianceVerifyLawfulRequest", "verify", "Checker verifies the authority and the officer's rank."),
    ("approve", None, "approve", "Approver (different from the verifier) approves disclosure."),
    ("respond", "ComplianceRespondLawfulRequest", "respond", "Records the response; disclosures enter the register."),
    ("reject", "ComplianceReasonBody", "reject", "Rejects with a reason."),
]:
    op("post", C + "/lawful-requests/{id}/" + verb, verb + "LawfulRequest", "Compliance", verb.capitalize() + " a lawful request",
       desc, CR + ["R41"], params=[ID], body=body, resp=(200, "ComplianceLawfulRequest"),
       cmd="compliance.lawful-request." + cmdv, perm="compliance.modify")
op("get", C + "/contacts", "listComplianceContacts", "Compliance", "List contacts", "DPO, grievance officer and other published contacts.",
   CR + ["DPDP s.8(9)"], resp=(200, "array:ComplianceContact"), perm="compliance.read", errors=STD_R)
op("post", C + "/contacts/{role}", "setComplianceContact", "Compliance", "Set a contact",
   "Sets the effective contact for a role of the jurisdiction pack (residency checked where required).",
   CR + ["DPDP s.8(9)", "DPDP s.10(2)", "R41"], params=[pp("role")], body="ComplianceContact", resp=(204, None),
   cmd="compliance.contact.set", perm="compliance.admin")
op("post", C + "/evidence/audits", "recordComplianceAudit", "Compliance", "Record audit evidence",
   "Independent data audit (Significant Data Fiduciary obligations).", CR + ["DPDP s.10", "R41"],
   body="ComplianceAuditEvidenceRequest", resp=(201, "ComplianceAuditEvidence"), cmd="compliance.evidence.audit",
   perm="compliance.admin", created=True)
op("post", C + "/evidence/impact-assessments", "recordImpactAssessment", "Compliance", "Record an impact assessment",
   "Periodic data protection impact assessment.", CR + ["DPDP s.10(2)(c)", "R41"], body="ComplianceImpactAssessmentRequest",
   resp=(201, "ComplianceImpactAssessment"), cmd="compliance.evidence.impact-assessment", perm="compliance.admin",
   created=True)
op("post", C + "/transfers/evaluate", "evaluateTransfer", "Compliance", "Evaluate a cross-border transfer",
   "Applies the negative list, localisation and order restrictions; the decision is recorded.", CR + ["DPDP s.16", "R41"],
   body="ComplianceTransferRequest", resp=(200, "ComplianceTransferDecision"), cmd="compliance.transfer.evaluate",
   perm="compliance.modify")
op("post", C + "/logs/segments/seal", "sealComplianceLogs", "Compliance", "Seal a log segment",
   "Seals [from, to) into a hash-chained segment in the home-country archive (CERT-In log retention).",
   CR + ["CERT-In 2022", "R51", "R41"], body="ComplianceSealLogsRequest", resp=(201, "ComplianceLogSegment"),
   cmd="compliance.logs.seal", perm="compliance.admin", created=True)
op("get", C + "/logs/verify", "verifyComplianceLogs", "Compliance", "Verify audit and log integrity",
   "Verifies the tenant's audit chains and the latest sealed segment.", CR + ["R42", "X.2.3.4"],
   resp=(200, "ComplianceAuditVerification"), perm="audit.read", errors=STD_R)
op("get", C + "/reports", "getComplianceReport", "Compliance", "Compliance evidence report",
   "Evidence report for [from, to); `format=html` returns text/html.", CR + ["R42"],
   params=[q("from", "dt", required=True), q("to", "dt", required=True), q("verifyChains", "bool"),
           q("format", enum=["json", "html"])],
   resp=(200, {"application/json": {"schema": R("ComplianceReport")}, "text/html": {"schema": {"type": "string"}}}),
   perm="audit.read", errors=STD_R)
op("get", C + "/jurisdiction", "getComplianceJurisdiction", "Compliance", "Active jurisdiction pack",
   "Code, languages, rights, authorities, contact roles and limits of the tenant's jurisdiction (for the UI).", CR,
   resp=(200, "ComplianceJurisdiction"), perm="authenticated", errors=("Unauthorized",))

# ---------------------------------------------------------------- SCALE cell tenant-admin (sangam-cell IR-4)
schema("CellMoveRequest", "A tenant-move step; `moveId` identifies the move (also in the Idempotency-Key).", "moveId")
schema("CellImportRequest", "Pull handle of the source cell's export.", "moveId url token watermark", ["url", "token"])
schema("CellActivateRequest", "Opens the imported tenant; `sourceAuditHead` must equal the imported chain heads.",
       "moveId sourceAuditHead")
schema("CellMovedRequest", "Marks the tenant MOVED at the source; later requests get 421 with `targetCell`.",
       "moveId targetCell", ["targetCell"])
schema("CellTenantState", "Move state of a tenant in this cell: ACTIVE, IMPORTING, FROZEN, MOVED, PURGED.",
       "tenant state moveId targetCell since:dt deleted:obj", enums={"state": ["ACTIVE", "IMPORTING", "FROZEN", "MOVED",
                                                                       "PURGED"]})
schema("CellDrainStatus", "Drain progress of a frozen tenant; drained when the first three are 0.",
       "inFlightCommands:long pendingOutbox:long lockedWork:long dueTimers:long")
schema("CellExportHandle", "One-time pull handle of a consistent tenant export (engine + product rows, audit chain).",
       "url token rows:long watermark auditHead")
schema("CellImportResult", "Rows loaded per table.", "rowCounts:obj")
schema("CellTenantChecksum", "Per-table row counts and an order-independent content hash; equal on both cells = verified.",
       "rowCounts:obj contentHash")

CELL = "/admin/cell/tenants/{tenant}"
TENANT_PATH = pp("tenant", "Tenant code")
CELL_REQ = ["SCALE IR-4", "R41"]
for verb, opid, summary, desc, body, resp, cmd in [
    ("import/prepare", "prepareCellImport", "Prepare a tenant import (target)",
     "Creates the tenant in IMPORTING state: it rejects traffic (503) and accepts the import. The tenant must have no rows.",
     "CellMoveRequest", "CellTenantState", "cell.tenant.import.prepare"),
    ("freeze", "freezeCellTenant", "Freeze a tenant (source)",
     "Stops new starts, commands and inbound events of the tenant (503 Retry-After); locked work may still complete.",
     "CellMoveRequest", "CellTenantState", "cell.tenant.freeze"),
    ("export", "exportCellTenant", "Export a frozen, drained tenant (source)",
     "Consistent export of every row of the tenant; returns a one-time pull handle. 409 NOT_DRAINED while work is in flight.",
     "CellMoveRequest", "CellExportHandle", "cell.tenant.export"),
    ("import", "importCellTenant", "Import a tenant from the source cell (target)",
     "Pulls the export with its token and loads it in one transaction; 409 TENANT_NOT_EMPTY if rows exist.",
     "CellImportRequest", "CellImportResult", "cell.tenant.import"),
    ("activate", "activateCellTenant", "Activate an imported tenant (target)",
     "Opens the tenant for traffic after checking the audit chain heads (409 AUDIT_HEAD_MISMATCH); audits TENANT_MOVED_IN.",
     "CellActivateRequest", "CellTenantState", "cell.tenant.activate"),
    ("moved", "markCellTenantMoved", "Mark a tenant moved (source)",
     "From now on every request of the tenant gets 421 Misdirected Request with `targetCell`; data kept until purge.",
     "CellMovedRequest", "CellTenantState", "cell.tenant.moved"),
    ("unfreeze", "unfreezeCellTenant", "Unfreeze a tenant (source rollback)",
     "Returns a FROZEN tenant to ACTIVE.", "CellMoveRequest", "CellTenantState", "cell.tenant.unfreeze"),
    ("import/drop", "dropCellImport", "Drop a partial import (target rollback)",
     "Deletes every row of a tenant still IMPORTING.", "CellMoveRequest", "CellTenantState", "cell.tenant.import.drop"),
    ("purge", "purgeCellTenant", "Purge a moved tenant (source)",
     "Deletes the tenant's rows after the move; refused with 409 LEGAL_HOLD_ACTIVE while a legal hold exists (R61).",
     "CellMoveRequest", "CellTenantState", "cell.tenant.purge"),
]:
    op("post", CELL + "/" + verb, opid, "Cell", summary, desc, CELL_REQ + (["R61"] if verb == "purge" else []),
       params=[TENANT_PATH], body=body, body_required=verb in ("import", "moved"), resp=(200, resp), cmd=cmd,
       perm="role CELL_ADMIN", errors=("BadRequest", "Unauthorized", "Forbidden", "Conflict", "ServiceUnavailable"))
op("get", CELL + "/drain-status", "getCellDrainStatus", "Cell", "Drain status of a frozen tenant",
   "In-flight commands, pending outbox deliveries, locked external work and due timers.", ["SCALE IR-4"],
   params=[TENANT_PATH], resp=(200, "CellDrainStatus"), perm="role CELL_ADMIN", errors=("Unauthorized", "Forbidden"))
op("get", CELL + "/checksum", "getCellTenantChecksum", "Cell", "Checksum of a tenant's rows",
   "Row counts and an order-independent content hash, comparable across cells and database vendors.", ["SCALE IR-4"],
   params=[TENANT_PATH], resp=(200, "CellTenantChecksum"), perm="role CELL_ADMIN", errors=("Unauthorized", "Forbidden"))
op("get", "/admin/cell/exports/{exportId}", "downloadCellExport", "Cell", "Pull a tenant export (target cell)",
   "Gzipped NDJSON stream of the export. Authenticated by the one-time export token, not by a principal.",
   ["SCALE IR-4"], params=[pp("exportId"), {"name": "X-Sangam-Export-Token", "in": "header", "required": True,
                                            "schema": {"type": "string"}}],
   resp=(200, {"application/octet-stream": {"schema": {"type": "string", "contentMediaType": "application/gzip"}}}),
   perm="export token", errors=("Forbidden", "NotFound", "Gone"))
public("/admin/cell/exports/{exportId}", "get")

TAGS = [
    ("Work", "Worker API with lease fencing (VI.3, FC-36)"), ("Tasks", "Human tasks (VI.4 rule 5, R43)"),
    ("Processes", "Process instances, definitions, messages, signals (VI.5, R49, FC-01, FC-12, FC-15, FC-17)"),
    ("Cases", "CMMN cases (R45, FC-55…FC-59)"), ("Decisions", "DMN evaluation"), ("Deployments", "Raw engine deployments"),
    ("Packages", "Executable packages (R43, FC-41)"), ("Documents", "Content and attachments (FC-40, R74)"),
    ("Links", "Entity links (FC-31, FC-32)"), ("History", "Class-A history queries (R63)"), ("Audit", "Audit timeline and verification (R42)"),
    ("Admin", "Organisation, seats, assignments, delegations, policies, tenants (IX.5, VI.4)"),
    ("Ops", "Incidents, reconciliation, outbox, jobs, batches, migration (R63, R64, VI.2, VI.3)"),
    ("Events", "Event registry and CloudEvents (FC-02…FC-04, R38)"), ("Forms", "Forms contract and drafts (R47, FC-39)"),
    ("Analytics", "KPIs and process mining exports (R15, R65, R66)"), ("Me", "The caller: profile, seats, notifications, delegations, leave"),
    ("Workspace", "Inbox counts, bulk actions, candidates, diff, lifecycle, start catalogue (R76, S03–S09)"),
    ("Portal", "Citizen / applicant portal (C01–C06, UP-22)"), ("Debug", "Non-production model debugger (R80, FC-44)"),
    ("Registries", "Service registry, SLA models, routing, templates, channels, agents, retention (R71, R72, R74, R79, R81, R82, R29)"),
    ("LowCode", "Pages, named queries and data objects (R77, R78, R25)"), ("Field", "Field inspection app (S15, EC-16)"),
    ("Compliance", "Data protection and incident reporting: notices, consent, rights, breaches, lawful requests, evidence (GOV-4, R53)"),
    ("Cell", "SCALE cell tenant-admin API for tenant moves between cells (sangam-cell IR-4; service role CELL_ADMIN)"),
]

# ---------------------------------------------------------------- per-module route files (api/routes/*.py)
# Each file runs in this module's namespace, so it can call schema(), op(), crud(), q(), pp(), public() and append to
# TAGS. One file per Sangam module keeps parallel contract work conflict-free; files load in name order.
import glob as _glob
import os as _os
for _route_file in sorted(_glob.glob(_os.path.join(_os.path.dirname(_os.path.abspath(__file__)), "routes", "*.py"))):
    with open(_route_file, encoding="utf-8") as _f:
        exec(compile(_f.read(), _route_file, "exec"), globals())

# ---------------------------------------------------------------- API lifecycle (SL-38, API-LIFECYCLE-POLICY.md)
# api/sangam-api-deprecations.yaml is the single deprecation registry: the server's DeprecationHeadersFilter reads the
# same file from the class path. Every entry must name an operation that exists, and is marked deprecated here.
with open(_os.path.join(_os.path.dirname(_os.path.abspath(__file__)), "sangam-api-deprecations.yaml"),
          encoding="utf-8") as _f:
    _dep = yaml.safe_load(_f) or {}
for _e in _dep.get("deprecations") or []:
    _path = _e["path"][len("/api/v1"):] if _e["path"].startswith("/api/v1/") else _e["path"]
    _o = PATHS.get(_path, {}).get(_e["method"].lower())
    assert _o is not None, "deprecation registry names an unknown operation: %s %s" % (_e["method"], _e["path"])
    _o["deprecated"] = True
    _o["x-sangam-deprecated-on"] = str(_e["deprecated"])
    _o["x-sangam-sunset"] = str(_e["sunset"])
    if _e.get("successor"):
        _o["x-sangam-successor"] = _e["successor"]

doc = {
    "openapi": "3.1.0",
    "info": {
        "title": "Sangam API",
        "version": "0.1.0",
        "summary": "Universal workflow platform on Operaton 2.2 (ADR-D-5 REST first)",
        "description": (
            "Sangam REST API (ARCHITECTURE.md §4). Conventions:\n"
            "- Base path /api/v1, JSON, ISO-8601 UTC times, string ids.\n"
            "- Auth: `Authorization: Bearer <OIDC JWT>`; the tenant comes from the token claim `tenant` and never from the request (VI.4 rule 6). The dev profile accepts a static token.\n"
            "- Mutations accept `Idempotency-Key` (R41). Replays carry `Idempotent-Replayed`. Operations marked `x-sangam-idempotent: true` store their result atomically with the state change (P-1).\n"
            "- Worker calls (`x-sangam-fenced: true`) are fenced by (workId, lockVersion, attemptId) instead: 409 STALE_LEASE.\n"
            "- Errors are RFC 9457 `application/problem+json` with a stable `code`.\n"
            "- Lists use `?page=&size=` and return `X-Total-Count`, capped with `X-Total-Count-Capped: true` (A-16).\n"
            "- Variables use R33 typed JSON (see TypedValue).\n"
            "- `x-sangam-permission` names the endpoint permission; object-level policy (VI.4) is applied in addition.\n"
            "- The Operaton REST API stays available unchanged at /engine-rest behind Sangam authentication."),
        "license": {"name": "Apache-2.0", "identifier": "Apache-2.0"},
    },
    "servers": [{"url": "/api/v1", "description": "Sangam server"}],
    "security": [{"bearerAuth": []}],
    "tags": [{"name": n, "description": d} for n, d in TAGS],
    "paths": PATHS,
    "components": {
        "securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT",
                                           "description": "OIDC access token; claims: sub (principal), tenant, groups/roles. Dev profile: static token."}},
        "parameters": PARAMS, "headers": HEADERS, "responses": RESPONSES, "schemas": SCHEMAS,
    },
}

# sanity: every $ref resolves
import json
txt = json.dumps(doc)
import re
for ref in set(re.findall(r'"#/components/(\w+)/([\w-]+)"', txt)):
    assert ref[1] in doc["components"][ref[0]], ref


class D(yaml.SafeDumper):
    def ignore_aliases(self, data):
        return True


with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write("# SPDX-License-Identifier: Apache-2.0\n")
    f.write("# Sangam API contract (OpenAPI 3.1). Generated from the WS-REST route table; WS-SDK and WS-UI code against it.\n")
    yaml.dump(doc, f, Dumper=D, sort_keys=False, allow_unicode=True, width=140)
ops = sum(len(v) for v in PATHS.values())
print("paths", len(PATHS), "operations", ops, "schemas", len(SCHEMAS))
