// SPDX-License-Identifier: Apache-2.0
/**
 * R18 test engine (M-021): an in-process fake of the Sangam worker API for unit-testing workers without a server.
 *
 * `SangamTestEngine` is a `fetch`-compatible function (no sockets, no dependency): pass `engine.fetch` as
 * `SangamClientOptions.fetch`, or use `engine.client()`. It answers `/api/v1/work/**` and `POST /api/v1/process-instances`
 * with the lease semantics of the server (SANGAM.md VI.3), so the real `SangamClient` and `SangamWorker` run unchanged:
 *
 * ```ts
 * const engine = new SangamTestEngine();
 * const workId = engine.addWork('pay', { amount: { type: 'Decimal', value: '12.50' } });
 * const worker = new SangamWorker(engine.client(), { workerId: 'w1' }).subscribe('pay', handler).start();
 * ...
 * assert.equal(engine.state(workId), 'COMPLETED');
 * ```
 *
 * Semantics (mirrors sangam-rest OperatonWorkAdapter, sangam-core LeaseTable, ApiProblem, IdempotencyStore; identical
 * to the Python and PHP test engines):
 * - Lease row per item: a grant gives `lockVersion + 1` and a new `attemptId`; a lease that ends without completing
 *   (fail, unlock, expiry, outcome-unknown) gives `lockVersion + 1` and no owner; complete / bpmn-error remove it.
 * - Fencing on every worker call in the server's order: no lease, stale version, not owner, expired, then attempt
 *   mismatch -> `409 STALE_LEASE` (`reason` NO_LEASE | STALE_VERSION | NOT_OWNER | EXPIRED | ATTEMPT_MISMATCH),
 *   nothing changes. Unknown work -> `404 NOT_FOUND`. A result for a deleted instance -> `410 GONE_INSTANCE_DELETED`.
 * - Lease per topic: default 5 min, capped at 24 h, on the engine's own clock (`advance`). Every request first sweeps
 *   expired leases: LOCKED -> AVAILABLE (`lockVersion + 1`), or UNKNOWN_OUTCOME with a reconciliation item when the
 *   topic is irreversible or a cancel was requested and not acknowledged.
 * - `fail` with retries > 0 -> AVAILABLE after `retryTimeoutMs`; retries = 0 -> INCIDENT. On an irreversible topic a
 *   failure with retries absent or > 0 becomes outcome-unknown (VI.3 rule 5).
 * - An identical repeat of a completion (same lockVersion, same variables/outcome) answers 204 again; the work is
 *   completed once.
 * - R41 for `POST /process-instances`: same Idempotency-Key + same body -> the stored response with
 *   `Idempotent-Replayed: true`; same key + another body -> `422 IDEMPOTENCY_KEY_REUSED`.
 * - `fetch-and-lock` answers at once (no long-poll wait); an empty answer comes after one timer tick of at most 10 ms.
 * - The step journal of durable steps (M-025): `GET/PUT /work/{id}/step?stepName=`, `GET /work/{id}/steps`, with the
 *   server's transitions and fencing (`testing-steps.ts`).
 */
import { SangamClient, type SangamClientOptions } from './client.js';
import { randomUUID } from 'node:crypto';
import { EngineSteps } from './testing-steps.js';

export const DEFAULT_LOCK_MS = 5 * 60 * 1000;
export const MAX_LOCK_MS = 24 * 60 * 60 * 1000;
const API_PREFIX = '/api/v1';
const TYPE_BASE = 'https://sangam.dev/problems/';
const TITLES: Record<number, string> = { 400: 'Bad request', 404: 'Not found', 409: 'Conflict', 410: 'Gone', 422: 'Unprocessable content' };

type Json = Record<string, unknown>;

/** An RFC 9457 answer of the engine (the server's ApiProblem). */
export class EngineProblem extends Error {
  constructor(readonly status: number, readonly code: string, detail: string, readonly title?: string,
    readonly extensions: Json = {}) {
    super(detail);
  }

  body(instance: string): Json {
    const b: Json = {
      type: TYPE_BASE + this.code.toLowerCase().replace(/_/g, '-'),
      title: this.title ?? TITLES[this.status] ?? 'Error',
      status: this.status,
      detail: this.message,
      instance,
      code: this.code,
    };
    for (const [k, v] of Object.entries(this.extensions)) if (v !== undefined && v !== null && !(k in b)) b[k] = v;
    return b;
  }
}

const stale = (workId: string, reason: string, detail: string) =>
  new EngineProblem(409, 'STALE_LEASE', detail, 'Stale lease', { workId, reason });
const notFound = (what: string, id: string) =>
  new EngineProblem(404, 'NOT_FOUND', `${what} ${id} was not found or is not visible to you`, undefined, { resource: what });

function canonical(v: unknown): string {
  return JSON.stringify(v, (_k, val: unknown) => {
    if (val && typeof val === 'object' && !Array.isArray(val)) {
      const o = val as Json;
      return Object.fromEntries(Object.keys(o).sort().map((k) => [k, o[k]]));
    }
    return val;
  }) ?? 'null';
}

const iso = (ms: number) => new Date(ms).toISOString();

interface LeaseRow { lockVersion: number; attemptId: string | null; workerId: string | null; expiresAt: number | null }

export type EngineWorkState = 'AVAILABLE' | 'LOCKED' | 'CANCEL_REQUESTED' | 'COMPLETED' | 'COMPLETED_WITH_ERROR' | 'INCIDENT'
  | 'UNKNOWN_OUTCOME' | 'CANCELLED' | 'FAILED';

/** States in which the server still has the work's external task (incident and reconciliation hold keep it). */
const TASK_EXISTS: ReadonlySet<EngineWorkState> = new Set<EngineWorkState>(['AVAILABLE', 'LOCKED', 'INCIDENT', 'UNKNOWN_OUTCOME']);

interface Work {
  id: string; topic: string; variables: Json; retries: number | null; priority: number; businessKey?: string;
  processDefinitionKey: string; processInstanceId: string; activityInstanceId: string; tenantId: string;
  operationId: string; state: Exclude<EngineWorkState, 'CANCEL_REQUESTED'>; availableAt: number; errorMessage?: string;
  row: LeaseRow | null; cancelRequested: boolean; deleted: boolean; ledgerState: string; attempts: number;
  storedOutcome?: Json; completion?: [unknown, string]; completions: number; output?: Json; errorCode?: string;
}

export interface EngineReconciliationItem { id: string; kind: string; workId: string; operationId: string; state: string; detail?: string; resolution?: string }
export interface EngineCall { method: string; path: string; idempotencyKey?: string; body: unknown }
export interface AddWorkOptions { retries?: number | null; priority?: number; businessKey?: string; processDefinitionKey?: string; processInstanceId?: string }
export interface TestEngineOptions { irreversibleTopics?: string[]; tenantId?: string; startMs?: number }

interface Answer { status: number; headers: Record<string, string>; body?: unknown; lose: boolean }

/** In-process fake of the Sangam worker API with the VI.3 lease semantics. */
export class SangamTestEngine {
  readonly irreversibleTopics: Set<string>;
  readonly tenantId: string;
  /** Every request (path without /api/v1). */
  readonly calls: EngineCall[] = [];
  /** Started process instances (ProcessInstance wire objects). */
  readonly instances: Json[] = [];
  readonly reconciliationItems: EngineReconciliationItem[] = [];
  private now: number;
  private readonly work = new Map<string, Work>();
  private readonly lose = new Map<string, number>();
  private readonly idem = new Map<string, [string, number, Json]>();
  private reconSeq = 0;
  private readonly journal = new EngineSteps<Work>({
    work: (id) => this.work.get(id),
    fence: (w, b) => this.fence(w, b),
    open: (w) => TASK_EXISTS.has(w.state),
    problem: (status, code, detail, ext) => new EngineProblem(status, code, detail, undefined, ext ?? {}),
    notFound,
    nowMs: () => this.now,
  });

  constructor(o: TestEngineOptions = {}) {
    this.irreversibleTopics = new Set(o.irreversibleTopics ?? []);
    this.tenantId = o.tenantId ?? 'test';
    this.now = o.startMs ?? Date.now();
  }

  /** A `SangamClient` on this engine (`fetch` is set; other options pass through). */
  client(options: Partial<SangamClientOptions> = {}): SangamClient {
    return new SangamClient({ baseUrl: 'http://sangam-test-engine', ...options, fetch: this.fetch });
  }

  // ------------------------------------------------------------------ clock and steering

  get nowMs(): number { return this.now; }

  /** Moves the engine clock forward; leases expire on the next request. */
  advance(ms: number): void {
    if (ms < 0) throw new RangeError('the clock only moves forward');
    this.now += ms;
  }

  /** Adds AVAILABLE external work; returns its id. Its operationId is fixed for the life of the item (R36). */
  addWork(topic: string, variables: Json = {}, o: AddWorkOptions = {}): string {
    const id = randomUUID();
    this.work.set(id, {
      id, topic, variables: { ...variables }, retries: o.retries === undefined ? 3 : o.retries, priority: o.priority ?? 0,
      businessKey: o.businessKey, processDefinitionKey: o.processDefinitionKey ?? 'test-process',
      processInstanceId: o.processInstanceId ?? randomUUID(), activityInstanceId: `task:${randomUUID().substring(0, 12)}`,
      tenantId: this.tenantId, operationId: randomUUID(), state: 'AVAILABLE', availableAt: 0, row: null,
      cancelRequested: false, deleted: false, ledgerState: 'INTENT', attempts: 0, completions: 0,
    });
    return id;
  }

  /** CANCEL_REQUESTED: the next heartbeat answers `cancelRequested: true`. */
  requestCancel(workId: string): void { this.get(workId).cancelRequested = true; }

  /** Deletes the work's instance (tombstone): late results get 410 GONE_INSTANCE_DELETED. */
  deleteInstance(workId: string): void { this.get(workId).deleted = true; }

  /** CONFIRM_SUCCESS -> COMPLETED, RETRY -> AVAILABLE with the same operationId, MARK_FAILED -> FAILED. */
  resolveReconciliation(itemId: string, action: 'CONFIRM_SUCCESS' | 'RETRY' | 'MARK_FAILED'): void {
    const item = this.reconciliationItems.find((i) => i.id === itemId);
    if (!item || item.state !== 'OPEN') throw new Error(`no open reconciliation item ${itemId}`);
    const w = this.get(item.workId);
    if (action === 'CONFIRM_SUCCESS') {
      w.state = 'COMPLETED'; w.ledgerState = 'SUCCEEDED'; w.completions++;
    } else if (action === 'RETRY') {
      w.state = 'AVAILABLE'; w.ledgerState = 'INTENT'; w.availableAt = this.now;
    } else if (action === 'MARK_FAILED') {
      w.state = 'FAILED'; w.ledgerState = 'FAILED';
    } else {
      throw new Error(`unknown resolution ${String(action)}`);
    }
    item.state = 'RESOLVED';
    item.resolution = action;
  }

  /** The next call of `action` is applied, then the client sees a transport error (no answer). */
  loseNextResponse(action: string): void { this.lose.set(action, (this.lose.get(action) ?? 0) + 1); }

  // ------------------------------------------------------------------ inspection

  state(workId: string): EngineWorkState {
    const w = this.get(workId);
    return w.state === 'LOCKED' && w.cancelRequested ? 'CANCEL_REQUESTED' : w.state;
  }

  lockVersion(workId: string): number { return this.get(workId).row?.lockVersion ?? 0; }

  completions(workId: string): number { return this.get(workId).completions; }

  snapshot(workId: string): Json {
    const w = this.get(workId);
    return {
      id: w.id, topic: w.topic, state: this.state(workId), lockVersion: this.lockVersion(workId),
      attemptId: w.row?.attemptId ?? null, workerId: w.row?.workerId ?? null, operationId: w.operationId,
      attempts: w.attempts, ledgerState: w.ledgerState, retries: w.retries, completions: w.completions,
      output: w.output ?? null, errorCode: w.errorCode ?? null, errorMessage: w.errorMessage ?? null,
    };
  }

  callsOf(action: string): EngineCall[] {
    return this.calls.filter((c) => c.path.substring(c.path.lastIndexOf('/') + 1) === action);
  }

  // ------------------------------------------------------------------ fetch seam

  /** `fetch`-compatible entry point. */
  readonly fetch: typeof fetch = async (input, init) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url);
    let path = url.pathname;
    if (path.startsWith(API_PREFIX)) path = path.substring(API_PREFIX.length);
    let key: string | undefined;
    for (const [k, v] of Object.entries((init?.headers ?? {}) as Record<string, string>)) {
      if (k.toLowerCase() === 'idempotency-key') key = v;
    }
    let body: unknown;
    if (typeof init?.body === 'string' && init.body.length > 0) {
      try { body = JSON.parse(init.body); } catch { body = undefined; }
    }
    const a = this.handle(init?.method ?? 'GET', path, key, body, url.searchParams);
    if (a.lose) throw new TypeError('fetch failed: connection closed by the test engine (lost response)');
    if (path === '/work/fetch-and-lock' && Array.isArray(a.body) && a.body.length === 0) {
      // no long poll, but one short timer tick (<= 10 ms) so a worker loop never monopolises the event loop
      const wait = Math.min(10, Math.max(0, Number((body as Json | undefined)?.asyncResponseTimeoutMs ?? 10)));
      await new Promise<void>((resolve) => setTimeout(resolve, wait));
    }
    const headers: Record<string, string> = { ...a.headers };
    if (a.body !== undefined && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
    return new Response(a.body === undefined ? null : JSON.stringify(a.body), { status: a.status, headers });
  };

  /** One request without HTTP: status, headers, JSON body, and whether the answer is lost. `query`: the step GET's lease. */
  handle(method: string, path: string, key: string | undefined, body: unknown, query?: URLSearchParams): Answer {
    this.calls.push({ method, path, idempotencyKey: key, body });
    const action = path.substring(path.lastIndexOf('/') + 1);
    const pending = this.lose.get(action) ?? 0;
    const lose = pending > 0;
    if (lose) this.lose.set(action, pending - 1);
    const b = body && typeof body === 'object' && !Array.isArray(body) ? (body as Json) : {};
    try {
      this.sweep();
      const step = this.journal.route(method, path, query, b);
      if (step) return { status: step.status, headers: {}, body: step.body, lose };
      return { ...this.route(method, path, key, b), lose };
    } catch (e) {
      if (e instanceof EngineProblem) {
        return { status: e.status, headers: { 'Content-Type': 'application/problem+json' }, body: e.body(API_PREFIX + path), lose };
      }
      throw e;
    }
  }

  private get(workId: string): Work {
    const w = this.work.get(workId);
    if (!w) throw new Error(`no work ${workId}`);
    return w;
  }

  private route(method: string, path: string, key: string | undefined, b: Json): Omit<Answer, 'lose'> {
    const parts = path.split('/').filter((p) => p);
    if (method === 'POST' && parts.length === 1 && parts[0] === 'process-instances') return this.start(key, b);
    if (method === 'POST' && parts.length === 2 && parts[0] === 'work' && parts[1] === 'fetch-and-lock') {
      return { status: 200, headers: {}, body: this.fetchAndLock(b) };
    }
    if (method === 'POST' && parts.length === 3 && parts[0] === 'work') {
      const wid = decodeURIComponent(parts[1]!);
      switch (parts[2]) {
        case 'extend-lock': return { status: 200, headers: {}, body: this.extend(wid, b) };
        case 'complete': this.complete(wid, b); return { status: 204, headers: {} };
        case 'fail': this.fail(wid, b); return { status: 204, headers: {} };
        case 'bpmn-error': this.bpmnError(wid, b); return { status: 204, headers: {} };
        case 'unlock': this.unlock(wid, b); return { status: 204, headers: {} };
        case 'outcome-unknown': return { status: 202, headers: {}, body: this.outcomeUnknown(wid, b) };
        case 'cancel-ack': this.cancelAck(wid, b); return { status: 204, headers: {} };
        default: break;
      }
    }
    throw new EngineProblem(404, 'NOT_FOUND', `${method} ${path} is not served by the test engine`);
  }

  // ------------------------------------------------------------------ lease table

  private sweep(): void {
    for (const w of this.work.values()) {
      if (w.state === 'LOCKED' && w.row && w.row.expiresAt !== null && w.row.expiresAt <= this.now) {
        if (w.cancelRequested || this.irreversibleTopics.has(w.topic)) {
          this.toUnknown(w, w.cancelRequested ? 'cancel not acknowledged before lease expiry' : 'lease expired on irreversible topic');
        } else {
          this.end(w);
          w.state = 'AVAILABLE';
        }
      }
    }
  }

  /** LeaseTable.end: lock_version + 1, no owner. */
  private end(w: Work): void {
    w.row = { lockVersion: (w.row?.lockVersion ?? 0) + 1, attemptId: null, workerId: null, expiresAt: null };
  }

  private fence(w: Work, b: Json): void {
    const worker = b.workerId;
    if (typeof worker !== 'string' || !worker) throw new EngineProblem(400, 'WORKER_ID_REQUIRED', 'workerId is required');
    const lv = b.lockVersion;
    const r = w.row;
    if (!r) throw stale(w.id, 'NO_LEASE', 'no lease was granted for this work');
    if (lv !== r.lockVersion) throw stale(w.id, 'STALE_VERSION', `lockVersion ${String(lv)} is not the current version ${r.lockVersion}`);
    if (r.workerId === null || r.workerId !== worker) {
      throw stale(w.id, 'NOT_OWNER', `${worker} does not hold the lease (owner: ${String(r.workerId)})`);
    }
    if (r.expiresAt === null || r.expiresAt <= this.now) {
      throw stale(w.id, 'EXPIRED', `the lease of ${worker} expired at ${iso(r.expiresAt ?? 0)}`);
    }
    const att = b.attemptId;
    if (att !== undefined && att !== null && r.attemptId !== null && att !== r.attemptId) {
      throw stale(w.id, 'ATTEMPT_MISMATCH', `attemptId ${String(att)} is not the current attempt`);
    }
  }

  /**
   * requireTask: the external task exists until the work finished or was deleted; an incident or an outcome awaiting
   * reconciliation keeps it, so a call on such work reaches the fence (409 STALE_LEASE: outcome-unknown ended the lease)
   * as on both server work adapters (SL-174).
   */
  private task(wid: string): Work {
    const w = this.work.get(wid);
    if (!w || w.deleted || !TASK_EXISTS.has(w.state)) throw notFound('external work', wid);
    return w;
  }

  private gone(wid: string, effect: boolean, result: unknown): void {
    const w = this.work.get(wid);
    if (!w || !w.deleted) return;
    const item = effect ? this.openRecon(w, 'LATE_RESULT', `late result for a deleted instance: ${canonical(result ?? null)}`) : undefined;
    throw new EngineProblem(410, 'GONE_INSTANCE_DELETED', 'The instance of this work was deleted; the result was recorded',
      undefined, { workId: wid, reconciliationItemId: item?.id });
  }

  private openRecon(w: Work, kind: string, detail: string | undefined): EngineReconciliationItem {
    const item: EngineReconciliationItem = { id: String(++this.reconSeq), kind, workId: w.id, operationId: w.operationId, state: 'OPEN', detail };
    this.reconciliationItems.push(item);
    return item;
  }

  private toUnknown(w: Work, detail: string | undefined): EngineReconciliationItem {
    if (w.row) this.end(w);
    w.state = 'UNKNOWN_OUTCOME';
    w.ledgerState = 'UNKNOWN_OUTCOME';
    return this.openRecon(w, 'UNKNOWN_OUTCOME', detail);
  }

  // ------------------------------------------------------------------ operations

  private start(key: string | undefined, b: Json): Omit<Answer, 'lose'> {
    const fp = canonical(b);
    const idemKey = `POST /process-instances ${key ?? ''}`;
    if (key !== undefined) {
      const stored = this.idem.get(idemKey);
      if (stored) {
        if (stored[0] !== fp) {
          throw new EngineProblem(422, 'IDEMPOTENCY_KEY_REUSED', 'the Idempotency-Key was used before with a different request');
        }
        return { status: stored[1], headers: { 'Idempotent-Replayed': 'true', Location: `${API_PREFIX}/process-instances/${String(stored[2].id)}` }, body: stored[2] };
      }
    }
    if (typeof b.key !== 'string' || !b.key) throw new EngineProblem(400, 'VALIDATION_FAILED', 'key is required');
    const id = randomUUID();
    const pi: Json = {
      id, processDefinitionId: `${b.key}:1:test`, processDefinitionKey: b.key, processDefinitionVersion: 1,
      tenantId: this.tenantId, state: 'ACTIVE', ended: false, startTime: iso(this.now),
    };
    if (b.businessKey !== undefined && b.businessKey !== null) pi.businessKey = b.businessKey;
    pi.rootProcessInstanceId = id;
    this.instances.push(pi);
    if (key !== undefined) this.idem.set(idemKey, [fp, 201, pi]);
    return { status: 201, headers: { Location: `${API_PREFIX}/process-instances/${id}` }, body: pi };
  }

  private fetchAndLock(b: Json): Json[] {
    const worker = b.workerId;
    if (typeof worker !== 'string' || !worker.trim()) throw new EngineProblem(400, 'VALIDATION_FAILED', 'workerId is required');
    const topics = b.topics;
    if (!Array.isArray(topics) || topics.length === 0) throw new EngineProblem(400, 'VALIDATION_FAILED', 'at least one topic is required');
    const byName = new Map<string, Json>();
    for (const t of topics) {
      const name = t && typeof t === 'object' ? (t as Json).name : undefined;
      if (typeof name !== 'string' || !name.trim()) throw new EngineProblem(400, 'TOPIC_NAME_REQUIRED', 'every topic needs a name');
      byName.set(name, t as Json);
    }
    const mt = b.maxTasks;
    const n = typeof mt === 'number' ? Math.max(1, Math.min(100, Math.trunc(mt))) : 1;
    const candidates = [...this.work.values()];
    if (b.usePriority === true) candidates.sort((x, y) => y.priority - x.priority); // stable sort
    const out: Json[] = [];
    for (const w of candidates) {
      if (out.length >= n) break;
      const t = byName.get(w.topic);
      if (!t || w.state !== 'AVAILABLE' || w.deleted || w.availableAt > this.now) continue;
      if (t.processDefinitionKey != null && t.processDefinitionKey !== w.processDefinitionKey) continue;
      if (t.businessKey != null && t.businessKey !== w.businessKey) continue;
      const ms = typeof t.lockDurationMs === 'number' ? t.lockDurationMs : 0;
      const d = ms <= 0 ? DEFAULT_LOCK_MS : Math.min(ms, MAX_LOCK_MS);
      w.row = { lockVersion: w.row ? w.row.lockVersion + 1 : 1, attemptId: randomUUID(), workerId: worker, expiresAt: this.now + d };
      w.state = 'LOCKED';
      w.cancelRequested = false;
      w.attempts++;
      out.push(this.external(w, t));
    }
    return out;
  }

  private external(w: Work, t: Json): Json {
    const r = w.row!;
    const names = Array.isArray(t.variables) ? (t.variables as unknown[]) : undefined;
    const variables: Json = {};
    for (const [k, v] of Object.entries(w.variables)) if (!names || names.includes(k)) variables[k] = v;
    const op: Json = { state: w.ledgerState, attempts: w.attempts, mayDispatch: w.ledgerState !== 'SUCCEEDED' && w.ledgerState !== 'UNKNOWN_OUTCOME' };
    if (w.storedOutcome) op.storedOutcome = w.storedOutcome;
    const e: Json = {
      id: w.id, topic: w.topic, tenantId: w.tenantId, processInstanceId: w.processInstanceId,
      processDefinitionKey: w.processDefinitionKey, activityId: 'task', activityInstanceId: w.activityInstanceId,
      variables, lockVersion: r.lockVersion, attemptId: r.attemptId, operationId: w.operationId,
      lockExpiresAt: iso(r.expiresAt!), priority: w.priority, operation: op,
    };
    if (w.retries !== null) e.retries = w.retries;
    if (w.businessKey !== undefined) e.businessKey = w.businessKey;
    if (w.errorMessage !== undefined) e.errorMessage = w.errorMessage;
    return e;
  }

  private extend(wid: string, b: Json): Json {
    const w = this.task(wid);
    this.fence(w, b);
    const ms = typeof b.newDurationMs === 'number' ? b.newDurationMs : 0;
    const d = ms <= 0 ? DEFAULT_LOCK_MS : Math.min(ms, MAX_LOCK_MS);
    w.row!.expiresAt = this.now + d;
    return { id: wid, lockVersion: b.lockVersion, lockExpiresAt: iso(w.row!.expiresAt), cancelRequested: w.cancelRequested };
  }

  private complete(wid: string, b: Json): void {
    this.gone(wid, true, b.variables);
    const outcome = canonical({ output: b.variables ?? null, outcome: b.outcome ?? null });
    const done = this.work.get(wid);
    if (done && done.state === 'COMPLETED' && done.completion && done.completion[0] === b.lockVersion && done.completion[1] === outcome) {
      return; // the same completion again (a retried request): answered as the first one
    }
    const w = this.task(wid);
    this.fence(w, b);
    w.state = 'COMPLETED';
    w.ledgerState = 'SUCCEEDED';
    w.row = null;
    w.completion = [b.lockVersion, outcome];
    w.completions++;
    w.output = (b.variables as Json | undefined) ?? {};
    const o = b.outcome && typeof b.outcome === 'object' ? (b.outcome as Json) : undefined;
    w.storedOutcome = { output: w.output, providerRef: o?.reference ?? null, duplicate: false };
  }

  private fail(wid: string, b: Json): void {
    const probe = this.work.get(wid);
    const retries = b.retries;
    if (probe && this.irreversibleTopics.has(probe.topic) && (retries === undefined || retries === null || (retries as number) > 0)) {
      this.outcomeUnknown(wid, { workerId: b.workerId, lockVersion: b.lockVersion, attemptId: b.attemptId,
        detail: `failure on irreversible topic: ${String(b.errorMessage)}` });
      return;
    }
    this.gone(wid, false, null);
    const w = this.task(wid);
    this.fence(w, b);
    const left = typeof retries === 'number' ? Math.max(0, Math.trunc(retries)) : Math.max(0, (w.retries ?? 1) - 1);
    w.retries = left;
    w.errorMessage = typeof b.errorMessage === 'string' ? b.errorMessage : undefined;
    this.end(w);
    if (left > 0) {
      w.state = 'AVAILABLE';
      w.availableAt = this.now + Math.max(0, typeof b.retryTimeoutMs === 'number' ? b.retryTimeoutMs : 0);
    } else {
      w.state = 'INCIDENT';
      w.ledgerState = 'FAILED';
    }
  }

  private bpmnError(wid: string, b: Json): void {
    if (typeof b.errorCode !== 'string' || !b.errorCode.trim()) throw new EngineProblem(400, 'VALIDATION_FAILED', 'errorCode is required');
    this.gone(wid, false, b.variables);
    const w = this.task(wid);
    this.fence(w, b);
    w.state = 'COMPLETED_WITH_ERROR';
    w.row = null;
    w.errorCode = b.errorCode;
    w.errorMessage = typeof b.errorMessage === 'string' ? b.errorMessage : undefined;
  }

  private unlock(wid: string, b: Json): void {
    const w = this.task(wid);
    this.fence(w, b);
    this.end(w);
    w.state = 'AVAILABLE';
  }

  private outcomeUnknown(wid: string, b: Json): Json {
    this.gone(wid, true, b.evidence);
    const w = this.task(wid);
    this.fence(w, b);
    const item = this.toUnknown(w, typeof b.detail === 'string' ? b.detail : undefined);
    return { workId: wid, operationId: w.operationId, reconciliationItemId: item.id, state: 'UNKNOWN_OUTCOME' };
  }

  private cancelAck(wid: string, b: Json): void {
    const w = this.work.get(wid);
    if (!w || !w.cancelRequested || (w.state !== 'LOCKED' && w.state !== 'AVAILABLE')) throw notFound('cancel request for work', wid);
    const current = w.row ? w.row.lockVersion : null;
    if (b.lockVersion !== current) {
      throw stale(wid, 'STALE_VERSION', `no CANCEL_REQUESTED tombstone with lockVersion ${String(b.lockVersion)}`);
    }
    w.state = 'CANCELLED';
    w.row = null;
  }
}
