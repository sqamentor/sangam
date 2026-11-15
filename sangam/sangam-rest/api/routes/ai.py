# SPDX-License-Identifier: Apache-2.0
# Routes of sangam-ai, derived from org.sangam.rest.modules.ai.AiController.
# Executed inside gen_openapi.py: schema(), op(), q(), pp() and TAGS come from there.

TAGS.append(('AI', 'Agent runs and approvals, knowledge bases, drafts, expressions, classification, MCP and A2A. sangam-ai; off unless enabled'))
_R = ['VI.4', 'R41']
_ANY = {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}
_LIST = {"application/json": {"schema": {"type": "array", "items": {}}}}
_OBJ = {"required": True, "content": {"application/json": {"schema": typ('obj')}}}

schema('AiRun', 'RunView.', 'runId:s agentKey:s state:s stepsUsed:int tokensUsed:long rootId:s businessKey:s principalId:s result:s')
schema('AiStartRun', 'StartRunRequest.', 'agentKey:s input:s businessKey:s')
schema('AiReason', 'ReasonRequest.', 'reason:s')
schema('AiApproval', 'Approval.', 'approvalId:s tenantId:s runId:s toolCallId:s toolName:s argsJson:s argsHash:s state:s requestedBy:s decidedBy:s reason:s')
schema('AiDecide', 'DecideRequest.', 'approve:bool argsHash:s reason:s', ['approve'])
schema('AiKbVersion', 'KbVersion.', 'version:int')
schema('AiIngest', 'IngestRequest.', 'documentId:s docVersion:s text:s')
schema('AiIngestResult', 'IngestResult.', 'chunks:int')
schema('AiKbSearch', 'KbSearchRequest.', 'query:s k:int')
schema('AiDraftGenerate', 'GenerateDraftRequest.', 'kind:s prompt:s current:s context:obj packageId:s')
schema('AiDraft', 'Draft.', 'id:s tenantId:s kind:s state:s content:s validationErrors:list:s prompt:s contentHash:s model:s createdBy:s reviewedBy:s')
schema('AiDraftReview', 'ReviewDraftRequest.', 'contentHash:s note:s reason:s')
schema('AiEvaluate', 'EvaluateRequest.', 'expression:s variables:obj')
schema('AiEvaluation', 'Evaluation.', 'ok:bool value:any error:s millis:long')
schema('AiPropose', 'ProposeRequest.', 'intent:s variables:obj goal:s')
schema('AiProposal', 'Proposal.', 'expression:s')
schema('AiClassify', 'ClassifyRequest.', 'key:s version:int labels:list:s fields:list:s threshold:num sourceRef:s text:s', ['version'])
schema('AiClassification', 'Classification.', 'id:s label:s confidence:num fields:obj route:s model:s')
schema('AiCorrect', 'CorrectRequest.', 'label:s')
schema('AiEvalResult', 'Evaluation of a template.', 'sampled:long correct:long')


def _b(name):
    return {"required": True, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/" + name}}}}


op('post', '/ai/runs', 'aiStartRun', 'AI', 'Start an agent run', 'Starts a configured agent as the caller.', _R, body=_b('AiStartRun'), resp=(201, 'AiRun'), cmd='ai.run.start', perm='ai.use', created=True)
op('get', '/ai/runs', 'aiListRuns', 'AI', 'List agent runs', 'Own runs; all runs of the tenant with ai.read.', _R, params=[q('limit', 'int')], resp=(200, _LIST), perm='ai.use')
op('get', '/ai/runs/{id}', 'aiGetRun', 'AI', 'Get an agent run', 'Visible to its owner and to ai.read.', _R, params=[pp('id')], resp=(200, 'AiRun'), perm='ai.use')
op('post', '/ai/runs/{id}/cancel', 'aiCancelRun', 'AI', 'Cancel an agent run', 'Cancels a run the caller can see.', _R, params=[pp('id')], body=_b('AiReason'), resp=(200, 'AiRun'), cmd='ai.run.cancel', perm='ai.use')
op('get', '/ai/approvals', 'aiListApprovals', 'AI', 'Pending tool approvals', 'Pending approvals of the tenant.', _R, resp=(200, _LIST), perm='ai.review')
op('post', '/ai/approvals/{id}/decide', 'aiDecideApproval', 'AI', 'Decide a tool approval', 'The decider differs from the requester and confirms the reviewed argument hash.', _R, params=[pp('id')], body=_b('AiDecide'), resp=(200, 'AiApproval'), cmd='ai.approval.decide', perm='ai.review')
op('post', '/ai/knowledge-bases/{kb}/versions', 'aiKbNewVersion', 'AI', 'Create a knowledge base version', 'Starts a BUILDING version.', _R, params=[pp('kb')], body_required=False, resp=(200, 'AiKbVersion'), cmd='ai.kb.version', perm='ai.admin')
op('post', '/ai/knowledge-bases/{kb}/versions/{version}/documents', 'aiKbIngest', 'AI', 'Ingest a document', 'Only a BUILDING version accepts documents.', _R, params=[pp('kb'), pp('version')], body=_b('AiIngest'), resp=(200, 'AiIngestResult'), cmd='ai.kb.ingest', perm='ai.admin')
op('post', '/ai/knowledge-bases/{kb}/versions/{version}/activate', 'aiKbActivate', 'AI', 'Activate a version', 'Makes the version the searched one.', _R, params=[pp('kb'), pp('version')], body_required=False, resp=(204, None), cmd='ai.kb.activate', perm='ai.admin')
op('post', '/ai/knowledge-bases/{kb}/search', 'aiKbSearch', 'AI', 'Search a knowledge base', 'Results are filtered by what the caller may read.', _R, params=[pp('kb')], body=_b('AiKbSearch'), resp=(200, _LIST), perm='ai.use')
op('post', '/ai/drafts', 'aiGenerateDraft', 'AI', 'Generate a draft', 'Generates and validates content for review.', _R, body=_b('AiDraftGenerate'), resp=(200, 'AiDraft'), cmd='ai.draft.generate', perm='ai.use')
op('get', '/ai/drafts/{id}', 'aiGetDraft', 'AI', 'Get a draft', 'Visible to its author and to ai.review.', _R, params=[pp('id')], resp=(200, 'AiDraft'), perm='ai.use')
op('post', '/ai/drafts/{id}/approve', 'aiApproveDraft', 'AI', 'Approve a draft', 'Stores the content as a package draft; never deploys. Also needs package.author.', _R, params=[pp('id')], body=_b('AiDraftReview'), resp=(200, 'AiDraft'), cmd='ai.draft.approve', perm='ai.review')
op('post', '/ai/drafts/{id}/reject', 'aiRejectDraft', 'AI', 'Reject a draft', 'Rejects the reviewed content.', _R, params=[pp('id')], body=_b('AiDraftReview'), resp=(200, 'AiDraft'), cmd='ai.draft.reject', perm='ai.review')
op('post', '/ai/expressions/evaluate', 'aiEvaluateExpression', 'AI', 'Evaluate a FEEL expression', 'Time-limited evaluation against sample variables.', _R, body=_b('AiEvaluate'), resp=(200, 'AiEvaluation'), perm='ai.use')
op('post', '/ai/expressions/propose', 'aiProposeExpression', 'AI', 'Propose a FEEL expression', 'Needs a configured LLM; otherwise 503 LLM_DISABLED.', _R, body=_b('AiPropose'), resp=(200, 'AiProposal'), perm='ai.use')
op('post', '/ai/classifications', 'aiClassify', 'AI', 'Classify a document', 'Low confidence routes to human review.', _R, body=_b('AiClassify'), resp=(200, 'AiClassification'), cmd='ai.classify', perm='ai.use')
op('post', '/ai/classifications/{id}/correct', 'aiCorrectClassification', 'AI', 'Correct a classification', 'Records the reviewer label.', _R, params=[pp('id')], body=_b('AiCorrect'), resp=(204, None), cmd='ai.classify.correct', perm='ai.review')
op('get', '/ai/classifications/evaluation', 'aiClassificationEvaluation', 'AI', 'Classification accuracy', 'Reviewed samples and correct count for a template version.', _R, params=[q('key', required=True), q('version', 'int', required=True)], resp=(200, 'AiEvalResult'), perm='ai.review')
# Owner decision 171 (W4Q-26): MCP revision 2026-07-28 and A2A v1.0.1 (SPEC-LOOKUPS-WAVE4-2026-11-12.md sections 4a and
# 4b). No conformance claim until the tests run.
def _hdr(name, required, desc):
    return {"name": name, "in": "header", "required": required, "schema": {"type": "string"}, "description": desc}


op('post', '/ai/mcp', 'aiMcp', 'AI', 'MCP endpoint',
   'MCP 2026-07-28 Streamable HTTP, stateless: one POST endpoint (GET and DELETE answer 405), no initialize handshake '
   'and no Mcp-Session-Id; every request carries _meta io.modelcontextprotocol/protocolVersion and clientCapabilities, '
   'and server/discover is served. A request whose _meta carries no protocol version is served as the earlier '
   'revisions (initialize, ping, tools/list, tools/call) and its headers are not checked. When _meta carries a '
   'protocol version, a header that is absent or does not match the body is 400 HeaderMismatch (-32020); an '
   'unsupported revision is UnsupportedProtocolVersionError (-32022). The answer is application/json (the revision also '
   'allows an SSE stream scoped to the request; the server never sends JSON-RPC requests to the client). Sampling, '
   'elicitation and roots are never server-initiated: tools/call, resources/read and prompts/get may answer resultType '
   'input_required with inputRequests and an integrity-protected requestState bound to the principal, a time limit and '
   'the originating request, and the client repeats the request with a new id, inputResponses and the echoed '
   'requestState. Tools use the caller token; write tools are off per tenant by default. A browser Origin must be on '
   'sangam.ai.http-allowed-origins (403 otherwise).', _R,
   params=[_hdr('MCP-Protocol-Version', False, 'Protocol revision, 2026-07-28. Required when _meta carries a protocol '
                                               'version, and then equal to it (400 HeaderMismatch otherwise); not '
                                               'checked for a request without one (decision 328)'),
           _hdr('Mcp-Method', False, 'The JSON-RPC method of the body. Required when _meta carries a protocol '
                                     'version, and then equal to the body method (400 HeaderMismatch otherwise); not '
                                     'checked for a request without one (decision 328)'),
           _hdr('Mcp-Name', False, 'The tool name. Required when _meta carries a protocol version and the method is '
                                   'tools/call, and then equal to params.name (400 HeaderMismatch otherwise)')],
   body=_OBJ, resp=(200, _ANY), perm='ai.use')
op('get', '/ai/a2a/{agent}/card', 'aiA2aCard', 'AI', 'A2A agent card', 'Card of a configured inbound agent of the tenant.', _R, params=[pp('agent')], resp=(200, _ANY), perm='ai.use')
op('post', '/ai/a2a/{agent}', 'aiA2a', 'AI', 'A2A endpoint',
   'A2A v1.0 JSON-RPC binding (application/json). Methods: SendMessage; GetTask; CancelTask (params id, tenant, '
   'metadata; result Task; errors -32001, -32002, -32004); ListTasks (params tenant, contextId, status, pageSize 1 to '
   '100 default 50, pageToken, historyLength, statusTimestampAfter, includeArtifacts; result tasks, nextPageToken (empty '
   'on the last page), pageSize, totalSize; -32602 on a bad pageSize; -32004 while the process gateway does not '
   'implement it). SendStreamingMessage, SubscribeToTask and GetExtendedAgentCard answer -32004 (unsupported '
   'operation) and the four TaskPushNotificationConfig methods -32003 (push notifications not supported); the agent '
   'card says extendedAgentCard false. The caller must be on the agent allow-list; a browser Origin must be '
   'allow-listed.', _R,
   params=[pp('agent'), _hdr('A2A-Version', False, 'Major.Minor protocol version, 1.0 for A2A v1.0.x')],
   body=_OBJ, resp=(200, _ANY), perm='ai.use')

# The console's routes (AiUiController).
_U = {"required": False, "content": {"application/json": {"schema": typ('obj')}}}
_P = [q('page', 'int'), q('size', 'int')]
_AIUI = 'sangam-rest/src/main/java/org/sangam/rest/modules/ai/AiUiController.java'
op('get', '/ai/agent-runs', 'aiUiListRuns', 'AI', 'List agent runs (console)', 'Own runs; all of the tenant with ai.read.', _R, params=[q('agentKey'), q('status'), q('businessKey')] + _P, resp=(200, _LIST), perm='ai.use')
op('get', '/ai/agent-runs/{runId}', 'aiUiGetRun', 'AI', 'Get an agent run (console)', 'Visible to its owner and to ai.read.', _R, params=[pp('runId')], resp=(200, _ANY), perm='ai.use')
op('get', '/ai/agent-runs/{runId}/steps', 'aiUiRunSteps', 'AI', 'Steps of an agent run', 'Visible to its owner and to ai.read.', _R, params=[pp('runId')], resp=(200, _LIST), perm='ai.use')
op('get', '/ai/agent-approvals', 'aiUiListApprovals', 'AI', 'List approvals', 'The tenant only.', _R, params=[q('status')] + _P, resp=(200, _LIST), perm='ai.review')
op('post', '/ai/agent-approvals/{approvalId}/approve', 'aiUiApprove', 'AI', 'Approve a tool call', 'Not by the requester.', _R, params=[pp('approvalId')], body=record_body(_AIUI, 'DecisionRequest', False), resp=(200, _ANY), cmd='ai.approval.approve', perm='ai.review')
op('post', '/ai/agent-approvals/{approvalId}/reject', 'aiUiReject', 'AI', 'Reject a tool call', 'Not by the requester.', _R, params=[pp('approvalId')], body=record_body(_AIUI, 'DecisionRequest', False), resp=(200, _ANY), cmd='ai.approval.reject', perm='ai.review')
op('get', '/ai/knowledge-bases', 'aiUiListKb', 'AI', 'List knowledge bases', 'Active version, else newest.', _R, resp=(200, _LIST), perm='ai.admin')
op('post', '/ai/knowledge-bases', 'aiUiPublishKb', 'AI', 'Publish a knowledge base', 'A new version with this text, activated.', _R, body=record_body(_AIUI, 'PublishKbRequest'), resp=(201, _ANY), cmd='ai.kb.publish', perm='ai.admin', created=True)
op('post', '/ai/knowledge-bases/{key}/retire', 'aiUiRetireKb', 'AI', 'Retire a knowledge base', 'Retrieval stops. Needs a reason; actor and reason are kept.', _R, params=[pp('key')], body=record_body(_AIUI, 'DecisionRequest', False), resp=(204, None), cmd='ai.kb.retire', perm='ai.admin')
op('get', '/ai/drafts', 'aiUiListDrafts', 'AI', 'List drafts', 'Own drafts; all of the tenant with ai.review. Filter by status or packageId.', _R, params=[q('status'), q('packageId')] + _P, resp=(200, _LIST), perm='ai.use')
op('get', '/ai/classifications', 'aiUiListClassifications', 'AI', 'List classifications', 'The tenant only.', _R, params=[q('status')] + _P, resp=(200, _LIST), perm='ai.review')
op('post', '/ai/classifications/{id}/review', 'aiUiReviewClassification', 'AI', 'Review a classification', 'ACCEPT or RETURN.', _R, params=[pp('id')], body=record_body(_AIUI, 'ReviewRequest'), resp=(200, _ANY), cmd='ai.classification.review', perm='ai.review')

# M-051: the JSON-RPC bodies are bound as strings and parsed by the MCP and A2A servers; their contract types come from
# the members those servers read (_v2_bodykeys.py).
map_body('aiMcp', 'sangam-ai/src/main/java/org/sangam/ai/mcp/McpServer.java', 'McpServer.handle', 'req',
         via='AiController.mcp',
         also=[('sangam-ai/src/main/java/org/sangam/ai/mcp/McpServer.java', 'McpServer.validateHttpHeaders', 'req')])
map_body('aiA2a', 'sangam-ai/src/main/java/org/sangam/ai/a2a/A2a.java', 'A2a.Server.handle', 'req', via='AiController.a2a')
bk_opaque({
    ("McpServer.result", "put"): "the JSON-RPC id is echoed in the response as given",
    ("McpServer.error", "put"): "the JSON-RPC id is echoed in the response as given",
    ("McpServer.errorWithData", "put"): "the JSON-RPC id is echoed in the response as given",
    ("A2a.Server.ok", "put"): "the JSON-RPC id is echoed in the response as given",
    ("A2a.Server.err", "put"): "the JSON-RPC id is echoed in the response as given",
})
