describe('M-001 old plaintext queue (owner decisions 2026-11-12: delete after the encrypted store, bound to the first user)', () => {
  const old = [
    { visitId: 'task-9', key: 'old-key-9', body: { answers: { c: true }, notes: 'old note', recordedAt: '2026-11-01T08:00:00.000Z' } },
    { visitId: 'task-8', key: 'old-key-8', body: { answers: {}, notes: '', recordedAt: '2026-11-01T09:00:00.000Z' } },
  ];

  it('deletes the plaintext as soon as the encrypted copies are stored, and sends nothing before confirmation', async () => {
    local.setItem(LEGACY_QUEUE_KEY, JSON.stringify(old));
    const f = await openAs('u1');
    expect(local.getItem(LEGACY_QUEUE_KEY)).toBeNull();
    expect(local.m.size).toBe(0);
    const stored = JSON.stringify(await backend.list('legacy'));
    expect(stored).not.toContain('old note');
    expect(stored).not.toContain('task-9');
    expect(await f.unclaimedCount()).toBe(2);
    expect(await f.pending()).toEqual([]);
    await f.sync();
    expect(server.records).toEqual([]);
    expect(await (await openAs('u1')).unclaimedCount()).toBe(2); // opened again: still there, not imported twice
  });

  it('keeps the plaintext when the encrypted store fails, and imports it on the next attempt', async () => {
    local.setItem(LEGACY_QUEUE_KEY, JSON.stringify(old));
    let fail = true;
    const write = backend.write.bind(backend);
    backend.write = async (ops) => {
      if (fail && ops.some((o) => 'put' in o && o.put === 'legacy')) throw new Error('QuotaExceededError');
      return write(ops);
    };
    const f = await openAs('u1');
    expect(JSON.parse(local.getItem(LEGACY_QUEUE_KEY) ?? '[]')).toEqual(old);
    expect(await backend.list('legacy')).toEqual([]);
    expect(await f.unclaimedCount()).toBe(0);
    // the next sync retries
    fail = false;
    await f.sync();
    expect(local.getItem(LEGACY_QUEUE_KEY)).toBeNull();
    expect(await f.unclaimedCount()).toBe(2);
    expect(server.records).toEqual([]);
    // a later open retries as well (after another failed store)
    local.setItem(LEGACY_QUEUE_KEY, JSON.stringify([{ visitId: 'task-7', key: 'old-key-7', body: old[1].body }]));
    fail = true;
    await f.sync();
    expect(local.getItem(LEGACY_QUEUE_KEY)).not.toBeNull();
    fail = false;
    await openAs('u1');
    expect(local.getItem(LEGACY_QUEUE_KEY)).toBeNull();
    expect(await f.unclaimedCount()).toBe(3);
  });

  it('binds them to the first signed-in user: no other user, same tenant or another, can see, send, confirm or discard them', async () => {
    local.setItem(LEGACY_QUEUE_KEY, JSON.stringify(old));
    const first = await openAs('u1');
    const ids = (await backend.list('legacy')).map((e) => e.key);
    expect(ids).toHaveLength(2);
    for (const other of [await openAs('u2'), await openAs('u2', 't2'), await openAs('u1', 't2')]) {
      expect(await other.unclaimedCount()).toBe(0);
      expect(await other.pending()).toEqual([]);
      expect(await other.claimUnclaimed()).toBe(0);
      for (const id of ids) await other.discard(id);
      await other.register();
      expect(await other.sync()).toMatchObject({ sent: 0, pending: 0 });
    }
    expect(server.records).toEqual([]);
    expect((await backend.list('legacy')).map((e) => e.key)).toEqual(ids);
    // the AAD binds the user: the entry does not open under another user's binding even with the key
    const install = await backend.get<{ queueKey: CryptoKey; installId: string }>('meta', 'install');
    const env = (await backend.list<{ env: never }>('legacy'))[0].value.env;
    expect(await openEnv(c.subtle, install!.queueKey, { tenantId: 't1', deviceId: install!.installId, userId: 'u2' }, env)).toBeNull();
    expect(await openEnv(c.subtle, install!.queueKey, { tenantId: 't1', deviceId: install!.installId, userId: 'u1' }, env)).not.toBeNull();
    // the first user confirms: sent with their original Idempotency-Keys and bodies
    expect(await first.claimUnclaimed()).toBe(2);
    expect(await first.sync()).toMatchObject({ sent: 2, pending: 0 });
    expect(server.records.map((r) => [r.visitId, r.key]).sort()).toEqual([
      ['task-8', 'old-key-8'],
      ['task-9', 'old-key-9'],
    ]);
    expect(server.records.find((r) => r.visitId === 'task-9')?.body).toEqual(old[0].body);
  });

  it('ignores a malformed old queue', async () => {
    local.setItem(LEGACY_QUEUE_KEY, '{not json');
    expect(await (await openAs('u1')).unclaimedCount()).toBe(0);
  });
});

describe('M-001 logout prompt count', () => {
  it('counts this user\'s unsent records (queued, kept, unconfirmed) without creating a key', async () => {
    expect(await FieldSync.unsyncedCount(backend, c, 't1', 'u1')).toBe(0);
    expect(await backend.list('meta')).toEqual([]);
    local.setItem(LEGACY_QUEUE_KEY, JSON.stringify([{ visitId: 'v', key: 'k', body: { answers: {}, notes: '', recordedAt: 'x' } }]));
    const f = await openAs('u1');
    await f.register();
    await f.captureVisit({ assignmentId: 'a1', answers: {} });
    await f.captureRecord('task-1', { answers: {}, notes: '', recordedAt: 'x' }, 'k1');
    expect(await FieldSync.unsyncedCount(backend, c, 't1', 'u1')).toBe(3);
    expect(await FieldSync.unsyncedCount(backend, c, 't1', 'u2')).toBe(0);
    await f.claimUnclaimed();
    await f.sync();
    expect(await FieldSync.unsyncedCount(backend, c, 't1', 'u1')).toBe(0);
  });
});
