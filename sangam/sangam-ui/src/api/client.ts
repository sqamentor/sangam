// SPDX-License-Identifier: Apache-2.0
/**
 * Typed HTTP client for the Sangam API (ARCHITECTURE §4):
 * - base path /api/v1, JSON, ISO-8601 UTC times, string ids;
 * - Authorization: Bearer <OIDC JWT> (or the dev token);
 * - Idempotency-Key on every mutation (R41), reused on a safe retry (CX-4);
 * - errors as application/problem+json (RFC 9457);
 * - lists with ?page=&size= and X-Total-Count / X-Total-Count-Capped (A-16).
 */
import { config } from '../config';

export interface Problem {
  type?: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  /** Machine code, e.g. stale-lease, FORM_VERSION_STALE, APPROVAL_INVALIDATED, CONFLICT_RETRY_EXHAUSTED. */
  code?: string;
  traceId?: string;
  errors?: { field?: string; message: string }[];
  [k: string]: unknown;
}

export class ApiError extends Error {
  readonly problem: Problem;
  constructor(problem: Problem) {
    super(problem.detail ?? problem.title);
    this.problem = problem;
  }
  get status(): number {
    return this.problem.status;
  }
  /** 403 and 404 are shown identically so a hidden object is never revealed (X.4.7). */
  get isNotAvailable(): boolean {
    return this.problem.status === 403 || this.problem.status === 404;
  }
  get isStale(): boolean {
    const c = this.problem.code ?? this.problem.type ?? '';
    return this.problem.status === 409 && /STALE|stale|APPROVAL_INVALIDATED/.test(c);
  }
  get isConflict(): boolean {
    return this.problem.status === 409;
  }
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  /** null when the server did not send a count. */
  total: number | null;
  /** true when the server capped the count ("more than N"). */
  capped: boolean;
}

export type Query = Record<string, string | number | boolean | undefined | null | string[]>;

type TokenProvider = () => Promise<string | null>;
type HeaderProvider = () => Record<string, string>;

let tokenProvider: TokenProvider = async () => null;
let headerProviders: HeaderProvider[] = [];
let onUnauthenticated: () => void = () => undefined;

export function setTokenProvider(p: TokenProvider): void {
  tokenProvider = p;
}
export function addHeaderProvider(p: HeaderProvider): () => void {
  headerProviders.push(p);
  return () => {
    headerProviders = headerProviders.filter((x) => x !== p);
  };
}
export function setUnauthenticatedHandler(h: () => void): void {
  onUnauthenticated = h;
}

export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}`;
}

export function buildUrl(path: string, query?: Query, baseOverride?: string): string {
  const base = (baseOverride ?? (path.startsWith('/engine-rest') ? '' : config().apiBase)).replace(/\/$/, '');
  const url = new URL(base + path, window.location.origin);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v === undefined || v === null || v === '') continue;
      if (Array.isArray(v)) v.forEach((x) => url.searchParams.append(k, x));
      else url.searchParams.set(k, String(v));
    }
  }
  return url.pathname + url.search;
}

export interface RequestOptions {
  query?: Query;
  body?: unknown;
  /** Supply to retry with the same key; otherwise a new key is generated for mutations. */
  idempotencyKey?: string;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** FormData bodies are sent as multipart. */
  form?: FormData | Blob;
  /** Base path other than the Sangam API, e.g. '/engine-rest' (Operaton REST, unchanged, behind Sangam auth). */
  base?: string;
}

async function headersFor(method: string, opts: RequestOptions): Promise<Headers> {
  const h = new Headers();
  h.set('Accept', 'application/json, application/problem+json');
  const token = await tokenProvider();
  if (token) h.set('Authorization', `Bearer ${token}`);
  for (const p of headerProviders) for (const [k, v] of Object.entries(p())) h.set(k, v);
  if (opts.body !== undefined && !opts.form) h.set('Content-Type', 'application/json');
  if (method !== 'GET' && method !== 'HEAD') h.set('Idempotency-Key', opts.idempotencyKey ?? newIdempotencyKey());
  for (const [k, v] of Object.entries(opts.headers ?? {})) h.set(k, v);
  return h;
}

async function toProblem(res: Response): Promise<Problem> {
  const ct = res.headers.get('Content-Type') ?? '';
  const traceId = res.headers.get('traceparent')?.split('-')[1] ?? res.headers.get('X-Trace-Id') ?? undefined;
  if (ct.includes('json')) {
    try {
      const p = (await res.json()) as Problem;
      return { ...p, status: p.status ?? res.status, title: p.title ?? res.statusText, traceId: p.traceId ?? traceId };
    } catch {
      /* fall through */
    }
  }
  return { title: res.statusText || 'Request failed', status: res.status, traceId };
}

export async function raw(method: string, path: string, opts: RequestOptions = {}): Promise<Response> {
  const headers = await headersFor(method, opts);
  let res: Response;
  try {
    res = await fetch(buildUrl(path, opts.query, opts.base), {
      method,
      headers,
      body: opts.form ?? (opts.body !== undefined ? JSON.stringify(opts.body) : undefined),
      signal: opts.signal,
      credentials: 'same-origin',
    });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError({ title: 'Network error', status: 0, detail: (e as Error).message, code: 'NETWORK' });
  }
  if (res.status === 401) {
    onUnauthenticated();
  }
  if (!res.ok) throw new ApiError(await toProblem(res));
  return res;
}

export async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  const res = await raw(method, path, opts);
  if (res.status === 204) return undefined as T;
  const ct = res.headers.get('Content-Type') ?? '';
  if (ct.includes('json')) return (await res.json()) as T;
  return (await res.text()) as unknown as T;
}

export async function page<T>(path: string, query: Query = {}, signal?: AbortSignal): Promise<Page<T>> {
  const p = Number(query.page ?? 0);
  const s = Number(query.size ?? 25);
  const res = await raw('GET', path, { query: { ...query, page: p, size: s }, signal });
  const body = (await res.json()) as T[] | { items: T[] };
  const items = Array.isArray(body) ? body : body.items ?? [];
  const totalHeader = res.headers.get('X-Total-Count');
  return {
    items,
    page: p,
    size: s,
    total: totalHeader === null ? null : Number(totalHeader),
    capped: res.headers.get('X-Total-Count-Capped') === 'true',
  };
}

export const http = {
  get: <T>(path: string, query?: Query, signal?: AbortSignal) => request<T>('GET', path, { query, signal }),
  post: <T>(path: string, body?: unknown, idempotencyKey?: string) =>
    request<T>('POST', path, { body: body ?? {}, idempotencyKey }),
  put: <T>(path: string, body?: unknown, idempotencyKey?: string) => request<T>('PUT', path, { body, idempotencyKey }),
  patch: <T>(path: string, body?: unknown, idempotencyKey?: string) =>
    request<T>('PATCH', path, { body, idempotencyKey }),
  /** Contract DELETEs carry the audit reason as ?reason= (parameter eason). */
  del: <T>(path: string, query?: Query, idempotencyKey?: string) => request<T>('DELETE', path, { query, idempotencyKey }),
  engine: <T>(path: string, query?: Query) => request<T>('GET', path, { query, base: '/engine-rest' }),
  /** GET returning the body and the ETag (optimistic concurrency with If-Match). */
  getTagged: async <T>(path: string, query?: Query): Promise<{ body: T; etag: string | null }> => {
    const res = await raw('GET', path, { query });
    return { body: (await res.json()) as T, etag: res.headers.get('ETag') };
  },
  upload: <T>(path: string, form: FormData, idempotencyKey?: string) =>
    request<T>('POST', path, { form, idempotencyKey }),
  page,
};

/** Downloads a protected resource with the bearer token and hands it to the browser as a file. */
export async function download(path: string, query?: Query, fallbackName = 'download'): Promise<void> {
  const res = await raw('GET', path, { query, headers: { Accept: '*/*' } });
  const blob = await res.blob();
  const cd = res.headers.get('Content-Disposition') ?? '';
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(cd);
  const name = m ? decodeURIComponent(m[1]) : fallbackName;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
