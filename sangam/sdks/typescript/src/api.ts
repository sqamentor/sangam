// SPDX-License-Identifier: Apache-2.0
// API groups for sangam-api.yaml 0.1.0.
import { HttpTransport, seg, type ApiRequest, type QueryValue } from './http.js';
import type {
  AuditEntry,
  AuditVerifyResult,
  BpmnErrorRequest,
  CaseInstance,
  CloudEvent,
  CompleteTaskRequest,
  CompleteTaskResult,
  CompleteWorkRequest,
  DecisionEvaluateRequest,
  DecisionEvaluateResult,
  DelegateTaskRequest,
  Deployment,
  ExtendLockRequest,
  ExternalWork,
  FailWorkRequest,
  FetchAndLockRequest,
  IncidentAction,
  JobRetriesRequest,
  JsonObject,
  LockState,
  MessageRequest,
  MessageResult,
  OutboxRepairRequest,
  OutcomeUnknownRequest,
  OutcomeUnknownResult,
  Page,
  ProcessInstance,
  ProcessInstanceQuery,
  PublishResult,
  ReconciliationItem,
  ReconciliationQuery,
  ResolveReconciliationRequest,
  ReturnTaskRequest,
  SignalRequest,
  StartCaseRequest,
  StartProcessRequest,
  TaskQuery,
  TaskView,
  TerminateRequest,
  TerminationResult,
  UnlockRequest,
  Variables,
} from './models.js';

/**
 * Per-call options: Idempotency-Key (R41; generated when omitted on mutations; the government profile requires
 * one on start, correlate, task decisions, cancel, delete and deploy), seat headers and an abort signal.
 */
export interface CallOptions {
  idempotencyKey?: string;
  signal?: AbortSignal;
  seat?: string;
  actingForSeat?: string;
}

type Query = Record<string, QueryValue>;

function asQuery(o: object | undefined): Query {
  return { ...(o as Query | undefined) };
}

abstract class ApiGroup {
  constructor(protected readonly http: HttpTransport) {}

  protected req(method: ApiRequest['method'], path: string, body?: unknown, o?: CallOptions, query?: Query): ApiRequest {
    return {
      method, path, body, query,
      idempotencyKey: o?.idempotencyKey, signal: o?.signal, seat: o?.seat, actingForSeat: o?.actingForSeat,
    };
  }

  protected postJson<T>(path: string, body?: unknown, o?: CallOptions): Promise<T> {
    return this.http.call<T>(this.req('POST', path, body, o));
  }

  /** POST returning an object whose `idempotentReplayed` is set from the response header. */
  protected postObj<T extends object>(path: string, body?: unknown, o?: CallOptions): Promise<T> {
    return this.http.callReplayable<T>(this.req('POST', path, body, o));
  }

  protected getJson<T>(path: string, query?: Query, o?: CallOptions): Promise<T> {
    return this.http.call<T>(this.req('GET', path, undefined, o, query));
  }

  /** Reads a list endpoint: bare JSON array or `{items}`, plus the A-16 count headers. */
  protected async page<T>(req: ApiRequest): Promise<Page<T>> {
    const r = await this.http.execute<unknown>(req);
    const body = r.body as unknown;
    const obj = body && typeof body === 'object' && !Array.isArray(body) ? (body as Record<string, unknown>) : undefined;
    const items = (Array.isArray(body) ? body : Array.isArray(obj?.items) ? (obj!.items as unknown[]) : []) as T[];
    const header = r.headers.get('x-total-count');
    let totalCount = header !== null && header.trim() !== '' ? Number(header) : undefined;
    if ((totalCount === undefined || Number.isNaN(totalCount)) && typeof obj?.totalCount === 'number') totalCount = obj.totalCount;
    if (totalCount !== undefined && Number.isNaN(totalCount)) totalCount = undefined;
    const capped = (r.headers.get('x-total-count-capped') ?? '').toLowerCase() === 'true' || obj?.totalCountCapped === true;
    const page = typeof obj?.page === 'number' ? obj.page : Number(req.query?.page ?? 0);
    const size = typeof obj?.size === 'number' ? obj.size : Number(req.query?.size ?? items.length);
    return { items, page, size, totalCount, totalCountCapped: capped };
  }

  protected listPage<T>(path: string, query?: Query, o?: CallOptions): Promise<Page<T>> {
    return this.page<T>(this.req('GET', path, undefined, o, query));
  }
}

/**
 * Worker API `/api/v1/work` (VI.3). Fenced by `(workId, lockVersion, attemptId)`: a stale lockVersion throws
 * `StaleLeaseError` (409 STALE_LEASE) and the server changed nothing. The contract declares no Idempotency-Key for
 * fenced calls; the SDK still sends its derived key, which is harmless.
 * Most applications use `SangamWorker`, which drives these calls.
 */
export class WorkApi extends ApiGroup {
  /** Long-poll fetch-and-lock; HTTP timeout = async timeout + 15 s. Not retried by the transport. */
  async fetchAndLock(request: FetchAndLockRequest, signal?: AbortSignal): Promise<ExternalWork[]> {
    const r = await this.http.call<ExternalWork[] | undefined>({
      method: 'POST',
      path: '/work/fetch-and-lock',
      body: request,
      timeoutMs: (request.asyncResponseTimeoutMs ?? 30_000) + 15_000,
      noRetry: true,
      signal,
    });
    return r ?? [];
  }

  extendLock(workId: string, request: ExtendLockRequest, o?: CallOptions): Promise<LockState | undefined> {
    return this.postJson(`/work/${seg(workId)}/extend-lock`, request, o);
  }

  async complete(workId: string, request: CompleteWorkRequest, o?: CallOptions): Promise<void> {
    await this.postJson(`/work/${seg(workId)}/complete`, request, o);
  }

  async fail(workId: string, request: FailWorkRequest, o?: CallOptions): Promise<void> {
    await this.postJson(`/work/${seg(workId)}/fail`, request, o);
  }

  async bpmnError(workId: string, request: BpmnErrorRequest, o?: CallOptions): Promise<void> {
    await this.postJson(`/work/${seg(workId)}/bpmn-error`, request, o);
  }

  async unlock(workId: string, request: UnlockRequest, o?: CallOptions): Promise<void> {
    await this.postJson(`/work/${seg(workId)}/unlock`, request, o);
  }

  /** VI.3.2 rule 4: 202 → UNKNOWN_OUTCOME with an open reconciliation item. */
  async outcomeUnknown(workId: string, request: OutcomeUnknownRequest, o?: CallOptions): Promise<OutcomeUnknownResult> {
    return (await this.postJson<OutcomeUnknownResult | undefined>(`/work/${seg(workId)}/outcome-unknown`, request, o)) ?? {};
  }

  /** VI.3.1: CANCEL_REQUESTED --worker ack--> CANCELLED. */
  async cancelAck(workId: string, request: UnlockRequest, o?: CallOptions): Promise<void> {
    await this.postJson(`/work/${seg(workId)}/cancel-ack`, request, o);
  }
}

/** User tasks `/api/v1/tasks` (results filtered by the caller's VI.4 policy). */
export class TasksApi extends ApiGroup {
  list(query: TaskQuery = {}, o?: CallOptions): Promise<Page<TaskView>> {
    return this.listPage<TaskView>('/tasks', asQuery(query), o);
  }

  get(taskId: string, o?: CallOptions): Promise<TaskView> {
    return this.getJson<TaskView>(`/tasks/${seg(taskId)}`, undefined, o);
  }

  /** `GET /tasks/{id}/variables` (VariableMap; unwrap with `unwrapVariables`). */
  async variables(taskId: string, o?: CallOptions): Promise<Variables> {
    return (await this.getJson<Variables | undefined>(`/tasks/${seg(taskId)}/variables`, undefined, o)) ?? {};
  }

  /** No body; 204. */
  async claim(taskId: string, o?: CallOptions): Promise<void> {
    await this.postJson(`/tasks/${seg(taskId)}/claim`, undefined, o);
  }

  /** No body; 204. */
  async unclaim(taskId: string, o?: CallOptions): Promise<void> {
    await this.postJson(`/tasks/${seg(taskId)}/unclaim`, undefined, o);
  }

  /** `payloadHash` must equal the hash the user approved (VI.4 rule 5). */
  complete(taskId: string, request: CompleteTaskRequest, o?: CallOptions): Promise<CompleteTaskResult> {
    return this.postObj<CompleteTaskResult>(`/tasks/${seg(taskId)}/complete`, request, o);
  }

  async returnTask(taskId: string, request: ReturnTaskRequest, o?: CallOptions): Promise<JsonObject | undefined> {
    return this.postJson(`/tasks/${seg(taskId)}/return`, request, o);
  }

  async delegate(taskId: string, request: DelegateTaskRequest, o?: CallOptions): Promise<JsonObject | undefined> {
    return this.postJson(`/tasks/${seg(taskId)}/delegate`, request, o);
  }
}

/** Process and case instances, messages and signals. The tenant always comes from the token. */
export class ProcessesApi extends ApiGroup {
  /** 201. Pass a business-derived Idempotency-Key to make "start once" safe across retries. */
  start(request: StartProcessRequest, o?: CallOptions): Promise<ProcessInstance> {
    return this.postObj<ProcessInstance>('/process-instances', request, o);
  }

  list(query: ProcessInstanceQuery = {}, o?: CallOptions): Promise<Page<ProcessInstance>> {
    return this.listPage<ProcessInstance>('/process-instances', asQuery(query), o);
  }

  getInstance(processInstanceId: string, o?: CallOptions): Promise<ProcessInstance> {
    return this.getJson(`/process-instances/${seg(processInstanceId)}`, undefined, o);
  }

  async variables(processInstanceId: string, o?: CallOptions): Promise<Variables> {
    return (await this.getJson<Variables | undefined>(`/process-instances/${seg(processInstanceId)}/variables`, undefined, o)) ?? {};
  }

  /** Business cancel (VI.5): requires CANCEL_INSTANCE and a reason. */
  cancel(processInstanceId: string, request: TerminateRequest | string, o?: CallOptions): Promise<TerminationResult> {
    const body: TerminateRequest = typeof request === 'string' ? { reason: request } : request;
    return this.postObj<TerminationResult>(`/process-instances/${seg(processInstanceId)}/cancel`, body, o);
  }

  correlateMessage(request: MessageRequest, o?: CallOptions): Promise<MessageResult> {
    return this.postObj<MessageResult>('/messages', request, o);
  }

  /** 204. */
  async signal(request: SignalRequest, o?: CallOptions): Promise<void> {
    await this.postJson('/signals', request, o);
  }

  /** `POST /case-instances` (CMMN 1.1). */
  startCase(request: StartCaseRequest, o?: CallOptions): Promise<CaseInstance> {
    return this.postObj<CaseInstance>('/case-instances', request, o);
  }
}

/** DMN decisions; evaluation is side-effect free. */
export class DecisionsApi extends ApiGroup {
  evaluate(decisionKey: string, request: DecisionEvaluateRequest, o?: CallOptions): Promise<DecisionEvaluateResult> {
    return this.postJson(`/decisions/${seg(decisionKey)}/evaluate`, request, o);
  }
}

/** Audit timeline and hash-chain verification (R42, AC-01). */
export class AuditApi extends ApiGroup {
  timeline(
    businessKey: string,
    q: { includeHistory?: boolean; page?: number; size?: number } = {},
    o?: CallOptions,
  ): Promise<Page<AuditEntry>> {
    return this.listPage<AuditEntry>(`/audit/${seg(businessKey)}/timeline`, asQuery(q), o);
  }

  verify(q: { chainId?: string; businessKey?: string } = {}, o?: CallOptions): Promise<AuditVerifyResult> {
    return this.getJson('/audit/verify', asQuery(q), o);
  }
}

/** Generic CRUD over one admin/ops collection (bodies are JSON objects). */
export class ResourceApi extends ApiGroup {
  constructor(http: HttpTransport, readonly path: string) {
    super(http);
  }

  list(filters: Query = {}, page?: number, size?: number, o?: CallOptions): Promise<Page<JsonObject>> {
    return this.listPage<JsonObject>(this.path, { ...filters, page, size }, o);
  }

  getById(id: string, o?: CallOptions): Promise<JsonObject> {
    return this.getJson(`${this.path}/${seg(id)}`, undefined, o);
  }

  /** `GET <path>/<sub>`, e.g. `/ops/outbox/stats`. */
  getSub(sub: string, query?: Query, o?: CallOptions): Promise<JsonObject> {
    return this.getJson(`${this.path}/${seg(sub)}`, query, o);
  }

  create(body: JsonObject, o?: CallOptions): Promise<JsonObject> {
    return this.postObj(this.path, body, o);
  }

  update(id: string, body: JsonObject, o?: CallOptions): Promise<JsonObject> {
    return this.http.callReplayable(this.req('PUT', `${this.path}/${seg(id)}`, body, o));
  }

  async delete(id: string, o?: CallOptions): Promise<void> {
    await this.http.call(this.req('DELETE', `${this.path}/${seg(id)}`, undefined, o));
  }

  /** `POST <path>/<id>/<action>`, e.g. `/ops/jobs/{id}/execute`, `/admin/delegations/{id}/revoke`. */
  action<T extends object = JsonObject>(id: string, action: string, body?: unknown, o?: CallOptions): Promise<T> {
    return this.postObj<T>(`${this.path}/${seg(id)}/${seg(action)}`, body, o);
  }
}

/** Admin basics `/admin/**` (IX.3–IX.5, VI.4). */
export class AdminApi {
  constructor(private readonly http: HttpTransport) {}
  readonly orgUnits = (): ResourceApi => new ResourceApi(this.http, '/admin/org-units');
  readonly seats = (): ResourceApi => new ResourceApi(this.http, '/admin/seats');
  readonly assignments = (): ResourceApi => new ResourceApi(this.http, '/admin/assignments');
  readonly delegations = (): ResourceApi => new ResourceApi(this.http, '/admin/delegations');
  readonly policies = (): ResourceApi => new ResourceApi(this.http, '/admin/policies');
  readonly tenants = (): ResourceApi => new ResourceApi(this.http, '/admin/tenants');
  resource(name: string): ResourceApi {
    return new ResourceApi(this.http, `/admin/${seg(name)}`);
  }

  /** `POST /admin/assignments/{id}/end`. */
  endAssignment(id: string, body: { reason: string } & JsonObject, o?: CallOptions): Promise<JsonObject> {
    return this.assignments().action(id, 'end', body, o);
  }

  /** `POST /admin/delegations/{id}/revoke`. */
  revokeDelegation(id: string, body: { reason: string } & JsonObject, o?: CallOptions): Promise<JsonObject> {
    return this.delegations().action(id, 'revoke', body, o);
  }
}

/** Operations `/ops/**`: incidents, reconciliation (UNKNOWN_OUTCOME), outbox, jobs. */
export class OpsApi extends ApiGroup {
  readonly incidents = (): ResourceApi => new ResourceApi(this.http, '/ops/incidents');
  readonly outbox = (): ResourceApi => new ResourceApi(this.http, '/ops/outbox');
  readonly jobs = (): ResourceApi => new ResourceApi(this.http, '/ops/jobs');

  /** `POST /ops/incidents/{id}/retry` (reason mandatory). */
  retryIncident(id: string, request: IncidentAction, o?: CallOptions): Promise<JsonObject> {
    return this.incidents().action(id, 'retry', request, o);
  }

  /** `POST /ops/incidents/{id}/resolve`. */
  resolveIncident(id: string, request: IncidentAction, o?: CallOptions): Promise<JsonObject> {
    return this.incidents().action(id, 'resolve', request, o);
  }

  /** `POST /ops/jobs/{id}/retries`. */
  setJobRetries(id: string, request: JobRetriesRequest, o?: CallOptions): Promise<JsonObject> {
    return this.jobs().action(id, 'retries', request, o);
  }

  /** `POST /ops/outbox/{outboxId}/requeue`. */
  requeueOutbox(outboxId: string, request: OutboxRepairRequest, o?: CallOptions): Promise<JsonObject> {
    return this.outbox().action(outboxId, 'requeue', request, o);
  }

  /** `POST /ops/outbox/{outboxId}/skip`. */
  skipOutbox(outboxId: string, request: OutboxRepairRequest, o?: CallOptions): Promise<JsonObject> {
    return this.outbox().action(outboxId, 'skip', request, o);
  }

  /** `GET /ops/outbox/stats`. */
  outboxStats(o?: CallOptions): Promise<JsonObject> {
    return this.getJson('/ops/outbox/stats', undefined, o);
  }

  reconciliation(query: ReconciliationQuery = {}, o?: CallOptions): Promise<Page<ReconciliationItem>> {
    return this.listPage<ReconciliationItem>('/ops/reconciliation', asQuery(query), o);
  }

  reconciliationItem(id: string, o?: CallOptions): Promise<ReconciliationItem> {
    return this.getJson(`/ops/reconciliation/${seg(id)}`, undefined, o);
  }

  /** `CONFIRM_SUCCESS`, `RETRY_SAME_OPERATION` or `MARK_FAILED`; evidence is mandatory. */
  resolve(id: string, request: ResolveReconciliationRequest, o?: CallOptions): Promise<ReconciliationItem> {
    return this.postObj<ReconciliationItem>(`/ops/reconciliation/${seg(id)}/resolve`, request, o);
  }
}

export interface DeployOptions extends CallOptions {
  source?: string;
  duplicateFiltering?: boolean;
}

/** Deployments, event registry, forms. */
export class RepositoryApi extends ApiGroup {
  /**
   * `POST /deployments` multipart: fields `name`, `source`, `duplicateFiltering`, and one part named `files` per
   * resource (filename = resource name). The tenant comes from the token. 201.
   */
  deploy(name: string, resources: Record<string, Uint8Array | string>, opts: DeployOptions = {}): Promise<Deployment> {
    const form = new FormData();
    form.append('name', name);
    if (opts.source) form.append('source', opts.source);
    if (opts.duplicateFiltering !== undefined) form.append('duplicateFiltering', String(opts.duplicateFiltering));
    for (const [file, content] of Object.entries(resources)) {
      const part = typeof content === 'string' ? content : new Uint8Array(content);
      form.append('files', new Blob([part], { type: 'application/octet-stream' }), file);
    }
    const req = this.req('POST', '/deployments', undefined, opts);
    req.rawBody = form;
    return this.http.callReplayable<Deployment>(req);
  }

  deployments(page?: number, size?: number, o?: CallOptions): Promise<Page<Deployment>> {
    return this.listPage<Deployment>('/deployments', { page, size }, o);
  }

  async eventChannels(o?: CallOptions): Promise<JsonObject[]> {
    return (await this.listPage<JsonObject>('/events/channels', undefined, o)).items;
  }

  async eventDefinitions(o?: CallOptions): Promise<JsonObject[]> {
    return (await this.listPage<JsonObject>('/events/definitions', undefined, o)).items;
  }

  /** `POST /events/{channel}` with a CloudEvents 1.0 envelope; 202. Build one with `cloudEvent()`. */
  publishEvent(channel: string, event: CloudEvent, o?: CallOptions): Promise<PublishResult> {
    return this.postObj<PublishResult>(`/events/${seg(channel)}`, event, o);
  }

  form(formKey: string, o?: CallOptions): Promise<JsonObject> {
    return this.getJson(`/forms/${seg(formKey)}`, undefined, o);
  }
}

/** Builds a CloudEvents 1.0 envelope (id defaults to a random UUID, time to now). */
export function cloudEvent<D>(
  e: { source: string; type: string; data?: D; id?: string } & Partial<Omit<CloudEvent<D>, 'source' | 'type' | 'data' | 'id'>>,
): CloudEvent<D> {
  return {
    ...e,
    id: e.id ?? globalThis.crypto.randomUUID(),
    specversion: e.specversion ?? '1.0',
    time: e.time ?? new Date().toISOString(),
    datacontenttype: e.datacontenttype ?? (e.data !== undefined ? 'application/json' : undefined),
  };
}

/** `GET /me`: the authenticated caller and the permissions the server grants. */
export class MeApi extends ApiGroup {
  get(o?: CallOptions): Promise<JsonObject> {
    return this.getJson('/me', undefined, o);
  }
}
