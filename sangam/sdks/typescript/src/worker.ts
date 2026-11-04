// SPDX-License-Identifier: Apache-2.0
import { hostname } from 'node:os';
import { randomUUID } from 'node:crypto';
import type { SangamClient } from './client.js';
import { GoneError, SangamApiError, StaleLeaseError, TransportError } from './errors.js';
import { delay } from './http.js';
import { withFields, type LogFields, type Logger } from './logger.js';
import type { ExternalWork, TopicRequest, Variables } from './models.js';
import { fromTypedValue, isTypedValue } from './typed.js';

// ---------------------------------------------------------------- handler outcomes

/** A business error routed by the BPMN model (error boundary event). Not retried. */
export class BpmnError extends Error {
  constructor(readonly errorCode: string, message?: string, readonly variables?: Variables) {
    super(message ?? errorCode);
    this.name = 'BpmnError';
  }
}

/**
 * The handler cannot know whether the downstream effect happened (e.g. a payment call timed out after sending).
 * Reported as `outcome-unknown`: UNKNOWN_OUTCOME reconciliation item, never repeated automatically (VI.3.2 rules 4–5, AC-03).
 * The reason and `details` are sent as the contract's `detail`; `variables` are sent as `evidence`.
 */
export class OutcomeUnknownError extends Error {
  readonly details?: string;
  readonly variables?: Variables;
  constructor(reason: string, opts: { details?: string; variables?: Variables; cause?: unknown } = {}) {
    super(reason, { cause: opts.cause });
    this.name = 'OutcomeUnknownError';
    this.details = opts.details;
    this.variables = opts.variables;
  }
}

/**
 * A technical failure where the handler asserts that no external effect happened (retry is safe).
 * `retries` / `retryTimeoutMs` override the subscription defaults; `retries: 0` raises an incident.
 */
export class TaskFailedError extends Error {
  readonly details?: string;
  readonly retries?: number;
  readonly retryTimeoutMs?: number;
  constructor(message: string, opts: { details?: string; retries?: number; retryTimeoutMs?: number; cause?: unknown } = {}) {
    super(message, { cause: opts.cause });
    this.name = 'TaskFailedError';
    this.details = opts.details;
    this.retries = opts.retries;
    this.retryTimeoutMs = opts.retryTimeoutMs;
  }
}

/**
 * Cooperative stop: thrown by `ctx.throwIfCancelled()` (or the handler). If the server requested cancel the worker
 * sends `cancel-ack` (VI.3.1); if the lease was lost or the worker is stopping, the item is abandoned or unlocked.
 */
export class TaskCancelledError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TaskCancelledError';
  }
}

// ---------------------------------------------------------------- back-off

/** Exponential back-off with jitter: `min(max, initial * factor^(n-1))` +/- `jitter` fraction. */
export interface Backoff {
  initialMs: number;
  maxMs: number;
  factor: number;
  jitter: number;
}

export const fetchBackoffDefaults: Backoff = { initialMs: 500, maxMs: 60_000, factor: 2, jitter: 0.2 };
/** R28 default: first retry after about one minute, growing, capped at 10 minutes. */
export const retryBackoffDefaults: Backoff = { initialMs: 60_000, maxMs: 600_000, factor: 2, jitter: 0.2 };
export const reportBackoffDefaults: Backoff = { initialMs: 500, maxMs: 15_000, factor: 2, jitter: 0.2 };

/** Delay for the `n`-th consecutive failure (1-based). */
export function backoffDelay(b: Backoff, n: number): number {
  const capped = Math.min(b.initialMs * Math.pow(b.factor, Math.max(0, n - 1)), b.maxMs);
  const j = capped * b.jitter;
  return Math.max(0, Math.floor(capped + (j === 0 ? 0 : (Math.random() * 2 - 1) * j)));
}

// ---------------------------------------------------------------- context

/**
 * What a handler sees while it holds the lease: the item, its identities, the live lease state kept fresh by
 * the heartbeat, an AbortSignal (aborted on lease loss, cancel request or forced shutdown), a logger pre-filled
 * with the work fields, and the client.
 */
export class TaskContext {
  readonly logger: Logger;
  readonly logFields: Readonly<LogFields>;
  /** @internal */ lockVersionValue: number;
  /** @internal */ lockExpiresAtMs: number | undefined;
  /** @internal */ cancelRequestedFlag = false;
  /** @internal */ leaseLostFlag = false;
  /** @internal */ shuttingDownFlag = false;
  /** @internal */ readonly abort = new AbortController();

  constructor(readonly work: ExternalWork, readonly workerId: string, readonly client: SangamClient, logger: Logger) {
    this.lockVersionValue = work.lockVersion;
    this.lockExpiresAtMs = work.lockExpiresAt ? Date.parse(work.lockExpiresAt) : undefined;
    const f: LogFields = { workerId, workId: work.id, topic: work.topic, lockVersion: work.lockVersion };
    if (work.operationId) f.operationId = work.operationId;
    if (work.attemptId) f.attemptId = work.attemptId;
    if (work.businessKey) f.businessKey = work.businessKey;
    if (work.processInstanceId) f.processInstanceId = work.processInstanceId;
    this.logFields = Object.freeze(f);
    this.logger = withFields(logger, f);
  }

  get workId(): string { return this.work.id; }
  get topic(): string { return this.work.topic; }
  get businessKey(): string | undefined { return this.work.businessKey; }
  get processInstanceId(): string | undefined { return this.work.processInstanceId; }
  /** R36 stable business-operation id (falls back to the work id). */
  get operationId(): string { return this.work.operationId ?? this.work.id; }
  /** Key to forward to downstream systems (= operationId, VI.3.2 rule 4). */
  get idempotencyKey(): string { return this.operationId; }
  /** Changes per lease (A-2). */
  get attemptId(): string { return this.work.attemptId; }
  /** Current fencing token. */
  get lockVersion(): number { return this.lockVersionValue; }
  get lockExpiresAt(): Date | undefined { return this.lockExpiresAtMs === undefined ? undefined : new Date(this.lockExpiresAtMs); }
  /** The raw VariableMap (typed values still wrapped). */
  get variables(): Variables { return this.work.variables ?? {}; }
  /**
   * A variable with any R33 TypedValue unwrapped (`fromTypedValue`): Decimal → exact `string`, BigInteger/Long →
   * `bigint`, Date → `Date`, LocalDate → `'YYYY-MM-DD'`, Bytes → `Uint8Array`; plain JSON as is.
   */
  variable<T = unknown>(name: string): T | undefined { return fromTypedValue(this.variables[name]) as T | undefined; }
  /** The raw (possibly typed) value. */
  rawVariable(name: string): unknown { return this.variables[name]; }
  /** Exact decimal string of a Decimal (or a plain number/string) variable, e.g. `'12.50'`. */
  decimal(name: string): string | undefined {
    const v = this.variables[name];
    if (v === undefined || v === null) return undefined;
    if (isTypedValue(v)) return v.value === null || v.value === undefined ? undefined : String(v.value);
    return String(v);
  }
  /** A BigInteger/Long (or integral plain) variable as `bigint`. */
  bigint(name: string): bigint | undefined {
    const v = this.variable<unknown>(name);
    return v === undefined || v === null ? undefined : BigInt(v as string | number | bigint);
  }
  /** A Date (or ISO string) variable as `Date`. */
  date(name: string): Date | undefined {
    const v = this.variable<unknown>(name);
    return v === undefined || v === null ? undefined : v instanceof Date ? v : new Date(String(v));
  }
  /** True once a heartbeat reported `cancelRequested` (VI.3.1 CANCEL_REQUESTED). */
  get cancelRequested(): boolean { return this.cancelRequestedFlag; }
  /** True once the lease is gone (409, 410, or expiry while unreachable); results are discarded. */
  get leaseLost(): boolean { return this.leaseLostFlag; }
  get shuttingDown(): boolean { return this.shuttingDownFlag; }
  /** Pass this to downstream `fetch` calls so they stop when the lease is lost. */
  get signal(): AbortSignal { return this.abort.signal; }

  /** Cooperative checkpoint. */
  throwIfCancelled(): void {
    if (this.cancelRequestedFlag) throw new TaskCancelledError('cancel requested by server');
    if (this.leaseLostFlag) throw new TaskCancelledError('lease lost');
    if (this.shuttingDownFlag) throw new TaskCancelledError('worker shutting down');
  }
}

/**
 * Handles one locked item; runs only after the LOCKED confirmation (A-1).
 * Return variables → `complete`; throw `BpmnError` → `bpmn-error`; `OutcomeUnknownError` → `outcome-unknown`;
 * `TaskFailedError` → `fail`; `TaskCancelledError` → `cancel-ack` / `unlock`; any other error → `fail` with
 * retries, or `outcome-unknown` on irreversible topics (VI.3.2 rule 5).
 * Downstream calls should send `ctx.operationId` as their idempotency key (R36).
 */
export type TaskHandler = (ctx: TaskContext) => Promise<Variables | void | undefined> | Variables | void | undefined;

export interface SubscriptionOptions {
  /** Lease per fetch (VI.3.2 rule 3: default 5 min, max 24 h); heartbeat at lease/3. */
  lockDurationMs?: number;
  /** Variables to fetch (omit = all). */
  variables?: string[];
  /** Fetch only local variables of the activity. */
  localVariables?: boolean;
  /** Only items of this process definition key. */
  processDefinitionKey?: string;
  /** Only items of this business key. */
  businessKey?: string;
  /** Retries for the first failure when the item carries none (default 3). */
  retries?: number;
  retryBackoff?: Backoff;
  /** Treat unexpected handler errors as outcome-unknown even when the server does not mark `effect=irreversible`. */
  irreversible?: boolean;
}

export interface TopicSubscription
  extends Required<Omit<SubscriptionOptions, 'variables' | 'localVariables' | 'processDefinitionKey' | 'businessKey'>> {
  topic: string;
  handler: TaskHandler;
  variables?: string[];
  localVariables?: boolean;
  processDefinitionKey?: string;
  businessKey?: string;
}

const MAX_LOCK_MS = 24 * 3600 * 1000;

export function topicSubscription(topic: string, handler: TaskHandler, o: SubscriptionOptions = {}): TopicSubscription {
  if (!topic) throw new TypeError('topic is required');
  const lockDurationMs = o.lockDurationMs ?? 300_000;
  if (lockDurationMs > MAX_LOCK_MS) throw new RangeError('lockDurationMs exceeds 24 h (VI.3)');
  if (lockDurationMs < 3_000) throw new RangeError('lockDurationMs must be at least 3 s');
  const retries = o.retries ?? 3;
  if (retries < 0) throw new RangeError('retries must be >= 0');
  return {
    topic,
    handler,
    lockDurationMs,
    variables: o.variables ? [...o.variables] : undefined,
    localVariables: o.localVariables,
    processDefinitionKey: o.processDefinitionKey,
    businessKey: o.businessKey,
    retries,
    retryBackoff: o.retryBackoff ?? retryBackoffDefaults,
    irreversible: o.irreversible ?? false,
  };
}

export interface WorkerOptions {
  workerId?: string;
  /** Maximum handlers running at once (default 8). */
  maxConcurrency?: number;
  maxTasksPerFetch?: number;
  /** Long-poll wait on the server (default 30 s). */
  asyncResponseTimeoutMs?: number;
  /** Heartbeat interval as a fraction of the lease (VI.3: 1/3). */
  heartbeatFraction?: number;
  fetchBackoff?: Backoff;
  reportBackoff?: Backoff;
  /** Ask the server to hand out higher-priority work first (`usePriority`). */
  usePriority?: boolean;
  /** Wait this long for in-flight handlers on stop before aborting them (default 30 s). */
  shutdownTimeoutMs?: number;
  logger?: Logger;
}

type OutcomeKind = 'complete' | 'bpmn-error' | 'outcome-unknown' | 'fail' | 'cancel-ack' | 'unlock';
interface Outcome {
  kind: OutcomeKind;
  variables?: Variables;
  error?: unknown;
}

function isAbortError(e: unknown): boolean {
  for (let t: unknown = e; t; t = (t as { cause?: unknown }).cause) {
    if (t instanceof Error && (t.name === 'AbortError' || t.name === 'TimeoutError' || t instanceof TaskCancelledError)) return true;
    if (typeof t !== 'object') break;
  }
  return false;
}

function errorText(e: unknown): string {
  if (e instanceof Error) return e.message || e.name;
  return String(e);
}

function stackOf(e: unknown): string | undefined {
  if (e === undefined) return undefined;
  const s = e instanceof Error ? (e.stack ?? `${e.name}: ${e.message}`) : String(e);
  return s.length > 4000 ? s.substring(0, 4000) : s;
}

function defaultWorkerId(): string {
  let host = 'worker';
  try {
    host = hostname();
  } catch {
    // keep default
  }
  return `${host}-${process.pid}-${randomUUID().substring(0, 8)}`;
}

/**
 * External-worker runtime for the Sangam worker API (VI.3, A-1, A-2, R9, R20, R36).
 *
 * - Long-poll fetch-and-lock for all subscribed topics, asking only for free slots; fetch errors back off with jitter.
 * - A-1: a handler starts only for items that came back LOCKED with an attemptId.
 * - Heartbeat (`extend-lock`) every lease/3; the answer carries `cancelRequested`.
 * - Fencing: every call carries `(workerId, lockVersion, attemptId)`; 409 stale-lease / 410 gone aborts the
 *   handler and discards its result.
 * - Idempotency-Key per call `<operationId>:<action>:<lockVersion>` (R41).
 * - Result calls retried until the lease expires; failures reported with decreasing retries and growing back-off.
 * - Graceful `stop()`: no new fetches, wait for in-flight handlers, then abort the rest and unlock them.
 */
export class SangamWorker {
  readonly workerId: string;
  private readonly maxConcurrency: number;
  private readonly maxTasksPerFetch: number;
  private readonly asyncResponseTimeoutMs: number;
  private readonly heartbeatFraction: number;
  private readonly fetchBackoff: Backoff;
  private readonly reportBackoff: Backoff;
  private readonly shutdownTimeoutMs: number;
  private readonly usePriority?: boolean;
  private readonly log: Logger;
  private readonly subscriptions = new Map<string, TopicSubscription>();
  private readonly inFlight = new Map<string, { ctx: TaskContext; done: Promise<void> }>();
  private heartbeatSeq = 0;
  private running = false;
  private stopping = false;
  private pollAbort?: AbortController;
  private pollLoopDone?: Promise<void>;
  private slotWaiter?: () => void;
  private stopPromise?: Promise<void>;
  private signalHandlers: Array<[NodeJS.Signals, () => void]> = [];

  constructor(private readonly client: SangamClient, options: WorkerOptions = {}) {
    this.workerId = options.workerId ?? defaultWorkerId();
    this.maxConcurrency = options.maxConcurrency ?? 8;
    if (this.maxConcurrency < 1) throw new RangeError('maxConcurrency must be >= 1');
    this.maxTasksPerFetch = Math.min(options.maxTasksPerFetch ?? 8, this.maxConcurrency);
    if (this.maxTasksPerFetch < 1) throw new RangeError('maxTasksPerFetch must be >= 1');
    this.asyncResponseTimeoutMs = options.asyncResponseTimeoutMs ?? 30_000;
    this.heartbeatFraction = options.heartbeatFraction ?? 1 / 3;
    if (this.heartbeatFraction <= 0 || this.heartbeatFraction >= 1) throw new RangeError('heartbeatFraction must be in (0,1)');
    this.fetchBackoff = options.fetchBackoff ?? fetchBackoffDefaults;
    this.reportBackoff = options.reportBackoff ?? reportBackoffDefaults;
    this.shutdownTimeoutMs = options.shutdownTimeoutMs ?? 30_000;
    this.usePriority = options.usePriority;
    this.log = options.logger ?? client.logger;
  }

  /** Registers (or replaces) a handler; takes effect on the next fetch. */
  subscribe(topic: string, handler: TaskHandler, options?: SubscriptionOptions): this;
  subscribe(subscription: TopicSubscription): this;
  subscribe(a: string | TopicSubscription, handler?: TaskHandler, options?: SubscriptionOptions): this {
    const sub = typeof a === 'string' ? topicSubscription(a, handler as TaskHandler, options) : a;
    this.subscriptions.set(sub.topic, sub);
    this.log.log('info', 'worker.subscribed', 'subscribed', { workerId: this.workerId, topic: sub.topic });
    return this;
  }

  /** Stops fetching a topic; running items finish normally. */
  unsubscribe(topic: string): this {
    this.subscriptions.delete(topic);
    this.log.log('info', 'worker.unsubscribed', 'unsubscribed', { workerId: this.workerId, topic });
    return this;
  }

  get inFlightCount(): number {
    return this.inFlight.size;
  }

  /** Starts the poll loop and returns. */
  start(): this {
    if (this.running) return this;
    this.running = true;
    this.stopping = false;
    this.stopPromise = undefined;
    this.pollAbort = new AbortController();
    this.log.log('info', 'worker.started', 'worker started', {
      workerId: this.workerId, maxConcurrency: this.maxConcurrency, topics: [...this.subscriptions.keys()],
    });
    this.pollLoopDone = this.pollLoop().catch((e: unknown) => {
      this.log.log('error', 'worker.poller.crashed', 'poll loop crashed', { workerId: this.workerId }, e);
    });
    return this;
  }

  /** Starts, stops gracefully on SIGTERM/SIGINT, and resolves once stopped. */
  async run(): Promise<void> {
    this.handleSignals();
    this.start();
    await this.pollLoopDone;
    await this.stop();
  }

  /** Calls `stop()` on SIGTERM and SIGINT. */
  handleSignals(signals: NodeJS.Signals[] = ['SIGTERM', 'SIGINT']): this {
    for (const s of signals) {
      const h = (): void => {
        void this.stop();
      };
      process.once(s, h);
      this.signalHandlers.push([s, h]);
    }
    return this;
  }

  /** Graceful shutdown: no new fetches, wait up to `timeoutMs` for handlers, then abort the rest (they are unlocked). */
  stop(timeoutMs: number = this.shutdownTimeoutMs): Promise<void> {
    if (!this.running) return Promise.resolve();
    if (!this.stopPromise) this.stopPromise = this.doStop(timeoutMs);
    return this.stopPromise;
  }

  private async doStop(timeoutMs: number): Promise<void> {
    this.stopping = true;
    this.log.log('info', 'worker.stopping', 'stopping: no new fetches', { workerId: this.workerId, inFlight: this.inFlight.size });
    this.pollAbort?.abort(new TaskCancelledError('worker shutting down'));
    this.slotWaiter?.();
    await this.pollLoopDone;
    const all = (): Promise<unknown> => Promise.allSettled([...this.inFlight.values()].map((v) => v.done));
    let timer: NodeJS.Timeout | undefined;
    const timedOut = await Promise.race([
      all().then(() => false),
      new Promise<boolean>((r) => {
        timer = setTimeout(() => r(true), timeoutMs);
      }),
    ]);
    if (timer) clearTimeout(timer);
    if (timedOut) {
      this.log.log('warn', 'worker.shutdown.timeout', 'aborting handlers still running', {
        workerId: this.workerId, inFlight: this.inFlight.size,
      });
      for (const { ctx } of this.inFlight.values()) {
        ctx.shuttingDownFlag = true;
        ctx.abort.abort(new TaskCancelledError('worker shutting down'));
      }
      await Promise.race([all(), delay(10_000).catch(() => undefined)]);
    }
    for (const [s, h] of this.signalHandlers) process.removeListener(s, h);
    this.signalHandlers = [];
    this.running = false;
    this.log.log('info', 'worker.stopped', 'worker stopped', { workerId: this.workerId });
  }

  // ------------------------------------------------------------ fetch loop

  private waitForSlot(): Promise<void> {
    return new Promise((resolve) => {
      this.slotWaiter = () => {
        this.slotWaiter = undefined;
        resolve();
      };
    });
  }

  private slotFreed(): void {
    this.slotWaiter?.();
  }

  private async pollLoop(): Promise<void> {
    let consecutiveErrors = 0;
    const signal = this.pollAbort!.signal;
    while (!this.stopping) {
      const free = this.maxConcurrency - this.inFlight.size;
      if (free <= 0) {
        await this.waitForSlot();
        continue;
      }
      const permits = Math.min(free, this.maxTasksPerFetch);
      const subs = [...this.subscriptions.values()];
      if (subs.length === 0) {
        try {
          await delay(1_000, signal);
        } catch {
          break;
        }
        continue;
      }
      let items: ExternalWork[];
      try {
        const topics: TopicRequest[] = subs.map((s) => ({
          name: s.topic,
          lockDurationMs: s.lockDurationMs,
          ...(s.variables ? { variables: s.variables } : {}),
          ...(s.localVariables !== undefined ? { localVariables: s.localVariables } : {}),
          ...(s.processDefinitionKey ? { processDefinitionKey: s.processDefinitionKey } : {}),
          ...(s.businessKey ? { businessKey: s.businessKey } : {}),
        }));
        items = await this.client.work.fetchAndLock(
          {
            workerId: this.workerId,
            topics,
            maxTasks: permits,
            asyncResponseTimeoutMs: this.asyncResponseTimeoutMs,
            ...(this.usePriority !== undefined ? { usePriority: this.usePriority } : {}),
          },
          signal,
        );
        consecutiveErrors = 0;
      } catch (e) {
        if (this.stopping || signal.aborted) break;
        consecutiveErrors++;
        const d = backoffDelay(this.fetchBackoff, consecutiveErrors);
        this.log.log('warn', 'work.fetch.failed', 'fetch-and-lock failed; backing off', {
          workerId: this.workerId, attempt: consecutiveErrors, delayMs: d,
        }, e);
        try {
          await delay(d, signal);
        } catch {
          break;
        }
        continue;
      }
      let used = 0;
      for (const w of items) {
        if (used >= permits) {
          this.log.log('warn', 'work.overdelivered', 'server returned more items than requested; unlocking', {
            workerId: this.workerId, workId: w.id,
          });
          void this.unlockQuietly(w);
          continue;
        }
        const sub = this.subscriptions.get(w.topic);
        if (!sub) {
          this.log.log('warn', 'work.unsubscribed', 'no handler for topic; unlocking', {
            workerId: this.workerId, workId: w.id, topic: String(w.topic),
          });
          void this.unlockQuietly(w);
          continue;
        }
        if (!w.id || !w.attemptId) {
          // A-1: run only after the LOCKED confirmation with lockVersion + attemptId
          this.log.log('error', 'work.protocol.violation', 'item without LOCKED confirmation (attemptId); not started', {
            workerId: this.workerId, workId: String(w.id),
          });
          continue;
        }
        used++;
        this.launch(w, sub);
      }
    }
    this.log.log('debug', 'worker.poller.exit', 'poller exited', { workerId: this.workerId });
  }

  // ------------------------------------------------------------ one item

  private launch(work: ExternalWork, sub: TopicSubscription): void {
    const ctx = new TaskContext(work, this.workerId, this.client, this.log);
    const done = this.runItem(ctx, sub)
      .catch((e: unknown) => ctx.logger.log('error', 'work.crashed', 'worker internal error', undefined, e))
      .finally(() => {
        this.inFlight.delete(work.id);
        this.slotFreed();
      });
    this.inFlight.set(work.id, { ctx, done });
  }

  private async runItem(ctx: TaskContext, sub: TopicSubscription): Promise<void> {
    const hbStop = new AbortController();
    const heartbeat = this.heartbeatLoop(ctx, sub, hbStop.signal);
    let outcome: Outcome;
    try {
      ctx.logger.log('info', 'work.started', 'handler started');
      outcome = await this.invoke(ctx, sub);
    } finally {
      hbStop.abort();
      await heartbeat;
    }
    if (ctx.leaseLostFlag) {
      ctx.logger.log('warn', 'work.abandoned', 'lease lost; result discarded (another attempt owns it)', { outcome: outcome.kind });
      return;
    }
    await this.report(ctx, sub, outcome);
  }

  private async invoke(ctx: TaskContext, sub: TopicSubscription): Promise<Outcome> {
    try {
      const vars = await sub.handler(ctx);
      return { kind: 'complete', variables: vars ?? undefined };
    } catch (e) {
      if (e instanceof BpmnError) return { kind: 'bpmn-error', variables: e.variables, error: e };
      if (e instanceof OutcomeUnknownError) return { kind: 'outcome-unknown', variables: e.variables, error: e };
      if (e instanceof TaskFailedError) return { kind: 'fail', error: e };
      if (e instanceof TaskCancelledError) return { kind: ctx.cancelRequestedFlag ? 'cancel-ack' : 'unlock', error: e };
      if (isAbortError(e) && (ctx.shuttingDownFlag || ctx.leaseLostFlag || ctx.cancelRequestedFlag)) {
        return { kind: ctx.cancelRequestedFlag ? 'cancel-ack' : 'unlock', error: e };
      }
      const irreversible = sub.irreversible || (ctx.work.effect ?? '').toLowerCase() === 'irreversible';
      return { kind: irreversible ? 'outcome-unknown' : 'fail', error: e };
    }
  }

  private async heartbeatLoop(ctx: TaskContext, sub: TopicSubscription, stop: AbortSignal): Promise<void> {
    const lease = sub.lockDurationMs;
    const interval = Math.max(1_000, Math.floor(lease * this.heartbeatFraction));
    let failures = 0;
    while (!stop.aborted) {
      try {
        await delay(failures === 0 ? interval : Math.min(interval, backoffDelay(this.reportBackoff, failures)), stop);
      } catch {
        return;
      }
      if (stop.aborted) return;
      try {
        const key = `${ctx.operationId}:extend-lock:${ctx.lockVersion}:${++this.heartbeatSeq}`;
        const r = await this.client.work.extendLock(
          ctx.workId,
          { workerId: this.workerId, lockVersion: ctx.lockVersion, attemptId: ctx.attemptId, newDurationMs: lease },
          { idempotencyKey: key },
        );
        failures = 0;
        if (r) {
          if (r.lockExpiresAt) ctx.lockExpiresAtMs = Date.parse(r.lockExpiresAt);
          if (typeof r.lockVersion === 'number') ctx.lockVersionValue = r.lockVersion;
          if (r.cancelRequested && !ctx.cancelRequestedFlag) {
            ctx.cancelRequestedFlag = true;
            ctx.logger.log('warn', 'work.cancel.requested', 'server requested cancel');
            ctx.abort.abort(new TaskCancelledError('cancel requested by server'));
          }
        }
        ctx.logger.log('debug', 'work.heartbeat', 'lease extended', { lockExpiresAt: ctx.lockExpiresAt?.toISOString() });
      } catch (e) {
        if (e instanceof StaleLeaseError || e instanceof GoneError) {
          this.loseLease(ctx, e);
          return;
        }
        if (stop.aborted) return;
        failures++;
        if (ctx.lockExpiresAtMs !== undefined && Date.now() > ctx.lockExpiresAtMs) {
          this.loseLease(ctx, e);
          return;
        }
        ctx.logger.log('warn', 'work.heartbeat.failed', 'heartbeat failed; will retry before lease expiry', { failures }, e);
      }
    }
  }

  private loseLease(ctx: TaskContext, cause: unknown): void {
    ctx.leaseLostFlag = true;
    ctx.logger.log('warn', 'work.lease.lost', 'lease lost; aborting handler', undefined, cause);
    ctx.abort.abort(new TaskCancelledError('lease lost'));
  }

  // ------------------------------------------------------------ reporting

  private async report(ctx: TaskContext, sub: TopicSubscription, o: Outcome): Promise<void> {
    const action = o.kind;
    const key = `${ctx.operationId}:${action}:${ctx.lockVersion}`;
    const base = { workerId: this.workerId, lockVersion: ctx.lockVersion, attemptId: ctx.attemptId };
    const opts = { idempotencyKey: key };
    const w = this.client.work;
    let call: () => Promise<void>;
    switch (action) {
      case 'complete':
        call = () => w.complete(ctx.workId, { ...base, ...(o.variables ? { variables: o.variables } : {}) }, opts);
        break;
      case 'bpmn-error': {
        const be = o.error as BpmnError;
        call = () => w.bpmnError(ctx.workId, { ...base, errorCode: be.errorCode, errorMessage: be.message, variables: be.variables }, opts);
        break;
      }
      case 'outcome-unknown': {
        const ou = o.error instanceof OutcomeUnknownError ? o.error : undefined;
        const reason = ou ? ou.message : `unexpected error on irreversible topic: ${errorText(o.error)}`;
        const details = ou?.details ?? stackOf(o.error);
        const detail = details ? `${reason}
${details}` : reason;
        call = async () => {
          const r = await w.outcomeUnknown(ctx.workId, { ...base, detail, ...(o.variables ? { evidence: o.variables } : {}) }, opts);
          if (r.reconciliationItemId) {
            ctx.logger.log('info', 'work.reconciliation.opened', 'reconciliation item opened', {
              reconciliationItemId: r.reconciliationItemId, state: r.state,
            });
          }
        };
        break;
      }
      case 'fail': {
        const req = { ...base, ...this.failFields(ctx, sub, o.error) };
        call = () => w.fail(ctx.workId, req, opts);
        break;
      }
      case 'cancel-ack':
        call = () => w.cancelAck(ctx.workId, base, opts);
        break;
      default:
        call = () => w.unlock(ctx.workId, base, opts);
    }
    for (let attempt = 1; ; attempt++) {
      try {
        await call();
        const f: LogFields = { attempts: attempt };
        if (o.error !== undefined) f.error = errorText(o.error);
        ctx.logger.log('info', `work.${action}`, `reported ${action}`, f);
        return;
      } catch (e) {
        if (e instanceof StaleLeaseError || e instanceof GoneError) {
          ctx.leaseLostFlag = true;
          ctx.logger.log('warn', 'work.abandoned', `${action} rejected: lease no longer ours`, undefined, e);
          return;
        }
        if (e instanceof SangamApiError) {
          if (!e.retryable || !this.retryAllowed(ctx)) {
            ctx.logger.log('error', 'work.report.failed', `${action} rejected by server`, { status: e.status }, e);
            return;
          }
        } else if (e instanceof TransportError) {
          if (!this.retryAllowed(ctx)) {
            ctx.logger.log('error', 'work.report.gave-up',
              `${action} not delivered before lease expiry; the lease will expire and the same operationId is reused`,
              { attempts: attempt }, e);
            return;
          }
        } else {
          ctx.logger.log('error', 'work.report.failed', `${action} failed`, undefined, e);
          return;
        }
        await delay(backoffDelay(this.reportBackoff, attempt));
      }
    }
  }

  private retryAllowed(ctx: TaskContext): boolean {
    return ctx.lockExpiresAtMs === undefined || Date.now() < ctx.lockExpiresAtMs;
  }

  private failFields(ctx: TaskContext, sub: TopicSubscription, error: unknown) {
    const base = ctx.work.retries ?? sub.retries;
    let retries = Math.max(0, base - 1);
    let retryTimeoutMs = backoffDelay(sub.retryBackoff, Math.max(1, sub.retries - retries));
    let errorDetails = stackOf(error);
    if (error instanceof TaskFailedError) {
      if (error.retries !== undefined) retries = Math.max(0, error.retries);
      if (error.retryTimeoutMs !== undefined) retryTimeoutMs = error.retryTimeoutMs;
      if (error.details !== undefined) errorDetails = error.details;
    }
    let errorMessage = error === undefined ? 'failed' : errorText(error);
    if (errorMessage.length > 1000) errorMessage = errorMessage.substring(0, 1000);
    return { errorMessage, errorDetails, retries, retryTimeoutMs };
  }

  private async unlockQuietly(w: ExternalWork): Promise<void> {
    try {
      const op = w.operationId ?? w.id;
      await this.client.work.unlock(
        w.id,
        { workerId: this.workerId, lockVersion: w.lockVersion, attemptId: w.attemptId },
        { idempotencyKey: `${op}:unlock:${w.lockVersion}` },
      );
    } catch (e) {
      this.log.log('warn', 'work.unlock.failed', 'unlock failed; lease will expire', { workerId: this.workerId, workId: w.id }, e);
    }
  }
}
