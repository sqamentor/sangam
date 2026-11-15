# SPDX-License-Identifier: Apache-2.0
# sangam-modeller: landscape, token walk, edit leases, templates, actions, test reports and the catalogue. Executed
# inside gen_openapi.py. Off unless sangam.modules.modeller.enabled=true.

TAGS.append(("Modeller", "Landscape, token walk, edit leases, templates, actions, test reports, catalogue (sangam-modeller)"))
MR = ["R43", "R80"]

schema("ModelTokenWalkRequest", "Walk a token through a BPMN model.", "bpmnXml xml file processId choices:obj maxSteps:int state:obj choice", [],
       descs={"choices": "Element id to the values to take, in order for repeated visits (M-128): for a gateway the "
                         "outgoing flow id; for an activity with boundary events its own id (it completes) or the id "
                         "of the boundary event that fires; for the start event of an event sub-process the flow "
                         "node ids at which its event occurs (never asked for)",
              "state": "The previous answer's state; with choice, the step-by-step form",
              "choice": "The value for the element the previous answer waits for; added after its earlier choices"})
schema("ModelEditLease", "The holder of a model file's edit lease.", "fileId holder fence:long expiresAt:dt requestedBy", ["fileId", "holder", "fence"])
schema("ModelLeaseOutcome", "Whether the caller may edit.", "editable:bool lease:ModelEditLease", ["editable"])
schema("ModelFenceRequest", "The fence given with the lease.", "fence:long packageId", [])
schema("ModelTakeoverRequest", "Take over a lease; the former editor's draft is kept.", "formerDraft reason", ["reason"])
schema("ElementTemplatePublish", "Publish a template; the same id is a new version.", "kind document:obj key name content", ["kind"])
schema("ElementTemplateApply", "Apply a template to an element.", "elementType values:obj", ["elementType"])
schema("ActionModelPublish", "Publish an action model.", "key label scope scopeType permission formKey targetType target "
       "name form:any processKey",
       ["key", "label", "scope", "permission", "targetType", "target"])
schema("ActionModelInvoke", "Invoke an action.", "targetRef input:obj clientKey")
schema("TestBundleExport", "Export test cases as a sealed bundle.", "packageRevision cases:list:obj mocks:list:obj")
schema("TestBundleVerify", "Verify a sealed test bundle.", "bundle", ["bundle"])

# M-125 semantic model diff (W4-G REQUEST 2026-11-12T16:49:15Z; back end org.sangam.modeller.diff.ModelDiff)
_DIFF_LEVELS = ["ELEMENT", "FLOW", "EXPRESSION", "PROPERTY"]
schema("ModelDiffRequest", "Two versions of one model file to compare. Each side is at most 5 MiB of UTF-8 text.",
       "resource before after", ["resource"], descs={
           "resource": "File name; its extension (.bpmn, .bpmn20.xml, .dmn, .cmmn, .json) picks the format",
           "before": "The old model text (UTF-8); null or empty = a new file: every element of the new version is "
                     "reported as ADDED",
           "after": "The new model text (UTF-8); null or empty = a deleted file: every element of the old version is "
                    "reported as REMOVED"})
SCHEMAS["ModelDiffRequest"]["properties"]["resource"]["maxLength"] = 255
for _side in ("before", "after"):
    SCHEMAS["ModelDiffRequest"]["properties"][_side]["type"] = ["string", "null"]
schema("ModelDiffSummary", "Number of changes per level.", "ELEMENT:int FLOW:int EXPRESSION:int PROPERTY:int", _DIFF_LEVELS)
schema("ModelDiffChange", "One semantic change. Elements are matched by id (form components by id or key); layout "
       "(BPMNDI, DMNDI, CMMNDI) is never a change.",
       "level kind elementId elementType name path before after",
       ["level", "kind", "elementId", "elementType", "name", "path"],
       enums={"level": _DIFF_LEVELS, "kind": ["ADDED", "REMOVED", "CHANGED"]},
       descs={"elementType": "BPMN/DMN/CMMN local name, or the form component's type",
              "path": "The changed property or expression inside the element; empty for the element itself",
              "before": "The old value; null when added", "after": "The new value; null when removed"})
for _side in ("before", "after"):
    SCHEMAS["ModelDiffChange"]["properties"][_side]["type"] = ["string", "null"]
schema("ModelDiffReport", "Semantic diff of two model versions.", "resource format changed:bool summary:ModelDiffSummary "
       "changes:list:ModelDiffChange", ["resource", "format", "changed", "summary", "changes"],
       enums={"format": ["BPMN", "DMN", "CMMN", "FORM"]},
       descs={"changes": "Sorted by level, elementId, path, kind"})

ML = "/models/{file}/lease"
F = pp("file", "model file: the package id")
op("get", "/landscape", "getProcessLandscape", "Modeller", "Process landscape", "Call graph across packages, with cycles.", MR,
   resp=(200, "object"), perm="package.read")
op("post", "/models/token-walk", "walkModelToken", "Modeller", "Token walk",
   "Reads the model only; nothing runs. Walks gateways, boundary events, event sub-processes, error and escalation "
   "propagation, compensation and terminate end events; each move names how the token got there (via FLOW, "
   "BOUNDARY, EVENT_SUB_PROCESS or COMPENSATION). 422 MODELLER_REJECTED when a choice names no outgoing flow or "
   "boundary event, or an event sub-process trigger names no flow node.", MR,
   body="ModelTokenWalkRequest", resp=(200, "object"), cmd="modeller.tokenWalk", perm="package.read")
op("post", "/models/diff", "diffModels", "Modeller", "Semantic model diff",
   "Compares two versions of a BPMN, DMN, CMMN or form file by meaning (elements, flows, expressions, properties), "
   "not by text; reads only, nothing is stored (M-125). 400 MODEL_UNREADABLE when a side is not XML/JSON, has a "
   "DOCTYPE, or the two sides are of different formats.", MR,
   body="ModelDiffRequest", resp=(200, "ModelDiffReport"), cmd="modeller.diff", perm="package.read")
op("get", ML, "getModelLease", "Modeller", "Current lease", "The lease on a model file.", MR, params=[F], resp=(200, "ModelEditLease"), perm="package.read")
op("post", ML, "acquireModelLease", "Modeller", "Acquire the lease", "Saves then send the fence in X-Sangam-Edit-Fence.", MR,
   params=[F], resp=(200, "ModelLeaseOutcome"), cmd="modeller.lease.acquire", perm="package.author")
op("post", ML + "/heartbeat", "heartbeatModelLease", "Modeller", "Renew the lease", "Holder only.", MR, params=[F],
   body="ModelFenceRequest", resp=(204, None), cmd="modeller.lease.heartbeat", perm="package.author")
op("post", ML + "/release", "releaseModelLease", "Modeller", "Release the lease", "Holder only.", MR, params=[F],
   body="ModelFenceRequest", resp=(204, None), cmd="modeller.lease.release", perm="package.author")
op("post", ML + "/request-takeover", "requestModelLeaseTakeover", "Modeller", "Ask for the lease", "Tells the holder.", MR,
   params=[F], resp=(204, None), cmd="modeller.lease.requestTakeover", perm="package.author")
op("post", ML + "/takeover", "takeOverModelLease", "Modeller", "Take over the lease", "Needs modeller.lease.takeover; audited.", MR,
   params=[F], body="ModelTakeoverRequest", resp=(200, "ModelEditLease"), cmd="modeller.lease.takeover", perm="package.author")
# M-101 presence (W4-J REQUEST 2026-11-12T17:13:56Z), following the M-040 conversation stream (channels.py)
schema("ModelViewer", "One user who has the model file open.", "userId displayName editing:bool since:dt",
       ["userId", "editing", "since"],
       descs={"editing": "True for the holder of the file's edit lease", "since": "When this user opened the file"})
schema("ModelPresence", "Who has a model file open.", "fileId viewers:list:ModelViewer", ["fileId", "viewers"])
op("get", "/models/{file}/presence/stream", "streamModelPresence", "Modeller", "Model presence stream",
   "Server-sent events, event name 'presence', data = ModelPresence: one event on connect and one on every join, leave "
   "or lease change (M-101). The caller is present while the stream is open; closing it leaves. Live across nodes like "
   "the conversation stream (M-040).", MR, params=[F],
   resp=(200, "ModelPresence"), resp_ct="text/event-stream", perm="package.read", errors=GET_E)
op("get", "/templates", "listElementTemplates", "Modeller", "Element templates", "Latest version of each template.", MR,
   params=[q("kind", required=True)], resp=(200, "array:VariableMap"), perm="package.read")
op("post", "/templates", "publishElementTemplate", "Modeller", "Publish a template", "A new version for an existing id.", MR,
   body="ElementTemplatePublish", resp=(200, "object"), cmd="modeller.template.publish", perm="package.author")
op("post", "/templates/{id}/apply", "applyElementTemplate", "Modeller", "Apply a template", "Returns properties and field errors.", MR,
   params=[pp("id"), q("version", "int")], body="ElementTemplateApply", resp=(200, "object"), cmd="modeller.template.apply", perm="package.read")
op("get", "/actions", "listActionModels", "Modeller", "Available actions", "Filtered by each action's own permission.", MR,
   params=[q("scope", required=True), q("scopeType"), q("targetRef")], resp=(200, "array:VariableMap"), perm="modeller.action.invoke")
op("post", "/actions", "publishActionModel", "Modeller", "Publish an action", "A new version for an existing key. target is the process key for BPMN and "
   "connectorId:operation (a registered connector and one of its operations) for REST; anything else is 400.", MR,
   body="ActionModelPublish", resp=(200, "object"), cmd="modeller.action.publish", perm="modeller.action.publish")
op("post", "/actions/{key}/invoke", "invokeActionModel", "Modeller", "Invoke an action",
   "BPMN and connector targets run; SCRIPT targets are refused. The permission and the subject (targetRef: the "
   "case, process instance or task of the action's scope, visible to the caller) are checked on every call.", MR, params=[pp("key")], body="ActionModelInvoke",
   resp=(200, "object"), cmd="modeller.action.invoke", perm="modeller.action.invoke")
op("get", "/packages/{id}/tests/reports", "listPackageTestReports", "Modeller", "Stored test reports", "Newest first.", MR,
   params=[pp("id")], resp=(200, "array:VariableMap"), perm="package.read")
op("post", "/packages/{id}/tests/export", "exportPackageTests", "Modeller", "Export a test bundle", "Hash-sealed.", MR,
   params=[pp("id")], body="TestBundleExport", resp=(200, "object"), cmd="modeller.tests.export", perm="package.author")
op("post", "/packages/tests/verify-bundle", "verifyPackageTestBundle", "Modeller", "Verify a test bundle", "Rejects a changed bundle.", MR,
   body="TestBundleVerify", resp=(200, "object"), cmd="modeller.tests.verify", perm="package.read")
# the extension catalogue routes are in catalogue.py (M-128, decision 380)

_MB = {"required": True, "content": {"application/json": {"schema": typ('obj')}}}
_MU = {"required": False, "content": {"application/json": {"schema": typ('obj')}}}
_ML = {"application/json": {"schema": {"type": "array", "items": {}}}}
_MOD = "sangam-rest/src/main/java/org/sangam/rest/modules/modeller/ModellerController.java"
_MOCKS = "sangam-rest/src/main/java/org/sangam/rest/modules/modeller/ServiceMocksController.java"
op("get", "/templates/{key}", "getElementTemplate", "Modeller", "Get a template", "Latest version unless one is named.", MR, params=[pp("key"), q("version", "int")], resp=(200, "object"), perm="package.read")
op("post", ML + "/acquire", "acquireModelLeaseUi", "Modeller", "Acquire the edit lease (console)", "Same as POST on the lease.", MR, params=[pp("file")], body=record_body(_MOD, "FenceRequest", False), resp=(200, "object"), cmd="modeller.lease.acquire.ui", perm="package.author")
op("post", "/actions/{key}/run", "runActionModel", "Modeller", "Run an action", "The permission and the subject (subjectRef: the case, process instance or task of the action's scope, visible to the caller) are checked on every call; idempotent per request key; a reason is recorded in the ACTION_INVOKED audit entry.", MR, params=[pp("key")], body=record_body(_MOD, "RunActionRequest"), resp=(200, "object"), cmd="modeller.action.run", perm="modeller.action.invoke")
op("delete", "/actions/{key}", "retireActionModel", "Modeller", "Retire an action", "Needs a reason; audited.", MR, params=[pp("key"), q("reason", required=True)], resp=(204, None), cmd="modeller.action.retire", perm="modeller.action.publish")
op("get", "/service-mocks", "listServiceMocks", "Modeller", "List service mocks", "Not on a production profile.", MR, params=[q("page", "int"), q("size", "int")], resp=(200, _ML), perm="ops.read")
op("post", "/service-mocks", "createServiceMock", "Modeller", "Add a service mock", "This node, until restart.", MR, body=record_body(_MOCKS, "CreateMock"), resp=(201, "object"), cmd="modeller.mock.create", perm="ops.write")
op("put", "/service-mocks/global", "setServiceMocksGlobal", "Modeller", "Toggle all mocks", "This node.", MR, body=record_body(_MOCKS, "Toggle"), resp=(200, "object"), cmd="modeller.mock.global", perm="ops.write")
op("patch", "/service-mocks/{id}", "toggleServiceMock", "Modeller", "Toggle a mock's service", "Affects every rule of the service.", MR, params=[pp("id")], body=record_body(_MOCKS, "Toggle"), resp=(200, "object"), cmd="modeller.mock.toggle", perm="ops.write")
