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
schema('AiDecide', 'DecideRequest.', 'approve:bool argsHash:s reason:s')
schema('AiKbVersion', 'KbVersion.', 'version:int')
schema('AiIngest', 'IngestRequest.', 'documentId:s docVersion:s text:s')
schema('AiIngestResult', 'IngestResult.', 'chunks:int')
schema('AiKbSearch', 'KbSearchRequest.', 'query:s k:int')
schema('AiDraftGenerate', 'GenerateDraftRequest.', 'kind:s prompt:s current:s context:obj')
schema('AiDraft', 'Draft.', 'id:s tenantId:s kind:s state:s content:s validationErrors:list:s prompt:s contentHash:s model:s createdBy:s reviewedBy:s')
schema('AiDraftReview', 'ReviewDraftRequest.', 'contentHash:s note:s')
schema('AiEvaluate', 'EvaluateRequest.', 'expression:s variables:obj')
schema('AiEvaluation', 'Evaluation.', 'ok:bool value:any error:s millis:long')
schema('AiPropose', 'ProposeRequest.', 'intent:s variables:obj')
schema('AiProposal', 'Proposal.', 'expression:s')
schema('AiClassify', 'ClassifyRequest.', 'key:s version:int labels:list:s fields:list:s threshold:num sourceRef:s text:s')
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
op('post', '/ai/mcp', 'aiMcp', 'AI', 'MCP endpoint', 'JSON-RPC. Tools use the caller token; write tools are off per tenant by default. A browser Origin must be on sangam.ai.http-allowed-origins; MCP-Protocol-Version, Mcp-Method and Mcp-Name must match the body (400).', _R, body=_OBJ, resp=(200, _ANY), perm='ai.use')
op('get', '/ai/a2a/{agent}/card', 'aiA2aCard', 'AI', 'A2A agent card', 'Card of a configured inbound agent of the tenant.', _R, params=[pp('agent')], resp=(200, _ANY), perm='ai.use')
op('post', '/ai/a2a/{agent}', 'aiA2a', 'AI', 'A2A endpoint', 'JSON-RPC; the caller must be on the agent allow-list. Honours the A2A-Version header; a browser Origin must be allow-listed.', _R, params=[pp('agent')], body=_OBJ, resp=(200, _ANY), perm='ai.use')

# The console's routes (AiUiController).
_U = {"required": False, "content": {"application/json": {"schema": typ('obj')}}}
_P = [q('page', 'int'), q('size', 'int')]
op('get', '/ai/agent-runs', 'aiUiListRuns', 'AI', 'List agent runs (console)', 'Own runs; all of the tenant with ai.read.', _R, params=[q('agentKey'), q('status'), q('businessKey')] + _P, resp=(200, _LIST), perm='ai.use')
op('get', '/ai/agent-runs/{runId}', 'aiUiGetRun', 'AI', 'Get an agent run (console)', 'Visible to its owner and to ai.read.', _R, params=[pp('runId')], resp=(200, _ANY), perm='ai.use')
op('get', '/ai/agent-runs/{runId}/steps', 'aiUiRunSteps', 'AI', 'Steps of an agent run', 'Visible to its owner and to ai.read.', _R, params=[pp('runId')], resp=(200, _LIST), perm='ai.use')
op('get', '/ai/agent-approvals', 'aiUiListApprovals', 'AI', 'List approvals', 'The tenant only.', _R, params=[q('status')] + _P, resp=(200, _LIST), perm='ai.review')
op('post', '/ai/agent-approvals/{approvalId}/approve', 'aiUiApprove', 'AI', 'Approve a tool call', 'Not by the requester.', _R, params=[pp('approvalId')], body=_U, resp=(200, _ANY), cmd='ai.approval.approve', perm='ai.review')
op('post', '/ai/agent-approvals/{approvalId}/reject', 'aiUiReject', 'AI', 'Reject a tool call', 'Not by the requester.', _R, params=[pp('approvalId')], body=_U, resp=(200, _ANY), cmd='ai.approval.reject', perm='ai.review')
op('get', '/ai/knowledge-bases', 'aiUiListKb', 'AI', 'List knowledge bases', 'Active version, else newest.', _R, resp=(200, _LIST), perm='ai.admin')
op('post', '/ai/knowledge-bases', 'aiUiPublishKb', 'AI', 'Publish a knowledge base', 'A new version with this text, activated.', _R, body=_OBJ, resp=(201, _ANY), cmd='ai.kb.publish', perm='ai.admin', created=True)
op('post', '/ai/knowledge-bases/{key}/retire', 'aiUiRetireKb', 'AI', 'Retire a knowledge base', 'Retrieval stops. Needs a reason; actor and reason are kept.', _R, params=[pp('key')], body=_U, resp=(204, None), cmd='ai.kb.retire', perm='ai.admin')
op('get', '/ai/drafts', 'aiUiListDrafts', 'AI', 'List drafts', 'Own drafts; all of the tenant with ai.review. Filter by status or packageId.', _R, params=[q('status'), q('packageId')] + _P, resp=(200, _LIST), perm='ai.use')
op('get', '/ai/classifications', 'aiUiListClassifications', 'AI', 'List classifications', 'The tenant only.', _R, params=[q('status')] + _P, resp=(200, _LIST), perm='ai.review')
op('post', '/ai/classifications/{id}/review', 'aiUiReviewClassification', 'AI', 'Review a classification', 'ACCEPT or RETURN.', _R, params=[pp('id')], body=_OBJ, resp=(200, _ANY), cmd='ai.classification.review', perm='ai.review')
