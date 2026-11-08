// SPDX-License-Identifier: Apache-2.0
/**
 * Every Sangam API call the UI makes, reconciled with sangam-rest/api/sangam-api.yaml (OpenAPI 3.1, read 2026-11-04).
 * Every static path here was checked against the contract. [E] marks the unchanged Operaton REST API at /engine-rest.
 */
import { http, download, raw, type Page, type Query } from './client';
import * as A from './adapters';
import type * as T from './types';

const enc = encodeURIComponent;

export interface ListQuery extends Query {
  page?: number;
  size?: number;
  sort?: string;
  q?: string;
}

/** '-field' → sortBy=field&sortOrder=desc (contract list convention). */
function sortParams(sort?: string): Query {
  if (!sort) return {};
  const keys = sort.split(',').filter(Boolean);
  return { sortBy: keys.map((k) => k.replace(/^-/, '')).join(','), sortOrder: keys.map((k) => (k.startsWith('-') ? 'desc' : 'asc')).join(',') };
}

/** Inbox columns → the contract's task sort keys. */
const TASK_SORT: Record<string, string> = { received: 'created', created: 'created', slaDue: 'due', due: 'due', priority: 'priority', subject: 'name', name: 'name' };
function taskSort(sort?: string): string | undefined {
  const out = (sort ?? '').split(',').filter(Boolean).flatMap((k) => {
    const m = TASK_SORT[k.replace(/^-/, '')];
    return m ? [(k.startsWith('-') ? '-' : '') + m] : [];
  });
  return out.join(',') || undefined;
}

/**
 * P13: the query a tree selection adds to one history log, by what that log's API filters on. The operation log and
 * the called-instance list have none (the server answers 400 FILTER_UNSUPPORTED rather than ignore a filter).
 */
export function historyActivityFilter(kind: T.HistoryLogKind, at: T.ActivitySelection): Query {
  switch (kind) {
    case 'variables':
    case 'decisions':
    case 'external-tasks':
      return { activityInstanceId: at.activityInstanceId };
    case 'jobs':
      return { activityId: at.activityId };
    case 'user-tasks':
      return { taskDefinitionKey: at.activityId };
    default:
      return {};
  }
}

/** Whether a history log narrows to a tree selection (P13). */
export const historyFilterable = (kind: T.HistoryLogKind): boolean => kind !== 'audit' && kind !== 'called';

const draftIds = new Map<string, { id: string; etag: string | null }>();
const packageTags = new Map<string, string | null>();

export const api = {
  me: {
    get: async () => A.normMe(await http.get<Record<string, unknown>>('/me')),
    notifications: (q: ListQuery = {}) => http.page<T.Notification>('/me/notifications', q),
    markRead: (id: string) => http.post<void>(`/me/notifications/${enc(id)}/read`),
    delegations: () => http.get<T.Assignment[]>('/me/delegations'),
    requestLeave: (body: { seatId: string; substituteSeatId?: string; substitutePersonId?: string; from: string; to: string; reason: string }) =>
      http.post<T.Assignment>('/me/leave-requests', body),
  },

  // ------------------------------------------------------------ tasks
  tasks: {
    /** GET /tasks. Inbox sections map onto contract filters; `section` is also sent for the richer server. */
    list: async (q: ListQuery & { section?: T.TaskSection; packKey?: string; slaState?: string; seatId?: string; variableNames?: string[] }) => {
      const s = q.section;
      const mapped: Query = {
        page: q.page,
        size: q.size,
        businessKey: q.q,
        processDefinitionKey: q.packKey,
        section: s,
        slaState: q.slaState,
        mine: s === 'MY_SEAT' ? true : undefined,
        unassigned: s === 'POOL' ? true : undefined,
        state: s === 'ESCALATED' || s === 'RETURNED' ? s : undefined,
        seatId: q.seatId,
        variableNames: q.variableNames?.length ? q.variableNames : undefined, // P45 card variables
        ...sortParams(taskSort(q.sort)),
      };
      return A.mapPage(await http.page<Record<string, unknown>>('/tasks', mapped), A.normTask);
    },
    counts: () => http.get<Record<T.TaskSection, number | string>>('/tasks/counts'),
    get: async (id: string) => A.normTaskDetail(await http.get<Record<string, unknown>>(`/tasks/${enc(id)}`)),
    /** SL-37: the task's embedded/deployed form through the Sangam API (task users have no /engine-rest access). */
    form: (id: string) => http.get<T.TaskForm>(`/tasks/${enc(id)}/form`),
    claim: (id: string, key?: string) => http.post<void>(`/tasks/${enc(id)}/claim`, {}, key),
    unclaim: (id: string, key?: string) => http.post<void>(`/tasks/${enc(id)}/unclaim`, {}, key),
    bulkClaim: (ids: string[], key?: string) => http.post<T.BulkResult>('/tasks/bulk-claim', { ids }, key),
    bulkComplete: (ids: string[], action: T.TaskAction, remarks: string, key?: string) =>
      http.post<T.Batch>('/tasks/bulk-complete', { ids, action, remarks }, key).then(A.normBatch), // (D-34)
    /** CompleteTaskRequest {payloadHash, caseVersion, decision, remarks, formVersion, variables}; extras are UI proposals. */
    complete: (id: string, b: T.CompleteTaskRequest & { formVersion?: string }, key?: string) =>
      http.post<T.CompleteTaskResult>(
        `/tasks/${enc(id)}/complete`,
        {
          payloadHash: b.payloadHash,
          caseVersion: b.version,
          decision: b.action,
          remarks: b.remarks,
          formVersion: b.formVersion,
          variables: A.toVariableMap(b.variables),
          reasonCode: b.reasonCode,
          nextSeatId: b.nextSeatId,
          nextSeatReason: b.nextSeatReason,
        },
        key,
      ),
    /** ReturnTaskRequest {reason, reasonCode, targetActivityId, variables}; level/seat are UI proposals. */
    return: (id: string, b: T.ReturnTaskRequest & { targetActivityId?: string }, key?: string) =>
      http.post<T.CompleteTaskResult>(
        `/tasks/${enc(id)}/return`,
        {
          reason: b.remarks,
          reasonCode: b.reasonCodes.join(',') || undefined,
          targetActivityId: b.targetActivityId,
          targetSeatId: b.targetSeatId,
          targetLevel: b.targetLevel,
          payloadHash: b.payloadHash,
          caseVersion: b.version,
        },
        key,
      ),
    delegate: (id: string, body: T.DelegateTaskRequest, key?: string) => http.post<void>(`/tasks/${enc(id)}/delegate`, body, key),
    /** Form drafts: POST /forms/drafts, then PUT /forms/drafts/{id} with If-Match. */
    saveDraft: async (taskId: string, variables: Record<string, unknown>, formKey: string | undefined, formVersion: string | undefined, key?: string) => {
      const data = A.toVariableMap(variables);
      let d = draftIds.get(taskId);
      if (!d) {
        const existing = await http.page<{ draftId: string }>('/forms/drafts', { taskId, size: 1 }).catch(() => null);
        if (existing?.items[0]) {
          const g = await http.getTagged<{ draftId: string }>(`/forms/drafts/${enc(existing.items[0].draftId)}`);
          d = { id: g.body.draftId, etag: g.etag };
        }
      }
      if (!d) {
        const res = await raw('POST', '/forms/drafts', { body: { taskId, formKey, formVersion, ownerKind: 'SEAT', data }, idempotencyKey: key });
        const body = (await res.json()) as { draftId: string; updatedAt?: string };
        draftIds.set(taskId, { id: body.draftId, etag: res.headers.get('ETag') });
        return { savedAt: body.updatedAt ?? new Date().toISOString() };
      }
      const res = await raw('PUT', `/forms/drafts/${enc(d.id)}`, {
        body: { taskId, formKey, formVersion, ownerKind: 'SEAT', data },
        idempotencyKey: key,
        headers: d.etag ? { 'If-Match': d.etag } : {},
      });
      const body = (await res.json()) as { updatedAt?: string };
      draftIds.set(taskId, { id: d.id, etag: res.headers.get('ETag') });
      return { savedAt: body.updatedAt ?? new Date().toISOString() };
    },
    diff: (id: string, from: number, to: number) => http.get<T.FieldChange[]>(`/tasks/${enc(id)}/diff`, { from, to }),
    candidates: (id: string, action: T.TaskAction) => http.get<T.CandidateSeat[]>(`/tasks/${enc(id)}/candidates`, { action }),
    attachments: async (id: string) => ((await http.get<Record<string, unknown>[]>(`/tasks/${enc(id)}/attachments`)) ?? []).map(A.normDocument),
    upload: (id: string, file: File, key?: string) => {
      const f = new FormData();
      f.append('file', file, file.name);
      return http.upload<Record<string, unknown>>(`/tasks/${enc(id)}/attachments`, f, key).then(A.normDocument);
    },
    downloadAttachment: (_taskId: string, docId: string, name: string) => download(`/documents/${enc(docId)}/content`, undefined, name),
    comments: async (id: string) => ((await http.get<Record<string, unknown>[]>(`/tasks/${enc(id)}/comments`)) ?? []).map(A.normComment),
    addComment: (id: string, text: string, key?: string) =>
      http.post<Record<string, unknown>>(`/tasks/${enc(id)}/comments`, { message: text }, key).then(A.normComment),
    lifecycle: (id: string) =>
      http.get<{ at: string; event: string; by?: string; seatName?: string; actedFor?: string }[]>(`/tasks/${enc(id)}/lifecycle`), // (FC-52)
  },

  // ------------------------------------------------------------ start catalogue, processes, cases, decisions
  catalogue: {
    list: () => http.get<{ key: string; name: string; kind: 'PROCESS' | 'CASE'; formKey?: string; description?: string }[]>('/start-catalogue'),
  },
  processes: {
    definitions: (q: ListQuery & { latest?: boolean; key?: string }) => http.page<T.ProcessDefinition>('/process-definitions', { ...q, key: q.key ?? q.q }),
    definition: (id: string) => http.get<T.ProcessDefinition>(`/process-definitions/${enc(id)}`),
    xml: async (id: string) => ({ bpmn20Xml: A.xmlOf(await http.get<unknown>(`/process-definitions/${enc(id)}/xml`), 'bpmn20Xml') }),
    /** /analytics/activities: active = runtime tokens, count = history heatmap. */
    statistics: async (id: string, mode: 'runtime' | 'history', q: Query = {}, definitionKey?: string) =>
      A.normActivityStats(id, await http.get<Record<string, unknown>[]>('/analytics/activities', { processDefinitionKey: definitionKey, from: q.from, to: q.to }), mode),
    suspendDefinition: (id: string, suspended: boolean, reason: string, includeInstances: boolean) =>
      http.post<void>(`/process-definitions/${enc(id)}/suspension`, { suspended, reason, includeInstances }),
    start: (body: { key: string; businessKey?: string; variables?: Record<string, unknown> }, key?: string) =>
      http.post<Record<string, unknown>>('/process-instances', { ...body, variables: A.toVariableMap(body.variables) }, key).then(A.normInstance),
    instances: async (q: ListQuery & T.InstanceQuery & { history?: boolean }) => {
      const query: Query = {
        page: q.page,
        size: q.size,
        key: q.definitionKey,
        processDefinitionId: q.definitionId,
        businessKey: q.businessKey,
        state: q.state === 'CANCELED' ? 'CANCELLED' : q.state === 'INCIDENT' ? undefined : q.state,
        withIncidents: q.state === 'INCIDENT' ? true : q.withIncidents,
        activityId: q.activityId,
        startedAfter: q.startedAfter,
        startedBefore: q.startedBefore,
      };
      const path = q.history ? '/history/process-instances' : '/process-instances'; // both
      if (q.history) {
        query.processDefinitionKey = q.definitionKey;
        query.processInstanceId = undefined;
      }
      return A.mapPage(await http.page<Record<string, unknown>>(path, query), A.normInstance);
    },
    instance: async (id: string) => A.normInstance(await http.get<Record<string, unknown>>(`/process-instances/${enc(id)}`)),
    activityTree: (id: string) => http.get<T.ActivityInstance>(`/process-instances/${enc(id)}/activity-tree`),
    instanceStatistics: async (id: string) => A.statsFromTree(await http.get<T.ActivityInstance>(`/process-instances/${enc(id)}/activity-tree`)), // derived from the activity tree
    /** P13: `activityInstanceId` narrows to the variables of that activity-instance scope. */
    variables: async (id: string, activityInstanceId?: string) =>
      A.variableEntries(await http.get<unknown>(`/process-instances/${enc(id)}/variables`, activityInstanceId ? { activityInstanceId } : undefined)),
    /** P14: historic activity instances of one process instance, optionally by state (running, completed, canceled). */
    activityHistory: (id: string, q: ListQuery & { state?: T.ActivityState; activityInstanceId?: string } = {}) =>
      http.page<T.HistoricActivityInstance>('/history/activity-instances', { page: q.page, size: q.size, processInstanceId: id, state: q.state, activityInstanceId: q.activityInstanceId }),
    /** Incidents of one instance (open and resolved), optionally of one activity (P13). */
    incidentHistory: (id: string, q: ListQuery & { activityId?: string } = {}) =>
      http.page<T.HistoricIncident>('/history/incidents', { page: q.page, size: q.size, processInstanceId: id, activityId: q.activityId }),
    /** PUT VariableMap; the reason travels as ?reason= like other audited commands. */
    setVariable: (id: string, v: { name: string; type: string; value: unknown; reason: string }, key?: string) =>
      http.put<void>(`/process-instances/${enc(id)}/variables?reason=${enc(v.reason)}`, { [v.name]: { type: v.type, value: v.value } }, key),
    /**
     * History logs of one instance. P13 `at` (a tree selection) narrows each log by what its API filters on: the
     * activity instance for variables, decisions and external tasks, the activity for jobs, the task definition for
     * user tasks; the operation log and called instances have no activity filter (see `filterable`).
     */
    history: async (id: string, kind: T.HistoryLogKind, q: ListQuery = {}, at?: T.ActivitySelection) => {
      const map: Record<T.HistoryLogKind, string> = {
        audit: '/history/operation-log',
        jobs: '/history/job-log',
        'external-tasks': '/history/external-task-log',
        variables: '/history/variables',
        'user-tasks': '/history/tasks',
        decisions: '/history/decisions',
        called: '/process-instances',
      };
      const query: Query = kind === 'called' ? { ...q, superProcessInstanceId: id } : { ...q, processInstanceId: id };
      if (at && !at.root) Object.assign(query, historyActivityFilter(kind, at));
      return A.mapPage(await http.page<Record<string, unknown>>(map[kind], query), (r) => A.normHistory(kind, r));
    },
    links: async (id: string) => ((await http.get<Record<string, unknown>[]>(`/process-instances/${enc(id)}/links`)) ?? []).map(A.normLink),
    cancelPreview: (id: string, mode: 'BUSINESS' | 'TECHNICAL') => http.get<T.ConsequencePreview>(`/process-instances/${enc(id)}/cancel-preview`, { mode }),
    /** business cancel = POST /cancel {reason}; technical delete = DELETE ?reason= (A-4). */
    cancel: (id: string, mode: 'BUSINESS' | 'TECHNICAL', reason: string, key?: string) =>
      mode === 'BUSINESS'
        ? http.post<void>(`/process-instances/${enc(id)}/cancel`, { reason }, key)
        : http.del<void>(`/process-instances/${enc(id)}`, { reason }, key),
    suspend: (id: string, suspended: boolean, reason: string, redateDeadlines?: boolean) =>
      http.post<void>(`/process-instances/${enc(id)}/suspension`, { suspended, reason, redateDeadlines }), // (D-32)
    modifyPreview: (id: string, instructions: T.ModificationInstruction[]) =>
      http.post<T.ConsequencePreview>(`/process-instances/${enc(id)}/modify/preview`, { instructions }),
    modify: (id: string, instructions: T.ModificationInstruction[], reason: string, key?: string) =>
      http.post<void>(`/process-instances/${enc(id)}/modify`, { instructions, reason }, key),
    moveAcross: (id: string, body: { fromActivityInstanceId: string; targetInstanceId: string; targetActivityId: string; reason: string }, key?: string) =>
      http.post<void>(`/process-instances/${enc(id)}/cross-boundary-move`, body, key), // (FC-17)
    /** InjectionRequest → InjectionResult (FC-15). */
    inject: (id: string, body: { kind: 'USER_TASK' | 'EMBEDDED_SUBPROCESS' | 'PARALLEL_USER_TASK'; name: string; afterActivityId: string; assigneeSeatId?: string; reason: string }, key?: string) =>
      http.post<{ packageId?: string; derivedDefinitionId?: string; injectedActivityIds?: string[]; state?: string }>(
        `/process-instances/${enc(id)}/inject`,
        { kind: body.kind, name: body.name, afterActivityId: body.afterActivityId, assignee: body.assigneeSeatId, reason: body.reason },
        key,
      ),
    /** FC-01 ad-hoc sub-process. */
    adHoc: (id: string, adHocId: string) => http.get<Record<string, unknown>>(`/process-instances/${enc(id)}/ad-hoc/${enc(adHocId)}`),
    adHocStart: (id: string, adHocId: string, activityId: string, key?: string) =>
      http.post<Record<string, unknown>>(`/process-instances/${enc(id)}/ad-hoc/${enc(adHocId)}/start`, { activityId }, key),
    adHocComplete: (id: string, adHocId: string, reason: string, key?: string) =>
      http.post<Record<string, unknown>>(`/process-instances/${enc(id)}/ad-hoc/${enc(adHocId)}/complete?reason=${enc(reason)}`, {}, key),
    /** R43 resubmit with version history. */
    resubmit: (id: string, body: { variables: Record<string, unknown>; reason?: string; expectedVersion?: number }, key?: string) =>
      http.post<Record<string, unknown>>(`/process-instances/${enc(id)}/resubmit`, { ...body, variables: A.toVariableMap(body.variables) }, key).then(A.normCaseVersion),
    versions: async (id: string) => ((await http.get<Record<string, unknown>[]>(`/process-instances/${enc(id)}/versions`)) ?? []).map(A.normCaseVersion),
    restart: (body: { definitionId: string; instanceIds: string[]; startBeforeActivityId: string; initialVariables: boolean; reason: string }, key?: string) =>
      http
        .post<Record<string, unknown>>(
          '/ops/restarts',
          { processDefinitionId: body.definitionId, processInstanceIds: body.instanceIds, startBeforeActivityIds: [body.startBeforeActivityId], initialVariables: body.initialVariables, reason: body.reason },
          key,
        )
        .then(A.normBatch),
    message: (body: { name: string; businessKey?: string; processInstanceId?: string; branchKey?: string; variables?: Record<string, unknown> }, key?: string) =>
      http.post<void>(
        '/messages',
        {
          messageName: body.name,
          businessKey: body.businessKey,
          processInstanceId: body.processInstanceId,
          correlationKeys: body.branchKey ? A.toVariableMap({ branchKey: body.branchKey }) : undefined,
          variables: A.toVariableMap(body.variables),
        },
        key,
      ), // (D-35 via correlationKeys)
    signal: (body: { name: string; variables?: Record<string, unknown>; tenantOnly?: boolean }, key?: string) =>
      http.post<void>('/signals', { name: body.name, signalName: body.name, variables: A.toVariableMap(body.variables) }, key),
  },
  cases: {
    list: (q: ListQuery & { state?: string }) =>
      http.page<T.CaseInstance>('/case-instances', { page: q.page, size: q.size, businessKey: q.q, active: q.state ? q.state === 'ACTIVE' : undefined, state: q.state }),
    get: (id: string) => http.get<T.CaseInstance>(`/case-instances/${enc(id)}`),
    start: (body: { key: string; businessKey?: string; variables?: Record<string, unknown> }, key?: string) =>
      http.post<T.CaseInstance>('/case-instances', { ...body, variables: A.toVariableMap(body.variables) }, key),
    /** CaseExecutionCommand {action: manualStart|complete|disable|reenable|terminate, reason}. */
    planItemAction: (id: string, planItemId: string, action: 'manual-start' | 'complete' | 'disable' | 'reenable' | 'terminate', reason: string) =>
      http.post<void>(`/case-instances/${enc(id)}/executions/${enc(planItemId)}/commands`, { action: action === 'manual-start' ? 'manualStart' : action, reason }),
    reactivate: (id: string, reason: string, key?: string) => http.post<T.CaseInstance>(`/case-instances/${enc(id)}/reactivation`, { reason }, key), // (FC-59)
    documents: async (id: string) => A.mapPage(await http.page<Record<string, unknown>>('/documents', { caseInstanceId: id, size: 100 }), A.normDocument).items,
    comments: (id: string) => http.get<T.Comment[]>(`/case-instances/${enc(id)}/comments`),
    addComment: (id: string, text: string) => http.post<T.Comment>(`/case-instances/${enc(id)}/comments`, { text }),
    participants: (id: string) => http.get<{ id: string; name: string; role: string; external?: boolean }[]>(`/case-instances/${enc(id)}/participants`),
  },
  decisions: {
    definitions: (q: ListQuery) => http.page<T.DecisionDefinition>('/decision-definitions', { ...q, key: q.q }),
    xml: async (id: string) => ({ dmnXml: A.xmlOf(await http.get<unknown>(`/decision-definitions/${enc(id)}/xml`), 'dmnXml') }),
    instances: (q: ListQuery & { decisionKey?: string; processInstanceId?: string }) => http.page<T.DecisionInstance>('/history/decisions', q),
    evaluate: (key: string, variables: Record<string, unknown>) =>
      http.post<{ outputs: Record<string, unknown>[] }>(`/decisions/${enc(key)}/evaluate`, { variables: A.toVariableMap(variables) }),
  },

  // ------------------------------------------------------------ deployments, packages, modeller (R43, R69)
  deployments: {
    list: (q: ListQuery) => http.page<T.Deployment>('/deployments', { ...q, name: q.q }),
    create: (name: string, files: { name: string; content: string; type: string }[], key?: string) => {
      const f = new FormData();
      f.append('name', name);
      f.append('target', 'sandbox');
      for (const x of files) f.append('files', new Blob([x.content], { type: x.type }), x.name);
      return http.upload<T.Deployment>('/deployments', f, key);
    },
  },
  packages: {
    list: async (q: ListQuery & { state?: T.PackageState }) => A.mapPage(await http.page<Record<string, unknown>>('/packages', q), A.normPackage),
    get: async (id: string) => {
      const r = await http.getTagged<Record<string, unknown>>(`/packages/${enc(id)}`); // with ETag
      packageTags.set(id, r.etag);
      return A.normPackage(r.body);
    },
    create: (body: { key: string; name: string }) => http.post<Record<string, unknown>>('/packages', body).then(A.normPackage),
    /** PUT /packages/{id} {resources: {fileName: content}} with If-Match (optimistic concurrency). */
    saveResources: async (id: string, artifacts: T.ModelArtifact[], name?: string) => {
      const resources = Object.fromEntries(artifacts.map((a) => [A.fileName(a), a.content]));
      const tag = packageTags.get(id);
      const res = await raw('PUT', `/packages/${enc(id)}`, { body: { name, resources }, headers: tag ? { 'If-Match': tag } : {} });
      packageTags.set(id, res.headers.get('ETag'));
      return A.normPackage((await res.json()) as Record<string, unknown>);
    },
    validate: (id: string) => http.post<T.ValidationIssue[]>(`/packages/${enc(id)}/validate`),
    diff: async (id: string) => A.normPackageDiff(await http.get<unknown>(`/packages/${enc(id)}/diff`)),
    history: async (id: string) => {
      const p = (await http.get<{ history?: { action?: string; state?: string; at?: string; by?: string; actor?: string; revision?: number; packageHash?: string }[]; revision?: number }>(`/packages/${enc(id)}`)) ?? {};
      return (p.history ?? []).map((h) => ({ version: String(h.revision ?? p.revision ?? ''), state: (h.state ?? h.action ?? '') as T.PackageState, at: h.at ?? '', by: h.by ?? h.actor ?? '', hash: h.packageHash })); // Package.history
    },
    /** PackageTransitionRequest {action, reason, environment, activateAt}. */
    transition: (id: string, action: 'submit' | 'approve' | 'reject' | 'deploy' | 'retire', body: { reason?: string; effectiveFrom?: string; tenantId?: string }, key?: string) =>
      http.post<Record<string, unknown>>(`/packages/${enc(id)}/transitions`, { action, reason: body.reason, environment: body.tenantId, activateAt: body.effectiveFrom }, key).then(A.normPackage),
    runTests: (id: string) => http.post<{ name: string; passed: boolean; message?: string; durationMs: number }[]>(`/packages/${enc(id)}/tests/run`), // (R80)
    gitStatus: (id: string) => http.get<{ remote?: string; branch?: string; ahead: number; behind: number; lastSync?: string; conflicts: string[] }>(`/packages/${enc(id)}/git`), // (R69)
    gitConfigure: (id: string, body: { remote: string; branch: string; credentialRef: string; provider: string }) => http.put<void>(`/packages/${enc(id)}/git`, body),
    gitSync: (id: string, direction: 'pull' | 'push', message?: string) =>
      http.post<{ ahead: number; behind: number; conflicts: string[] }>(`/packages/${enc(id)}/git/${direction}`, { message }),
    comments: (id: string) => http.get<T.Comment[]>(`/packages/${enc(id)}/comments`),
    addComment: (id: string, text: string, elementId?: string) => http.post<T.Comment>(`/packages/${enc(id)}/comments`, { text, elementId }),
    assess: (files: File[]) => {
      const f = new FormData();
      files.forEach((x) => f.append('files', x, x.name));
      return http.upload<{ file: string; element?: string; construct: string; classification: 'SUPPORTED' | 'MAPPED' | 'PATTERN' | 'REJECTED'; note?: string }[]>('/packages/assessment', f); // (EC-21)
    },
  },
  forms: {
    get: async (key: string, version?: number) => A.normForm(await http.get<Record<string, unknown>>(`/forms/${enc(key)}`, { version })),
    validate: (key: string, data: Record<string, unknown>) =>
      http.post<{ valid: boolean; errors?: { field?: string; message: string }[] }>(`/forms/${enc(key)}/validate`, { data: A.toVariableMap(data) }),
  },

  // ------------------------------------------------------------ operations (R63, R64, O01–O09)
  ops: {
    dashboard: () => http.get<T.OpsDashboard>('/ops/dashboard'),
    outboxStats: () => http.get<Record<string, unknown>>('/ops/outbox/stats'),
    incidents: async (q: ListQuery & { type?: string; owner?: string }) => A.mapPage(await http.page<Record<string, unknown>>('/ops/incidents', q), A.normIncident),
    incident: async (id: string) => A.normIncident(await http.get<Record<string, unknown>>(`/ops/incidents/${enc(id)}`)),
    incidentPreview: (id: string, action: 'retry' | 'skip' | 'cancel') => http.get<T.ConsequencePreview>(`/ops/incidents/${enc(id)}/preview`, { action }),
    /** retry / resolve: IncidentAction {reason, retries, annotation}; claim / note have their own routes. */
    incidentAction: (id: string, action: 'retry' | 'claim' | 'note' | 'resolve', body: { reason?: string; note?: string; retries?: number }, key?: string) =>
      action === 'retry' || action === 'resolve'
        ? http.post<void>(`/ops/incidents/${enc(id)}/${action}`, { reason: body.reason ?? '', retries: body.retries, annotation: body.note }, key)
        : http.post<void>(`/ops/incidents/${enc(id)}/${action}`, body, key),
    bulkRetry: (ids: string[], reason: string, key?: string) =>
      http.post<Record<string, unknown>>('/ops/batches', { type: 'RETRY', filter: { processInstanceIds: ids }, reason }, key).then(A.normBatch), // as a RETRY batch
    batches: async (q: ListQuery & { status?: T.BatchStatus }) =>
      A.mapPage(await http.page<Record<string, unknown>>('/ops/batches', { page: q.page, size: q.size, state: q.status === 'CANCELED' ? 'CANCELLED' : q.status }), A.normBatch),
    batch: async (id: string) => A.normBatch(await http.get<Record<string, unknown>>(`/ops/batches/${enc(id)}`)),
    batchItems: async (id: string, q: ListQuery & { status?: string }) =>
      A.mapPage(await http.page<Record<string, unknown>>(`/ops/batches/${enc(id)}/items`, { page: q.page, size: q.size, state: q.status }), A.normBatchItem),
    batchPreview: async (b: T.BatchRequest) =>
      A.previewFromBatch(await http.post<Record<string, unknown>>('/ops/batches/preview', { type: A.toBatchType(b.type), filter: A.toBatchFilter(b.query), params: b.payload && A.toVariableMap(b.payload), reason: b.reason || 'preview' })),
    createBatch: (b: T.BatchRequest, key?: string) =>
      http
        .post<Record<string, unknown>>('/ops/batches', { type: A.toBatchType(b.type), filter: A.toBatchFilter(b.query), params: b.payload && A.toVariableMap(b.payload), reason: b.reason }, key)
        .then(A.normBatch),
    /** BatchControlRequest {action: PAUSE|RESUME|CANCEL|APPROVE}; "reject" is a CANCEL with the reviewer's reason. */
    batchAction: (id: string, action: 'pause' | 'resume' | 'cancel' | 'approve' | 'reject', reason?: string) =>
      http.post<Record<string, unknown>>(`/ops/batches/${enc(id)}/control`, { action: action === 'reject' ? 'CANCEL' : action.toUpperCase(), reason: reason ?? '' }).then(A.normBatch),
    jobs: async (q: ListQuery & { failed?: boolean; type?: string }) =>
      A.mapPage(await http.page<Record<string, unknown>>('/ops/jobs', { page: q.page, size: q.size, withException: q.failed || undefined, timers: q.type === 'timer' || undefined, type: q.type }), A.normJob),
    jobSummary: () => http.get<T.JobSummary>('/ops/jobs/summary'),
    /** JobRetriesRequest {retries, reason}; dueDate is a UI proposal. */
    jobRetry: (id: string, retries: number, reason: string, dueDate?: string) => http.post<void>(`/ops/jobs/${enc(id)}/retries`, { retries, reason, dueDate }),
    jobExecute: (id: string, reason: string) => http.post<void>(`/ops/jobs/${enc(id)}/execute?reason=${enc(reason)}`),
    jobStacktrace: (id: string) => http.get<string>(`/ops/jobs/${enc(id)}/stacktrace`),
    outbox: async (q: ListQuery & { status?: string; destination?: string }) =>
      A.mapPage(await http.page<Record<string, unknown>>('/ops/outbox', { page: q.page, size: q.size, state: q.status, destination: q.destination }), A.normOutbox),
    outboxAction: (id: string, action: 'requeue' | 'skip', reason: string, key?: string) => http.post<void>(`/ops/outbox/${enc(id)}/${action}`, { reason }, key),
    inbox: (q: ListQuery & { status?: string }) => http.page<T.InboxEntry>('/ops/inbox', q),
    reconciliation: async (q: ListQuery & { status?: T.ReconStatus; owner?: string; open?: boolean }) =>
      A.mapPage(
        await http.page<Record<string, unknown>>('/ops/reconciliation', { page: q.page, size: q.size, kind: q.status, state: q.open ? 'OPEN' : undefined, owner: q.owner, ...sortParams(q.sort) }),
        A.normRecon,
      ),
    reconItem: async (id: string) => A.normRecon(await http.get<Record<string, unknown>>(`/ops/reconciliation/${enc(id)}`)),
    reconClaim: (id: string) => http.post<void>(`/ops/reconciliation/${enc(id)}/claim`),
    reconLookup: (id: string) => http.post<{ result: string; at: string }>(`/ops/reconciliation/${enc(id)}/lookup`),
    reconEvidence: (id: string, kind: string, file: File, key?: string) => {
      const f = new FormData();
      f.append('kind', kind);
      f.append('file', file, file.name);
      return http.upload<void>(`/ops/reconciliation/${enc(id)}/evidence`, f, key);
    },
    /** ReconciliationResolveRequest {action, evidence (text), result, reason}. */
    reconResolve: (id: string, b: { resolution: 'CONFIRM_SUCCESS' | 'RETRY_SAME_OPERATION' | 'MARK_FAILED'; reason: string; result?: Record<string, unknown>; evidence: string }, key?: string) =>
      http
        .post<Record<string, unknown>>(`/ops/reconciliation/${enc(id)}/resolve`, { action: b.resolution, evidence: b.evidence, result: b.result && A.toVariableMap(b.result), reason: b.reason }, key)
        .then((r) => ({ state: String(r.state ?? ''), fourEyesPending: r.state === 'OPEN' })),
    workers: () => http.get<T.WorkerInfo[]>('/ops/workers'),
    topics: () => http.get<T.TopicInfo[]>('/ops/topics'),
    topicPause: (name: string, paused: boolean, reason: string) => http.post<void>(`/ops/topics/${enc(name)}/${paused ? 'pause' : 'resume'}`, { reason }),
    cleanup: async () => A.normCleanup(await http.get<Record<string, unknown>>('/ops/cleanup')),
    cleanupRun: (reason: string) => http.post<void>(`/ops/cleanup?reason=${enc(reason)}`),
    cleanupTtl: (key: string, ttlDays: number, reason: string) => http.put<void>(`/ops/cleanup/ttl/${enc(key)}`, { ttlDays, reason }),
    auditLog: async (q: ListQuery & { actor?: string; operation?: string }) =>
      A.mapPage(await http.page<Record<string, unknown>>('/history/operation-log', { page: q.page, size: q.size, assignee: q.actor, operation: q.operation }), (r) => A.normHistory('audit', r)),
    notificationFailures: (q: ListQuery) =>
      http.page<{ id: string; at: string; channel: string; reason: string; templateId?: string; businessKey?: string; recipientMasked: string }>('/ops/notification-failures', q),
    resendNotification: (id: string, reason: string) => http.post<void>(`/ops/notification-failures/${enc(id)}/resend`, { reason }),
  },
  migration: {
    generate: (sourceDefinitionId: string, targetDefinitionId: string) =>
      http.post<T.MigrationPlan>('/ops/migrations/plan', { sourceDefinitionId, targetDefinitionId, mapEqualActivities: true }),
    /** the plan endpoint validates the given instructions and returns valid / validationErrors. */
    validate: async (plan: T.MigrationPlan): Promise<T.MigrationValidation> => {
      const r = await http.post<T.MigrationPlan & { valid?: boolean; validationErrors?: string[] }>('/ops/migrations/plan', { ...plan, mapEqualActivities: false });
      if ((r as unknown as T.MigrationValidation).instructionReports) return r as unknown as T.MigrationValidation;
      return { valid: !!r.valid, instructionReports: (r.validationErrors ?? []).map((e) => ({ instruction: { sourceActivityIds: [], targetActivityIds: [] }, failures: [e] })) };
    },
    /** count through the batch preview with type MIGRATE. */
    count: async (plan: T.MigrationPlan, query: T.InstanceQuery) => {
      const p = await http.post<{ count?: number; capped?: boolean }>('/ops/batches/preview', {
        type: 'MIGRATE',
        filter: A.toBatchFilter({ definitionId: plan.sourceDefinitionId, ...query }),
        params: A.toVariableMap({ targetDefinitionId: plan.targetDefinitionId }),
        reason: 'preview',
      });
      return { count: Number(p.count ?? 0), capped: p.capped };
    },
    execute: (b: { plan: T.MigrationPlan; query: T.InstanceQuery; reason: string; preHook?: string; postModification?: T.ModificationInstruction[] }, key?: string) =>
      http
        .post<Record<string, unknown>>(
          '/ops/migrations/execute',
          {
            plan: b.plan,
            processInstanceIds: b.query.instanceIds,
            filter: b.query.instanceIds ? undefined : A.toBatchFilter({ definitionId: b.plan.sourceDefinitionId, ...b.query }),
            reason: b.reason,
            preHook: b.preHook,
            postModification: b.postModification, // (FC-18 1→N / N→1)
          },
          key,
        )
        .then(A.normBatch),
  },
  debug: {
    status: () => http.get<{ enabled: boolean; profile: string; sandboxTenant?: string }>('/debug/status'), // (R80)
    breakpoints: (instanceId: string) => http.get<string[]>(`/debug/instances/${enc(instanceId)}/breakpoints`),
    setBreakpoints: (instanceId: string, activityIds: string[]) => http.put<void>(`/debug/instances/${enc(instanceId)}/breakpoints`, { activityIds }),
    step: (instanceId: string) => http.post<{ executed?: string }>(`/debug/instances/${enc(instanceId)}/step`),
    fireTimer: (_instanceId: string, jobId: string) => http.post<void>(`/ops/jobs/${enc(jobId)}/execute?reason=${enc('debugger: fire timer')}`),
    timers: async (instanceId: string) =>
      A.mapPage(await http.page<Record<string, unknown>>('/ops/jobs', { processInstanceId: instanceId, timers: true, size: 100 }), A.normJob).items,
  },

  // ------------------------------------------------------------ admin (A01–A11, IX.5, VI.4)
  admin: {
    tenants: (q: ListQuery) => http.page<T.Tenant>('/admin/tenants', q),
    saveTenant: (t: Partial<T.Tenant>) => (t.id ? http.put<T.Tenant>(`/admin/tenants/${enc(t.id)}`, t) : http.post<T.Tenant>('/admin/tenants', t)),
    orgTree: async (asOf?: string) => A.toTree((await http.page<T.OrgUnit>('/admin/org-units', { activeOn: asOf, includeInactive: true, size: 500 })).items),
    saveOrgUnit: (u: Partial<T.OrgUnit>) => (u.id ? http.put<T.OrgUnit>(`/admin/org-units/${enc(u.id)}`, u) : http.post<T.OrgUnit>('/admin/org-units', u)),
    moveOrgUnit: (id: string, parentId: string, effectiveFrom: string, reason: string) =>
      http.put<T.OrgUnit>(`/admin/org-units/${enc(id)}?reason=${enc(reason)}`, { parentId, validFrom: effectiveFrom }), // PUT with new parent, // effective-dated
    importOrg: (file: File) => {
      const f = new FormData();
      f.append('file', file, file.name);
      return http.upload<{ created: number; updated: number; errors: string[] }>('/admin/org-units/import', f);
    },
    seats: (q: ListQuery & { orgUnitId?: string }) => http.page<T.Seat>('/admin/seats', q),
    assignments: (q: ListQuery & { seatId?: string; personId?: string; kind?: T.SeatHoldingKind; asOf?: string }) =>
      http.page<T.Assignment>('/admin/assignments', { ...q, activeOn: q.asOf, asOf: undefined }),
    assignmentImpact: (a: Partial<T.Assignment>) => http.post<T.AssignmentImpact>('/admin/assignments/impact', a),
    saveAssignment: (a: Partial<T.Assignment>, key?: string) => http.post<T.Assignment>('/admin/assignments', a, key),
    endAssignment: (id: string, validTo: string, reason: string) => http.post<void>(`/admin/assignments/${enc(id)}/end`, { validTo, endDate: validTo, reason }),
    approveAssignment: (id: string, approve: boolean, reason: string) => http.post<void>(`/admin/assignments/${enc(id)}/${approve ? 'approve' : 'reject'}`, { reason }),
    /** Delegation-of-powers DMN tables (A04): the contract's /admin/delegations is person-to-person delegation. */
    delegationTables: (q: ListQuery) => http.page<T.DelegationTable>('/admin/power-tables', q),
    delegationTable: (id: string) => http.get<T.DelegationTable>(`/admin/power-tables/${enc(id)}`),
    saveDelegationTable: (d: Partial<T.DelegationTable>) =>
      d.id ? http.put<T.DelegationTable>(`/admin/power-tables/${enc(d.id)}`, d) : http.post<T.DelegationTable>('/admin/power-tables', d),
    checkDelegationTable: (id: string) => http.post<T.DelegationCheckResult>(`/admin/power-tables/${enc(id)}/check`),
    transitionDelegationTable: (id: string, action: 'submit' | 'approve' | 'reject', body: { reason: string; effectiveFrom?: string }) =>
      http.post<T.DelegationTable>(`/admin/power-tables/${enc(id)}/${action}`, body),
    /** Person / seat delegations (contract /admin/delegations). */
    delegations: (q: ListQuery & { personId?: string; seatId?: string }) => http.page<T.Assignment>('/admin/delegations', q),
    revokeDelegation: (id: string, reason: string) => http.post<void>(`/admin/delegations/${enc(id)}/revoke`, { reason }),
    policies: (q: ListQuery & { effect?: string; resourceType?: string }) => http.page<T.Policy>('/admin/policies', q),
    /** create; an edit is create-new + delete-old in the contract (no PUT), so the version history stays intact. */
    savePolicy: async (p: Partial<T.Policy>) => {
      const created = await http.post<T.Policy>('/admin/policies', { ...p, id: undefined });
      if (p.id) await http.del<void>(`/admin/policies/${enc(p.id)}`, { reason: `replaced by ${created.id}` });
      return created;
    },
    deletePolicy: (id: string, reason: string) => http.del<void>(`/admin/policies/${enc(id)}`, { reason }),
    policyVersion: () => http.get<{ version?: number; policyVersion?: number }>('/admin/policies/version'),
    explain: (body: { principalId: string; action: string; resourceType: string; resourceId: string }) =>
      http.post<{ allowed: boolean; matched: { policyId: string; effect: 'ALLOW' | 'DENY'; scope: string }[]; policyVersion: number }>('/admin/policies/explain', body),
    sodExceptions: (q: ListQuery) => http.page<T.SodException>('/admin/sod-exceptions', q),
    saveSodException: (e: Partial<T.SodException>) => http.post<T.SodException>('/admin/sod-exceptions', e),
    decideSodException: (id: string, approve: boolean, reason: string) => http.post<void>(`/admin/sod-exceptions/${enc(id)}/${approve ? 'approve' : 'reject'}`, { reason }),
    groupMembers: (id: string) => http.get<T.User[]>(`/admin/groups/${enc(id)}/members`),
    setMembership: (groupId: string, userId: string, member: boolean) =>
      member ? http.post<void>(`/admin/groups/${enc(groupId)}/members/${enc(userId)}`) : http.del<void>(`/admin/groups/${enc(groupId)}/members/${enc(userId)}`),
    identityProviders: () => http.get<T.IdentityProvider[]>('/admin/identity-providers'),
    saveIdentityProvider: (p: Partial<T.IdentityProvider>) =>
      p.id ? http.put<T.IdentityProvider>(`/admin/identity-providers/${enc(p.id)}`, p) : http.post<T.IdentityProvider>('/admin/identity-providers', p),
    testIdentityProvider: (id: string) => http.post<{ ok: boolean; message: string }>(`/admin/identity-providers/${enc(id)}/test`),
    syncIdentityProvider: (id: string) => http.post<{ users: number; groups: number }>(`/admin/identity-providers/${enc(id)}/sync`),
    servicePrincipals: () => http.get<T.ServicePrincipal[]>('/admin/service-principals'),
    saveServicePrincipal: (p: Partial<T.ServicePrincipal>) => http.post<T.ServicePrincipal & { secret?: string }>('/admin/service-principals', p),
    rotateServicePrincipal: (id: string) => http.post<{ secret: string }>(`/admin/service-principals/${enc(id)}/rotate`),
    revokeServicePrincipal: (id: string, reason: string) => http.post<void>(`/admin/service-principals/${enc(id)}/revoke`, { reason }),
  },
  /** Declarative CRUD screens. DELETE carries ?reason=. */
  resource: {
    list: <R>(path: string, q: ListQuery) => http.page<R>(path, q),
    save: <R extends { id?: string }>(path: string, r: R) => (r.id ? http.put<R>(`${path}/${enc(r.id)}`, r) : http.post<R>(path, r)),
    remove: (path: string, id: string, reason: string) => http.del<void>(`${path}/${enc(id)}`, { reason }),
    action: <R>(path: string, id: string, action: string, body: unknown = {}) => http.post<R>(`${path}/${enc(id)}/${action}`, body),
  },

  // ------------------------------------------------------------ analytics (R65, R66, R79)
  analytics: {
    kpis: async (q: Query) => {
      const s = await http.get<Record<string, unknown>>('/analytics/summary', { processDefinitionKey: q.definitionKey, from: q.from, to: q.to });
      return A.kpisFromSummary(s);
    },
    throughput: (q: Query) => http.get<T.TimePoint[]>('/analytics/throughput', q),
    sla: (q: Query) => http.get<T.SlaRow[]>('/analytics/sla', q), // (R79)
    bottlenecks: async (q: Query) =>
      ((await http.get<Record<string, unknown>[]>('/analytics/activities', { processDefinitionKey: q.definitionKey, from: q.from, to: q.to })) ?? []).map(A.bottleneckFromActivity),
    variants: async (q: Query) =>
      ((await http.get<Record<string, unknown>[]>('/analytics/variants', { processDefinitionKey: q.definitionKey, from: q.from, to: q.to, limit: 50 })) ?? []).map(A.normVariant),
    conformance: (q: Query) => http.get<T.ConformanceResult>('/analytics/conformance', q), // (R66)
    durationReport: (q: Query) => http.get<T.DurationReportRow[]>('/analytics/reports/duration', q), // (PF-07)
    taskReport: async (q: Query) =>
      ((await http.get<{ name?: string; taskDefinitionKey?: string; completed?: number; avgDurationMs?: number }[]>('/analytics/tasks', { processDefinitionKey: q.definitionKey, from: q.from, to: q.to })) ?? []).map(
        (r) => ({ taskName: r.name ?? r.taskDefinitionKey ?? '', completed: Number(r.completed ?? 0), avgMs: Number(r.avgDurationMs ?? 0), byUser: undefined as { user: string; count: number }[] | undefined }),
      ),
    alerts: () => http.get<T.AlertRule[]>('/analytics/alerts'),
    saveAlert: (a: Partial<T.AlertRule>) => (a.id ? http.put<T.AlertRule>(`/analytics/alerts/${enc(a.id)}`, a) : http.post<T.AlertRule>('/analytics/alerts', a)),
    deleteAlert: (id: string) => http.del<void>(`/analytics/alerts/${enc(id)}`),
    exportLog: (format: 'xes' | 'ocel', q: Query) =>
      download(`/analytics/export/${format}`, { processDefinitionKey: q.definitionKey, from: q.from, to: q.to }, `sangam-log.${format === 'xes' ? 'xes' : 'jsonocel'}`),
    agentUsage: (q: Query) =>
      http.get<{ agent: string; runs: number; tokens: number; costUnits: number; budget?: number; guardrailBlocks: number; humanReviews: number }[]>('/analytics/agents', q), // (R72)
  },

  // ------------------------------------------------------------ audit (R42)
  audit: {
    timeline: async (businessKey: string, q: ListQuery = {}) =>
      A.mapPage(await http.page<Record<string, unknown>>(`/audit/${enc(businessKey)}/timeline`, { page: q.page, size: q.size, includeHistory: true, type: q.q }), (e) => A.normAudit(e, businessKey)),
    verify: async (businessKey?: string) => A.normVerify(await http.get<Record<string, unknown>>('/audit/verify', { businessKey })),
    search: async (q: ListQuery & { actor?: string; seat?: string; type?: string; operationId?: string; ruleVersion?: string; from?: string; to?: string }) =>
      A.mapPage(await http.page<Record<string, unknown>>('/audit/events', q), (e) => A.normAudit(e)), //
    /** synchronous export: GET /audit/export?businessKey&format. */
    exportNow: (businessKey: string | undefined, format: 'CSV' | 'JSON' | 'PDFA') =>
      download('/audit/export', { businessKey, format: format.toLowerCase() }, `audit-${businessKey ?? 'all'}.${format === 'PDFA' ? 'pdf' : format.toLowerCase()}`),
    createExport: (body: { format: T.AuditExportJob['format']; scope: Record<string, unknown> }, key?: string) => http.post<T.AuditExportJob>('/audit/exports', body, key), // async jobs
    exports: () => http.get<T.AuditExportJob[]>('/audit/exports'),
    downloadExport: (id: string) => download(`/audit/exports/${enc(id)}/content`, undefined, `audit-${id}`),
    evidencePack: (businessKey: string) => download(`/audit/${enc(businessKey)}/evidence-pack`, undefined, `evidence-${businessKey}.zip`), // (U03)
  },

  // ------------------------------------------------------------ documents (R74, EC-18)
  documents: {
    list: async (q: ListQuery & { businessKey?: string }) =>
      A.mapPage(await http.page<Record<string, unknown>>('/documents', { page: q.page, size: q.size, name: q.q, businessKey: q.businessKey }), A.normDocument),
    versions: (id: string) => http.get<T.Attachment[]>(`/documents/${enc(id)}/versions`),
    content: (id: string, name: string, version?: number) => download(`/documents/${enc(id)}/content`, { version }, name),
    setHold: (id: string, hold: boolean, reason: string) => http.post<void>(`/documents/${enc(id)}/legal-hold`, { hold, reason }),
    templates: () => http.get<{ id: string; name: string; languages: string[]; format: 'PDFA' | 'DOCX' | 'HTML'; version: number }[]>('/documents/templates'),
    generate: (templateId: string, businessKey: string, language: string, key?: string) =>
      http.post<Record<string, unknown>>('/documents/generate', { templateId, businessKey, language }, key).then(A.normDocument),
  },

  // ------------------------------------------------------------ pages and data objects (R77, R78, EC-03, EC-06)
  pages: {
    list: () => http.get<{ id: string; key: string; title: string; version: number; state: string }[]>('/pages'),
    get: (key: string) => http.get<PageModel>(`/pages/${enc(key)}`),
    save: (p: PageModel) => (p.id ? http.put<PageModel>(`/pages/${enc(p.key)}`, p) : http.post<PageModel>('/pages', p)),
    query: (name: string, params: Query) => http.page<Record<string, unknown>>(`/queries/${enc(name)}`, params),
  },
  dataObjects: {
    list: () => http.get<DataObjectDef[]>('/data-objects'),
    save: (d: DataObjectDef) => (d.id ? http.put<DataObjectDef>(`/data-objects/${enc(d.id)}`, d) : http.post<DataObjectDef>('/data-objects', d)),
    records: (key: string, q: ListQuery) => http.page<Record<string, unknown>>(`/data-objects/${enc(key)}/records`, q),
  },

  // ------------------------------------------------------------ events (FC-02, FC-03), keyed by `key`
  events: {
    channels: async () => (await http.page<EventChannel>('/events/channels', { size: 500 })).items,
    saveChannel: (c: EventChannel, isNew: boolean) => (isNew ? http.post<EventChannel>('/events/channels', c) : http.put<EventChannel>(`/events/channels/${enc(c.key)}`, c)),
    definitions: async () => (await http.page<EventDefinition>('/events/definitions', { size: 500 })).items,
    /** The contract has no PUT for definitions: an edit re-creates the definition under the same key. */
    saveDefinition: async (d: EventDefinition, isNew: boolean) => {
      if (!isNew) await http.del<void>(`/events/definitions/${enc(d.key)}`, { reason: 'definition updated' });
      return http.post<EventDefinition>('/events/definitions', d);
    },
    send: (channel: string, payload: unknown, key?: string) =>
      http.post<void>(`/events/${enc(channel)}`, { specversion: '1.0', id: key ?? crypto.randomUUID(), source: 'sangam-ui', type: 'sangam.test', datacontenttype: 'application/json', data: payload }, key),
  },

  // ------------------------------------------------------------ citizen portal (C01–C06)
  portal: {
    services: () => http.get<T.PortalService[]>('/portal/services'),
    apply: (serviceKey: string, variables: Record<string, unknown>, submit: boolean, key?: string) =>
      http.post<T.Application>('/portal/applications', { serviceKey, variables: A.toVariableMap(variables), submit }, key),
    mine: (q: ListQuery = {}) => http.page<T.Application>('/portal/applications', q),
    get: (id: string) => http.get<T.Application>(`/portal/applications/${enc(id)}`),
    reply: (id: string, clarificationId: string, text: string, files: File[], key?: string) => {
      const f = new FormData();
      f.append('text', text);
      files.forEach((x) => f.append('files', x, x.name));
      return http.upload<void>(`/portal/applications/${enc(id)}/clarifications/${enc(clarificationId)}/reply`, f, key);
    },
    withdraw: (id: string, reason: string, key?: string) => http.post<void>(`/portal/applications/${enc(id)}/withdraw`, { reason }, key),
    appeal: (id: string, grounds: string, key?: string) => http.post<void>(`/portal/applications/${enc(id)}/appeal`, { grounds }, key),
    outcome: (id: string) => download(`/portal/applications/${enc(id)}/outcome`, undefined, `outcome-${id}.pdf`),
    grievances: () => http.get<{ id: string; reference: string; subject: string; status: string; filedAt: string; dueAt: string }[]>('/portal/grievances'),
    fileGrievance: (body: { subject: string; text: string; kind: 'GRIEVANCE' | 'DATA_RIGHTS' }, key?: string) => http.post<{ reference: string }>('/portal/grievances', body, key),
  },

  // ------------------------------------------------------------ field app (S15, EC-16)
  field: {
    visits: () => http.get<{ id: string; taskId: string; businessKey: string; address: string; scheduledAt?: string; checklist: { id: string; label: string }[] }[]>('/field/visits'),
    record: (id: string, body: { answers: Record<string, boolean | string>; notes: string; recordedAt: string }, key: string) => http.post<void>(`/field/visits/${enc(id)}/record`, body, key),
  },
};

export type { Page };

// --------------------------------------------------------------- page / data-object / event models (R77, R78, FC-02)

export interface PageBlock {
  id: string;
  kind: 'heading' | 'text' | 'list' | 'detail' | 'kpi' | 'action';
  title?: string;
  text?: string;
  query?: string;
  columns?: string[];
  dataObject?: string;
  processKey?: string;
}

export interface PageModel {
  id?: string;
  key: string;
  title: string;
  version?: number;
  state?: string;
  blocks: PageBlock[];
}

export interface DataObjectField {
  name: string;
  type: 'string' | 'number' | 'boolean' | 'date' | 'reference' | 'json';
  required?: boolean;
  reference?: string;
  sensitive?: boolean;
}

export interface DataObjectDef {
  id?: string;
  key: string;
  name: string;
  backing: 'DB' | 'REST';
  endpointRef?: string;
  version?: number;
  fields: DataObjectField[];
}

export interface EventChannel {
  id?: string;
  key: string;
  kind: 'KAFKA' | 'AMQP' | 'JMS' | 'WEBHOOK' | 'MATRIX' | 'TEAMS' | 'SLACK' | 'WHATSAPP';
  direction: 'INBOUND' | 'OUTBOUND';
  destination: string;
  keyDetector?: string;
  tenantDetector?: string;
  dedupWindowSec?: number;
  active: boolean;
}

export interface EventDefinition {
  id?: string;
  key: string;
  name: string;
  correlationParameters: string[];
  payload: { name: string; type: string }[];
  uniqueStart?: boolean;
}
