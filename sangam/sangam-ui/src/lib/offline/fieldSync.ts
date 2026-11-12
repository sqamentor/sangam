// SPDX-License-Identifier: Apache-2.0
/**
 * The PWA field client (M-001 / SL-129): encrypted local queue (sangam-field sync/LocalQueueContract.java:8-31), device
 * registration (POST /field/devices, DeviceRegistry.register) and the sync protocol (POST /field/sync/{deviceId}/upload,
 * GET /field/sync/{deviceId}/pull, SyncService), with the owner's decisions of 2026-11-12:
 * - one queue key per device installation; userId and tenantId are bound into every entry's AES-GCM AAD (crypto.ts);
 * - at logout entries stay encrypted and hidden from other users; they sync only under the same user. The server binds
 *   a device id to one principal (DeviceRegistry.java:79-83, requireActive :147-157), so each user of an installation
 *   gets their own device registration; the queue key is shared, the AAD is not;
 * - plaintext entries from the old localStorage queue (owner decisions 2026-11-12, second round) are encrypted on the
 *   first load, bound to the signed-in user who loads them (tenant, installation, user in the AAD); the plaintext is
 *   deleted as soon as the encrypted copies are durably stored (a failed store keeps the plaintext and the import is
 *   retried on the next open or sync); the records are sent only after that user confirms them; no other user, of
 *   the same tenant or another, can open, send or discard them.
 *
 * Two kinds of entries:
 * - packets: N05 CapturePackets (VISIT_SUBMIT) for field assignments, uploaded in deviceSeq order; deleted only on NEW
 *   or REPLAY, kept and shown on CONFLICT or REJECTED (contract item 5). A REPLAY of a packet the server had REJECTED
 *   (SyncService.java:116-128 returns the stored result) is still a rejection and is kept;
 * - visit records: the task-based field visits of GET /field/visits (WorkspaceController.java:180-195), replayed through
 *   POST /field/visits/{id}/record with the Idempotency-Key fixed at capture (R41). The old queue held only these.
 */
import { canonicalPacket, hex, javaInstant, javaUtf8, packetHash, type CapturePacket, type GpsFix } from './canonical';
import { newQueueKey, newSigningKey, open as openEnvelope, publicKeySpki, seal, signHash, uuidV5, type Binding, type Envelope } from './crypto';
import type { QueueBackend, WriteOp } from './storage';

/** QueuePolicy (sangam-field sync/QueuePolicy.java:145-155); the server defaults when absent. */
export interface QueuePolicy {
  maxItems: number;
  maxAgeHours: number;
  wipeOnRevoke: boolean;
  requireSignature: boolean;
  maxOfflineHours: number;
}
export const DEFAULT_POLICY: QueuePolicy = { maxItems: 500, maxAgeHours: 72, wipeOnRevoke: true, requireSignature: false, maxOfflineHours: 168 };

/** DeviceRegistry.Device.view() (DeviceRegistry.java:34-52). */
export interface DeviceView {
  deviceId: string;
  status: 'ACTIVE' | 'REVOKED' | 'LOST';
  policy?: Partial<QueuePolicy>;
  queueKeyId?: string;
  lastSeq?: number;
  lastPacketHash?: string | null;
}

/** SyncService.SyncOutcome (SyncService.java:57). */
export interface SyncOutcome {
  operationId: string | null;
  packetId?: string;
  status: 'NEW' | 'REPLAY' | 'CONFLICT' | 'REJECTED';
  code?: string;
  result?: Record<string, unknown>;
}

export interface PulledTemplate {
  key: string;
  version: number;
  status?: string;
  contentHash?: string;
  template: FieldTemplate;
}

/** The parts of sangam-field's FieldTemplate the capture form uses (template/FieldTemplate.java:29-74). */
export interface FieldTemplate {
  key: string;
  version: number;
  title?: Record<string, string>;
  sections?: { id: string; title?: Record<string, string>; items?: TemplateItem[] }[];
}
export interface TemplateItem {
  id: string;
  type: 'BOOLEAN' | 'CHOICE' | 'MULTI_CHOICE' | 'NUMBER' | 'TEXT' | 'DATE' | 'PHOTO' | 'SIGNATURE' | 'GEO_POINT';
  label?: Record<string, string>;
  required?: boolean;
  options?: { value: string; label?: Record<string, string> }[];
}

/** SyncService.PullResult (SyncService.java:60-63). */
export interface PullResult {
  cursor: number;
  hasMore: boolean;
  deviceStatus: string;
  policy?: Partial<QueuePolicy>;
  assignments: Record<string, unknown>[];
  revokedAssignmentIds: string[];
  templates: PulledTemplate[];
  jobs?: unknown[];
  offers?: unknown[];
}

export interface VisitRecordBody {
  answers: Record<string, boolean | string>;
  notes: string;
  recordedAt: string;
}

export interface FieldTransport {
  registerDevice(body: { deviceId: string; platform: string; appVersion?: string; publicKey?: string; keyAlg?: string }): Promise<DeviceView>;
  upload(deviceId: string, packets: CapturePacket[]): Promise<SyncOutcome[]>;
  pull(deviceId: string, cursor: number): Promise<PullResult>;
  recordVisit(visitId: string, body: VisitRecordBody, idempotencyKey: string): Promise<void>;
}

/** An error carrying the server problem (ApiError-compatible: status and problem.code). */
interface ProblemLike {
  status?: number;
  problem?: { code?: string };
}
const statusOf = (e: unknown): number => (typeof (e as ProblemLike)?.status === 'number' ? ((e as ProblemLike).status as number) : 0);
const codeOf = (e: unknown): string | undefined => (e as ProblemLike)?.problem?.code;

export class FieldQueueError extends Error {
  constructor(readonly code: 'NOT_REGISTERED' | 'QUEUE_FULL' | 'OFFLINE_TOO_LONG' | 'DEVICE_REVOKED') {
    super(code);
  }
}

interface InstallRecord {
  installId: string;
  queueKey: CryptoKey;
  signing: CryptoKeyPair;
}

interface DeviceRecord {
  deviceId: string;
  kid: string;
  policy: QueuePolicy;
  status: 'ACTIVE' | 'REVOKED' | 'LOST';
  /** Last deviceSeq used for a capture. */
  seq: number;
  /** packetHash of the last captured packet (the next packet's prevHash). */
  chainHead?: string;
  registeredAt: string;
  lastSyncAt?: string;
  signed: boolean;
}

/** UNCONFIRMED: imported from the old plaintext queue, waiting for its user to confirm before it is sent. */
type EntryState = 'PENDING' | 'KEPT' | 'UNCONFIRMED';

interface PacketEntry {
  owner: string;
  seq: number;
  env: Envelope;
  state: EntryState;
  status?: string;
  code?: string;
}

interface RecordEntry {
  owner: string;
  env: Envelope;
  state: EntryState;
  code?: string;
}

interface LegacyPlain {
  visitId: string;
  key: string;
  body: VisitRecordBody;
}

export interface CachedState {
  cursor: number;
  assignments: Record<string, Record<string, unknown>>;
  templates: Record<string, PulledTemplate>;
  pulledAt?: string;
}

export interface PendingItem {
  kind: 'packet' | 'record';
  /** Storage key, for discard. */
  id: string;
  operationId: string;
  state: EntryState;
  code?: string;
  /** assignmentId (packet) or visitId (record). */
  target?: string;
  at?: string;
}

export interface SyncReport {
  sent: number;
  kept: number;
  pending: number;
  revoked: boolean;
  wiped: boolean;
  error?: unknown;
}

/** localStorage key of the old plaintext queue (MyWork.tsx before M-001). */
export const LEGACY_QUEUE_KEY = 'sangam.field.queue';
const UPLOAD_BATCH = 50;
const MAX_PULL_PAGES = 100;

export interface FieldSyncOptions {
  backend: QueueBackend;
  crypto: Crypto;
  transport: FieldTransport;
  tenantId: string;
  userId: string;
  appVersion?: string;
  platform?: string;
  /** The old plaintext queue's storage (window.localStorage); null when unavailable. */
  legacyStorage?: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null;
  now?: () => Date;
}

const pad = (n: number) => String(n).padStart(12, '0');

export class FieldSync {
  private constructor(
    private readonly o: FieldSyncOptions,
    private readonly install: InstallRecord,
    readonly ownerTag: string,
  ) {}

  /** hex SHA-256 of tenantId|userId: the owner of an entry, stored beside it so lists need no decryption. */
  static async ownerTagOf(c: Crypto, tenantId: string, userId: string): Promise<string> {
    return hex(await c.subtle.digest('SHA-256', javaUtf8(`${tenantId}|${userId}`)));
  }

  /**
   * How many of this user's field records are on the device and not yet accepted by the server (queued, kept after a
   * refusal, or imported and unconfirmed). Reads only owner tags: no key is created and nothing is decrypted.
   */
  static async unsyncedCount(backend: QueueBackend, c: Crypto, tenantId: string, userId: string): Promise<number> {
    const tag = await FieldSync.ownerTagOf(c, tenantId, userId);
    const own = (e: { value: { owner?: string } }) => e.value?.owner === tag;
    return (await backend.list<PacketEntry>('entries')).filter(own).length + (await backend.list<RecordEntry>('legacy')).filter(own).length;
  }

  /** Serialises every read-modify-write of the device record (seq, chain head), so a capture made while a sync is
   *  in flight never reuses a deviceSeq. Network calls run outside it. */
  private chain: Promise<unknown> = Promise.resolve();
  private exclusive<T>(fn: () => Promise<T>): Promise<T> {
    const run = this.chain.then(fn, fn);
    this.chain = run.catch(() => undefined);
    return run;
  }
  /** Re-reads the device record inside the lock and writes the patched record with the given entry operations. */
  private patchDevice(patch: (d: DeviceRecord) => DeviceRecord, ops: WriteOp[] = []): Promise<DeviceRecord | undefined> {
    return this.exclusive(async () => {
      const cur = await this.device();
      const next = cur ? patch(cur) : undefined;
      await this.o.backend.write(next ? [...ops, { put: 'devices', key: this.ownerTag, value: next }] : ops);
      return next;
    });
  }

  private get subtle(): SubtleCrypto {
    return this.o.crypto.subtle;
  }
  private now(): Date {
    return this.o.now ? this.o.now() : new Date();
  }

  /** Opens (or creates) the installation's key and imports any old plaintext entries as unclaimed. */
  static async open(o: FieldSyncOptions): Promise<FieldSync> {
    let install = await o.backend.get<InstallRecord>('meta', 'install');
    if (!install) {
      install = { installId: o.crypto.randomUUID(), queueKey: await newQueueKey(o.crypto.subtle), signing: await newSigningKey(o.crypto.subtle) };
      await o.backend.write([{ put: 'meta', key: 'install', value: install }]);
    }
    const f = new FieldSync(o, install, await FieldSync.ownerTagOf(o.crypto, o.tenantId, o.userId));
    await f.importPlaintext();
    return f;
  }

  // ------------------------------------------------------------------ device registration

  async device(): Promise<DeviceRecord | undefined> {
    return this.o.backend.get<DeviceRecord>('devices', this.ownerTag);
  }

  /** Registers this user's device (idempotent: re-registers the same id, DeviceRegistry.java:73-92). Online only. */
  async register(): Promise<DeviceRecord> {
    const known = await this.device();
    const deviceId = known?.deviceId ?? this.o.crypto.randomUUID();
    let view: DeviceView;
    try {
      view = await this.o.transport.registerDevice({
        deviceId,
        platform: this.o.platform ?? 'web',
        appVersion: this.o.appVersion,
        publicKey: await publicKeySpki(this.subtle, this.install.signing.publicKey),
        keyAlg: 'EC',
      });
    } catch (e) {
      if (codeOf(e) === 'DEVICE_REVOKED') await this.revoked(known);
      throw e;
    }
    return this.exclusive(async () => {
      const cur = await this.device();
      const rec: DeviceRecord = {
        deviceId: view.deviceId ?? deviceId,
        kid: view.queueKeyId ?? cur?.kid ?? 'local',
        policy: { ...DEFAULT_POLICY, ...(view.policy ?? {}) },
        status: view.status ?? 'ACTIVE',
        seq: Math.max(cur?.seq ?? 0, view.lastSeq ?? 0),
        chainHead: cur?.chainHead ?? view.lastPacketHash ?? undefined,
        registeredAt: cur?.registeredAt ?? this.now().toISOString(),
        lastSyncAt: cur?.lastSyncAt ?? this.now().toISOString(),
        signed: true,
      };
      await this.o.backend.write([{ put: 'devices', key: this.ownerTag, value: rec }]);
      return rec;
    });
  }

  private binding(deviceId: string): Binding {
    return { tenantId: this.o.tenantId, deviceId, userId: this.o.userId };
  }
  /** Visit records (and imported old records) are bound to tenant, installation and the signed-in user. */
  private recordBinding(): Binding {
    return { tenantId: this.o.tenantId, deviceId: this.install.installId, userId: this.o.userId };
  }

  // ------------------------------------------------------------------ capture

  /** Queues a VISIT_SUBMIT packet for a field assignment (works offline once the device is registered). */
  captureVisit(v: { assignmentId: string; baseVersion?: number; answers: Record<string, unknown>; notes?: string; fix?: GpsFix }): Promise<string> {
    return this.exclusive(() => this.capture(v));
  }

  private async capture(v: { assignmentId: string; baseVersion?: number; answers: Record<string, unknown>; notes?: string; fix?: GpsFix }): Promise<string> {
    const d = await this.device();
    if (!d) throw new FieldQueueError('NOT_REGISTERED');
    if (d.status !== 'ACTIVE') throw new FieldQueueError('DEVICE_REVOKED');
    const now = this.now();
    // QueuePolicy.maxItems: refuse new captures when full, never drop queued work (QueuePolicy.java:139)
    if ((await this.ownPackets()).length + (await this.ownRecords()).length >= d.policy.maxItems) throw new FieldQueueError('QUEUE_FULL');
    // QueuePolicy.maxOfflineHours: sync at least this often or stop offering capture (QueuePolicy.java:143)
    const since = Date.parse(d.lastSyncAt ?? d.registeredAt);
    if (now.getTime() - since > d.policy.maxOfflineHours * 3_600_000) throw new FieldQueueError('OFFLINE_TOO_LONG');
    const seq = d.seq + 1;
    const operationId = await uuidV5(this.subtle, d.deviceId, String(seq));
    const packet: CapturePacket = {
      packetId: operationId,
      type: 'VISIT_SUBMIT',
      operationId,
      deviceId: d.deviceId,
      deviceSeq: seq,
      appVersion: this.o.appVersion,
      assignmentId: v.assignmentId,
      baseVersion: v.baseVersion,
      deviceTime: javaInstant(now),
      answers: v.answers,
      notes: v.notes || undefined,
      fix: v.fix,
      payload: {},
      prevHash: d.chainHead,
    };
    packet.packetHash = await packetHash(this.subtle, packet);
    if (d.signed) packet.signature = await signHash(this.subtle, this.install.signing.privateKey, packet.packetHash);
    const env = await seal(this.o.crypto, this.install.queueKey, d.kid, this.binding(d.deviceId), operationId, JSON.stringify(packet), now);
    const entry: PacketEntry = { owner: this.ownerTag, seq, env, state: 'PENDING' };
    await this.o.backend.write([
      { put: 'entries', key: `${this.ownerTag}:${pad(seq)}`, value: entry },
      { put: 'devices', key: this.ownerTag, value: { ...d, seq, chainHead: packet.packetHash } },
    ]);
    return operationId;
  }

  /** Queues a task-based visit record; the Idempotency-Key is fixed now and reused on every retry (R41). */
  async captureRecord(visitId: string, body: VisitRecordBody, idempotencyKey: string): Promise<void> {
    const d = await this.device();
    const plain: LegacyPlain = { visitId, key: idempotencyKey, body };
    const env = await seal(this.o.crypto, this.install.queueKey, d?.kid ?? 'local', this.recordBinding(), idempotencyKey, JSON.stringify(plain), this.now());
    const entry: RecordEntry = { owner: this.ownerTag, env, state: 'PENDING' };
    await this.o.backend.write([{ put: 'legacy', key: `${this.ownerTag}:${idempotencyKey}`, value: entry }]);
  }

  // ------------------------------------------------------------------ reading the queue (own entries only)

  private async ownPackets(): Promise<{ key: string; value: PacketEntry }[]> {
    return (await this.o.backend.list<PacketEntry>('entries')).filter((e) => e.value.owner === this.ownerTag).sort((a, b) => a.value.seq - b.value.seq);
  }
  private async ownRecords(): Promise<{ key: string; value: RecordEntry }[]> {
    return (await this.o.backend.list<RecordEntry>('legacy')).filter((e) => e.value.owner === this.ownerTag);
  }

  private async openPacket(e: PacketEntry, deviceId: string): Promise<CapturePacket | null> {
    const pt = await openEnvelope(this.subtle, this.install.queueKey, this.binding(deviceId), e.env);
    return pt === null ? null : (JSON.parse(pt) as CapturePacket);
  }
  private async openRecord(e: RecordEntry): Promise<LegacyPlain | null> {
    if (e.owner !== this.ownerTag) return null;
    const pt = await openEnvelope(this.subtle, this.install.queueKey, this.recordBinding(), e.env);
    return pt === null ? null : (JSON.parse(pt) as LegacyPlain);
  }

  /** This user's queued entries, decrypted for display. Entries that do not open (another user's AAD) never appear. */
  async pending(): Promise<PendingItem[]> {
    const out: PendingItem[] = [];
    const d = await this.device();
    if (d) {
      for (const { key, value } of await this.ownPackets()) {
        const p = await this.openPacket(value, d.deviceId);
        if (p) out.push({ kind: 'packet', id: key, operationId: p.operationId, state: value.state, code: value.code, target: p.assignmentId, at: p.deviceTime });
      }
    }
    for (const { key, value } of await this.ownRecords()) {
      if (value.state === 'UNCONFIRMED') continue; // shown through unclaimedCount until the user confirms them
      const r = await this.openRecord(value);
      if (r) out.push({ kind: 'record', id: key, operationId: r.key, state: value.state, code: value.code, target: r.visitId, at: r.body.recordedAt });
    }
    return out;
  }

  /** Removes one of this user's entries (the user decided, e.g. after a rejection). */
  async discard(id: string): Promise<void> {
    const own = id.startsWith(`${this.ownerTag}:`);
    if (!own) return;
    await this.o.backend.write([{ del: 'entries', key: id }, { del: 'legacy', key: id }]);
  }

  // ------------------------------------------------------------------ sync

  /** Uploads this user's queue: visit records first (idempotent replays), then packets in deviceSeq order. */
  async sync(): Promise<SyncReport> {
    const report: SyncReport = { sent: 0, kept: 0, pending: 0, revoked: false, wiped: false };
    try {
      await this.importPlaintext(); // retries an import whose encrypted store failed (no-op when nothing is left)
      await this.syncRecords(report);
      await this.syncPackets(report);
    } catch (e) {
      report.error = e;
      if (codeOf(e) === 'DEVICE_REVOKED') {
        report.revoked = true;
        report.wiped = await this.revoked(await this.device());
      } else if (e instanceof FieldQueueError && e.code === 'DEVICE_REVOKED') {
        report.revoked = true;
      }
    }
    if (!report.wiped) {
      const left = await this.pending();
      report.pending = left.filter((x) => x.state === 'PENDING').length;
      report.kept = left.filter((x) => x.state === 'KEPT').length;
    }
    return report;
  }

  private async syncRecords(report: SyncReport): Promise<void> {
    for (const { key, value } of await this.ownRecords()) {
      if (value.state !== 'PENDING') continue;
      const r = await this.openRecord(value);
      if (!r) continue;
      try {
        await this.o.transport.recordVisit(r.visitId, r.body, r.key);
        await this.o.backend.write([{ del: 'legacy', key }]);
        report.sent++;
      } catch (e) {
        const s = statusOf(e);
        // network, authentication, throttling or server trouble: keep it pending and stop for now
        if (s === 0 || s === 401 || s === 408 || s === 429 || s >= 500) throw e;
        // the server refused this record: keep it and show the reason; it is not retried by itself
        await this.o.backend.write([{ put: 'legacy', key, value: { ...value, state: 'KEPT', code: codeOf(e) ?? String(s) } }]);
      }
    }
  }

  private async syncPackets(report: SyncReport): Promise<void> {
    let d = await this.device();
    if (!d) return;
    if (d.status !== 'ACTIVE') throw new FieldQueueError('DEVICE_REVOKED');
    const queued: { key: string; value: PacketEntry; packet: CapturePacket }[] = [];
    for (const e of await this.ownPackets()) {
      if (e.value.state !== 'PENDING') continue;
      const packet = await this.openPacket(e.value, d.deviceId);
      if (packet) queued.push({ ...e, packet });
    }
    for (let i = 0; i < queued.length; i += UPLOAD_BATCH) {
      const batch = queued.slice(i, i + UPLOAD_BATCH);
      let outcomes: SyncOutcome[];
      try {
        outcomes = await this.o.transport.upload(d.deviceId, batch.map((b) => b.packet));
      } catch (e) {
        if (codeOf(e) !== 'DEVICE_UNKNOWN') throw e;
        // the server no longer knows this device id: register it again (same id) and retry the batch once
        d = await this.register();
        outcomes = await this.o.transport.upload(d.deviceId, batch.map((b) => b.packet));
      }
      const byOp = new Map(outcomes.filter((o) => o.operationId).map((o) => [o.operationId as string, o]));
      const ops: WriteOp[] = [];
      for (const b of batch) {
        const o = byOp.get(b.packet.operationId);
        if (!o) continue; // no answer for it: stays pending
        if (keeps(o)) {
          ops.push({ put: 'entries', key: b.key, value: { ...b.value, state: 'KEPT', status: o.status, code: o.code } });
        } else {
          ops.push({ del: 'entries', key: b.key });
          report.sent++;
        }
      }
      d = (await this.patchDevice((cur) => ({ ...cur, lastSyncAt: this.now().toISOString() }), ops)) ?? d;
    }
    if (queued.length === 0) await this.patchDevice((cur) => ({ ...cur, lastSyncAt: this.now().toISOString() }));
  }

  /**
   * DEVICE_REVOKED: with wipeOnRevoke (the server default, QueuePolicy.java:153-155) the queue key is destroyed and every
   * entry with it (contract item 6), together with the old plaintext queue; otherwise the device stops syncing and the
   * entries are kept. Returns whether the queue was wiped.
   */
  private async revoked(d: DeviceRecord | undefined): Promise<boolean> {
    if ((d?.policy ?? DEFAULT_POLICY).wipeOnRevoke) {
      await this.wipe();
      return true;
    }
    if (d) await this.patchDevice((cur) => ({ ...cur, status: 'REVOKED' }));
    return false;
  }

  /** Crypto-erase: the queue key and every entry of every user on this installation. */
  async wipe(): Promise<void> {
    await this.o.backend.clear();
    try {
      this.o.legacyStorage?.removeItem(LEGACY_QUEUE_KEY);
    } catch {
      /* storage unavailable */
    }
  }

  // ------------------------------------------------------------------ pull

  /** The last pulled assignments and templates (decrypted), or an empty state. */
  async cached(): Promise<CachedState> {
    const d = await this.device();
    const env = await this.o.backend.get<Envelope>('cache', this.ownerTag);
    const empty: CachedState = { cursor: 0, assignments: {}, templates: {} };
    if (!d || !env) return empty;
    const pt = await openEnvelope(this.subtle, this.install.queueKey, this.binding(d.deviceId), env);
    return pt === null ? empty : (JSON.parse(pt) as CachedState);
  }

  /** Pulls every change since the cached cursor (registering first if needed) and stores the result encrypted. */
  async pull(): Promise<CachedState> {
    let d = (await this.device()) ?? (await this.register());
    if (d.status !== 'ACTIVE') throw new FieldQueueError('DEVICE_REVOKED');
    const state = await this.cached();
    let policy = d.policy;
    try {
      for (let page = 0; page < MAX_PULL_PAGES; page++) {
        let r: PullResult;
        try {
          r = await this.o.transport.pull(d.deviceId, state.cursor);
        } catch (e) {
          if (codeOf(e) !== 'DEVICE_UNKNOWN') throw e;
          d = await this.register();
          r = await this.o.transport.pull(d.deviceId, state.cursor);
        }
        for (const a of r.assignments ?? []) if (typeof a.id === 'string') state.assignments[a.id] = a;
        for (const id of r.revokedAssignmentIds ?? []) delete state.assignments[id];
        for (const t of r.templates ?? []) state.templates[`${t.key}:${t.version}`] = t;
        state.cursor = r.cursor;
        if (r.policy) policy = { ...DEFAULT_POLICY, ...r.policy };
        if (!r.hasMore) break;
      }
    } catch (e) {
      if (codeOf(e) === 'DEVICE_REVOKED') await this.revoked(d);
      throw e;
    }
    state.pulledAt = this.now().toISOString();
    const env = await seal(this.o.crypto, this.install.queueKey, d.kid, this.binding(d.deviceId), 'pull-state', JSON.stringify(state), this.now());
    // a completed pull is contact with the server for maxOfflineHours, as an upload is
    await this.patchDevice((cur) => ({ ...cur, policy, lastSyncAt: this.now().toISOString() }), [{ put: 'cache', key: this.ownerTag, value: env }]);
    return state;
  }

  // ------------------------------------------------------------------ old plaintext queue

  private readPlaintext(): LegacyPlain[] {
    try {
      const raw = this.o.legacyStorage?.getItem(LEGACY_QUEUE_KEY);
      const list = raw ? (JSON.parse(raw) as unknown) : [];
      return Array.isArray(list) ? (list as LegacyPlain[]).filter((r) => r && typeof r.visitId === 'string' && typeof r.key === 'string' && r.body) : [];
    } catch {
      return [];
    }
  }

  /**
   * First load: every old plaintext record not yet held encrypted is sealed for the signed-in user (UNCONFIRMED), and
   * the plaintext is removed once the encrypted copies are durably stored (the backend's write resolves after its
   * transaction completes). If the store fails, the plaintext stays and the import runs again on the next open or sync.
   */
  private async importPlaintext(): Promise<void> {
    const plain = this.readPlaintext();
    if (plain.length === 0) return;
    try {
      const held = new Set((await this.o.backend.list<RecordEntry>('legacy')).map((e) => e.value.env.op));
      const ops: WriteOp[] = [];
      for (const r of plain) {
        if (held.has(r.key)) continue;
        held.add(r.key);
        const env = await seal(this.o.crypto, this.install.queueKey, 'local', this.recordBinding(), r.key, JSON.stringify(r), this.now());
        const entry: RecordEntry = { owner: this.ownerTag, env, state: 'UNCONFIRMED' };
        ops.push({ put: 'legacy', key: `${this.ownerTag}:${r.key}`, value: entry });
      }
      await this.o.backend.write(ops);
    } catch {
      return; // not stored: keep the plaintext, retry later
    }
    try {
      this.o.legacyStorage?.removeItem(LEGACY_QUEUE_KEY);
    } catch {
      /* storage unavailable: nothing more can be done here */
    }
  }

  private async unconfirmed(): Promise<{ key: string; value: RecordEntry }[]> {
    return (await this.ownRecords()).filter((e) => e.value.state === 'UNCONFIRMED');
  }

  /** How many of this user's records from the old queue wait for confirmation. */
  async unclaimedCount(): Promise<number> {
    return (await this.unconfirmed()).length;
  }

  /** This user confirmed the old records are theirs: the next sync sends them (same envelope, same key). */
  async claimUnclaimed(): Promise<number> {
    const list = await this.unconfirmed();
    await this.o.backend.write(list.map(({ key, value }) => ({ put: 'legacy', key, value: { ...value, state: 'PENDING' } satisfies RecordEntry })));
    return list.length;
  }
}

/** Contract item 5: keep CONFLICT and REJECTED, and a REPLAY whose stored result was a rejection. */
export function keeps(o: SyncOutcome): boolean {
  if (o.status === 'CONFLICT' || o.status === 'REJECTED') return true;
  return o.status === 'REPLAY' && o.result?.status === 'REJECTED';
}

/** For tests and conformance checks: the canonical form the server hashes. */
export { canonicalPacket };
