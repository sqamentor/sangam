// SPDX-License-Identifier: Apache-2.0
/**
 * Storage behind the field queue. The browser backend is IndexedDB, which can hold a non-extractable CryptoKey by
 * structured clone (contract item 1: "WebCrypto non-extractable key in IndexedDB"). The in-memory backend serves
 * tests and browsers without IndexedDB (nothing then survives a reload, and the screen says so).
 */

export type StoreName = 'meta' | 'devices' | 'entries' | 'legacy' | 'cache';
export const STORES: readonly StoreName[] = ['meta', 'devices', 'entries', 'legacy', 'cache'];

export type WriteOp = { put: StoreName; key: string; value: unknown } | { del: StoreName; key: string };

export interface QueueBackend {
  /** false for a backend whose data is lost on reload. */
  readonly durable: boolean;
  get<T>(store: StoreName, key: string): Promise<T | undefined>;
  list<T>(store: StoreName): Promise<{ key: string; value: T }[]>;
  /** Applies every operation or none. */
  write(ops: WriteOp[]): Promise<void>;
  /** Removes every store's content (crypto-erase with the key). */
  clear(): Promise<void>;
}

/** Shallow copy of plain records, so a caller never mutates stored state by reference (keys inside stay shared). */
const copy = <T>(v: T): T => (v && typeof v === 'object' && Object.getPrototypeOf(v) === Object.prototype ? ({ ...v } as T) : v);

export class MemoryBackend implements QueueBackend {
  readonly durable = false;
  private readonly data = new Map<StoreName, Map<string, unknown>>(STORES.map((s) => [s, new Map()]));

  async get<T>(store: StoreName, key: string): Promise<T | undefined> {
    return copy(this.data.get(store)?.get(key) as T | undefined);
  }

  async list<T>(store: StoreName): Promise<{ key: string; value: T }[]> {
    return [...(this.data.get(store) ?? new Map()).entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([key, value]) => ({ key, value: copy(value as T) }));
  }

  async write(ops: WriteOp[]): Promise<void> {
    for (const op of ops) {
      if ('put' in op) this.data.get(op.put)?.set(op.key, copy(op.value));
      else this.data.get(op.del)?.delete(op.key);
    }
  }

  async clear(): Promise<void> {
    for (const m of this.data.values()) m.clear();
  }
}

const DB = 'sangam-field-queue';
const DB_VERSION = 1;

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'));
  });
}

/** IndexedDB backend (one database, one object store per StoreName, out-of-line string keys). */
export class IndexedDbBackend implements QueueBackend {
  readonly durable = true;
  private db: Promise<IDBDatabase> | null = null;

  constructor(private readonly factory: IDBFactory, private readonly name: string = DB) {}

  private open(): Promise<IDBDatabase> {
    if (!this.db) {
      const r = this.factory.open(this.name, DB_VERSION);
      r.onupgradeneeded = () => {
        for (const s of STORES) if (!r.result.objectStoreNames.contains(s)) r.result.createObjectStore(s);
      };
      this.db = req(r);
    }
    return this.db;
  }

  async get<T>(store: StoreName, key: string): Promise<T | undefined> {
    const db = await this.open();
    return (await req(db.transaction(store, 'readonly').objectStore(store).get(key))) as T | undefined;
  }

  async list<T>(store: StoreName): Promise<{ key: string; value: T }[]> {
    const db = await this.open();
    const os = db.transaction(store, 'readonly').objectStore(store);
    const [keys, values] = await Promise.all([req(os.getAllKeys()), req(os.getAll())]);
    return keys.map((k, i) => ({ key: String(k), value: values[i] as T }));
  }

  async write(ops: WriteOp[]): Promise<void> {
    if (ops.length === 0) return;
    const db = await this.open();
    const names = [...new Set(ops.map((o) => ('put' in o ? o.put : o.del)))];
    const tx = db.transaction(names, 'readwrite');
    for (const op of ops) {
      if ('put' in op) tx.objectStore(op.put).put(op.value, op.key);
      else tx.objectStore(op.del).delete(op.key);
    }
    await done(tx);
  }

  async clear(): Promise<void> {
    const db = await this.open();
    const tx = db.transaction([...STORES], 'readwrite');
    for (const s of STORES) tx.objectStore(s).clear();
    await done(tx);
  }
}

/** IndexedDB when the browser has it, else memory. */
export function defaultBackend(): QueueBackend {
  try {
    if (typeof indexedDB !== 'undefined' && indexedDB) return new IndexedDbBackend(indexedDB);
  } catch {
    /* blocked (private mode, policy): fall through */
  }
  return new MemoryBackend();
}
