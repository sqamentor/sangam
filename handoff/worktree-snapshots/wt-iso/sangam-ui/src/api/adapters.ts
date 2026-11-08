// SPDX-License-Identifier: Apache-2.0
/**
 * Adapters between the published contract (sangam-rest/api/sangam-api.yaml, OpenAPI 3.1) and the UI view models in
 * types.ts. Every adapter accepts both the contract shape and the richer UI-proposal shape, so the UI keeps working
 * when WS-REST adds the proposed fields (README "API reconciliation").
 */
import type { Page } from './client';
import type * as T from './types';

/* eslint-disable @typescript-eslint/no-explicit-any */
// Contract payloads are read defensively; ny keeps the adapters tolerant of both shapes.
type Any = any;

export function mapPage<A, B>(p: Page<A>, fn: (a: A) => B): Page<B> {
  return { ...p, items: p.items.map(fn) };
}

// ------------------------------------------------------------------ typed values (R33)

const JS_TYPES = new Set(['Decimal', 'BigInteger', 'Long', 'Integer', 'Boolean', 'String', 'Date', 'LocalDate', 'Json', 'Bytes']);

function isTyped(v: unknown): v is { type: string; value: unknown } {
  return !!v && typeof v === 'object' && 'type' in (v as Any) && 'value' in (v as Any) && JS_TYPES.has(String((v as Any).type));
}

/** Plain values → R33 TypedValue map. Numbers become Decimal strings (no float rounding of money). */
export function toVariableMap(vars: Record<string, unknown> = {}): Record<string, { type: string; value: unknown }> {
  const out: Record<string, { type: string; value: unknown }> = {};
  for (const [k, v] of Object.entries(vars)) {
    if (v === undefined) continue;
    if (isTyped(v)) out[k] = v;
    else if (typeof v === 'number') out[k] = { type: Number.isInteger(v) ? 'Long' : 'Decimal', value: Number.isInteger(v) ? v : String(v) };
    else if (typeof v === 'boolean') out[k] = { type: 'Boolean', value: v };
    else if (typeof v === 'string') out[k] = { type: 'String', value: v };
    else out[k] = { type: 'Json', value: v };
  }
  return out;
}

/** R33 TypedValue map → plain values for forms (Decimal stays numeric for inputs). */
export function fromVariableMap(m: unknown): Record<string, unknown> {
  if (!m || typeof m !== 'object') return {};
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(m as Any)) {
    if (isTyped(v)) out[k] = v.type === 'Decimal' || v.type === 'BigInteger' ? Number(v.value) : v.value;
    else out[k] = v;
  }
  return out;
}

export function variableEntries(m: unknown): T.VariableEntry[] {
  if (Array.isArray(m)) return m as T.VariableEntry[];
  if (!m || typeof m !== 'object') return [];
  return Object.entries(m as Any).map(([name, v]) =>
    isTyped(v) ? { name, type: v.type, value: v.value, masked: (v as Any).masked } : { name, type: typeof v, value: v },
  );
}

// ------------------------------------------------------------------ identity

export function normMe(m: Any): T.Me {
  const seats: T.SeatHolding[] = Array.isArray(m.seats)
    ? m.seats
    : [
        ...(m.seatId ? [{ seatId: m.seatId, seatName: m.seatName ?? m.seatId, designation: '', orgUnitName: '', kind: 'SUBSTANTIVE' as const, validFrom: '' }] : []),
        ...(m.actingForSeatId
          ? [{ seatId: m.actingForSeatId, seatName: m.actingForSeatName ?? m.actingForSeatId, designation: '', orgUnitName: '', kind: 'ADDITIONAL_CHARGE' as const, validFrom: '' }]
          : []),
      ];
  const roles = (m.roles ?? (m.groups ?? []).map((g: string) => g.replace(/^ROLE_/i, '').toUpperCase())) as T.Role[];
  return {
    userId: m.userId ?? m.principalId,
    displayName: m.displayName ?? m.name ?? m.principalId ?? '',
    tenantId: m.tenantId,
    tenantName: m.tenantName,
    roles,
    seats,
    permissions: m.permissions,
    preferredLanguage: m.preferredLanguage,
  };
}

// ------------------------------------------------------------------ tasks

export function normTask(t: Any): T.TaskSummary {
  return {
    ...t,
    id: t.id,
    name: t.name ?? t.taskDefinitionKey ?? t.id,
    section: t.section ?? (t.assignee ? 'MY_SEAT' : 'POOL'),
    created: t.created ?? t.createdAt,
    received: t.received ?? t.claimTime ?? t.created,
    due: t.due,
    slaDue: t.slaDue ?? t.due,
    claimable: t.claimable ?? !t.assignee,
    packKey: t.packKey ?? t.processDefinitionKey,
  } as T.TaskSummary;
}

export function normTaskDetail(d: Any): T.TaskDetail {
  const task = d.task ? normTask(d.task) : normTask(d);
  const variables = fromVariableMap(d.variables ?? {});
  const material = Array.isArray(d.materialFields)
    ? d.materialFields.map((f: any) => (typeof f === 'string' ? { key: f, label: f, value: variables[f] } : f))
    : undefined;
  return {
    ...(d.task ? {} : d),
    ...task,
    ...(d.task ? { form: d.form, sod: d.sod, returnTargets: d.returnTargets, candidates: d.candidates, rejectReasons: d.rejectReasons, returnReasons: d.returnReasons, changedSinceLastSeen: d.changedSinceLastSeen, ruleEvidence: d.ruleEvidence, delegatedFrom: d.delegatedFrom, description: d.description } : {}),
    formKey: d.formKey ?? d.task?.formKey,
    formVersion: d.formVersion,
    variables,
    payloadHash: d.payloadHash ?? '',
    version: Number(d.version ?? d.caseVersion ?? 0),
    allowedActions: ((d.allowedActions ?? []) as string[]).map((a) => a.toUpperCase()) as T.TaskAction[],
    returnTargets: d.returnTargets ?? [],
    materialFields: material,
  } as T.TaskDetail;
}

export function normComment(c: Any): T.Comment {
  return { id: c.id, author: c.author ?? c.userId ?? '', seatName: c.seatName, actedFor: c.actedFor, createdAt: c.createdAt ?? c.time, text: c.text ?? c.message ?? '', version: c.version };
}

export function normDocument(d: Any): T.Attachment & { businessKey?: string; legalHold?: boolean; retentionClass?: string } {
  return {
    ...d,
    id: d.id,
    name: d.name,
    contentType: d.contentType,
    size: Number(d.size ?? 0),
    sha256: d.sha256 ?? '',
    version: Number(d.version ?? 1),
    uploadedBy: d.uploadedBy ?? d.createdBy ?? '',
    uploadedAt: d.uploadedAt ?? d.createdAt,
    scanStatus: d.scanStatus === 'NOT_SCANNED' ? 'SKIPPED' : d.scanStatus,
  };
}

export function normCaseVersion(v: Any): T.CaseVersion {
  return {
    version: Number(v.version),
    createdAt: v.createdAt ?? v.at,
    createdBy: v.createdBy ?? v.actor ?? '',
    seatName: v.seatName ?? v.seatId,
    actedFor: v.actedFor,
    reason: v.reason,
    kind: v.kind,
    payloadHash: v.payloadHash ?? '',
  };
}

// ------------------------------------------------------------------ processes

const STATE: Record<string, T.InstanceState> = { CANCELLED: 'CANCELED', DELETED: 'CANCELED', EXTERNALLY_TERMINATED: 'CANCELED' };

export function normInstance(p: Any): T.ProcessInstance {
  return {
    ...p,
    id: p.id,
    definitionId: p.definitionId ?? p.processDefinitionId,
    definitionKey: p.definitionKey ?? p.processDefinitionKey,
    definitionVersion: p.definitionVersion ?? p.processDefinitionVersion,
    state: (STATE[p.state] ?? p.state) as T.InstanceState,
    superInstanceId: p.superInstanceId ?? p.superProcessInstanceId,
    lifecycleState: p.lifecycleState ?? p.businessStatus,
  };
}

export function xmlOf(r: unknown, key: 'bpmn20Xml' | 'dmnXml'): string {
  if (typeof r === 'string') return r;
  const o = (r ?? {}) as Any;
  return o[key] ?? o.xml ?? '';
}

/** Runtime statistics from the Operaton REST API: GET /engine-rest/process-definition/{id}/statistics?incidents=true. */
export function normEngineStatistics(definitionId: string, rows: Any[]): T.DefinitionStatistics {
  return {
    definitionId,
    mode: 'runtime',
    activities: rows.map((r) => ({
      activityId: r.id,
      count: Number(r.instances ?? 0),
      incidents: (r.incidents ?? []).reduce((a: number, i: Any) => a + Number(i.incidentCount ?? 0), 0),
    })),
  };
}

/** History heatmap from GET /analytics/activities (ActivityStat). */
export function normActivityStats(definitionId: string, rows: Any[], mode: 'runtime' | 'history' = 'history'): T.DefinitionStatistics {
  return {
    definitionId,
    mode,
    activities: rows.map((r) => ({
      activityId: r.activityId,
      activityName: r.activityName,
      count: Number((mode === 'runtime' ? r.active : r.count) ?? 0),
      incidents: Number(r.incidents ?? 0),
      avgDurationMs: r.avgDurationMs,
      p95DurationMs: r.p95DurationMs ?? r.maxDurationMs,
    })),
  };
}

/** Instance-level token counts derived from the activity tree (no extra endpoint needed). */
export function statsFromTree(tree: T.ActivityInstance): T.DefinitionStatistics {
  const counts = new Map<string, { n: number; inc: number; name?: string }>();
  const walk = (n: T.ActivityInstance, root: boolean) => {
    const kids = n.children ?? [];
    if (!root && kids.length === 0) {
      const c = counts.get(n.activityId) ?? { n: 0, inc: 0, name: n.activityName };
      c.n += 1;
      c.inc += n.incidentIds?.length ?? 0;
      counts.set(n.activityId, c);
    }
    kids.forEach((k) => walk(k, false));
  };
  walk(tree, true);
  return { definitionId: '', mode: 'runtime', activities: [...counts].map(([activityId, c]) => ({ activityId, activityName: c.name, count: c.n, incidents: c.inc })) };
}

export function normHistory(kind: T.HistoryLogKind, r: Any): T.HistoryLogEntry {
  return {
    id: r.id ?? r.eventId ?? `${kind}-${r.time ?? r.timestamp ?? r.startTime ?? Math.random()}`,
    time: r.time ?? r.timestamp ?? r.startTime ?? r.createTime ?? r.evaluationTime ?? r.createdAt,
    kind,
    activityId: r.activityId ?? r.taskDefinitionKey,
    activityName: r.activityName ?? r.name,
    actor: r.actor ?? r.userId ?? r.assignee,
    operation: r.operation ?? r.operationType ?? r.state ?? r.jobState,
    property: r.property,
    oldValue: r.oldValue,
    newValue: r.newValue ?? (kind === 'variables' ? r.value : undefined),
    message: r.message ?? r.jobExceptionMessage ?? (kind === 'variables' ? r.name : undefined) ?? r.decisionDefinitionKey,
    linkId: r.linkId ?? (kind === 'called' ? r.id : undefined),
  };
}

export function normLink(l: Any): { type: string; targetType: string; targetId: string; label?: string } {
  const scope = String(l.targetScopeType ?? l.targetType ?? '').toUpperCase();
  return { type: l.type ?? l.linkType, targetType: scope.includes('CASE') ? 'CASE' : scope.includes('TASK') ? 'TASK' : 'PROCESS', targetId: l.targetId, label: l.label ?? l.referenceType };
}

// ------------------------------------------------------------------ operations

export function normIncident(i: Any): T.Incident {
  return { ...i, firstSeen: i.firstSeen ?? i.createdAt, lastSeen: i.lastSeen ?? i.createdAt, note: i.note ?? i.annotation, definitionKey: i.definitionKey ?? i.processDefinitionId };
}

const BATCH_STATE: Record<string, T.BatchStatus> = { CANCELLED: 'CANCELED', FAILED: 'COMPLETED_WITH_FAILURES' };

export function normBatch(b: Any): T.Batch {
  const total = Number(b.total ?? 0);
  const succeeded = Number(b.succeeded ?? 0);
  const failed = Number(b.failed ?? 0);
  return {
    ...b,
    type: b.type,
    status: (b.status ?? BATCH_STATE[b.state] ?? b.state) as T.BatchStatus,
    total,
    succeeded,
    failed,
    remaining: Number(b.remaining ?? Math.max(0, total - succeeded - failed)),
    createdBy: b.createdBy ?? '',
    createdAt: b.createdAt,
    reason: b.reason ?? '',
  };
}

export function normBatchItem(i: Any): T.BatchItem {
  return { id: i.id ?? String(i.index), targetId: i.targetId ?? i.rootId ?? i.itemKey, status: i.status ?? i.state, message: i.message ?? i.error };
}

/** UI instance query → contract BatchFilter. */
export function toBatchFilter(q: T.InstanceQuery): Any {
  return {
    processDefinitionId: q.definitionId,
    processDefinitionKey: q.definitionKey,
    processInstanceIds: q.instanceIds,
    businessKeys: q.businessKey ? [q.businessKey] : undefined,
    activityId: q.activityId,
    withIncidents: q.withIncidents ?? (q.state === 'INCIDENT' ? true : undefined),
  };
}

export function toBatchType(t: T.BatchType): string {
  return t === 'ACTIVATE' ? 'RESUME' : t;
}

export function previewFromBatch(p: Any): T.ConsequencePreview {
  if (Array.isArray(p.summary)) return p as T.ConsequencePreview;
  return { summary: p.sample?.length ? [`e.g. ${(p.sample as string[]).slice(0, 5).join(', ')}`] : [], affectedCount: p.count, requiresFourEyes: p.fourEyesRequired, limitExceeded: p.limitExceeded };
}

export function normOutbox(o: Any): T.OutboxEntry {
  return {
    id: o.id ?? o.outboxId,
    destination: o.destination,
    rootKey: o.rootKey ?? o.rootId ?? '',
    status: o.status ?? o.state,
    attempts: Number(o.attempts ?? 0),
    lastError: o.lastError,
    createdAt: o.createdAt,
    operationId: o.operationId ?? '',
    ordered: o.ordered ?? o.rootSeq !== undefined,
  };
}

export function normJob(j: Any): T.Job {
  return { ...j, retries: Number(j.retries ?? 0), suspended: !!j.suspended };
}

export function normRecon(r: Any): T.ReconciliationDetail {
  const d = (r.details ?? {}) as Any;
  const created = r.createdAt ?? d.createdAt;
  return {
    ...d,
    ...r,
    id: r.id,
    status: (r.status ?? r.kind ?? 'UNKNOWN_OUTCOME') as T.ReconStatus,
    resolution: r.resolution && typeof r.resolution === 'string' ? r.resolution : r.state === 'RESOLVED' ? (r.action === 'CONFIRM_SUCCESS' ? 'CONFIRMED_SUCCESS' : r.action === 'MARK_FAILED' ? 'MARKED_FAILED' : 'RETRIED') : undefined,
    operationId: r.operationId ?? '',
    capabilityClass: r.capabilityClass ?? d.capabilityClass ?? 'c',
    amount: r.amount ?? d.amount,
    currency: r.currency ?? d.currency,
    businessKey: r.businessKey ?? d.businessKey,
    processInstanceId: r.processInstanceId ?? r.rootId,
    destination: r.destination ?? d.destination,
    ageSec: Number(r.ageSec ?? (created ? (Date.now() - new Date(created).getTime()) / 1000 : 0)),
    createdAt: created,
    owner: r.owner ?? d.owner,
    evidenceCount: Number(r.evidenceCount ?? (d.evidence ?? []).length),
    requestFingerprint: r.requestFingerprint ?? d.requestFingerprint ?? '',
    attempts: r.attempts ?? d.attempts ?? [],
    evidence: r.evidence ?? d.evidence ?? [],
    ledgerEntry: r.ledgerEntry ?? d.ledgerEntry,
    initiator: r.initiator ?? d.initiator,
    approver: r.approver ?? d.approver,
    lookupAvailable: r.lookupAvailable ?? d.lookupAvailable,
    lookupResult: r.lookupResult ?? d.lookupResult,
    fourEyesRequired: r.fourEyesRequired ?? d.fourEyesRequired,
  };
}

export function normCleanup(c: Any): T.CleanupStatus {
  if (Array.isArray(c.definitions)) return c as T.CleanupStatus;
  return {
    enabled: !!c.jobId,
    nextRun: c.dueDate,
    definitions: Object.entries((c.removable ?? {}) as Record<string, number>).map(([key, n]) => ({ key, version: 0, finished: n, cleanable: n })),
  };
}

// ------------------------------------------------------------------ packages

function kindOf(name: string): T.ModelArtifact['kind'] {
  if (/\.dmn$/i.test(name)) return 'DMN';
  if (/\.form$|\.json$/i.test(name)) return 'FORM';
  if (/\.cmmn$/i.test(name)) return 'CMMN';
  return 'BPMN';
}

export function normPackage(p: Any): T.ModelPackage {
  const artifacts: T.ModelArtifact[] = Array.isArray(p.artifacts)
    ? p.artifacts
    : Object.entries((p.resources ?? {}) as Record<string, string>).map(([name, content]) => ({
        id: name,
        name,
        kind: kindOf(name),
        content,
        sha256: p.resourceHashes?.[name],
      }));
  if (!Array.isArray(p.artifacts) && !p.resources && p.resourceHashes) {
    for (const [name, sha] of Object.entries(p.resourceHashes as Record<string, string>)) artifacts.push({ id: name, name, kind: kindOf(name), content: '', sha256: sha });
  }
  return {
    ...p,
    version: String(p.version ?? p.revision ?? ''),
    state: p.state === 'SUSPENDED' || p.state === 'ARCHIVED' || p.state === 'WITHDRAWN' ? (p.state === 'WITHDRAWN' ? 'DRAFT' : 'RETIRED') : p.state,
    author: p.author ?? '',
    updatedAt: p.updatedAt ?? p.createdAt,
    effectiveFrom: p.effectiveFrom ?? p.activateAt,
    hash: p.hash ?? p.packageHash,
    artifacts,
  };
}

export function fileName(a: Pick<T.ModelArtifact, 'name' | 'kind'>): string {
  if (/\.(bpmn|dmn|cmmn|form|json|xml)$/i.test(a.name)) return a.name;
  return `${a.name}.${a.kind === 'FORM' ? 'form' : a.kind.toLowerCase()}`;
}

export function normPackageDiff(d: unknown, toVersion = ''): T.PackageDiff {
  if (d && !Array.isArray(d) && (d as Any).artifacts) return d as T.PackageDiff;
  const rows = (Array.isArray(d) ? d : []) as Any[];
  return {
    toVersion,
    artifacts: rows.map((r) => ({
      name: r.name ?? r.resource ?? r.resourceName ?? '',
      change: String(r.change ?? r.kind ?? r.status ?? 'MODIFIED').toUpperCase() as T.PackageDiff['artifacts'][number]['change'],
      summary: r.summary ?? (r.oldHash || r.newHash ? [`${r.oldHash ?? '—'} → ${r.newHash ?? '—'}`] : undefined),
    })),
  };
}

// ------------------------------------------------------------------ forms

/** FormVersion (dataSchema / layout / i18n) → renderable form-js-style schema. */
export function normForm(f: Any): T.FormSchema {
  if (Array.isArray(f.components)) return f as T.FormSchema;
  if (f.layout && Array.isArray(f.layout.components)) return { ...f.layout, key: f.key } as T.FormSchema;
  const props = (f.dataSchema?.properties ?? {}) as Record<string, Any>;
  const required = new Set<string>(f.dataSchema?.required ?? []);
  const hi = (f.i18n?.hi ?? f.i18n?.bundles?.hi ?? {}) as Record<string, string>;
  return {
    key: f.key,
    components: Object.entries(props).map(([key, p]) => ({
      key,
      label: p.title ?? key,
      i18n: hi[key] ? { hi: { label: hi[key] } } : undefined,
      type: p.enum ? 'select' : p.type === 'number' || p.type === 'integer' ? 'number' : p.type === 'boolean' ? 'checkbox' : p.format === 'date' ? 'datetime' : 'textfield',
      subtype: p.format === 'date' ? 'date' : undefined,
      values: p.enum ? (p.enum as string[]).map((v) => ({ value: v, label: v })) : undefined,
      validate: { required: required.has(key), min: p.minimum, max: p.maximum, minLength: p.minLength, maxLength: p.maxLength, pattern: p.pattern },
      readonly: p.readOnly,
    })),
  };
}

// ------------------------------------------------------------------ audit, analytics

export function normAudit(e: Any, businessKey = ''): T.AuditEvent {
  return {
    seq: Number(e.seq ?? 0),
    id: e.id ?? e.eventId,
    time: e.time ?? e.occurredAt,
    type: e.type,
    businessKey: e.businessKey ?? businessKey,
    actor: e.actor ?? '',
    seatName: e.seatName ?? e.seatId,
    actedFor: e.actedFor ?? e.actedForSeatId,
    packageVersion: e.packageVersion,
    ruleVersion: e.ruleVersion,
    payloadHash: e.payloadHash,
    operationId: e.operationId,
    downstreamStatus: e.downstreamStatus,
    state: e.state,
    details: e.details ?? (e.payload ? fromVariableMap(e.payload) : undefined),
    prevHash: e.prevHash ?? '',
    hash: e.hash ?? '',
  };
}

export function normVerify(v: Any): T.AuditVerifyResult {
  if (typeof v.valid === 'boolean') return v as T.AuditVerifyResult;
  const chains = (v.chains ?? []) as Any[];
  const broken = chains.find((c) => c.ok === false || c.valid === false);
  return {
    valid: !!v.ok,
    checked: chains.reduce((a, c) => a + Number(c.checked ?? c.count ?? c.length ?? 0), 0),
    headHash: chains[0]?.headHash ?? chains[0]?.head,
    firstBrokenSeq: broken?.firstBrokenSeq ?? broken?.brokenAtSeq,
    anchoredAt: v.verifiedAt,
    message: broken?.message,
  };
}

export function kpisFromSummary(s: Any): T.Kpi[] {
  const k = (id: string, name: string, value: unknown, unit?: string): T.Kpi | null =>
    value === undefined || value === null ? null : { id, name, value: Number(value), unit };
  return [
    k('started', 'Started', s.started),
    k('completed', 'Completed', s.completed),
    k('active', 'Active', s.active),
    k('cancelled', 'Cancelled', s.cancelled),
    k('openIncidents', 'Open incidents', s.openIncidents),
    k('avg', 'Average duration (h)', s.avgDurationMs !== undefined ? Number(s.avgDurationMs) / 3600000 : undefined, 'h'),
    k('p95', '95th percentile (h)', s.p95DurationMs !== undefined ? Number(s.p95DurationMs) / 3600000 : undefined, 'h'),
  ].filter((x): x is T.Kpi => !!x);
}

export function normVariant(v: Any, i: number): T.Variant {
  return { id: v.id ?? String(i), path: v.path ?? v.activities ?? [], count: Number(v.count ?? 0), share: Number(v.share ?? 0), avgDurationMs: Number(v.avgDurationMs ?? 0), conformant: v.conformant };
}

export function bottleneckFromActivity(a: Any): T.Bottleneck {
  return {
    activityId: a.activityId,
    activityName: a.activityName,
    avgWaitMs: Number(a.avgWaitMs ?? a.avgDurationMs ?? 0),
    avgServiceMs: Number(a.avgServiceMs ?? a.avgDurationMs ?? 0),
    p95Ms: Number(a.p95Ms ?? a.maxDurationMs ?? 0),
    count: Number(a.count ?? 0),
  };
}

/** Flat org-unit list (contract) → tree for the hierarchy view. */
export function toTree(units: T.OrgUnit[]): T.OrgUnit[] {
  if (units.some((u) => u.children && u.children.length)) return units;
  const byId = new Map(units.map((u) => [u.id, { ...u, children: [] as T.OrgUnit[] }]));
  const roots: T.OrgUnit[] = [];
  for (const u of byId.values()) {
    const parent = u.parentId ? byId.get(u.parentId) : undefined;
    if (parent) parent.children.push(u);
    else roots.push(u);
  }
  return roots;
}
