# SPDX-License-Identifier: Apache-2.0
# Routes of sangam-ops, derived from org.sangam.rest.modules.ops.OpsController.
# Executed inside gen_openapi.py: schema(), op(), q(), pp() and TAGS come from there.

TAGS.append(('Tenant operations', 'Search, usage, outliers, variables, secrets, sequences, index jobs, retention, migration status (R19). sangam-ops; off unless enabled'))
_OR = ['R19', 'VI.4']
_OANY = {"application/json": {"schema": {"type": "object", "additionalProperties": True}}}
_OLIST = {"application/json": {"schema": {"type": "array", "items": {}}}}

schema('OpsValue', 'ValueRequest.', 'value:any reason:s')
schema('OpsSecret', 'SecretRequest; write-only.', 'value:s')
schema('OpsAdvance', 'AdvanceRequest; forward only.', 'series:s period:s value:long reason:s')
schema('OpsReason', 'ReasonRequest.', 'reason:s')
schema('OpsJob', 'Job.', 'generation:long state:s indexed:long expected:long message:s reason:s')
schema('OpsPurge', 'PurgeResult.', 'purged:int')


def _ob(name):
    return {"required": True, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/" + name}}}}


_T = 'Tenant operations'
op('get', '/ops-admin/search', 'opsSearch', _T, 'Global search', 'Results per resource type, filtered by the policy compiler.', _OR, params=[q('q', required=True), q('cap', 'int')], resp=(200, _OANY), perm='search.use')
op('get', '/ops-admin/usage', 'opsUsage', _T, 'Usage metrics', 'Daily metrics of the caller tenant.', _OR, params=[q('from', 'date', required=True), q('to', 'date', required=True)], resp=(200, _OLIST), perm='ops.tenant.read')
op('get', '/ops-admin/outliers', 'opsOutliers', _T, 'Slow-path analysis', 'Associations only, over server allow-listed variables.', _OR, params=[q('processKey', required=True), q('activityId', required=True), q('minSamples', 'int')], resp=(200, _OANY), perm='ops.tenant.read')
op('get', '/ops-admin/variables', 'opsVariables', _T, 'Scoped variables', 'Platform values overlaid with the tenant values.', _OR, resp=(200, _OANY), perm='ops.tenant.read')
op('get', '/ops-admin/variables/entries', 'opsVariableEntries', _T, 'Tenant variable entries', 'Tenant scope with version, last writer and reason.', _OR, resp=(200, _OLIST), perm='ops.tenant.read')
op('put', '/ops-admin/variables/{name}', 'opsSetVariable', _T, 'Set a tenant variable', 'Secret-like names are refused.', _OR, params=[pp('name')], body=_ob('OpsValue'), resp=(204, None), cmd='ops.variable.set', perm='ops.tenant.admin')
op('delete', '/ops-admin/variables/{name}', 'opsDeleteVariable', _T, 'Delete a tenant variable', 'Removes the tenant value.', _OR, params=[pp('name')], resp=(204, None), cmd='ops.variable.delete', perm='ops.tenant.admin')
op('get', '/ops-admin/secrets', 'opsSecrets', _T, 'List secrets', 'Names and versions; values are never returned.', _OR, resp=(200, _OLIST), perm='ops.tenant.admin')
op('put', '/ops-admin/secrets/{ref}', 'opsPutSecret', _T, 'Store a secret', 'Encrypted at rest; write-only.', _OR, params=[pp('ref')], body=_ob('OpsSecret'), resp=(204, None), cmd='ops.secret.put', perm='ops.tenant.admin')
op('delete', '/ops-admin/secrets/{ref}', 'opsDeleteSecret', _T, 'Delete a secret', 'Removes the secret.', _OR, params=[pp('ref')], resp=(204, None), cmd='ops.secret.delete', perm='ops.tenant.admin')
op('get', '/ops-admin/sequences/{table}', 'opsSequences', _T, 'List sequences', 'Current values in a registered sequence table.', _OR, params=[pp('table')], resp=(200, _OLIST), perm='ops.tenant.read')
op('post', '/ops-admin/sequences/{table}/advance', 'opsAdvanceSequence', _T, 'Advance a sequence', 'Forward only, with a reason.', _OR, params=[pp('table')], body=_ob('OpsAdvance'), resp=(204, None), cmd='ops.sequence.advance', perm='ops.tenant.admin')
op('post', '/ops-admin/index-jobs', 'opsRebuildIndex', _T, 'Rebuild the search index', 'Builds a new generation and switches when complete; an optional reason is kept on the job.', _OR, body=_ob('OpsReason'), body_required=False, resp=(200, 'OpsJob'), cmd='ops.index.rebuild', perm='ops.tenant.admin')
op('get', '/ops-admin/index-jobs/{generation}', 'opsIndexJob', _T, 'Get an index job', 'Platform operators only.', _OR, params=[pp('generation')], resp=(200, 'OpsJob'), perm='ops.read')
op('get', '/ops-admin/index-drift', 'opsIndexDrift', _T, 'Index drift', 'Source and indexed counts per type.', _OR, resp=(200, _OLIST), perm='ops.tenant.read')
op('get', '/ops-admin/retention/plan', 'opsRetentionPlan', _T, 'Retention plan', 'Whole trees due; excludes definitions with a history time to live and held roots.', _OR, params=[q('limit', 'int')], resp=(200, _OLIST), perm='ops.tenant.read')
op('post', '/ops-admin/retention/execute', 'opsRetentionExecute', _T, 'Execute retention', 'Re-plans on the server and purges due trees.', _OR, params=[q('limit', 'int')], body=_ob('OpsReason'), resp=(200, 'OpsPurge'), cmd='ops.retention.execute', perm='ops.tenant.admin')
op('get', '/ops-admin/retention/runs', 'opsRetentionRuns', _T, 'Retention runs', 'Executed purges with plan hash, reason and actor.', _OR, resp=(200, _OLIST), perm='ops.tenant.read')
op('get', '/ops-admin/migration', 'opsMigration', _T, 'Migration status', 'Checkpoints of history migration jobs (copied, failed, mismatched, status); platform operators only.', _OR, resp=(200, _OLIST), perm='ops.read')

# The console's routes (OpsUiController).
_OP = [q('page', 'int'), q('size', 'int')]
_OB = {"required": True, "content": {"application/json": {"schema": typ('obj')}}}
op('get', '/search', 'uiSearch', _T, 'Global search', 'Flat rows, filtered by the policy compiler.', _OR, params=[q('q', required=True)] + _OP, resp=(200, _OLIST), perm='search.use')
op('get', '/ops/usage', 'uiOpsUsage', _T, 'Usage metrics', 'The caller tenant; last 30 days by default.', _OR, params=[q('from', 'date'), q('to', 'date')], resp=(200, _OLIST), perm='ops.tenant.read')
op('get', '/ops/outliers', 'uiOpsOutliers', _T, 'Slow-path associations', 'Over server allow-listed variables only.', _OR, params=[q('processDefinitionKey'), q('activityId')], resp=(200, _OLIST), perm='ops.tenant.read')
op('get', '/ops/variables', 'uiOpsVariables', _T, 'Tenant variables', 'As rows.', _OR, params=[q('scope'), q('name')] + _OP, resp=(200, _OLIST), perm='ops.tenant.read')
op('put', '/ops/variables', 'uiOpsSetVariable', _T, 'Set a variable', 'TENANT scope only.', _OR, body=_OB, resp=(204, None), cmd='ops.variable.set.ui', perm='ops.tenant.admin')
op('delete', '/ops/variables/{id}', 'uiOpsDeleteVariable', _T, 'Delete a variable', 'Needs a reason.', _OR, params=[pp('id'), q('reason', required=True)], resp=(204, None), cmd='ops.variable.delete.ui', perm='ops.tenant.admin')
op('get', '/ops/secrets', 'uiOpsSecrets', _T, 'Secrets', 'Metadata only.', _OR, params=_OP, resp=(200, _OLIST), perm='ops.tenant.admin')
op('put', '/ops/secrets/{name}', 'uiOpsPutSecret', _T, 'Set a secret', 'The value is write-only.', _OR, params=[pp('name')], body=_OB, resp=(204, None), cmd='ops.secret.put.ui', perm='ops.tenant.admin')
op('delete', '/ops/secrets/{name}', 'uiOpsDeleteSecret', _T, 'Delete a secret', 'Needs a reason.', _OR, params=[pp('name'), q('reason', required=True)], resp=(204, None), cmd='ops.secret.delete.ui', perm='ops.tenant.admin')
op('get', '/ops/sequences', 'uiOpsSequences', _T, 'Sequences', 'All sequence tables.', _OR, resp=(200, _OLIST), perm='ops.tenant.read')
op('post', '/ops/sequences/{name}/advance', 'uiOpsAdvance', _T, 'Advance a sequence', 'Forward only.', _OR, params=[pp('name')], body=_OB, resp=(204, None), cmd='ops.sequence.advance.ui', perm='ops.tenant.admin')
op('get', '/ops/index-jobs', 'uiOpsIndexJobs', _T, 'Index jobs', 'The caller tenant.', _OR, params=_OP, resp=(200, _OLIST), perm='ops.tenant.read')
op('post', '/ops/index-jobs', 'uiOpsStartIndexJob', _T, 'Rebuild or check the index', 'REBUILD needs ops.tenant.admin.', _OR, body=_OB, resp=(200, _OANY), cmd='ops.index.start.ui', perm='ops.tenant.read')
op('get', '/ops/migration-status', 'uiOpsMigration', _T, 'Migration checkpoints', 'Platform operators.', _OR, params=[q('source')] + _OP, resp=(200, _OLIST), perm='ops.read')
op('get', '/ops/retention/plan', 'uiOpsRetentionPlan', _T, 'Preview retention', 'With a hash of the plan.', _OR, resp=(200, _OANY), perm='ops.tenant.read')
op('get', '/ops/retention/runs', 'uiOpsRetentionRuns', _T, 'Retention runs', 'Executed purges of the caller tenant, with plan hash, reason and actor.', _OR, params=_OP, resp=(200, _OLIST), perm='ops.tenant.read')
op('post', '/ops/retention/execute', 'uiOpsRetentionExecute', _T, 'Execute retention', 'Refused when the plan changed since the preview.', _OR, body=_OB, resp=(200, _OANY), cmd='ops.retention.execute.ui', perm='ops.tenant.admin')

# SL-44 bulk ingest (BulkController): async jobs, chunked idempotent uploads, per-row status and error report.
TAGS.append(('Bulk ingest', 'Asynchronous bulk data ingest per object type: chunked upload, per-row idempotency on (tenant, object type, external id), per-row status and error report (SL-44). sangam-ops; off unless enabled'))
_B = 'Bulk ingest'
_BR = ['SL-44', 'R19', 'VI.4', 'R36', 'R41']
_BPERM = 'the permission declared by the object type sink (ops.variable: ops.tenant.admin)'
schema('BulkCreateJob', 'CreateJobRequest. expectedChunks, when given, must all be uploaded before close.', 'objectType:s expectedChunks:int', required=('objectType',))
schema('BulkRowInput', 'RowInput: the stable id of the record in the caller system of the record and its fields.', 'externalId:s data:obj', required=('externalId', 'data'))
schema('BulkChunk', 'ChunkRequest. Limits: rows per chunk and bytes per chunk (413 BULK_LIMIT).', 'rows:list:BulkRowInput', required=('rows',))
schema('BulkChunkReceipt', 'ChunkReceipt; replayed = the same content was stored before.', 'chunkSeq:int rows:int invalidRows:int replayed:bool')
schema('BulkJob', 'Job with row counts per state.', 'id:s tenantId:s objectType:s state:s expectedChunks:int chunks:int totalRows:long rows:lmap createdBy:s createdAt:dt closedAt:dt completedAt:dt',
       enums={'state': ['OPEN', 'PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'ABORTED']})
schema('BulkRowStatus', 'RowStatus: per-row state, error and the sink result reference.', 'chunkSeq:int rowSeq:int externalId:s state:s attempts:int errorCode:s errorText:s resultRef:s',
       enums={'state': ['PENDING', 'RUNNING', 'DONE', 'SKIPPED', 'FAILED', 'CANCELLED']})
_BID = [pp('id')]


def _bulk_limits(path, method, quota=False):
    r = PATHS[path][method]['responses']
    r['413'] = {"description": "BULK_LIMIT (rows or bytes per chunk, chunks or rows per job)" + (", or TENANT_QUOTA_EXCEEDED (the tenant's SL-43 batch size); nothing was stored" if quota else ""), "content": PROBLEM}


op('post', '/bulk/jobs', 'bulkCreateJob', _B, 'Create a bulk job', 'Opens a job for one object type in the caller tenant; at most a configured number of open or processing jobs per tenant (409 BULK_ACTIVE_JOBS).', _BR, body='BulkCreateJob', resp=(201, 'BulkJob'), created=True, cmd='bulk.job.create', perm=_BPERM)
op('get', '/bulk/jobs', 'bulkListJobs', _B, 'List bulk jobs', 'Jobs of the caller tenant for the object types the caller may load, newest first.', _BR, params=[q('state', enum=['OPEN', 'PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'ABORTED'])], resp=(200, 'array:BulkJob'), list_=True, perm=_BPERM, errors=STD_R)
op('get', '/bulk/jobs/{id}', 'bulkGetJob', _B, 'Get a bulk job', 'State and row counts; a job of another tenant is 404.', _BR, params=_BID, resp=(200, 'BulkJob'), perm=_BPERM, errors=GET_E)
op('put', '/bulk/jobs/{id}/chunks/{seq}', 'bulkUploadChunk', _B, 'Upload a chunk', 'Idempotent per sequence number: the same content again is a replay, other content for a stored sequence is 409 CHUNK_CONFLICT. Invalid rows are stored as FAILED INVALID_ROW. The SL-43 tenant batch-size quota is checked per chunk before anything is stored (413 TENANT_QUOTA_EXCEEDED).', _BR, params=_BID + [{'name': 'seq', 'in': 'path', 'required': True, 'schema': typ('int'), 'description': 'Chunk sequence number, 0-based'}], body='BulkChunk', resp=(200, 'BulkChunkReceipt'), cmd='bulk.chunk.upload', perm=_BPERM)
_bulk_limits('/bulk/jobs/{id}/chunks/{seq}', 'put', quota=True)
op('post', '/bulk/jobs/{id}/close', 'bulkCloseJob', _B, 'Close a bulk job', 'No more chunks; the worker applies the rows asynchronously (409 BULK_JOB_EMPTY, BULK_CHUNKS_MISSING).', _BR, params=_BID, resp=(200, 'BulkJob'), cmd='bulk.job.close', perm=_BPERM)
op('post', '/bulk/jobs/{id}/abort', 'bulkAbortJob', _B, 'Abort a bulk job', 'Rows not yet applied are cancelled; applied rows stay applied.', _BR, params=_BID, resp=(200, 'BulkJob'), cmd='bulk.job.abort', perm=_BPERM)
op('get', '/bulk/jobs/{id}/rows', 'bulkJobRows', _B, 'Per-row status', 'In upload order, optionally by state.', _BR, params=_BID + [q('state', enum=['PENDING', 'RUNNING', 'DONE', 'SKIPPED', 'FAILED', 'CANCELLED'])], resp=(200, 'array:BulkRowStatus'), list_=True, perm=_BPERM, errors=GET_E)
op('get', '/bulk/jobs/{id}/errors', 'bulkJobErrors', _B, 'Error report', 'Failed rows with error code and text.', _BR, params=_BID, resp=(200, 'array:BulkRowStatus'), list_=True, perm=_BPERM, errors=GET_E)
