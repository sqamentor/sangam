// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 337 (CERT-In / DPDP matrix W-07): a saved inbox filter never stores the free-text query and is keyed
 * by a hash of the user, not the user name; what older builds stored (`sangam.inbox.filters.<userId>`, with `q`) loses
 * its free text on load.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { LEGACY_FILTERS_PREFIX, SAVED_FILTERS_PREFIX, loadSavedFilters, sanitise, savedFiltersKey, storeSavedFilters } from './savedFilters';

const c = globalThis.crypto;
const owner = { tenantId: 't1', userId: 'asha.verma' };
const store = () => window.localStorage;
const all = () => Object.fromEntries(Object.keys(store()).map((k) => [k, store().getItem(k)]));

const legacyFilter = (id: string, q: string) => ({ id, name: `Filter ${id}`, section: 'POOL', q, sla: 'WARNING', packKey: 'leave', currency: 'INR', variables: [{ name: 'amount', label: 'Amount' }] });

beforeEach(() => window.localStorage.clear());

describe('decision 337 saved inbox filters', () => {
  it('the key is a SHA-256 of tenant|user: no user name, a different key per tenant, none without Web Crypto', async () => {
    const key = await savedFiltersKey(c, owner);
    expect(key!.startsWith(SAVED_FILTERS_PREFIX)).toBe(true);
    expect(key!.slice(SAVED_FILTERS_PREFIX.length)).toMatch(/^[0-9a-f]{64}$/);
    expect(key).not.toContain('asha');
    expect(await savedFiltersKey(c, { ...owner, tenantId: 't2' })).not.toBe(key);
    expect(await savedFiltersKey(undefined, owner)).toBeNull();
    expect(await savedFiltersKey(c, { tenantId: 't1', userId: '' })).toBeNull();
  });

  it('storing never writes the free text, nor any field a saved filter does not have', async () => {
    const key = await savedFiltersKey(c, owner);
    storeSavedFilters(store(), key, [legacyFilter('f1', 'Ramesh Kumar 9876543210') as never]);
    const raw = store().getItem(key!)!;
    expect(raw).not.toContain('Ramesh');
    expect(JSON.parse(raw)).toEqual([{ id: 'f1', name: 'Filter f1', section: 'POOL', sla: 'WARNING', packKey: 'leave', currency: 'INR', variables: [{ name: 'amount', label: 'Amount' }] }]);
  });

  it("the user's own old entry moves to the hashed key without q, and the old key is removed", async () => {
    store().setItem(LEGACY_FILTERS_PREFIX + owner.userId, JSON.stringify([legacyFilter('f1', 'Ramesh Kumar')]));
    const key = await savedFiltersKey(c, owner);
    const loaded = loadSavedFilters(store(), key, owner);
    expect(loaded.map((f) => f.id)).toEqual(['f1']);
    expect(loaded[0]).not.toHaveProperty('q');
    expect(store().getItem(LEGACY_FILTERS_PREFIX + owner.userId)).toBeNull();
    expect(JSON.stringify(all())).not.toContain('Ramesh');
    expect(Object.keys(all()).some((k) => k.includes('asha'))).toBe(false);
  });

  it('other users\' old entries lose their free text at once; an entry with q under the hashed key is rewritten', async () => {
    const key = (await savedFiltersKey(c, owner))!;
    store().setItem(`${LEGACY_FILTERS_PREFIX}someone.else`, JSON.stringify([legacyFilter('g1', 'Sita Devi')]));
    store().setItem(`${LEGACY_FILTERS_PREFIX}anonymous`, JSON.stringify([legacyFilter('g2', 'Mohan Lal')]));
    store().setItem(key, JSON.stringify([legacyFilter('f9', 'Gita Rani')]));
    store().setItem('sangam.lang', 'en');
    const loaded = loadSavedFilters(store(), key, owner);
    expect(loaded.map((f) => f.id)).toEqual(['f9']);
    const dump = JSON.stringify(all());
    for (const text of ['Sita', 'Mohan', 'Gita']) expect(dump).not.toContain(text);
    expect(JSON.parse(store().getItem(`${LEGACY_FILTERS_PREFIX}someone.else`)!)[0]).toMatchObject({ id: 'g1', packKey: 'leave' });
    expect(store().getItem('sangam.lang')).toBe('en');
  });

  it('merging an old entry keeps what the hashed key already has (no duplicate ids)', async () => {
    const key = (await savedFiltersKey(c, owner))!;
    store().setItem(key, JSON.stringify([legacyFilter('f1', '')]));
    store().setItem(LEGACY_FILTERS_PREFIX + owner.userId, JSON.stringify([legacyFilter('f1', 'x'), legacyFilter('f2', 'y')]));
    expect(loadSavedFilters(store(), key, owner).map((f) => f.id)).toEqual(['f1', 'f2']);
  });

  it('without a key nothing is read or moved, but old free text is still dropped', () => {
    store().setItem(LEGACY_FILTERS_PREFIX + owner.userId, JSON.stringify([legacyFilter('f1', 'Ramesh Kumar')]));
    expect(loadSavedFilters(store(), null, owner)).toEqual([]);
    expect(store().getItem(LEGACY_FILTERS_PREFIX + owner.userId)).not.toContain('Ramesh');
    storeSavedFilters(store(), null, [legacyFilter('f2', 'z') as never]);
    expect(Object.keys(all())).toEqual([LEGACY_FILTERS_PREFIX + owner.userId]);
  });

  it('sanitise drops malformed entries and keeps only known fields', () => {
    expect(sanitise('nope')).toEqual([]);
    expect(sanitise([null, { id: 1, name: 'x' }, { id: 'a', name: 'A', extra: 'secret', variables: [{ name: 'v' }, 7] }])).toEqual([
      { id: 'a', name: 'A', section: 'MY_SEAT', sla: '', packKey: '', variables: [{ name: 'v', label: 'v' }] },
    ]);
  });
});
