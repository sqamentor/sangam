// SPDX-License-Identifier: Apache-2.0
/**
 * Operaton webapp concepts with no Sangam API equivalent, on the unchanged Operaton REST API (/engine-rest, behind
 * Sangam auth): job definitions, called definitions, external-task and user-task runtime views, identity links,
 * deployment resources, decision requirements, incidents annotation, system metrics and telemetry, saved task filters,
 * standalone tasks, authorizations, tenants and memberships, account, welcome and first-admin setup.
 * Written for Sangam from the REST contract (OPERATON-UI-PARITY.md); no Operaton UI code is copied.
 * Paged with firstResult/maxResults by a fixed page of 200 on the server query.
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { config } from '../config';
import { PluginSlot } from '../plugins/host';
import { AttachmentByUrl, FilterBoard, TaskSearch } from './operaton3';
import { BinaryVariable, BulkPriority, BulkRetry, DeploymentResources, MetricsChart, attachments, batchesEngine, decisionDefs, jobsEngine, opLog, variablesEngine } from './operaton2';
import { useTranslation } from 'react-i18next';
import { http } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { NAV } from '../app/nav';
import { announce, useAction, useAsync } from '../lib/hooks';
import { fmtDate } from '../i18n';
import { Badge, DataTable, Empty, KeyValues, Notice, PageHeader, ProblemAlert, Section, TextArea, TextField } from '../components/ui';
import { ModulePage, c, cell, f, type Row, type ScreenDef } from './engine';

const E = '/engine-rest';
const MAX = { maxResults: 200 };

const jobDefinitions: ScreenDef = {
  id: 'jobDefs',
  title: 'mod.op.jobDefinitions',
  description: 'mod.op.jobDefinitionsDesc',
  list: `${E}/job-definition`,
  listQuery: MAX,
  unpaged: true,
  filters: [f.text('processDefinitionId'), f.text('activityIdIn', 'fld.activityId'), f.text('jobType')],
  columns: [c('id'), c('activityId'), c('jobType'), c('jobConfiguration'), c('overridingJobPriority'), c('suspended')],
  rowKey: ['id'],
  actions: [
    { id: 'suspend', label: 'act.setSuspension', method: 'PUT', path: `${E}/job-definition/{id}/suspended`, fields: [f.bool('suspended'), f.bool('includeJobs'), f.dt('executionDate')], noJson: true },
    { id: 'retries', label: 'ops.setRetries', method: 'PUT', path: `${E}/job-definition/{id}/retries`, fields: [f.num('retries', undefined, true)], noJson: true },
    { id: 'priority', label: 'act.overridePriority', method: 'PUT', path: `${E}/job-definition/{id}/jobPriority`, fields: [f.num('priority', undefined, true), f.bool('includeJobs')], noJson: true },
  ],
  toolbar: [{ id: 'bulk', label: 'act.bulkSuspend', method: 'PUT', path: `${E}/job-definition/suspended`, fields: [f.text('processDefinitionKey', undefined, true), f.bool('suspended'), f.bool('includeJobs')], noJson: true }],
};

const calledDefs: ScreenDef = {
  id: 'called',
  title: 'mod.op.calledDefinitions',
  list: `${E}/process-definition/{processDefinitionId}/static-called-process-definitions`,
  unpaged: true,
  filters: [f.text('processDefinitionId', undefined, true)],
  requireFilters: ['processDefinitionId'],
  columns: [c('key'), c('name'), c('version'), c('calledFromActivityIds')],
  rowKey: ['id'],
};

const defTtl: ScreenDef = {
  id: 'definitions',
  title: 'mod.op.definitionAdmin',
  description: 'mod.op.definitionAdminDesc',
  list: `${E}/process-definition`,
  listQuery: { ...MAX, sortBy: 'name', sortOrder: 'asc' },
  unpaged: true,
  filters: [f.text('key'), f.text('nameLike', 'fld.name'), f.bool('latestVersion'), f.bool('startableInTasklist')],
  columns: [c('name'), c('key'), c('version'), c('versionTag'), c('historyTimeToLive'), c('suspended')],
  rowKey: ['id'],
  actions: [
    { id: 'ttl', label: 'act.setTtl', method: 'PUT', path: `${E}/process-definition/{id}/history-time-to-live`, fields: [f.num('historyTimeToLive')], noJson: true, hint: 'mod.op2.ttlClear', sendNulls: ['historyTimeToLive'] },
    // Operaton's TTL dialog follow-up: re-date the removal time of this definition's historic instances (async batch).
    { id: 'removal', label: 'act.setRemovalTime', path: `${E}/history/process-instance/set-removal-time`, fields: [f.text('historicProcessInstanceQuery.processDefinitionId', 'fld.processDefinitionId', true), f.bool('calculatedRemovalTime'), f.dt('absoluteRemovalTime'), f.bool('clearedRemovalTime'), f.bool('hierarchical')], noJson: true, hint: 'mod.op4.removalHint', showResult: true, defaults: (r) => ({ 'historicProcessInstanceQuery.processDefinitionId': r?.id, calculatedRemovalTime: true, hierarchical: true }) },
    { id: 'suspend', label: 'act.setSuspension', method: 'PUT', path: `${E}/process-definition/{id}/suspended`, fields: [f.bool('suspended'), f.bool('includeProcessInstances'), f.dt('executionDate')], noJson: true },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/process-definition/{id}`, fields: [f.q(f.bool('cascade')), f.q(f.bool('skipCustomListeners'))], noJson: true, danger: true },
  ],
};

const externalTasks: ScreenDef = {
  id: 'external',
  title: 'mod.op.externalTasks',
  description: 'mod.op.externalTasksDesc',
  list: `${E}/external-task`,
  listQuery: MAX,
  unpaged: true,
  filters: [f.text('processInstanceId'), f.text('topicName'), f.text('workerId'), f.bool('withRetriesLeft')],
  columns: [c('id'), c('topicName'), c('activityId'), c('workerId'), c('retries'), c('priority'), c('lockExpirationTime'), c('errorMessage')],
  rowKey: ['id'],
  actions: [
    { id: 'unlock', label: 'act.unlock', path: `${E}/external-task/{id}/unlock`, noJson: true, danger: true, hint: 'mod.op2.unlockHint' },
    { id: 'errors', label: 'act.errorDetails', method: 'GET', path: `${E}/external-task/{id}/errorDetails`, noJson: true, showResult: true },
    { id: 'retries', label: 'ops.setRetries', method: 'PUT', path: `${E}/external-task/{id}/retries`, fields: [f.num('retries', undefined, true)], noJson: true },
    { id: 'priority', label: 'act.overridePriority', method: 'PUT', path: `${E}/external-task/{id}/priority`, fields: [f.num('priority', undefined, true)], noJson: true },
  ],
};

const userTasks: ScreenDef = {
  id: 'userTasks',
  title: 'mod.op.userTasks',
  description: 'mod.op.userTasksDesc',
  list: `${E}/task`,
  listQuery: MAX,
  unpaged: true,
  filters: [f.text('processInstanceId'), f.text('assignee'), f.text('candidateGroup'), f.text('nameLike', 'fld.name'), f.dt('dueBefore')],
  columns: [c('name'), c('assignee'), c('owner'), c('created'), c('due'), c('followUp'), c('priority')],
  rowKey: ['id'],
  sections: [{ id: 'links', label: 'mod.op.identityLinks', path: `${E}/task/{id}/identity-links` }],
  actions: [
    { id: 'assign', label: 'act.assign', path: `${E}/task/{id}/assignee`, fields: [f.text('userId', undefined, true)], noJson: true },
    { id: 'link', label: 'act.addIdentityLink', path: `${E}/task/{id}/identity-links`, fields: [f.text('userId'), f.text('groupId'), f.select('type', ['candidate', 'assignee', 'owner'], undefined, true)], noJson: true },
    { id: 'unlink', label: 'act.removeIdentityLink', path: `${E}/task/{id}/identity-links/delete`, fields: [f.text('userId'), f.text('groupId'), f.select('type', ['candidate', 'assignee', 'owner'], undefined, true)], noJson: true },
    { id: 'edit', label: 'act.update', method: 'PUT', path: `${E}/task/{id}`, fields: [f.text('name', undefined, true), f.area('description'), f.text('owner'), f.text('assignee'), f.num('priority'), f.text('due', 'fld.due'), f.text('followUp')], noJson: true, hint: 'mod.op.taskEditHint' },
    { id: 'resolve', label: 'act.resolveDelegation', path: `${E}/task/{id}/resolve`, noJson: true },
  ],
  create: { id: 'standalone', label: 'act.createStandaloneTask', path: `${E}/task/create`, fields: [f.text('id', 'fld.taskId', true), f.text('name', undefined, true), f.area('description'), f.text('assignee'), f.text('owner'), f.num('priority'), f.text('tenantId')], noJson: true },
  toolbar: [{ id: 'batchMod', label: 'act.batchModification', path: `${E}/modification/executeAsync`, fields: [f.text('processDefinitionId', undefined, true), f.json('processInstanceIds', undefined, true), f.json('instructions', undefined, true), f.bool('skipCustomListeners'), f.bool('skipIoMappings')], noJson: true, showResult: true }],
};

const deployments: ScreenDef = {
  id: 'deployments',
  title: 'mod.op.deploymentResources',
  list: `${E}/deployment`,
  listQuery: { ...MAX, sortBy: 'deploymentTime', sortOrder: 'desc' },
  unpaged: true,
  filters: [f.text('nameLike', 'fld.name'), f.text('source')],
  columns: [c('name'), c('source'), c('deploymentTime'), c('tenantId'), c('id')],
  rowKey: ['id'],
  sections: [{ id: 'res', label: 'ops.resources', path: `${E}/deployment/{id}/resources` }, { id: 'count', label: 'mod.op3.instanceCount', path: `${E}/process-instance/count?deploymentId={id}` }],
  actions: [
    { id: 'redeploy', label: 'act.redeploy', path: `${E}/deployment/{id}/redeploy`, fields: [f.json('resourceNames'), f.text('source')], noJson: true, showResult: true },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/deployment/{id}`, fields: [f.q(f.bool('cascade')), f.q(f.bool('skipCustomListeners')), f.q(f.bool('skipIoMappings'))], noJson: true, danger: true },
  ],
};

const drds: ScreenDef = {
  id: 'drd',
  title: 'mod.op.drd',
  list: `${E}/decision-requirements-definition`,
  listQuery: { ...MAX, latestVersion: true },
  unpaged: true,
  columns: [c('name', 'fld.name', (r) => <Link to={`/ops/drd/${encodeURIComponent(String(r.id))}`}>{String(r.name ?? r.key)}</Link>), c('key'), c('version'), c('deploymentId')],
  rowKey: ['id'],
};

const decisionHistory: ScreenDef = {
  id: 'decisionHistory',
  title: 'mod.op.decisionHistory',
  description: 'mod.op.decisionHistoryDesc',
  list: `${E}/history/decision-instance?includeInputs=true&includeOutputs=true&disableBinaryFetching=true`,
  listQuery: MAX,
  unpaged: true,
  filters: [f.text('decisionDefinitionKey'), f.text('processInstanceId'), f.text('caseInstanceId')],
  columns: [c('decisionDefinitionKey'), c('evaluationTime'), c('processInstanceId'), c('activityId'), c('id')],
  rowKey: ['id'],
};

const batchHistory: ScreenDef = {
  id: 'batchHistory',
  title: 'mod.op.batchHistory',
  list: `${E}/history/batch`,
  listQuery: MAX,
  unpaged: true,
  filters: [f.text('type'), f.bool('completed')],
  columns: [c('id'), c('type'), c('totalJobs'), c('startTime'), c('endTime')],
  rowKey: ['id'],
  actions: [{ id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/history/batch/{id}`, noJson: true, danger: true }],
};

export function OperatonOpsPage() {
  return <ModulePage title="mod.op.opsTitle" description="mod.op.opsDesc" screens={[defTtl, jobDefinitions, jobsEngine, variablesEngine, calledDefs, externalTasks, userTasks, attachments, deployments, drds, decisionDefs, decisionHistory, batchesEngine, batchHistory, opLog]} extra={[{ id: 'annotate', label: 'mod.op.annotation', node: <IncidentAnnotation /> }, { id: 'bulk', label: 'mod.op2.bulkPriority', node: <BulkPriority /> }, { id: 'bulkRetry', label: 'mod.op3.bulkRetry', node: <BulkRetry /> }, { id: 'res', label: 'mod.op2.resources', node: <DeploymentResources /> }, { id: 'bin', label: 'mod.op2.binary', node: <BinaryVariable /> }]} />;
}

function IncidentAnnotation() {
  const { t } = useTranslation();
  const [id, setId] = useState('');
  const [note, setNote] = useState('');
  const act = useAction();
  return (
    <Section title={t('mod.op.annotation')}>
      <TextField label={t('fld.incidentId')} value={id} onChange={setId} required />
      <TextArea label={t('ops.note')} value={note} onChange={setNote} />
      <button type="button" disabled={!id || act.busy} onClick={() => void act.run((k) => http.put(`${E}/incident/${encodeURIComponent(id)}/annotation`, { annotation: note }, k).then(() => true)).then((ok) => ok && announce(t('common.saved')))}>
        {t('common.save')}
      </button>
      <ProblemAlert error={act.error} />
    </Section>
  );
}

// ------------------------------------------------------------------ tasklist: saved filters

const filters: ScreenDef = {
  id: 'filters',
  title: 'mod.op.filters',
  description: 'mod.op.filtersDesc',
  list: `${E}/filter`,
  listQuery: { resourceType: 'Task' },
  unpaged: true,
  columns: [c('name'), c('owner'), c('id')],
  rowKey: ['id'],
  detail: `${E}/filter/{id}`,
  sections: [{ id: 'tasks', label: 'mod.op.filterTasks', path: `${E}/filter/{id}/list`, columns: [c('name'), c('assignee'), c('created'), c('due')] }],
  create: { id: 'create', label: 'act.create', path: `${E}/filter/create`, fields: [f.text('name', undefined, true), f.text('owner'), f.json('query', 'fld.filterQuery', true), f.json('properties', 'fld.filterProperties')], noJson: true, defaults: () => ({ query: '{}', properties: '{"priority":0,"refresh":true,"variables":[]}' }), bodyExtra: { resourceType: 'Task' } },
  actions: [
    { id: 'edit', label: 'act.update', method: 'PUT', path: `${E}/filter/{id}`, fields: [f.text('name', undefined, true), f.text('owner'), f.json('query', 'fld.filterQuery', true), f.json('properties', 'fld.filterProperties')], noJson: true, bodyExtra: { resourceType: 'Task' }, defaults: (r) => ({ name: r?.name, owner: r?.owner, query: JSON.stringify(r?.query ?? {}), properties: JSON.stringify(r?.properties ?? {}) }) },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/filter/{id}`, noJson: true, danger: true },
  ],
};

const filterGrants: ScreenDef = {
  id: 'filterGrants',
  title: 'mod.op.filterSharing',
  description: 'mod.op.filterSharingDesc',
  list: `${E}/authorization`,
  listQuery: { resourceType: 5, ...MAX },
  unpaged: true,
  filters: [f.text('resourceId', 'fld.filterId')],
  columns: [c('resourceId', 'fld.filterId'), c('userId'), c('groupId'), c('permissions')],
  rowKey: ['id'],
  create: { id: 'share', label: 'act.share', path: `${E}/authorization/create`, fields: [f.text('resourceId', 'fld.filterId', true), f.text('userId'), f.text('groupId'), f.json('permissions', undefined, true)], noJson: true, defaults: () => ({ permissions: '["READ"]' }), bodyExtra: { type: 1, resourceType: 5 } },
  actions: [{ id: 'revoke', label: 'admin.revoke', method: 'DELETE', path: `${E}/authorization/{id}`, noJson: true, danger: true }],
};

export function TaskFiltersPage() {
  return <ModulePage title="mod.op.filtersTitle" screens={[filters, filterGrants]} extra={[{ id: 'board', label: 'mod.op3.board', node: <FilterBoard /> }, { id: 'search', label: 'mod.op3.search', node: <TaskSearch /> }, { id: 'url', label: 'mod.op3.attachUrl', node: <AttachmentByUrl /> }]} />;
}

// ------------------------------------------------------------------ admin

const RESOURCES = ['APPLICATION', 'USER', 'GROUP', 'GROUP_MEMBERSHIP', 'AUTHORIZATION', 'FILTER', 'PROCESS_DEFINITION', 'TASK', 'PROCESS_INSTANCE', 'DEPLOYMENT', 'DECISION_DEFINITION', 'TENANT', 'TENANT_MEMBERSHIP', 'BATCH', 'DECISION_REQUIREMENTS_DEFINITION', 'REPORT', 'DASHBOARD', 'OPERATION_LOG_CATEGORY', 'OPTIMIZE', 'HISTORIC_TASK', 'HISTORIC_PROCESS_INSTANCE', 'SYSTEM'];
const resOpts = RESOURCES.map((n, v) => ({ value: String(v), label: `opt.${n}` }));

const engineUsers: ScreenDef = {
  id: 'users',
  title: 'mod.op.users',
  list: `${E}/user`,
  listQuery: MAX,
  unpaged: true,
  filters: [f.text('id', 'fld.username'), f.text('firstNameLike', 'fld.firstName'), f.text('lastNameLike', 'fld.lastName'), f.text('memberOfGroup'), f.text('memberOfTenant')],
  columns: [c('id', 'fld.username'), c('firstName'), c('lastName'), c('email')],
  rowKey: ['id'],
  create: { id: 'create', label: 'act.create', path: `${E}/user/create`, fields: [f.text('profile.id', 'fld.username', true), f.text('profile.firstName', 'fld.firstName', true), f.text('profile.lastName', 'fld.lastName', true), f.text('profile.email', 'fld.email'), f.text('credentials.password', 'mod.op.newPassword', true, { sensitive: true })], noJson: true },
  actions: [
    { id: 'profile', label: 'act.update', method: 'PUT', path: `${E}/user/{id}/profile`, fields: [f.text('id', 'fld.username', true), f.text('firstName'), f.text('lastName'), f.text('email')], noJson: true, defaults: (r) => ({ id: r?.id, firstName: r?.firstName, lastName: r?.lastName, email: r?.email }) },
    { id: 'group', label: 'act.addToGroup', method: 'PUT', path: `${E}/group/{groupId}/members/{id}`, fields: [f.text('groupId', undefined, true, { lookup: `${E}/group` })], noJson: true },
    { id: 'ungroup', label: 'act.removeFromGroup', method: 'DELETE', path: `${E}/group/{groupId}/members/{id}`, fields: [f.text('groupId', undefined, true, { lookup: `${E}/group` })], noJson: true },
    { id: 'tenant', label: 'act.addToTenant', method: 'PUT', path: `${E}/tenant/{tenantId}/user-members/{id}`, fields: [f.text('tenantId', undefined, true, { lookup: `${E}/tenant` })], noJson: true },
    { id: 'untenant', label: 'act.removeFromTenant', method: 'DELETE', path: `${E}/tenant/{tenantId}/user-members/{id}`, fields: [f.text('tenantId', undefined, true, { lookup: `${E}/tenant` })], noJson: true },
    { id: 'password', label: 'act.resetPassword', method: 'PUT', path: `${E}/user/{id}/credentials`, fields: [f.text('password', 'mod.op.newPassword', true, { sensitive: true }), f.text('authenticatedUserPassword', 'mod.op.currentPassword', true, { sensitive: true })], noJson: true, hint: 'mod.op2.resetHint' },
    { id: 'unlock', label: 'act.unlockUser', path: `${E}/user/{id}/unlock`, noJson: true },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/user/{id}`, noJson: true, danger: true },
  ],
};

const engineGroups: ScreenDef = {
  id: 'groups',
  title: 'mod.op.groups',
  list: `${E}/group`,
  listQuery: MAX,
  unpaged: true,
  filters: [f.text('nameLike', 'fld.name'), f.text('type'), f.text('member')],
  columns: [c('id'), c('name'), c('type')],
  rowKey: ['id'],
  create: { id: 'create', label: 'act.create', path: `${E}/group/create`, fields: [f.text('id', 'fld.groupId', true), f.text('name', undefined, true), f.text('type')], noJson: true },
  actions: [
    { id: 'edit', label: 'act.update', method: 'PUT', path: `${E}/group/{id}`, fields: [f.text('id', 'fld.groupId', true), f.text('name', undefined, true), f.text('type')], noJson: true, defaults: (r) => ({ id: r?.id, name: r?.name, type: r?.type }) },
    { id: 'tenant', label: 'act.addToTenant', method: 'PUT', path: `${E}/tenant/{tenantId}/group-members/{id}`, fields: [f.text('tenantId', undefined, true, { lookup: `${E}/tenant` })], noJson: true },
    { id: 'untenant', label: 'act.removeFromTenant', method: 'DELETE', path: `${E}/tenant/{tenantId}/group-members/{id}`, fields: [f.text('tenantId', undefined, true, { lookup: `${E}/tenant` })], noJson: true },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/group/{id}`, noJson: true, danger: true },
  ],
};

const engineTenants: ScreenDef = {
  id: 'tenants',
  title: 'mod.op.tenants',
  list: `${E}/tenant`,
  listQuery: MAX,
  unpaged: true,
  columns: [c('id'), c('name')],
  rowKey: ['id'],
  create: { id: 'create', label: 'act.create', path: `${E}/tenant/create`, fields: [f.text('id', 'fld.tenantId', true), f.text('name', undefined, true)], noJson: true },
  actions: [
    { id: 'rename', label: 'act.update', method: 'PUT', path: `${E}/tenant/{id}`, fields: [f.text('id', 'fld.tenantId', true), f.text('name', undefined, true)], noJson: true, defaults: (r) => ({ id: r?.id, name: r?.name }) },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/tenant/{id}`, noJson: true, danger: true },
  ],
};

const authorizations: ScreenDef = {
  id: 'auth',
  title: 'mod.op.authorizations',
  description: 'mod.op.authorizationsDesc',
  list: `${E}/authorization`,
  listQuery: MAX,
  unpaged: true,
  filters: [{ key: 'resourceType', label: 'fld.resourceType', type: 'select', values: resOpts }, f.text('userIdIn', 'fld.userId'), f.text('groupIdIn', 'fld.groupId')],
  columns: [c('type', 'fld.authType', (r) => <Badge value={['GLOBAL', 'GRANT', 'REVOKE'][Number(r.type)] ?? String(r.type)} tone="info" />), c('resourceType'), c('resourceId'), c('userId'), c('groupId'), c('permissions')],
  rowKey: ['id'],
  create: { id: 'create', label: 'act.create', path: `${E}/authorization/create`, fields: [f.num('type', 'fld.authType', true), { key: 'resourceType', label: 'fld.resourceType', type: 'select', values: resOpts, validate: { required: true } }, f.text('resourceId', undefined, true), f.text('userId'), f.text('groupId'), f.json('permissions', undefined, true)], noJson: true, hint: 'mod.op.authTypeHint', defaults: () => ({ type: 1, resourceId: '*', permissions: '["ALL"]' }) },
  actions: [
    // The engine converts permission names against the body's resourceType (AuthorizationDto.update): without it the PUT fails.
    { id: 'edit', label: 'act.update', method: 'PUT', path: `${E}/authorization/{id}`, fields: [{ key: 'resourceType', label: 'fld.resourceType', type: 'select', values: resOpts, validate: { required: true } }, f.text('resourceId', undefined, true), f.text('userId'), f.text('groupId'), f.json('permissions', undefined, true)], noJson: true, hint: 'mod.op4.permissionsHint', defaults: (r) => ({ resourceType: r?.resourceType === undefined || r?.resourceType === null ? undefined : String(r.resourceType), resourceId: r?.resourceId, userId: r?.userId, groupId: r?.groupId, permissions: JSON.stringify(r?.permissions ?? []) }) },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/authorization/{id}`, noJson: true, danger: true },
  ],
};

const metrics: ScreenDef = {
  id: 'metrics',
  title: 'mod.op.metrics',
  description: 'mod.op.metricsDesc',
  list: `${E}/metrics`,
  listQuery: MAX,
  unpaged: true,
  filters: [f.text('name'), f.dt('startDate'), f.dt('endDate'), f.text('reporter')],
  columns: [c('timestamp'), c('name'), c('reporter'), c('value', 'fld.value')],
  rowKey: ['timestamp', 'name'],
};

const telemetry: ScreenDef = {
  id: 'telemetry',
  title: 'mod.op.diagnostics',
  description: 'mod.op.diagnosticsDesc',
  list: `${E}/telemetry/data`,
  unpaged: true,
  columns: [c('key', 'fld.field'), c('value', 'fld.value')],
  rowKey: ['key'],
};

export function EngineAdminPage() {
  return <ModulePage title="mod.op.adminTitle" description="mod.op.adminDesc" screens={[engineUsers, engineGroups, engineTenants, authorizations, metrics, telemetry]} extra={[{ id: 'chart', label: 'mod.op2.metricsChart', node: <MetricsChart /> }, { id: 'general', label: 'mod.op.systemInfo', node: <SystemInfo /> }]} />;
}

function SystemInfo() {
  const { t } = useTranslation();
  const v = useAsync(() => http.get<Row>(`${E}/version`), []);
  const eng = useAsync(() => http.get<Row[]>(`${E}/engine`), []);
  const tel = useAsync(() => http.get<Row>(`${E}/telemetry/configuration`), []);
  return (
    <Section title={t('mod.op.systemInfo')}>
      <ProblemAlert error={v.error ?? eng.error} />
      <KeyValues items={[[t('fld.version'), cell(v.data?.version)], [t('mod.op.engines'), (eng.data ?? []).map((x) => String(x.name)).join(', ')], [t('mod.op.telemetryEnabled'), cell(tel.data?.enableTelemetry)]]} />
      <Notice>{t('mod.op.telemetryOff')}</Notice>
    </Section>
  );
}

// ------------------------------------------------------------------ welcome, account, setup

export function Welcome() {
  const { t, i18n } = useTranslation();
  const { me, hasRole } = useAuth();
  const areas = NAV.filter((a) => hasRole(...a.roles));
  const links = config().welcomeLinks;
  return (
    <>
      <PageHeader title={t('mod.op.welcome', { name: me?.displayName ?? '' })} description={t('mod.op.welcomeDesc')} />
      <ul className="cards">
        {areas.map((a) => (
          <li key={a.id} className="card">
            <h2>{t(a.label)}</h2>
            <ul>{a.items.slice(0, 5).map((i) => <li key={i.to}><Link to={i.to}>{t(i.label)}</Link></li>)}</ul>
          </li>
        ))}
      </ul>
      {links.length > 0 && (
        <Section title={t('mod.op2.links')}>
          <ul>{links.map((l) => <li key={l.url}><a href={l.url} rel="noopener noreferrer">{i18n.language === 'hi' ? l.label.hi : l.label.en}</a></li>)}</ul>
        </Section>
      )}
      <PluginSlot point="dashboard.widget" />
      <Section title={t('mod.op.account')}>
        <KeyValues items={[[t('fld.userId'), me?.userId], [t('admin.tenantId'), me?.tenantId], [t('mod.op.roles'), me?.roles.join(', ')], [t('nav.seats'), me?.seats.map((s) => s.seatName).join(', ')]]} />
        <Link to="/account">{t('mod.op.manageAccount')}</Link>
      </Section>
    </>
  );
}

function AccountEdit({ id, profile, onSaved, onTenants }: { id: string; profile?: Row; onSaved: () => void; onTenants: () => void }) {
  const { t } = useTranslation();
  const [p, setP] = useState<Row | null>(null);
  const [tenant, setTenant] = useState('');
  const act = useAction();
  const cur = p ?? profile ?? {};
  const set = (k: string) => (v: string) => setP({ ...cur, [k]: v });
  const member = (add: boolean) => void act.run((k) => (add ? http.put(`${E}/tenant/${encodeURIComponent(tenant)}/user-members/${encodeURIComponent(id)}`, undefined, k) : http.del(`${E}/tenant/${encodeURIComponent(tenant)}/user-members/${encodeURIComponent(id)}`, undefined, k)).then(() => true)).then((ok) => ok && onTenants());
  return (
    <Section title={t('mod.op3.editProfile')}>
      <TextField label={t('fld.firstName')} value={String(cur.firstName ?? '')} onChange={set('firstName')} />
      <TextField label={t('fld.lastName')} value={String(cur.lastName ?? '')} onChange={set('lastName')} />
      <TextField label={t('fld.email')} type="email" value={String(cur.email ?? '')} onChange={set('email')} />
      <button type="button" disabled={!p || act.busy} onClick={() => void act.run((k) => http.put(`${E}/user/${encodeURIComponent(id)}/profile`, { ...cur, id }, k).then(() => true)).then((ok) => { if (ok) { setP(null); announce(t('common.saved')); onSaved(); } })}>{t('common.save')}</button>
      <TextField label={t('fld.tenantId')} value={tenant} onChange={setTenant} />
      <div className="toolbar">
        <button type="button" disabled={!tenant} onClick={() => member(true)}>{t('act.addToTenant')}</button>
        <button type="button" disabled={!tenant} onClick={() => member(false)}>{t('act.removeFromTenant')}</button>
      </div>
      <ProblemAlert error={act.error} />
    </Section>
  );
}

export function Account() {
  const { t } = useTranslation();
  const { me, mode, hasRole } = useAuth();
  const platform = hasRole('PLATFORM_ADMIN', 'PLATFORM_OPERATOR');
  const id = encodeURIComponent(me?.userId ?? '');
  const profile = useAsync(() => http.get<Row>(`${E}/user/${id}/profile`), [id], !!me && platform);
  const groups = useAsync(() => http.get<Row[]>(`${E}/group`, { member: me?.userId }), [id], !!me && platform);
  const tenants = useAsync(() => http.get<Row[]>(`${E}/tenant`, { userMember: me?.userId, includingGroupsOfUser: true }), [id], !!me && platform);
  const [cur, setCur] = useState('');
  const [next, setNext] = useState('');
  const act = useAction();
  return (
    <>
      <PageHeader title={t('mod.op.account')} />
      <ProblemAlert error={profile.error} onRetry={profile.reload} />
      {!platform && <KeyValues items={[[t('fld.userId'), me?.userId], [t('common.name'), me?.displayName], [t('mod.op.roles'), me?.roles.join(', ')]]} />}
      {platform && <><Section title={t('mod.op.profile')}>
        <KeyValues items={Object.entries(profile.data ?? {}).map(([k, v]) => [k, cell(v)])} />
      </Section>
      <AccountEdit id={me?.userId ?? ''} profile={profile.data} onSaved={profile.reload} onTenants={tenants.reload} />
      <Section title={t('mod.op.memberships')}>
        <KeyValues items={[[t('nav.groups'), (groups.data ?? []).map((g) => String(g.name ?? g.id)).join(', ')], [t('nav.tenants'), (tenants.data ?? []).map((g) => String(g.name ?? g.id)).join(', ')]]} />
      </Section>
      </>}
      <Section title={t('mod.op.changePassword')}>
        {mode === 'oidc' || !platform ? (
          <Notice>{t('mod.op.passwordAtIdp')}</Notice>
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void act.run((k) => http.put(`${E}/user/${id}/credentials`, { password: next, authenticatedUserPassword: cur }, k).then(() => true)).then((ok) => {
                if (ok) {
                  setCur('');
                  setNext('');
                  announce(t('common.saved'));
                }
              });
            }}
          >
            <TextField label={t('mod.op.currentPassword')} type="password" autoComplete="current-password" required value={cur} onChange={setCur} />
            <TextField label={t('mod.op.newPassword')} type="password" autoComplete="new-password" required value={next} onChange={setNext} />
            <button type="submit" disabled={act.busy}>{t('common.save')}</button>
            <ProblemAlert error={act.error} />
          </form>
        )}
      </Section>
    </>
  );
}

/** Tasks dashboard (Cockpit plugin "tasks"): counts by assignment state from the task query. */
export function TaskDashboard() {
  const { t } = useTranslation();
  const [drill, setDrill] = useState<Row | null>(null);
  const tasks = useAsync(() => http.get<Row[]>(`/engine-rest/task`, { ...drill, maxResults: 200 } as never), [JSON.stringify(drill)], !!drill);
  const count = (q: Row) => http.get<{ count: number }>(`${E}/task/count`, q as never).then((r) => r.count);
  const d = useAsync(async () => {
    const [open, assigned, unassigned, grouped] = await Promise.all([count({}), count({ assigned: true }), count({ unassigned: true }), http.get<Row[]>(`${E}/task/report/candidate-group-count`)]);
    return { open, assigned, unassigned, grouped };
  }, []);
  return (
    <>
      <PageHeader title={t('mod.op.taskDashboard')} />
      <ProblemAlert error={d.error} onRetry={d.reload} />
      {d.data && (
        <>
          <div className="toolbar" role="group" aria-label={t('mod.op.taskDashboard')}>
            <button type="button" onClick={() => setDrill({})}>{t('mod.op.openTasks')}: {d.data.open}</button>
            <button type="button" onClick={() => setDrill({ assigned: true })}>{t('mod.op.assigned')}: {d.data.assigned}</button>
            <button type="button" onClick={() => setDrill({ unassigned: true })}>{t('mod.op.unassigned')}: {d.data.unassigned}</button>
          </div>
          {d.data.grouped.length === 0 ? <Empty message={t('common.emptyNone')} /> : (
            <DataTable caption={t('mod.op.byGroup')} rows={d.data.grouped.map((x, i) => ({ ...x, i } as Row))} rowKey={(r) => String(r.i)} columns={[{ key: 'groupName', header: t('nav.groups'), render: (r: Row) => (r.groupName ? <button type="button" className="link" onClick={() => setDrill({ candidateGroup: String(r.groupName) })}>{String(r.groupName)}</button> : '—') }, { key: 'taskCount', header: t('batch.total'), render: (r: Row) => String(r.taskCount) }]} />
          )}
        </>
      )}
      {drill && (
        <>
          <ProblemAlert error={tasks.error} />
          <DataTable caption={t('mod.op.filterTasks')} rows={tasks.data ?? []} rowKey={(r: Row) => String(r.id)} columns={[{ key: 'name', header: t('common.name'), render: (r: Row) => <Link to={`/tasks/${encodeURIComponent(String(r.id))}`}>{String(r.name ?? r.id)}</Link> }, { key: 'assignee', header: t('fld.assignee'), render: (r: Row) => cell(r.assignee) }, { key: 'created', header: t('fld.created'), render: (r: Row) => cell(r.created) }]} />
        </>
      )}
      <p className="small muted">{fmtDate(new Date().toISOString())}</p>
    </>
  );
}
