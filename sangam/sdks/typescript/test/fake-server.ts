// SPDX-License-Identifier: Apache-2.0
// In-process fake of the Sangam worker API for unit tests: plugged in as the client's `fetch`, it records every
// request and answers fetch-and-lock from a queue and the result calls from scripted replies (default 204).
import { SangamClient } from '../src/client.js';
import type { LogFields, Logger, LogLevel } from '../src/logger.js';
import type { ExternalWork } from '../src/models.js';
import { SangamWorker, type WorkerOptions } from '../src/worker.js';

export interface Recorded {
  method: string;
  path: string;
  action: string;
  headers: Record<string, string>;
  body: Record<string, unknown> | undefined;
}

export interface Reply {
  status: number;
  body?: unknown;
}

export interface LogLine {
  level: LogLevel;
  event: string;
  fields?: LogFields;
}

/** Actions that end a work item (the worker's report). */
export const REPORT_ACTIONS: ReadonlySet<string> = new Set(['complete', 'fail', 'bpmn-error', 'outcome-unknown', 'unlock', 'cancel-ack']);

export class FakeServer {
  readonly calls: Recorded[] = [];
  readonly logs: LogLine[] = [];
  private readonly batches: ExternalWork[][] = [];
  private readonly replies = new Map<string, Reply[]>();
  private readonly waiters: Array<{ pred: (c: Recorded) => boolean; resolve: (c: Recorded) => void }> = [];

  readonly logger: Logger = {
    log: (level, event, _message, fields) => {
      this.logs.push({ level, event, fields });
    },
  };

  /** The next fetch-and-lock answers with these items. */
  enqueue(...items: ExternalWork[]): this {
    this.batches.push(items);
    return this;
  }

  /** Scripted answers for `POST /work/{id}/{action}`, used in order; afterwards 204. */
  reply(action: string, ...r: Reply[]): this {
    this.replies.set(action, [...(this.replies.get(action) ?? []), ...r]);
    return this;
  }

  callsOf(action: string): Recorded[] {
    return this.calls.filter((c) => c.action === action);
  }

  /** Resolves with the first recorded call (past or future) matching `pred`. */
  waitFor(pred: (c: Recorded) => boolean, timeoutMs = 5_000): Promise<Recorded> {
    const seen = this.calls.find(pred);
    if (seen) return Promise.resolve(seen);
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('timed out waiting for a matching call')), timeoutMs);
      this.waiters.push({
        pred,
        resolve: (c) => {
          clearTimeout(t);
          resolve(c);
        },
      });
    });
  }

  readonly fetch: typeof fetch = async (input, init) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url);
    const path = url.pathname.replace('/api/v1', '');
    const headers: Record<string, string> = {};
    for (const [k, v] of Object.entries((init?.headers ?? {}) as Record<string, string>)) headers[k.toLowerCase()] = v;
    const text = typeof init?.body === 'string' ? init.body : undefined;
    const rec: Recorded = {
      method: init?.method ?? 'GET',
      path,
      action: path.substring(path.lastIndexOf('/') + 1),
      headers,
      body: text ? (JSON.parse(text) as Record<string, unknown>) : undefined,
    };
    this.calls.push(rec);
    for (const w of [...this.waiters]) {
      if (w.pred(rec)) {
        this.waiters.splice(this.waiters.indexOf(w), 1);
        w.resolve(rec);
      }
    }
    if (rec.action === 'fetch-and-lock') {
      const batch = this.batches.shift();
      if (batch) return json(200, batch);
      await idle(25, init?.signal ?? undefined);
      return json(200, []);
    }
    const queued = this.replies.get(rec.action);
    const r = queued && queued.length > 0 ? queued.shift()! : { status: 204 };
    return json(r.status, r.body);
  };
}

function json(status: number, body: unknown): Response {
  const isProblem = status >= 400;
  return new Response(body === undefined || status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'content-type': isProblem ? 'application/problem+json' : 'application/json' },
  });
}

function idle(ms: number, signal: AbortSignal | undefined): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const t = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(t);
      reject(signal.reason);
    }, { once: true });
  });
}

/** A leased item as the server sends it after LOCKED (A-1); the lease runs one minute. */
export function workItem(over: Partial<ExternalWork> = {}): ExternalWork {
  return {
    id: 'wk-1',
    topic: 'pay',
    lockVersion: 3,
    attemptId: 'att-1',
    operationId: 'op-1',
    lockExpiresAt: new Date(Date.now() + 60_000).toISOString(),
    retries: 3,
    variables: {},
    ...over,
  };
}

export function clientFor(server: FakeServer): SangamClient {
  return new SangamClient({
    baseUrl: 'http://sangam.test/',
    fetch: server.fetch,
    logger: server.logger,
    // the worker's own report retry is under test, so the transport does not retry
    retryPolicy: { maxAttempts: 1, initialDelayMs: 0, maxDelayMs: 0, multiplier: 1 },
  });
}

export function workerFor(server: FakeServer, o: WorkerOptions = {}): SangamWorker {
  return new SangamWorker(clientFor(server), {
    workerId: 'worker-1',
    maxConcurrency: 1,
    maxTasksPerFetch: 1,
    asyncResponseTimeoutMs: 50,
    fetchBackoff: { initialMs: 1, maxMs: 2, factor: 1, jitter: 0 },
    reportBackoff: { initialMs: 1, maxMs: 2, factor: 1, jitter: 0 },
    shutdownTimeoutMs: 2_000,
    logger: server.logger,
    ...o,
  });
}
