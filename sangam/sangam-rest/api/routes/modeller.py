# SPDX-License-Identifier: Apache-2.0
# sangam-modeller: landscape, token walk, edit leases, templates, actions, test reports and the catalogue. Executed
# inside gen_openapi.py. Off unless sangam.modules.modeller.enabled=true.

TAGS.append(("Modeller", "Landscape, token walk, edit leases, templates, actions, test reports, catalogue (sangam-modeller)"))
MR = ["R43", "R80"]

schema("ModelTokenWalkRequest", "Walk a token through a BPMN model.", "bpmnXml xml file processId choices:obj maxSteps:int state:obj choice", [])
schema("ModelEditLease", "The holder of a model file's edit lease.", "fileId holder fence:long expiresAt:dt requestedBy", ["fileId", "holder", "fence"])
schema("ModelLeaseOutcome", "Whether the caller may edit.", "editable:bool lease:ModelEditLease", ["editable"])
schema("ModelFenceRequest", "The fence given with the lease.", "fence:long packageId", [])
schema("ModelTakeoverRequest", "Take over a lease; the former editor's draft is kept.", "formerDraft reason", ["reason"])
schema("ElementTemplatePublish", "Publish a template; the same id is a new version.", "kind document:obj key name content", ["kind"])
schema("ElementTemplateApply", "Apply a template to an element.", "elementType values:obj", ["elementType"])
schema("ActionModelPublish", "Publish an action model.", "key label scope scopeType permission formKey targetType target",
       ["key", "label", "scope", "permission", "targetType", "target"])
schema("ActionModelInvoke", "Invoke an action.", "targetRef input:obj clientKey")
schema("TestBundleExport", "Export test cases as a sealed bundle.", "packageRevision cases:list:obj mocks:list:obj")
schema("TestBundleVerify", "Verify a sealed test bundle.", "bundle", ["bundle"])

ML = "/models/{file}/lease"
F = pp("file", "model file: the package id")
op("get", "/landscape", "getProcessLandscape", "Modeller", "Process landscape", "Call graph across packages, with cycles.", MR,
   resp=(200, "object"), perm="package.read")
op("post", "/models/token-walk", "walkModelToken", "Modeller", "Token walk", "Reads the model only; nothing runs.", MR,
   body="ModelTokenWalkRequest", resp=(200, "object"), cmd="modeller.tokenWalk", perm="package.read")
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
op("get", "/templates", "listElementTemplates", "Modeller", "Element templates", "Latest version of each template.", MR,
   params=[q("kind", required=True)], resp=(200, "array:VariableMap"), perm="package.read")
op("post", "/templates", "publishElementTemplate", "Modeller", "Publish a template", "A new version for an existing id.", MR,
   body="ElementTemplatePublish", resp=(200, "object"), cmd="modeller.template.publish", perm="package.author")
op("post", "/templates/{id}/apply", "applyElementTemplate", "Modeller", "Apply a template", "Returns properties and field errors.", MR,
   params=[pp("id"), q("version", "int")], body="ElementTemplateApply", resp=(200, "object"), cmd="modeller.template.apply", perm="package.read")
op("get", "/actions", "listActionModels", "Modeller", "Available actions", "Filtered by each action's own permission.", MR,
   params=[q("scope", required=True), q("scopeType"), q("targetRef")], resp=(200, "array:VariableMap"), perm="modeller.action.invoke")
op("post", "/actions", "publishActionModel", "Modeller", "Publish an action", "A new version for an existing key.", MR,
   body="ActionModelPublish", resp=(200, "object"), cmd="modeller.action.publish", perm="modeller.action.publish")
op("post", "/actions/{key}/invoke", "invokeActionModel", "Modeller", "Invoke an action",
   "BPMN and connector targets run; SCRIPT targets are refused.", MR, params=[pp("key")], body="ActionModelInvoke",
   resp=(200, "object"), cmd="modeller.action.invoke", perm="modeller.action.invoke")
op("get", "/packages/{id}/tests/reports", "listPackageTestReports", "Modeller", "Stored test reports", "Newest first.", MR,
   params=[pp("id")], resp=(200, "array:VariableMap"), perm="package.read")
op("post", "/packages/{id}/tests/export", "exportPackageTests", "Modeller", "Export a test bundle", "Hash-sealed.", MR,
   params=[pp("id")], body="TestBundleExport", resp=(200, "object"), cmd="modeller.tests.export", perm="package.author")
op("post", "/packages/tests/verify-bundle", "verifyPackageTestBundle", "Modeller", "Verify a test bundle", "Rejects a changed bundle.", MR,
   body="TestBundleVerify", resp=(200, "object"), cmd="modeller.tests.verify", perm="package.read")
op("get", "/catalogue", "getExtensionCatalogue", "Modeller", "Extension catalogue", "Signed index read from a mirrored local file.", MR,
   resp=(200, "array:VariableMap"), perm="package.read")

_MB = {"required": True, "content": {"application/json": {"schema": typ('obj')}}}
_MU = {"required": False, "content": {"application/json": {"schema": typ('obj')}}}
_ML = {"application/json": {"schema": {"type": "array", "items": {}}}}
op("get", "/templates/{key}", "getElementTemplate", "Modeller", "Get a template", "Latest version unless one is named.", MR, params=[pp("key"), q("version", "int")], resp=(200, "object"), perm="package.read")
op("post", ML + "/acquire", "acquireModelLeaseUi", "Modeller", "Acquire the edit lease (console)", "Same as POST on the lease.", MR, params=[pp("file")], body=_MU, resp=(200, "object"), cmd="modeller.lease.acquire.ui", perm="package.author")
op("post", "/actions/{key}/run", "runActionModel", "Modeller", "Run an action", "Permission checked on the subject; idempotent per request key.", MR, params=[pp("key")], body=_MB, resp=(200, "object"), cmd="modeller.action.run", perm="modeller.action.invoke")
op("delete", "/actions/{key}", "retireActionModel", "Modeller", "Retire an action", "Needs a reason; audited.", MR, params=[pp("key"), q("reason", required=True)], resp=(204, None), cmd="modeller.action.retire", perm="modeller.action.publish")
op("get", "/service-mocks", "listServiceMocks", "Modeller", "List service mocks", "Not on a production profile.", MR, params=[q("page", "int"), q("size", "int")], resp=(200, _ML), perm="ops.read")
op("post", "/service-mocks", "createServiceMock", "Modeller", "Add a service mock", "This node, until restart.", MR, body=_MB, resp=(201, "object"), cmd="modeller.mock.create", perm="ops.write")
op("put", "/service-mocks/global", "setServiceMocksGlobal", "Modeller", "Toggle all mocks", "This node.", MR, body=_MB, resp=(200, "object"), cmd="modeller.mock.global", perm="ops.write")
op("patch", "/service-mocks/{id}", "toggleServiceMock", "Modeller", "Toggle a mock's service", "Affects every rule of the service.", MR, params=[pp("id")], body=_MB, resp=(200, "object"), cmd="modeller.mock.toggle", perm="ops.write")
