// SPDX-License-Identifier: Apache-2.0
/**
 * The field queue as the rest of the app sees it: the transport over the API client, the browser's backend, and the two
 * calls the logout prompt needs (M-001, owner decision 2026-11-12: "N records not yet sent", Sync now / Log out
 * anyway; in both cases the records stay encrypted and bound to their user).
 */
import { api } from '../../api/endpoints';
import { FieldSync, type FieldTransport } from './fieldSync';
import { defaultBackend } from './storage';
import pkg from '../../../package.json';

/** FieldWorkController: device registration, upload, pull; WorkspaceController: task visit record. */
export const fieldTransport: FieldTransport = {
  registerDevice: (b) => api.field.registerDevice(b),
  upload: (deviceId, packets) => api.field.syncUpload(deviceId, packets),
  pull: (deviceId, cursor) => api.field.syncPull(deviceId, cursor),
  recordVisit: (visitId, body, key) => api.field.record(visitId, body, key),
};

export function localStore(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export interface FieldUser {
  tenantId: string;
  userId: string;
}

/** Opens the queue for a signed-in user in this browser. */
export function openFieldSync(u: FieldUser): Promise<FieldSync> {
  return FieldSync.open({ backend: defaultBackend(), crypto: window.crypto, transport: fieldTransport, tenantId: u.tenantId, userId: u.userId, appVersion: pkg.version, platform: 'web', legacyStorage: localStore() });
}

/** The signed-in user's records not yet accepted by the server (0 when the queue cannot be read). */
export async function unsentFieldRecords(u: FieldUser): Promise<number> {
  try {
    return await FieldSync.unsyncedCount(defaultBackend(), window.crypto, u.tenantId, u.userId);
  } catch {
    return 0;
  }
}

/** Sends what can be sent now (the same sync the field screen runs) and returns how many remain unsent. */
export async function syncFieldRecords(u: FieldUser): Promise<number> {
  const f = await openFieldSync(u);
  await f.sync();
  return unsentFieldRecords(u);
}
