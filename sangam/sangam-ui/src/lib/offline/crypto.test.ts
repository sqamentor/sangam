// SPDX-License-Identifier: Apache-2.0
// M-001: LocalQueueContract envelope, AES-256-GCM with the user-bound AAD, UUIDv5 and the DER device signature.
import { describe, expect, it } from 'vitest';
import { aad, newQueueKey, newSigningKey, open, p1363ToDer, publicKeySpki, seal, signHash, unb64, uuidV5, validate, type Binding } from './crypto';

const c = globalThis.crypto;
const who: Binding = { tenantId: 't1', deviceId: 'd1', userId: 'u1' };
const now = new Date(Date.UTC(2026, 10, 12, 10, 0, 0));

/** DER SEQUENCE { INTEGER r, INTEGER s } back to r||s, to verify with WebCrypto. */
function derToP1363(der: Uint8Array, n = 32): Uint8Array {
  expect(der[0]).toBe(0x30);
  expect(der[1]).toBe(der.length - 2);
  const out = new Uint8Array(2 * n);
  let i = 2;
  for (let k = 0; k < 2; k++) {
    expect(der[i]).toBe(0x02);
    const len = der[i + 1];
    const v = der.slice(i + 2, i + 2 + len);
    expect(v[0] === 0 ? (v[1] & 0x80) !== 0 : (v[0] & 0x80) === 0).toBe(true); // minimal, non-negative
    const body = v[0] === 0 ? v.slice(1) : v;
    out.set(body, (k + 1) * n - body.length);
    i += 2 + len;
  }
  return out;
}

describe('M-001 queue crypto', () => {
  it('binds tenant, device, operation and user into the AAD (contract AAD + "|" + userId)', () => {
    expect(aad(who, 'op-1')).toBe('t1|d1|op-1|u1');
  });

  it('seals the v1 envelope with a 96-bit IV and opens it only under the same binding', async () => {
    const key = await newQueueKey(c.subtle);
    expect(key.extractable).toBe(false);
    expect(key.algorithm).toMatchObject({ name: 'AES-GCM', length: 256 });
    const env = await seal(c, key, 'qk-1', who, 'op-1', '{"secret":"नोट"}', now);
    expect(Object.keys(env).sort()).toEqual(['createdAt', 'ct', 'iv', 'kid', 'op', 'v']);
    expect(env).toMatchObject({ v: 1, kid: 'qk-1', op: 'op-1', createdAt: '2026-11-12T10:00:00.000Z' });
    expect(unb64(env.iv).length).toBe(12);
    expect(unb64(env.ct).length).toBe(new TextEncoder().encode('{"secret":"नोट"}').length + 16); // 128-bit tag
    expect(JSON.stringify(env)).not.toContain('secret');
    expect(await open(c.subtle, key, who, env)).toBe('{"secret":"नोट"}');
    expect(await open(c.subtle, key, { ...who, userId: 'u2' }, env)).toBeNull();
    expect(await open(c.subtle, key, { ...who, tenantId: 't2' }, env)).toBeNull();
    expect(await open(c.subtle, key, { ...who, deviceId: 'd2' }, env)).toBeNull();
    expect(await open(c.subtle, key, who, { ...env, op: 'op-2' })).toBeNull();
    const ct = unb64(env.ct);
    ct[0] ^= 1;
    expect(await open(c.subtle, key, who, { ...env, ct: btoa(String.fromCharCode(...ct)) })).toBeNull();
    expect(await open(c.subtle, await newQueueKey(c.subtle), who, env)).toBeNull();
  });

  it('opens the independent AES-GCM vector the server pins (LocalQueueCipherTest VECTOR_IV / VECTOR_CT)', async () => {
    // Key 00..1f, IV 00..0b, AAD "t1|d1|op-1|u1", plaintext {"secret":"नोट"} (sangam-field LocalQueueCipherTest.java:115-117).
    // A change on either side (AAD order, IV size, tag length, encoding) fails this test or the Java one.
    const raw = new Uint8Array(32).map((_, i) => i);
    const key = await c.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['decrypt']);
    const env = { v: 1, kid: 'qk-1', op: 'op-1', iv: 'AAECAwQFBgcICQoL', ct: 'PCClfqaXp2+ve7VrFUGYyAg2I6vSBu3HjGhP5Qq6HvYjKPLVXM0=', createdAt: '2026-11-12T10:00:00.000Z' };
    expect(await open(c.subtle, key, who, env)).toBe('{"secret":"नोट"}');
    expect(await open(c.subtle, key, { ...who, userId: 'u2' }, env)).toBeNull();
  });

  it('validates the envelope as LocalQueueContract.Envelope.validate does', () => {
    expect(() => validate({ v: 2, kid: 'k', op: 'o', iv: btoa('x'.repeat(12)), ct: '', createdAt: '' })).toThrow(/version/);
    expect(() => validate({ v: 1, kid: 'k', op: '', iv: btoa('x'.repeat(12)), ct: '', createdAt: '' })).toThrow(/op/);
    expect(() => validate({ v: 1, kid: 'k', op: 'o', iv: btoa('x'.repeat(8)), ct: '', createdAt: '' })).toThrow(/12 bytes/);
  });

  it('computes RFC 4122 UUIDv5 (known vector)', async () => {
    expect(await uuidV5(c.subtle, '6ba7b810-9dad-11d1-80b4-00c04fd430c8', 'python.org')).toBe('886313e1-3b8a-5372-9b90-0c9aee199e5d');
  });

  it('signs the hash hex as DER SHA256withECDSA, verifiable with the registered SPKI key', async () => {
    const kp = await newSigningKey(c.subtle);
    expect(kp.privateKey.extractable).toBe(false);
    const spki = await publicKeySpki(c.subtle, kp.publicKey);
    const pub = await c.subtle.importKey('spki', unb64(spki), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    for (let i = 0; i < 8; i++) {
      const hash = (i.toString(16) + 'f').repeat(32).slice(0, 64);
      const der = unb64(await signHash(c.subtle, kp.privateKey, hash));
      expect(await c.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, pub, derToP1363(der), new TextEncoder().encode(hash))).toBe(true);
    }
  });

  it('DER-encodes high-bit and leading-zero integers minimally', () => {
    const r = new Uint8Array(32).fill(0x80);
    const s = new Uint8Array(32);
    s[31] = 1;
    const der = p1363ToDer(new Uint8Array([...r, ...s]));
    expect([...der.slice(0, 4)]).toEqual([0x30, 2 + 33 + 2 + 1, 0x02, 33]);
    expect(der[4]).toBe(0);
    expect([...der.slice(-3)]).toEqual([0x02, 1, 1]);
  });
});
