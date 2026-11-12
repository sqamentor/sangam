<!-- SPDX-License-Identifier: Apache-2.0 -->
# @sangam/nestjs: NestJS adapter for the Sangam TypeScript SDK (M-027)

Runs a `SangamWorker` from `@sangam/sdk` with the NestJS application lifecycle:

- `onModuleInit` registers the configured subscriptions, handlers and sagas, then starts the worker (unless `autoStart: false`).
- `onModuleDestroy` (the first hook of `app.close()`) stops it gracefully with the SDK's own `SangamWorker.stop`: no new fetches, wait for running handlers (`shutdownTimeoutMs`), then abort and unlock the rest. `onApplicationShutdown` calls the same stop again, which is a no-op once stopped.
- Call `app.enableShutdownHooks()` so SIGTERM / SIGINT run these hooks.

The core SDK gets no new dependency. This package has peer dependencies only: `@sangam/sdk` 0.1.0, and `@nestjs/common` + `@nestjs/core` 10 or 11. It imports nothing from Nest. The module is a plain Nest `DynamicModule` object, the providers are factory providers (no decorators needed), and Nest calls the lifecycle hooks on provider instances by method name. Like the SDK it ships as ESM (Node 20.3+). A CommonJS Nest app loads it through `require(esm)` (Node 20.19+ / 22.12+) or a dynamic `import()`.

## Configuration

```ts
import { Module } from '@nestjs/common';
import { Saga, topicSubscription } from '@sangam/sdk';
import { SangamModule } from '@sangam/nestjs';

@Module({
  imports: [
    PaymentsModule,
    SangamModule.forRootAsync({
      imports: [PaymentsModule],
      inject: [PaymentService],                     // handlers from DI
      useFactory: (payments: PaymentService) => ({
        clientOptions: { baseUrl: process.env.SANGAM_URL!, token: process.env.SANGAM_TOKEN },
        worker: { workerId: 'payments-1', maxConcurrency: 8 },
        subscriptions: [topicSubscription('payment', (ctx) => payments.pay(ctx), { irreversible: true })],
        handlers: [{ topic: 'notify', handler: (ctx) => payments.notify(ctx) }],
        sagas: [
          Saga.step('charge')
            .action('payment.charge', (ctx) => payments.charge(ctx))
            .compensation('payment.refund', (c) => payments.refund(c.forwardProviderRef, c.idempotencyKey))
            .build(),
        ],
        shutdownTimeoutMs: 30_000,
      }),
    }),
  ],
})
export class AppModule {}
```

`SangamModule.forRoot(options)` takes the same options without a factory. Options:

| Option | Meaning |
|---|---|
| `client` / `clientOptions` | the `SangamClient`, or the options to create one (exactly one of the two) |
| `worker` | `WorkerOptions` of the SDK (workerId, maxConcurrency, logger, ...) |
| `subscriptions`, `handlers`, `sagas` | registered in this order; a topic may appear once |
| `autoStart` | default true; with false call `SangamWorkerHost.start()` yourself |
| `shutdownTimeoutMs` | wait for running handlers on shutdown (default: the worker's setting) |

Injection tokens: `SANGAM_CLIENT`, `SANGAM_WORKER` (other providers may `worker.subscribe(...)` in their own `onModuleInit`), `SangamWorkerHost` and `SANGAM_MODULE_OPTIONS`. Inject them with `@Inject(SANGAM_WORKER)`.

## Build and checks

```sh
npm install          # with @sangam/sdk linked (workspace) and the Nest peers installed
npm run build
npm test             # node:test, test/sangam-module.test.ts
```

`tsconfig.check.json` typechecks `src` and `test` against the SDK source (`../typescript/src`) without installing anything. The tests drive the module the way Nest does: they resolve the factory providers by token and call the hooks by method name, against the SDK's in-process `SangamTestEngine`. They have not been run against a real Nest application in this tree, because `@nestjs/*` is not installed here.
