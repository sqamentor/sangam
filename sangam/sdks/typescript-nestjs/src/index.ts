// SPDX-License-Identifier: Apache-2.0
/**
 * NestJS adapter for the Sangam TypeScript SDK (M-027): starts the SDK worker with the Nest application lifecycle,
 * registers handlers and sagas from configuration or DI, and stops the worker gracefully with `SangamWorker.stop`.
 *
 * `@nestjs/common` and `@nestjs/core` are peer dependencies only; nothing here imports them. The module is a plain
 * NestJS DynamicModule object, the providers are factory providers (no decorators needed), and Nest calls the lifecycle
 * hooks (`onModuleInit`, `onModuleDestroy`, `onApplicationShutdown`) on provider instances by method name.
 *
 * ```ts
 * @Module({
 *   imports: [
 *     SangamModule.forRootAsync({
 *       inject: [PaymentService],
 *       useFactory: (payments: PaymentService) => ({
 *         clientOptions: { baseUrl: process.env.SANGAM_URL!, token: process.env.SANGAM_TOKEN },
 *         worker: { workerId: 'payments-1', maxConcurrency: 8 },
 *         subscriptions: [topicSubscription('payment', (ctx) => payments.pay(ctx), { irreversible: true })],
 *       }),
 *     }),
 *   ],
 * })
 * export class AppModule {}
 * ```
 */
import {
  SangamClient,
  SangamWorker,
  topicSubscription,
  type Saga,
  type SangamClientOptions,
  type SubscriptionOptions,
  type TaskHandler,
  type TopicSubscription,
  type WorkerOptions,
} from '@sangam/sdk';

/** Injection token of the resolved `SangamModuleOptions`. */
export const SANGAM_MODULE_OPTIONS = 'SANGAM_MODULE_OPTIONS';
/** Injection token of the `SangamClient`. */
export const SANGAM_CLIENT = 'SANGAM_CLIENT';
/** Injection token of the `SangamWorker` (other providers may `subscribe` on it in their own `onModuleInit`). */
export const SANGAM_WORKER = 'SANGAM_WORKER';

/** A handler from configuration: topic, handler and subscription options. */
export interface SangamHandlerDefinition {
  topic: string;
  handler: TaskHandler;
  options?: SubscriptionOptions;
}

export interface SangamModuleOptions {
  /** A client to use; or give `clientOptions` and the module creates one. Exactly one of the two. */
  client?: SangamClient;
  clientOptions?: SangamClientOptions;
  /** Worker options (workerId, maxConcurrency, logger, shutdownTimeoutMs, ...). */
  worker?: WorkerOptions;
  /** Ready-made subscriptions (`topicSubscription(...)`). */
  subscriptions?: TopicSubscription[];
  /** Handlers as plain definitions. */
  handlers?: SangamHandlerDefinition[];
  /** Saga steps (M-026); both subscriptions of each are registered. */
  sagas?: Saga[];
  /** Start the worker in `onModuleInit` (default true). With false, call `SangamWorkerHost.start()` yourself. */
  autoStart?: boolean;
  /** How long `stop` waits for running handlers on shutdown before aborting them (default: the worker's setting). */
  shutdownTimeoutMs?: number;
}

/** Options of `SangamModule.forRootAsync`: a factory that may inject other providers. */
export interface SangamModuleAsyncOptions {
  /** Modules whose exported providers the factory injects. */
  imports?: any[];
  /** Tokens passed to `useFactory`, in order. */
  inject?: any[];
  useFactory: (...args: any[]) => SangamModuleOptions | Promise<SangamModuleOptions>;
  /** Register the module globally. */
  global?: boolean;
}

/**
 * The shape of a NestJS `DynamicModule` (`@nestjs/common`), declared structurally so this package compiles without
 * the framework. Assignable to Nest's `DynamicModule`.
 */
export interface SangamDynamicModule {
  module: new (...args: any[]) => any;
  global?: boolean;
  imports?: any[];
  providers?: any[];
  exports?: any[];
}

/** The client of `o`: `client`, or one built from `clientOptions`. */
export function createSangamClient(o: SangamModuleOptions): SangamClient {
  if (o.client && o.clientOptions) throw new TypeError('give either client or clientOptions, not both');
  if (o.client) return o.client;
  if (o.clientOptions) return new SangamClient(o.clientOptions);
  throw new TypeError('SangamModule needs client or clientOptions');
}

/** Every subscription of `o`, in order: subscriptions, handlers, then both subscriptions of each saga. */
export function subscriptionsOf(o: SangamModuleOptions): TopicSubscription[] {
  const out: TopicSubscription[] = [...(o.subscriptions ?? [])];
  for (const h of o.handlers ?? []) out.push(topicSubscription(h.topic, h.handler, h.options));
  for (const s of o.sagas ?? []) out.push(...s.subscriptions());
  const seen = new Set<string>();
  for (const s of out) {
    if (seen.has(s.topic)) throw new TypeError(`topic ${s.topic} is registered twice`);
    seen.add(s.topic);
  }
  return out;
}

/**
 * Binds one `SangamWorker` to the Nest lifecycle: registers the configured subscriptions and starts the worker in
 * `onModuleInit`; stops it gracefully (no new fetches, wait for running handlers, then abort and unlock the rest) in
 * `onModuleDestroy`, so handlers still have their dependencies, and again (a no-op once stopped) in
 * `onApplicationShutdown`. Call `app.enableShutdownHooks()` so SIGTERM/SIGINT run these hooks.
 */
export class SangamWorkerHost {
  private started = false;

  constructor(readonly worker: SangamWorker, private readonly options: SangamModuleOptions) {}

  /** Nest lifecycle hook. */
  onModuleInit(): void {
    for (const s of subscriptionsOf(this.options)) this.worker.subscribe(s);
    if (this.options.autoStart !== false) this.start();
  }

  /** Starts the poll loop (idempotent). */
  start(): void {
    if (this.started) return;
    this.started = true;
    this.worker.start();
  }

  /** Graceful stop with the worker's own `stop` (idempotent). */
  async stop(): Promise<void> {
    if (!this.started) return;
    await (this.options.shutdownTimeoutMs === undefined ? this.worker.stop() : this.worker.stop(this.options.shutdownTimeoutMs));
    this.started = false;
  }

  /** Nest lifecycle hook: runs first on `app.close()` / a shutdown signal. */
  onModuleDestroy(): Promise<void> {
    return this.stop();
  }

  /** Nest lifecycle hook (after `enableShutdownHooks()`); a no-op once `onModuleDestroy` stopped the worker. */
  onApplicationShutdown(_signal?: string): Promise<void> {
    return this.stop();
  }
}

function providers(optionsProvider: Record<string, unknown>): any[] {
  return [
    optionsProvider,
    { provide: SANGAM_CLIENT, useFactory: (o: SangamModuleOptions) => createSangamClient(o), inject: [SANGAM_MODULE_OPTIONS] },
    {
      provide: SANGAM_WORKER,
      useFactory: (client: SangamClient, o: SangamModuleOptions) => new SangamWorker(client, o.worker),
      inject: [SANGAM_CLIENT, SANGAM_MODULE_OPTIONS],
    },
    {
      provide: SangamWorkerHost,
      useFactory: (worker: SangamWorker, o: SangamModuleOptions) => new SangamWorkerHost(worker, o),
      inject: [SANGAM_WORKER, SANGAM_MODULE_OPTIONS],
    },
  ];
}

const EXPORTS = [SANGAM_CLIENT, SANGAM_WORKER, SangamWorkerHost];

/**
 * The Nest module (M-027). Use `SangamModule.forRoot(options)` or `SangamModule.forRootAsync({ inject, useFactory })`
 * in `imports`; inject `SANGAM_CLIENT`, `SANGAM_WORKER` or `SangamWorkerHost` with `@Inject(...)`.
 */
export class SangamModule {
  static forRoot(options: SangamModuleOptions): SangamDynamicModule {
    return {
      module: SangamModule,
      providers: providers({ provide: SANGAM_MODULE_OPTIONS, useValue: options }),
      exports: [...EXPORTS],
    };
  }

  static forRootAsync(options: SangamModuleAsyncOptions): SangamDynamicModule {
    if (typeof options?.useFactory !== 'function') throw new TypeError('forRootAsync needs useFactory');
    return {
      module: SangamModule,
      global: options.global ?? false,
      imports: [...(options.imports ?? [])],
      providers: providers({ provide: SANGAM_MODULE_OPTIONS, useFactory: options.useFactory, inject: [...(options.inject ?? [])] }),
      exports: [...EXPORTS],
    };
  }
}
