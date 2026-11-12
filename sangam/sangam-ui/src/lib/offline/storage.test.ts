// SPDX-License-Identifier: Apache-2.0
// M-001: the queue backends, both against one contract. The IndexedDB adapter runs on fake-indexeddb 6.2.8 (dev-only,
// Apache-2.0, owner approval 2026-11-12), which installs indexedDB on the Node global.
import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { IndexedDbBackend, MemoryBackend, type QueueBackend } from './storage';
import { newQueueKey } from './crypto';

async function contract(b: QueueBackend) {
  await b.write([
    { put: 'entries', key: 'u:000000000002', value: { n: 2 } },
    { put: 'entries', key: 'u:000000000001', value: { n: 1 } },
    { put: 'devices', key: 'u', value: { seq: 2 } },
  ]);
  expect((await b.list<{ n: number }>('entries')).map((e) => [e.key, e.value.n])).toEqual([
    ['u:000000000001', 1],
    ['u:000000000002', 2],
  ]);
  expect(await b.get('devices', 'u')).toEqual({ seq: 2 });
  expect(await b.get('devices', 'nobody')).toBeUndefined();
  await b.write([{ del: 'entries', key: 'u:000000000001' }]);
  expect((await b.list('entries')).map((e) => e.key)).toEqual(['u:000000000002']);
  // a stored record is not changed through a returned copy
  const d = (await b.get<{ seq: number }>('devices', 'u'))!;
  d.seq = 99;
  expect(await b.get('devices', 'u')).toEqual({ seq: 2 });
  // a CryptoKey is stored as itself (non-extractable stays non-extractable)
  const key = await newQueueKey(globalThis.crypto.subtle);
  await b.write([{ put: 'meta', key: 'install', value: { queueKey: key } }]);
  const back = await b.get<{ queueKey: CryptoKey }>('meta', 'install');
  expect(back?.queueKey.extractable).toBe(false);
  expect(back?.queueKey.type).toBe('secret');
  await b.clear();
  for (const s of ['meta', 'devices', 'entries', 'legacy', 'cache'] as const) expect(await b.list(s)).toEqual([]);
}

describe('M-001 queue storage', () => {
  it('memory backend: ordered lists, atomic batches, copies, clear', async () => {
    const b = new MemoryBackend();
    expect(b.durable).toBe(false);
    await contract(b);
  });

  it('IndexedDB backend: same contract, durable', async () => {
    const b = new IndexedDbBackend(indexedDB, `sangam-field-queue-test-${Date.now()}`);
    expect(b.durable).toBe(true);
    await contract(b);
  });
});
