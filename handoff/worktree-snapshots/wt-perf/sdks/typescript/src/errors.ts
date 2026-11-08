// SPDX-License-Identifier: Apache-2.0

/**
 * RFC 9457 `application/problem+json` body. `code` is the Sangam machine code
 * (for example `STALE_LEASE`, `GONE_INSTANCE_DELETED`); other members are kept as extensions.
 */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  traceId?: string;
  [extension: string]: unknown;
}

function norm(s: string): string {
  return s.toLowerCase().replace(/_/g, '-');
}

/** True when `code` or the last segment of `type` equals `name` (case and -/_ insensitive). */
export function problemIs(p: Problem | undefined, name: string): boolean {
  if (!p) return false;
  const n = norm(name);
  if (typeof p.code === 'string' && norm(p.code) === n) return true;
  if (typeof p.type === 'string') {
    let t = p.type;
    const slash = t.lastIndexOf('/');
    if (slash >= 0) t = t.substring(slash + 1);
    const hash = t.lastIndexOf('#');
    if (hash >= 0) t = t.substring(hash + 1);
    return norm(t) === n;
  }
  return false;
}

/** Root of all SDK errors. */
export class SangamError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = new.target.name;
  }
}

/** A non-2xx answer from the Sangam API with its parsed problem body. */
export class SangamApiError extends SangamError {
  readonly status: number;
  readonly problem: Problem;

  constructor(status: number, problem: Problem, message: string) {
    super(message);
    this.status = status;
    this.problem = problem;
  }

  /** 408, 425, 429, 500, 502, 503, 504: the same request (same Idempotency-Key) may be repeated. */
  get retryable(): boolean {
    return [408, 425, 429, 500, 502, 503, 504].includes(this.status);
  }
}

/**
 * 409 `stale-lease` (VI.3.2 rule 2, R20): the lockVersion is no longer current; the server changed nothing.
 * The worker abandons the item; another attempt owns it.
 */
export class StaleLeaseError extends SangamApiError {
  constructor(problem: Problem, message: string) {
    super(409, problem, message);
  }
  override get retryable(): boolean {
    return false;
  }
}

/** 410 `GONE_INSTANCE_DELETED` (VI.5, R23): the instance was cancelled/deleted; the late call was audited. */
export class GoneError extends SangamApiError {
  constructor(problem: Problem, message: string) {
    super(410, problem, message);
  }
  override get retryable(): boolean {
    return false;
  }
}

/** Network failure (connect, reset, timeout) after the configured retries were used up. */
export class TransportError extends SangamError {}

/** Raised when no OIDC token can be obtained. */
export class TokenError extends SangamError {}
