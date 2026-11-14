// One-off cross-language evidence: hash + sign packets with the PWA code, for the JVM PacketHasher to check.
import { writeFileSync } from 'node:fs';
import { canonicalPacket, packetHash, javaInstant, type CapturePacket } from './canonical.ts';
import { newSigningKey, publicKeySpki, signHash, uuidV5 } from './crypto.ts';

const subtle = globalThis.crypto.subtle;
const kp = await newSigningKey(subtle);
const spki = await publicKeySpki(subtle, kp.publicKey);
const packets: CapturePacket[] = [
  {
    packetId: 'op-1', type: 'VISIT_SUBMIT', operationId: 'op-1', deviceId: 'dev-1', deviceSeq: 1, appVersion: '0.1.0', assignmentId: 'a-1', baseVersion: 3,
    deviceTime: '2026-11-12T10:00:00Z', answers: { q1: true, q2: 2.5, q3: 'नमस्ते "x"\n', q4: null, q5: [1, null, 'b'] }, fix: { lat: 26.9, lon: 75.8, accuracyM: 12 },
  },
  {
    packetId: await uuidV5(subtle, '6ba7b810-9dad-11d1-80b4-00c04fd430c8', '7'), type: 'VISIT_SUBMIT', operationId: await uuidV5(subtle, '6ba7b810-9dad-11d1-80b4-00c04fd430c8', '7'),
    deviceId: '6ba7b810-9dad-11d1-80b4-00c04fd430c8', deviceSeq: 7, assignmentId: 'a-2', deviceTime: javaInstant(new Date(Date.UTC(2026, 10, 12, 10, 0, 0, 120))),
    answers: { big: 1e21, tiny: 1e-7, neg: -0, f: 0.1 + 0.2, int: 100, ctl: 'a\u0001b\tc\\d', sur: 'x\uD800y', list: ['A', 'B'], nested: { z: 1, a: { b: false } } },
    notes: 'line1\r\nline2', fix: { lat: 26, lon: -75.123456789, accuracyM: 3.5, altitudeM: 400.25, provider: 'gps', mockLocation: true, fixTime: javaInstant(new Date(Date.UTC(2026, 10, 12, 9, 59, 59, 0))) },
    payload: { k: 'v' }, prevHash: 'ab'.repeat(32),
  },
];
const out = [];
for (const p of packets) {
  const h = await packetHash(subtle, p);
  const sig = await signHash(subtle, kp.privateKey, h);
  out.push({ json: JSON.stringify({ ...p, packetHash: h, signature: sig }), canonical: canonicalPacket(p), hash: h });
}
writeFileSync(process.argv[2], JSON.stringify({ spki, packets: out }));
console.log(out.map((o) => o.hash).join('\n'));
console.log(await uuidV5(subtle, '6ba7b810-9dad-11d1-80b4-00c04fd430c8', 'python.org'));
