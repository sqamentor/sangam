// SPDX-License-Identifier: Apache-2.0
import {
  AdminApi,
  AuditApi,
  DecisionsApi,
  MeApi,
  OpsApi,
  ProcessesApi,
  RepositoryApi,
  TasksApi,
  WorkApi,
} from './api.js';
import { StaticTokenProvider, type TokenProvider } from './auth.js';
import { HttpTransport, type RetryPolicy, type TraceContext } from './http.js';
import { consoleJsonLogger, type Logger } from './logger.js';
import { SangamApis } from './generated/api.js';

export interface SangamClientOptions {
  /** Server root, e.g. `https://host:8080`; a trailing `/api/v1` is accepted. */
  baseUrl: string;
  tokenProvider?: TokenProvider;
  /** Static bearer token (dev profile); ignored when `tokenProvider` is set. */
  token?: string;
  retryPolicy?: RetryPolicy;
  requestTimeoutMs?: number;
  logger?: Logger;
  userAgent?: string;
  fetch?: typeof fetch;
  /** Default `X-Sangam-Seat` (seat context, IX.5 rule 1) for every call; override per call with `CallOptions.seat`. */
  seat?: string;
  /** Default `X-Sangam-Acting-For-Seat` (IX.5 rule 4); part of the R41 fingerprint. */
  actingForSeat?: string;
  /**
   * The caller's W3C trace context (R13, M-062): every request then carries `traceparent`/`tracestate` so server spans
   * join the caller's trace. Off by default (R56). With OpenTelemetry:
   * `() => { const c = trace.getActiveSpan()?.spanContext(); return c ? { traceparent: `00-${c.traceId}-${c.spanId}-0${c.traceFlags}` } : undefined; }`
   */
  traceContext?: TraceContext;
}

/**
 * Typed client for the Sangam API `/api/v1`. Share one instance.
 *
 * ```ts
 * const client = new SangamClient({ baseUrl: 'https://sangam.example.gov.in', tokenProvider });
 * const pi = await client.processes.start({ key: 'p1_bill', businessKey: 'BILL-2026-0001', variables: { amount: 125000 } });
 * ```
 */
export class SangamClient {
  readonly transport: HttpTransport;
  /** Worker API `/work/**` (driven by `SangamWorker`). */
  readonly work: WorkApi;
  readonly tasks: TasksApi;
  readonly processes: ProcessesApi;
  readonly decisions: DecisionsApi;
  readonly audit: AuditApi;
  readonly admin: AdminApi;
  readonly ops: OpsApi;
  readonly repository: RepositoryApi;
  /** `GET /me`. */
  readonly me: MeApi;
  /**
   * The typed surface generated from sangam-api.yaml (M-020): every operation of the contract, one member per
   * contract tag, on this client's transport, e.g. `client.api.work.fetchAndLock({...})`.
   */
  readonly api: SangamApis;

  constructor(options: SangamClientOptions) {
    if (!options.baseUrl) throw new TypeError('baseUrl is required');
    const tokenProvider = options.tokenProvider ?? (options.token ? new StaticTokenProvider(options.token) : undefined);
    this.transport = new HttpTransport({
      baseUrl: options.baseUrl,
      tokenProvider,
      retryPolicy: options.retryPolicy,
      requestTimeoutMs: options.requestTimeoutMs,
      logger: options.logger ?? consoleJsonLogger(),
      userAgent: options.userAgent,
      fetch: options.fetch,
      seat: options.seat,
      actingForSeat: options.actingForSeat,
      traceContext: options.traceContext,
    });
    this.work = new WorkApi(this.transport);
    this.tasks = new TasksApi(this.transport);
    this.processes = new ProcessesApi(this.transport);
    this.decisions = new DecisionsApi(this.transport);
    this.audit = new AuditApi(this.transport);
    this.admin = new AdminApi(this.transport);
    this.ops = new OpsApi(this.transport);
    this.repository = new RepositoryApi(this.transport);
    this.me = new MeApi(this.transport);
    this.api = new SangamApis(this.transport);
  }

  get logger(): Logger {
    return this.transport.logger;
  }
}
