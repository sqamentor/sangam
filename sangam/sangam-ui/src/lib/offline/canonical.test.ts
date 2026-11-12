// SPDX-License-Identifier: Apache-2.0
// M-001: the PWA hashes a CapturePacket exactly as the server's PacketHasher does. The two pinned hashes were checked
// on 2026-11-12 against sangam-field's own FieldJson.convert + PacketHasher.canonical/hash (JDK 25, Jackson 2.22.3):
// canonical strings equal, hashes equal, and PacketHasher.verifySignature accepted the PWA's DER signature.
import { describe, expect, it } from 'vitest';
import { canonicalJson, canonicalPacket, javaInstant, javaUtf8, normalise, packetHash, plainDecimal, type CapturePacket } from './canonical';
import { uuidV5 } from './crypto';

const subtle = globalThis.crypto.subtle;

const sample: CapturePacket = {
  packetId: 'op-1',
  type: 'VISIT_SUBMIT',
  operationId: 'op-1',
  deviceId: 'dev-1',
  deviceSeq: 1,
  appVersion: '0.1.0',
  assignmentId: 'a-1',
  baseVersion: 3,
  deviceTime: '2026-11-12T10:00:00Z',
  answers: { q1: true, q2: 2.5, q3: 'नमस्ते "x"\n', q4: null, q5: [1, null, 'b'] },
  fix: { lat: 26.9, lon: 75.8, accuracyM: 12 },
};

describe('M-001 packet hash = PacketHasher', () => {
  it('writes numbers as BigDecimal.stripTrailingZeros().toPlainString()', () => {
    expect(plainDecimal(0)).toBe('0');
    expect(plainDecimal(-0)).toBe('0');
    expect(plainDecimal(2)).toBe('2');
    expect(plainDecimal(2.5)).toBe('2.5');
    expect(plainDecimal(100)).toBe('100');
    expect(plainDecimal(-26.9)).toBe('-26.9');
    expect(plainDecimal(1e21)).toBe('1000000000000000000000');
    expect(plainDecimal(1.5e21)).toBe('1500000000000000000000');
    expect(plainDecimal(1e-7)).toBe('0.0000001');
    expect(plainDecimal(1.25e-7)).toBe('0.000000125');
    expect(plainDecimal(0.0001)).toBe('0.0001');
    expect(plainDecimal(0.1 + 0.2)).toBe('0.30000000000000004');
    expect(plainDecimal(123456.789)).toBe('123456.789');
    expect(() => plainDecimal(Number.NaN)).toThrow();
  });

  it('writes instants as ISO_INSTANT does: no fraction when the milliseconds are zero', () => {
    expect(javaInstant(new Date(Date.UTC(2026, 10, 12, 10, 0, 0, 0)))).toBe('2026-11-12T10:00:00Z');
    expect(javaInstant(new Date(Date.UTC(2026, 10, 12, 10, 0, 0, 120)))).toBe('2026-11-12T10:00:00.120Z');
    expect(javaInstant(new Date(Date.UTC(2026, 10, 12, 10, 0, 0, 5)))).toBe('2026-11-12T10:00:00.005Z');
  });

  it('drops nulls from objects but keeps them in arrays, and sorts keys by UTF-16 code unit', () => {
    expect(canonicalJson(normalise({ b: 1, a: null, A: [null, 2], é: true, Z: 'z' }) as never)).toBe('{"A":[null,"2"],"Z":"z","b":"1","é":true}');
  });

  it('escapes as CanonicalJson does (JCS short escapes, other controls as lower-case \\u00xx, the rest raw)', () => {
    expect(canonicalJson('a"b\\c\b\f\n\r\t\u0001\u001f /')).toBe('"a\\"b\\\\c\\b\\f\\n\\r\\t\\u0001\\u001f /"');
  });

  it('encodes a lone surrogate as Java getBytes(UTF_8) does (\'?\')', () => {
    expect([...javaUtf8('x\uD800y')]).toEqual([0x78, 0x3f, 0x79]);
    expect([...javaUtf8('😀')]).toEqual([0xf0, 0x9f, 0x98, 0x80]);
  });

  it('applies the record defaults: answers {}, media [], payload {}, mockLocation false', () => {
    const c = canonicalPacket({ type: 'VISIT_SUBMIT', operationId: 'o', deviceId: 'd', deviceSeq: 2, fix: { lat: 1, lon: 2 } });
    expect(c).toBe('{"answers":{},"deviceId":"d","deviceSeq":"2","fix":{"lat":"1","lon":"2","mockLocation":false},"media":[],"operationId":"o","payload":{},"type":"VISIT_SUBMIT"}');
  });

  it('never hashes packetHash or signature', () => {
    expect(canonicalPacket({ ...sample, packetHash: 'x', signature: 'y' })).toBe(canonicalPacket(sample));
  });

  it('matches the server for the pinned packets', async () => {
    expect(canonicalPacket(sample)).toBe(
      '{"answers":{"q1":true,"q2":"2.5","q3":"नमस्ते \\"x\\"\\n","q5":["1",null,"b"]},"appVersion":"0.1.0","assignmentId":"a-1","baseVersion":"3","deviceId":"dev-1",' +
        '"deviceSeq":"1","deviceTime":"2026-11-12T10:00:00Z","fix":{"accuracyM":"12","lat":"26.9","lon":"75.8","mockLocation":false},"media":[],"operationId":"op-1",' +
        '"packetId":"op-1","payload":{},"type":"VISIT_SUBMIT"}',
    );
    expect(await packetHash(subtle, sample)).toBe('3912dba8d24a0e4c484628fe4ef98e46cb9df8bf278ac9a7bf7be1071ec37e6d');

    const ns = '6ba7b810-9dad-11d1-80b4-00c04fd430c8';
    const op = await uuidV5(subtle, ns, '7');
    const edge: CapturePacket = {
      packetId: op,
      type: 'VISIT_SUBMIT',
      operationId: op,
      deviceId: ns,
      deviceSeq: 7,
      assignmentId: 'a-2',
      deviceTime: javaInstant(new Date(Date.UTC(2026, 10, 12, 10, 0, 0, 120))),
      answers: { big: 1e21, tiny: 1e-7, neg: -0, f: 0.1 + 0.2, int: 100, ctl: 'a\u0001b\tc\\d', sur: 'x\uD800y', list: ['A', 'B'], nested: { z: 1, a: { b: false } } },
      notes: 'line1\r\nline2',
      fix: { lat: 26, lon: -75.123456789, accuracyM: 3.5, altitudeM: 400.25, provider: 'gps', mockLocation: true, fixTime: javaInstant(new Date(Date.UTC(2026, 10, 12, 9, 59, 59, 0))) },
      payload: { k: 'v' },
      prevHash: 'ab'.repeat(32),
    };
    expect(await packetHash(subtle, edge)).toBe('7bc2e9d7d0bbeedb8a639c62caeadb29e6c8611720983433144598fa8c261800');
  });

  it('survives the JSON round trip the upload makes', async () => {
    const wire = JSON.parse(JSON.stringify(sample)) as CapturePacket;
    expect(await packetHash(subtle, wire)).toBe(await packetHash(subtle, sample));
  });
});
