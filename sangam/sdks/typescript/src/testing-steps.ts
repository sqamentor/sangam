// SPDX-License-Identifier: Apache-2.0
/**
 * The step journal of `SangamTestEngine` (M-025, R37): `GET/PUT /work/{id}/step?stepName=` and
 * `GET /work/{id}/steps`, keyed by (operationId, step name) and fenced by the lease like the server (`WorkStepJournal`
 * + `WorkPort.inFencedWork`). Same transitions: INTENT on a new, INTENT or FAILED step; SUCCEEDED/FAILED need an
 * INTENT (409 STEP_INTENT_REQUIRED); SUCCEEDED is final (409 STEP_ALREADY_RECORDED) unless an identical repeat (same
 * result and providerRef); an identical repeat of INTENT/FAILED by the same attempt is a no-op. Internal to the engine.
 */

type Json = Record<string, unknown>;

/** What the journal needs of an engine work item. */
export interface JournalWork {
  id: string;
  operationId: string;
  deleted: boolean;
}

/** The engine's error type and helpers, injected so this file has no import cycle with `testing.ts`. */
export interface JournalHooks<W extends JournalWork> {
  work(id: string): W | undefined;
  /** The engine's fence (order NO_LEASE, STALE_VERSION, NOT_OWNER, EXPIRED, ATTEMPT_MISMATCH); throws a problem. */
  fence(w: W, body: Json): void;
  /**
   * Whether the work's external task still exists on the server: open, in an incident, or held for reconciliation.
   * Completed, BPMN-error, cancelled or failed work has none, so a step call on it is 404 like any unknown work (SL-174).
   */
  open(w: W): boolean;
  problem(status: number, code: string, detail: string, extensions?: Json): Error;
  notFound(what: string, id: string): Error;
  nowMs(): number;
}

interface Row {
  operationId: string;
  name: string;
  workId: string;
  state: string;
  result: unknown;
  providerRef: string | null;
  error: string | null;
  attemptId: string;
  recordedAt: string;
}

const STATES: ReadonlySet<string> = new Set(['INTENT', 'SUCCEEDED', 'FAILED']);

function canonical(v: unknown): string {
  return JSON.stringify(v, (_k, val: unknown) => {
    if (val && typeof val === 'object' && !Array.isArray(val)) {
      const o = val as Json;
      return Object.fromEntries(Object.keys(o).sort().map((k) => [k, o[k]]));
    }
    return val;
  }) ?? 'null';
}

const nullable = (v: unknown): string | null => (typeof v === 'string' ? v : null);

export class EngineSteps<W extends JournalWork> {
  /** operationId -> step name -> row, in recording order. */
  private readonly steps = new Map<string, Map<string, Row>>();

  constructor(private readonly hooks: JournalHooks<W>) {}

  /**
   * Serves a request whose path (without /api/v1) is `/work/{id}/steps` or `/work/{id}/step` (the step name in
   * the stepName query, '/' allowed, as on the server: UI-DEMO-1); else undefined.
   */
  route(method: string, path: string, query: URLSearchParams | undefined, body: Json): { status: number; body: unknown } | undefined {
    const parts = path.split('/').filter((p) => p);
    if (parts[0] !== 'work' || (parts[2] !== 'steps' && parts[2] !== 'step') || parts.length !== 3) return undefined;
    const workId = decodeURIComponent(parts[1]!);
    let name: string | undefined;
    if (parts[2] === 'step') {
      name = query?.get('stepName') ?? undefined;
      if (name === undefined) throw this.hooks.problem(400, 'VALIDATION_FAILED', 'stepName is required');
    }
    if (name === undefined && method === 'GET') return { status: 200, body: this.list(workId) };
    if (name !== undefined && method === 'GET') {
      const raw = query?.get('lockVersion') ?? '';
      const lv = raw.trim() === '' ? NaN : Number(raw);
      if (!Number.isInteger(lv)) throw this.hooks.problem(400, 'VALIDATION_FAILED', 'lockVersion is required');
      return { status: 200, body: this.get(workId, name, query?.get('workerId') ?? undefined, lv, query?.get('attemptId') ?? undefined) };
    }
    if (name !== undefined && method === 'PUT') return { status: 200, body: this.put(workId, name, body) };
    throw this.hooks.problem(405, 'METHOD_NOT_ALLOWED', `${method} ${path} is not served by the SDK test engine`);
  }

  get(workId: string, name: string, workerId: string | undefined, lockVersion: number, attemptId: string | undefined): Json {
    const w = this.fenced(workId, { workerId, lockVersion, attemptId }, name);
    const r = this.steps.get(w.operationId)?.get(name);
    if (!r) throw this.hooks.notFound('work step', name);
    return view(r);
  }

  put(workId: string, name: string, body: Json): Json {
    const w = this.fenced(workId, body, name);
    const attemptId = body.attemptId as string;
    const state = body.state;
    if (typeof state !== 'string' || !STATES.has(state)) {
      throw this.hooks.problem(400, 'VALIDATION_FAILED', 'state must be INTENT, SUCCEEDED or FAILED');
    }
    const result = body.result === undefined ? null : body.result;
    const ref = nullable(body.providerRef);
    const error = nullable(body.error);
    let op = this.steps.get(w.operationId);
    if (!op) {
      op = new Map();
      this.steps.set(w.operationId, op);
    }
    let r = op.get(name);
    if (!r) {
      if (state !== 'INTENT') throw this.intentRequired(name, undefined);
      r = { operationId: w.operationId, name, workId, state, result: null, providerRef: null, error: null, attemptId, recordedAt: '' };
      op.set(name, r);
    } else {
      if (identical(r, state, attemptId, result, ref, error)) return view(r);
      if (r.state === 'SUCCEEDED') {
        throw this.hooks.problem(409, 'STEP_ALREADY_RECORDED', `step ${name} already SUCCEEDED`);
      }
      if (state !== 'INTENT' && r.state !== 'INTENT') throw this.intentRequired(name, r.state);
    }
    r.workId = workId;
    r.state = state;
    r.result = result;
    r.providerRef = ref;
    r.error = error;
    r.attemptId = attemptId;
    r.recordedAt = new Date(this.hooks.nowMs()).toISOString();
    return view(r);
  }

  list(workId: string): Json[] {
    const w = this.hooks.work(workId);
    const rows: Row[] = [];
    // as WorkStepController.list: the operation of open work, else the rows this work id recorded
    if (w && !w.deleted && this.hooks.open(w)) {
      rows.push(...(this.steps.get(w.operationId)?.values() ?? []));
    } else {
      for (const m of this.steps.values()) for (const r of m.values()) if (r.workId === workId) rows.push(r);
      if (rows.length === 0) throw this.hooks.notFound('external work', workId);
    }
    return rows.map(view);
  }

  private fenced(workId: string, b: Json, name: string): W {
    if (name.length === 0 || name.length > 128 || ![...name].every((c) => c.charCodeAt(0) >= 0x20 && c.charCodeAt(0) <= 0x7e)) {
      throw this.hooks.problem(400, 'VALIDATION_FAILED', 'stepName must be 1-128 printable ASCII characters');
    }
    const att = b.attemptId;
    if (typeof att !== 'string' || att.trim() === '') throw this.hooks.problem(400, 'VALIDATION_FAILED', 'attemptId is required');
    const w = this.hooks.work(workId);
    if (!w) throw this.hooks.notFound('external work', workId);
    // as OperatonWorkAdapter.inFencedWork: a deleted instance (tombstone) is 410, any other ended work 404, then the fence
    if (w.deleted) {
      throw this.hooks.problem(410, 'GONE_INSTANCE_DELETED', 'The instance of this work was deleted', { workId });
    }
    if (!this.hooks.open(w)) throw this.hooks.notFound('external work', workId);
    this.hooks.fence(w, b);
    return w;
  }

  private intentRequired(name: string, current: string | undefined): Error {
    return this.hooks.problem(409, 'STEP_INTENT_REQUIRED', `step ${name} has no INTENT${current === undefined ? '' : ` (it is ${current})`}`);
  }
}

function identical(r: Row, state: string, attemptId: string, result: unknown, ref: string | null, error: string | null): boolean {
  if (r.state !== state) return false;
  if (state === 'SUCCEEDED') return canonical(r.result) === canonical(result) && r.providerRef === ref;
  return r.attemptId === attemptId && canonical(r.result) === canonical(result) && r.providerRef === ref && r.error === error;
}

function view(r: Row): Json {
  return {
    operationId: r.operationId, name: r.name, state: r.state, result: r.result, providerRef: r.providerRef,
    error: r.error, attemptId: r.attemptId, recordedAt: r.recordedAt,
  };
}
