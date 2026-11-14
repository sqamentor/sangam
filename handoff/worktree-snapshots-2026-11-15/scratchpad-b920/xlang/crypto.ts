// SPDX-License-Identifier: Apache-2.0
/**
 * LocalQueueContract (sangam-field sync/LocalQueueContract.java:8-31) for the PWA: AES-256-GCM entries under a
 * non-extractable WebCrypto key, 96-bit random IV, 128-bit tag, the v1 envelope, operationId as UUIDv5.
 *
 * AAD (owner decision 2026-11-12, Q-01 a): the contract's tenantId|deviceId|operationId, followed by "|" + userId, so
 * an entry opens only for the user who captured it; under any other login the GCM tag fails and the entry stays
 * hidden and unreplayed. (The contract's media rule appends "|" + evidenceId in the same way.)
 */
import { hex } from './canonical.ts';

export const VERSION = 1;
export const KEY_BITS = 256;
export const IV_BYTES = 12;
export const TAG_BITS = 128;

/** LocalQueueContract.Envelope (:44): {"v":1,"kid":queueKeyId,"op":operationId,"iv":b64,"ct":b64,"createdAt":iso}. */
export interface Envelope {
  v: number;
  kid: string;
  op: string;
  iv: string;
  ct: string;
  createdAt: string;
}

export interface Binding {
  tenantId: string;
  deviceId: string;
  userId: string;
}

/** LocalQueueContract.aad (:58-60) plus the owner's user binding. */
export function aad(b: Binding, operationId: string): string {
  return `${b.tenantId}|${b.deviceId}|${operationId}|${b.userId}`;
}

export function b64(bytes: ArrayBuffer | Uint8Array): string {
  const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = '';
  for (const x of u) s += String.fromCharCode(x);
  return btoa(s);
}

export function unb64(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** The device queue key: AES-256-GCM, generated on the device, never exportable (contract item 1). */
export function newQueueKey(subtle: SubtleCrypto): Promise<CryptoKey> {
  return subtle.generateKey({ name: 'AES-GCM', length: KEY_BITS }, false, ['encrypt', 'decrypt']);
}

/** The device signing key: ECDSA P-256, private half non-exportable; the public half is registered (SPKI). */
export function newSigningKey(subtle: SubtleCrypto): Promise<CryptoKeyPair> {
  return subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
}

export async function seal(c: Crypto, key: CryptoKey, kid: string, b: Binding, operationId: string, plaintext: string, now: Date): Promise<Envelope> {
  const iv = c.getRandomValues(new Uint8Array(IV_BYTES));
  const ct = await c.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(aad(b, operationId)), tagLength: TAG_BITS }, key, new TextEncoder().encode(plaintext));
  return { v: VERSION, kid, op: operationId, iv: b64(iv), ct: b64(ct), createdAt: now.toISOString() };
}

/** LocalQueueContract.Envelope.validate (:45-55). */
export function validate(env: Envelope): void {
  if (env.v !== VERSION) throw new Error(`unsupported queue envelope version ${env.v}`);
  if (!env.op || env.iv == null || env.ct == null) throw new Error('envelope needs op, iv and ct');
  if (unb64(env.iv).length !== IV_BYTES) throw new Error(`IV must be ${IV_BYTES} bytes`);
}

/** Opens an entry; null when it was sealed for another tenant, device, operation or user, or was altered. */
export async function open(subtle: SubtleCrypto, key: CryptoKey, b: Binding, env: Envelope): Promise<string | null> {
  validate(env);
  try {
    const pt = await subtle.decrypt({ name: 'AES-GCM', iv: unb64(env.iv), additionalData: new TextEncoder().encode(aad(b, env.op)), tagLength: TAG_BITS }, key, unb64(env.ct));
    return new TextDecoder().decode(pt);
  } catch {
    return null;
  }
}

function uuidBytes(u: string): Uint8Array {
  const h = u.replace(/-/g, '');
  if (!/^[0-9a-fA-F]{32}$/.test(h)) throw new Error(`not a UUID: ${u}`);
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i++) out[i] = parseInt(h.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/** RFC 4122 §4.3 UUIDv5, as core Hashes.uuidV5 (Hashes.java:34-45): SHA-1(namespace bytes + UTF-8 name). */
export async function uuidV5(subtle: SubtleCrypto, namespace: string, name: string): Promise<string> {
  const ns = uuidBytes(namespace);
  const nm = new TextEncoder().encode(name);
  const buf = new Uint8Array(ns.length + nm.length);
  buf.set(ns);
  buf.set(nm, ns.length);
  const h = new Uint8Array(await subtle.digest('SHA-1', buf)).slice(0, 16);
  h[6] = (h[6] & 0x0f) | 0x50;
  h[8] = (h[8] & 0x3f) | 0x80;
  const x = hex(h);
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`;
}

/** WebCrypto ECDSA signatures are r||s (IEEE P1363); Java's SHA256withECDSA verifies ASN.1 DER. */
export function p1363ToDer(sig: Uint8Array): Uint8Array {
  const n = sig.length / 2;
  const int = (x: Uint8Array): number[] => {
    let i = 0;
    while (i < x.length - 1 && x[i] === 0) i++;
    const v = [...x.slice(i)];
    return v[0] & 0x80 ? [0, ...v] : v;
  };
  const r = int(sig.slice(0, n));
  const s = int(sig.slice(n));
  const body = [0x02, r.length, ...r, 0x02, s.length, ...s];
  return new Uint8Array([0x30, body.length, ...body]);
}

/**
 * The packet signature PacketHasher.verifySignature checks (PacketHasher.java:74-92): SHA256withECDSA over the UTF-8
 * bytes of the packet hash hex, base64 DER.
 */
export async function signHash(subtle: SubtleCrypto, privateKey: CryptoKey, hashHex: string): Promise<string> {
  const raw = await subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, privateKey, new TextEncoder().encode(hashHex));
  return b64(p1363ToDer(new Uint8Array(raw)));
}

/** Base64 X.509 SubjectPublicKeyInfo, the form DeviceRegistry stores and X509EncodedKeySpec reads. */
export async function publicKeySpki(subtle: SubtleCrypto, publicKey: CryptoKey): Promise<string> {
  return b64(await subtle.exportKey('spki', publicKey));
}
