// SPDX-License-Identifier: Apache-2.0
// M-027: the NestJS adapter's DynamicModule, providers and lifecycle, driven the way Nest drives them (factory providers
// resolved by token, lifecycle hooks called by method name), against the SDK's in-process test engine.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Saga, SangamTestEngine, SangamWorker, noopLogger, topicSubscription, type SangamClient } from '@sangam/sdk';
import {
  SANGAM_CLIENT,
  SANGAM_MODULE_OPTIONS,
  SANGAM_WORKER,
  SangamModule,
  SangamWorkerHost,
  createSangamClient,
  subscriptionsOf,
  type SangamDynamicModule,
} from '../src/index.js';

const NO_RETRY = { maxAttempts: 1, initialDelayMs: 0, maxDelayMs: 0, multiplier: 1 };

type Provider = { provide: unknown; useValue?: unknown; useFactory?: (...a: unknown[]) => unknown; inject?: unknown[] };

/** Resolves the module's providers like Nest's injector: by token, factories with their `inject` list. */
async function resolve(m: SangamDynamicModule, external: Map<unknown, unknown> = new Map()): Promise<Map<unknown, unknown>> {
  const byToken = new Map<unknown, Provider>();
  for (const p of (m.providers ?? []) as Provider[]) byToken.set(p.provide, p);
  const out = new Map<unknown, unknown>(external);
  const get = async (token: unknown): Promise<unknown> => {
    if (out.has(token)) return out.get(token);
    const p = byToken.get(token);
    if (!p) throw new Error(`no provider for ${String(token)}`);
    const v = 'useValue' in p ? p.useValue : await p.useFactory!(...(await Promise.all((p.inject ?? []).map(get))));
    out.set(token, v);
    return v;
  };
  for (const t of byToken.keys()) await get(t);
  return out;
}

function waitFor(pred: () => boolean, timeoutMs = 5_000): Promise<void> {
  const end = Date.now() + timeoutMs;
  return new Promise((ok, fail) => {
    const tick = (): void => {
      if (pred()) return ok();
      if (Date.now() > end) return fail(new Error('timed out'));
      setTimeout(tick, 5);
    };
    tick();
  });
}

describe('SangamModule (M-027)', () => {
  it('forRoot is a DynamicModule of SangamModule exporting the client, worker and host', () => {
    const engine = new SangamTestEngine();
    const m = SangamModule.forRoot({ client: engine.client() });
    assert.equal(m.module, SangamModule);
    assert.deepEqual(m.exports, [SANGAM_CLIENT, SANGAM_WORKER, SangamWorkerHost]);
    assert.deepEqual((m.providers as Provider[]).map((p) => p.provide), [SANGAM_MODULE_OPTIONS, SANGAM_CLIENT, SANGAM_WORKER, SangamWorkerHost]);
  });

  it('runs handlers from configuration with the lifecycle and stops gracefully on shutdown', async () => {
    const engine = new SangamTestEngine();
    const client: SangamClient = engine.client({ retryPolicy: NO_RETRY, logger: noopLogger });
    const pay = engine.addWork('pay', { amount: 1 });
    const charge = engine.addWork('charge');
    const saga = Saga.step('charge').action('charge', (ctx) => { ctx.outcomeReference = 'C-1'; return { ok: true }; })
      .compensation('refund', () => undefined).build();
    const m = SangamModule.forRoot({
      client,
      worker: { workerId: 'nest-1', logger: noopLogger, asyncResponseTimeoutMs: 10 },
      handlers: [{ topic: 'pay', handler: () => ({ paid: true }) }],
      sagas: [saga],
      shutdownTimeoutMs: 1_000,
    });
    const di = await resolve(m);
    assert.equal(di.get(SANGAM_CLIENT), client);
    const host = di.get(SangamWorkerHost) as SangamWorkerHost;
    assert.ok(di.get(SANGAM_WORKER) instanceof SangamWorker);
    host.onModuleInit();
    try {
      await waitFor(() => engine.state(pay) === 'COMPLETED' && engine.state(charge) === 'COMPLETED');
      assert.deepEqual(engine.snapshot(pay).output, { paid: true });
      assert.equal(engine.snapshot(charge).workerId, null);
    } finally {
      await host.onModuleDestroy();
      await host.onApplicationShutdown('SIGTERM');
    }
    assert.equal(host.worker.inFlightCount, 0);
  });

  it('forRootAsync builds the options from injected providers; autoStart false waits for start()', async () => {
    const engine = new SangamTestEngine();
    const m = SangamModule.forRootAsync({
      inject: ['CONFIG'],
      useFactory: async (cfg: unknown) => ({
        client: engine.client({ retryPolicy: NO_RETRY, logger: noopLogger }),
        worker: { workerId: String((cfg as { id: string }).id), logger: noopLogger },
        subscriptions: [topicSubscription('pay', () => undefined)],
        autoStart: false,
      }),
    });
    assert.equal(m.global, false);
    const di = await resolve(m, new Map([['CONFIG', { id: 'w-cfg' }]]));
    const host = di.get(SangamWorkerHost) as SangamWorkerHost;
    assert.equal(host.worker.workerId, 'w-cfg');
    host.onModuleInit();
    const wid = engine.addWork('pay');
    await new Promise((r) => setTimeout(r, 30));
    assert.equal(engine.state(wid), 'AVAILABLE', 'not started');
    host.start();
    try {
      await waitFor(() => engine.state(wid) === 'COMPLETED');
    } finally {
      await host.onApplicationShutdown();
    }
    await host.onModuleDestroy(); // idempotent
  });

  it('validates the options', () => {
    const engine = new SangamTestEngine();
    assert.throws(() => createSangamClient({}), /needs client or clientOptions/);
    assert.throws(() => createSangamClient({ client: engine.client(), clientOptions: { baseUrl: 'http://x' } }), /not both/);
    assert.ok(createSangamClient({ clientOptions: { baseUrl: 'http://x', logger: noopLogger } }));
    assert.throws(() => subscriptionsOf({
      subscriptions: [topicSubscription('pay', () => undefined)],
      handlers: [{ topic: 'pay', handler: () => undefined }],
    }), /registered twice/);
    assert.throws(() => SangamModule.forRootAsync({} as never), /needs useFactory/);
  });
});
