// SPDX-License-Identifier: Apache-2.0
/**
 * P35 saved inbox filters in this browser's localStorage (owner decision 337, CERT-In / DPDP matrix W-07):
 * - the free-text search (`q`) is never stored: it may hold a name, a reference or other personal data;
 * - the storage key is a hash of the user, never the user name: `sangam.inbox.savedFilters.<hex SHA-256 of
 *   tenantId|userId>`, the same owner tag as the field queue (FieldSync.ownerTagOf).
 * Migration of what older builds stored under `sangam.inbox.filters.<userId>` (with `q`), run on every load:
 * - the signed-in user's own old entry moves to the hashed key without `q`, and the old key is removed;
 * - every other old entry in this browser (other users, `anonymous`) loses its `q` at once; its key is renamed when
 *   its user next opens the inbox (the old key holds no tenant, so it cannot be hashed for them here).
 * Without a signed-in user or without Web Crypto, nothing is read or written: the filters then live for this page
 * only. The criteria always run on the server, so a stale or edited entry can never widen what is visible.
 */
import { hex, javaUtf8 } from '../../lib/offline/canonical';
import type { TaskSection } from '../../api/types';

/** Key prefix of the hashed store (decision 337). */
export const SAVED_FILTERS_PREFIX = 'sangam.inbox.savedFilters.';
/** Key prefix older builds used: `sangam.inbox.filters.<userId>`. */
export const LEGACY_FILTERS_PREFIX = 'sangam.inbox.filters.';

export interface CardVariable {
  name: string;
  label: string;
}

/** A saved filter: the criteria of the Sangam task API without the free text, plus the card variables (P45). */
export interface SavedInboxFilter {
  id: string;
  name: string;
  section: TaskSection;
  sla: string;
  packKey: string;
  /** M-042: ISO 4217 currency filter (absent in filters saved before it existed). */
  currency?: string;
  variables: CardVariable[];
}

export interface FilterOwner {
  tenantId: string;
  userId: string;
}

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'key' | 'length'>;

export function browserStore(): Store | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** The storage key of a user's saved filters: a hash of tenantId|userId, never the name itself. */
export async function savedFiltersKey(c: Crypto | undefined, owner: FilterOwner): Promise<string | null> {
  if (!c?.subtle || !owner.tenantId || !owner.userId) return null;
  try {
    return SAVED_FILTERS_PREFIX + hex(await c.subtle.digest('SHA-256', javaUtf8(`${owner.tenantId}|${owner.userId}`)));
  } catch {
    return null;
  }
}

/** Keeps only the fields a saved filter may have: `q` and anything unknown are dropped. */
export function sanitise(raw: unknown): SavedInboxFilter[] {
  if (!Array.isArray(raw)) return [];
  const out: SavedInboxFilter[] = [];
  for (const f of raw as Record<string, unknown>[]) {
    if (!f || typeof f.id !== 'string' || typeof f.name !== 'string') continue;
    const s: SavedInboxFilter = {
      id: f.id,
      name: f.name,
      section: (typeof f.section === 'string' ? f.section : 'MY_SEAT') as TaskSection,
      sla: typeof f.sla === 'string' ? f.sla : '',
      packKey: typeof f.packKey === 'string' ? f.packKey : '',
      variables: Array.isArray(f.variables)
        ? (f.variables as Record<string, unknown>[]).filter((v) => v && typeof v.name === 'string').map((v) => ({ name: v.name as string, label: typeof v.label === 'string' ? v.label : (v.name as string) }))
        : [],
    };
    if (typeof f.currency === 'string' && f.currency) s.currency = f.currency;
    out.push(s);
  }
  return out;
}

function read(store: Store, key: string): SavedInboxFilter[] {
  try {
    const raw = store.getItem(key);
    return raw ? sanitise(JSON.parse(raw) as unknown) : [];
  } catch {
    return [];
  }
}

function legacyKeys(store: Store): string[] {
  const keys: string[] = [];
  for (let i = 0; i < store.length; i++) {
    const k = store.key(i);
    if (k && k.startsWith(LEGACY_FILTERS_PREFIX)) keys.push(k);
  }
  return keys;
}

/**
 * Loads the user's saved filters from `key` after the decision 337 migration (see the file comment). Returns [] when
 * there is no store or no key.
 */
export function loadSavedFilters(store: Store | null, key: string | null, owner: FilterOwner): SavedInboxFilter[] {
  if (!store) return [];
  try {
    for (const k of legacyKeys(store)) {
      const entries = read(store, k);
      if (key && k === LEGACY_FILTERS_PREFIX + owner.userId) {
        const current = read(store, key);
        const ids = new Set(current.map((f) => f.id));
        store.setItem(key, JSON.stringify([...current, ...entries.filter((f) => !ids.has(f.id))]));
        store.removeItem(k);
      } else {
        store.setItem(k, JSON.stringify(entries));
      }
    }
  } catch {
    /* storage refused a write: what can be read is still returned below, never with q */
  }
  if (!key) return [];
  const filters = read(store, key);
  try {
    // a value written by anything else (an old build in another tab) is rewritten without q
    if (store.getItem(key) !== null) store.setItem(key, JSON.stringify(filters));
  } catch {
    /* read-only storage */
  }
  return filters;
}

/** Stores the filters under the hashed key, without q; does nothing without a store or a key. */
export function storeSavedFilters(store: Store | null, key: string | null, filters: SavedInboxFilter[]): void {
  if (!store || !key) return;
  try {
    store.setItem(key, JSON.stringify(sanitise(filters)));
  } catch {
    /* storage unavailable: the filter lives for this page only */
  }
}
