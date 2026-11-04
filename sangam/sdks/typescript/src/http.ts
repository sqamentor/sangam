// SPDX-License-Identifier: Apache-2.0
import { randomUUID } from 'node:crypto';
import { noAuth, type TokenProvider } from './auth.js';
import {
  GoneError,
  problemIs,
  SangamApiError,
  SangamError,
  StaleLeaseError,
  TransportError,
  type Problem,
} from './errors.js';
import { noopLogger, type Logger } from './logger.js';
import { jsonReplacer } from './typed.js';

export const API_PREFIX = '/api/v1';
export const SDK_VERSION = '0.1.0';

/**
 * Exponential back-off with full jitter for transport retries (R28). Only safe requests are retried:
 * GET, or a mutation carrying an Idempotency-Key (R41).
 */
export interface RetryPolicy {
  /** Total attempts including the first (1 = no retry). */
  maxAttempts: number;
  initialDelayMs: number;
  maxDelayMs: number;
  multiplier: number;
}

export const defaultRetryPolicy: RetryPolicy = { maxAttempts: 4, initialDelayMs: 200, maxDelayMs: 5_000, multiplier: 2 };

/** Delay before retry number `retry` (1-based), jittered in [cap/2, cap]. */
export function retryDelay(p: RetryPolicy, retry: number): number {
  const cap = Math.min(p.initialDelayMs * Math.pow(p.multiplier, Math.max(0, retry - 1)), p.maxDelayMs);
  if (cap <= 0) return 0;
  return Math.floor(cap / 2 + Math.random() * (cap / 2));
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
export type QueryValue = string | number | boolean | undefined | null | Array<string | number | boolean>;

export interface ApiRequest {
  method: HttpMethod;
  path: string;
  query?: Record<string, QueryValue>;
  body?: unknown;
  /** Pre-encoded body (multipart); `Content-Type` is then set by fetch. */
  rawBody?: FormData;
  /** Idempotency-Key (R41). On a mutation without one, a key is generated per logical call. */
  idempotencyKey?: string;
  timeoutMs?: number;
  /** Disable transport retries (long-poll fetch has its own loop). */
  noRetry?: boolean;
  headers?: Record<string, string>;
  /** Caller abort (worker shutdown). */
  signal?: AbortSignal;
  /** `X-Sangam-Seat` for this call (overrides the client default; IX.5 rule 1). */
  seat?: string;
  /** `X-Sangam-Acting-For-Seat` for this call (overrides the client default; IX.5 rule 4). */
  actingForSeat?: string;
}

export interface ApiResponse<T> {
  status: number;
  headers: Headers;
  body: T;
}

export interface TransportOptions {
  baseUrl: string;
  tokenProvider?: TokenProvider;
  retryPolicy?: RetryPolicy;
  requestTimeoutMs?: number;
  logger?: Logger;
  userAgent?: string;
  fetch?: typeof fetch;
  /** Default `X-Sangam-Seat` (seat context) sent on every call. */
  seat?: string;
  /** Default `X-Sangam-Acting-For-Seat` sent on every call. */
  actingForSeat?: string;
}

const sleep = (ms: number, signal?: AbortSignal): Promise<void> =>
  new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const t = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(t);
      reject(signal?.reason);
    };
    signal?.addEventListener('abort', onAbort, { once: true });
  });

export { sleep as delay };

/** Percent-encodes one path segment (ids, keys, channel names). */
export function seg(s: string): string {
  if (s === undefined || s === null) throw new TypeError('path parameter is required');
  return encodeURIComponent(s);
}

/**
 * The single HTTP path of the SDK: base URL + `/api/v1`, bearer auth with one refresh on 401, Idempotency-Key on
 * every mutation (R41), problem+json decoding (RFC 9457) and jittered retries of safe calls.
 */
export class HttpTransport {
  private readonly base: string;
  private readonly tokens: TokenProvider;
  private readonly retry: RetryPolicy;
  private readonly timeoutMs: number;
  readonly logger: Logger;
  private readonly userAgent: string;
  private readonly fetchImpl: typeof fetch;
  private readonly seat?: string;
  private readonly actingForSeat?: string;

  constructor(opts: TransportOptions) {
    let b = opts.baseUrl.replace(/\/+$/, '');
    if (b.endsWith(API_PREFIX)) b = b.substring(0, b.length - API_PREFIX.length);
    this.base = b;
    this.tokens = opts.tokenProvider ?? noAuth;
    this.retry = opts.retryPolicy ?? defaultRetryPolicy;
    this.timeoutMs = opts.requestTimeoutMs ?? 30_000;
    this.logger = opts.logger ?? noopLogger;
    this.userAgent = opts.userAgent ?? `sangam-sdk-typescript/${SDK_VERSION}`;
    this.fetchImpl = opts.fetch ?? fetch;
    this.seat = opts.seat;
    this.actingForSeat = opts.actingForSeat;
  }

  async execute<T>(req: ApiRequest): Promise<ApiResponse<T>> {
    const mutation = req.method !== 'GET';
    const idemKey = req.idempotencyKey ?? (mutation ? randomUUID() : undefined);
    const safe = !mutation || idemKey !== undefined;
    const maxAttempts = !req.noRetry && safe ? this.retry.maxAttempts : 1;
    let refreshedAuth = false;
    let attempt = 0;
    for (;;) {
      attempt++;
      let resp: Response;
      try {
        resp = await this.sendOnce(req, idemKey);
      } catch (e) {
        if (req.signal?.aborted) throw new SangamError(`${req.method} ${req.path} aborted`, { cause: e });
        if (attempt < maxAttempts) {
          const d = retryDelay(this.retry, attempt);
          this.logger.log('debug', 'http.retry', `retrying after transport error: ${String(e)}`, {
            method: req.method, path: req.path, attempt, delayMs: d,
          });
          await sleep(d, req.signal);
          continue;
        }
        throw new TransportError(`${req.method} ${req.path} failed: ${(e as Error).message ?? String(e)}`, { cause: e });
      }
      if (resp.status === 401 && !refreshedAuth) {
        refreshedAuth = true;
        this.tokens.invalidate?.();
        attempt--; // an auth refresh does not consume a retry
        await resp.body?.cancel().catch(() => undefined);
        continue;
      }
      if (resp.ok) {
        const body = (await this.decode(resp)) as T;
        return { status: resp.status, headers: resp.headers, body };
      }
      const err = await this.toError(req, resp);
      if (err.retryable && attempt < maxAttempts) {
        const d = retryAfterMs(resp) ?? retryDelay(this.retry, attempt);
        this.logger.log('debug', 'http.retry', `retrying after HTTP ${resp.status}`, {
          method: req.method, path: req.path, attempt, delayMs: d,
        });
        await sleep(d, req.signal);
        continue;
      }
      throw err;
    }
  }

  /** Convenience: returns only the body. */
  async call<T>(req: ApiRequest): Promise<T> {
    return (await this.execute<T>(req)).body;
  }

  /** Like `call`, and sets `idempotentReplayed` on an object body from the `Idempotent-Replayed` header (R41). */
  async callReplayable<T extends object>(req: ApiRequest): Promise<T> {
    const r = await this.execute<T>(req);
    const replayed = (r.headers.get('idempotent-replayed') ?? '').toLowerCase() === 'true';
    if (r.body && typeof r.body === 'object' && !Array.isArray(r.body)) {
      (r.body as { idempotentReplayed?: boolean }).idempotentReplayed = replayed;
    }
    return r.body;
  }

  private async sendOnce(req: ApiRequest, idemKey: string | undefined): Promise<Response> {
    const headers: Record<string, string> = {
      Accept: 'application/json, application/problem+json',
      'User-Agent': this.userAgent,
    };
    const auth = await this.tokens.authorization();
    if (auth) headers.Authorization = auth;
    if (idemKey) headers['Idempotency-Key'] = idemKey;
    const seat = req.seat ?? this.seat;
    const actingFor = req.actingForSeat ?? this.actingForSeat;
    if (seat) headers['X-Sangam-Seat'] = seat;
    if (actingFor) headers['X-Sangam-Acting-For-Seat'] = actingFor;
    let body: BodyInit | undefined;
    if (req.rawBody !== undefined) {
      body = req.rawBody;
    } else if (req.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(req.body, jsonReplacer);
    } else if (req.method !== 'GET') {
      headers['Content-Type'] = 'application/json';
      body = '{}';
    }
    Object.assign(headers, req.headers);
    const timeout = AbortSignal.timeout(req.timeoutMs ?? this.timeoutMs);
    const signal = req.signal ? AbortSignal.any([timeout, req.signal]) : timeout;
    return this.fetchImpl(this.url(req), { method: req.method, headers, body, signal, redirect: 'manual' });
  }

  private url(req: ApiRequest): string {
    let u = this.base + API_PREFIX + (req.path.startsWith('/') ? '' : '/') + req.path;
    const qs = new URLSearchParams();
    for (const [k, v] of Object.entries(req.query ?? {})) {
      if (v === undefined || v === null) continue;
      if (Array.isArray(v)) v.forEach((item) => qs.append(k, String(item)));
      else qs.append(k, String(v));
    }
    const s = qs.toString();
    if (s) u += `?${s}`;
    return u;
  }

  private async decode(resp: Response): Promise<unknown> {
    const text = await resp.text();
    if (!text) return undefined;
    const ct = resp.headers.get('content-type') ?? '';
    if (ct.includes('json') || /^[\s]*[[{"]/.test(text)) {
      try {
        return JSON.parse(text);
      } catch (e) {
        throw new SangamError('cannot decode JSON response', { cause: e });
      }
    }
    return text;
  }

  private async toError(req: ApiRequest, resp: Response): Promise<SangamApiError> {
    const status = resp.status;
    const text = await resp.text().catch(() => '');
    let p: Problem | undefined;
    if (text) {
      try {
        const parsed: unknown = JSON.parse(text);
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) p = parsed as Problem;
      } catch {
        // not JSON
      }
    }
    if (!p) p = { status, title: `HTTP ${status}`, detail: text || undefined };
    if (p.status === undefined) p.status = status;
    const msg = `${req.method} ${req.path} -> ${status}${p.title ? ` ${p.title}` : ''}${p.detail ? `: ${p.detail}` : ''}`;
    if (status === 409 && problemIs(p, 'stale-lease')) return new StaleLeaseError(p, msg);
    if (status === 410) return new GoneError(p, msg);
    return new SangamApiError(status, p, msg);
  }
}

function retryAfterMs(resp: Response): number | undefined {
  const v = resp.headers.get('retry-after');
  if (!v) return undefined;
  const n = Number.parseInt(v.trim(), 10);
  return Number.isFinite(n) ? Math.min(60, n) * 1000 : undefined;
}
