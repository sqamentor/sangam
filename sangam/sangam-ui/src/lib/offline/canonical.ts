// SPDX-License-Identifier: Apache-2.0
/**
 * N05 CapturePacket hashing, byte-for-byte the server's PacketHasher (sangam-field evidence/PacketHasher.java:26-72)
 * so a packet the PWA hashes is accepted (SyncService.java:111,133: a different hash is REJECTED HASH_MISMATCH).
 *
 * Server side, for the record:
 * - the hashed fields are CapturePacket.hashedFields() (CapturePacket.java:145-163), after the record constructor's
 *   defaults: answers {}, media [], payload {} (:138-142); GpsFix.mockLocation is a primitive, so it is always present;
 * - FieldJson.MAPPER writes instants as ISO-8601 (FieldJson.java:16-19: JavaTimeModule, WRITE_DATES_AS_TIMESTAMPS off),
 *   i.e. DateTimeFormatter.ISO_INSTANT: the fraction is printed in groups of three digits, and not at all when zero;
 * - normalise: nulls dropped from objects (kept in arrays), every number becomes
 *   BigDecimal(n.toString()).stripTrailingZeros().toPlainString(), zero as "0" (PacketHasher.java:45-72);
 * - CanonicalJson.write: keys sorted by UTF-16 code unit, no whitespace, JCS-style escaping of '"', '\\', \b \f \n \r
 *   \t, other controls as \\u00xx (lower-case hex), everything else raw (core layer/CanonicalJson.java:72-94);
 * - SHA-256 of String.getBytes(UTF_8) (Hashes.java:25-26), which encodes a lone surrogate as '?'.
 */

export interface GpsFix {
  lat: number;
  lon: number;
  accuracyM?: number;
  altitudeM?: number;
  provider?: string;
  mockLocation?: boolean;
  /** ISO-8601 instant, as {@link javaInstant} writes it. */
  fixTime?: string;
}

export interface MediaRef {
  evidenceId?: string;
  itemId?: string;
  sha256?: string;
  mediaType?: string;
  sizeBytes?: number;
  capturedAt?: string;
  fix?: GpsFix;
}

/** The wire shape of CapturePacket (CapturePacket.java:124-127). Instants are strings in {@link javaInstant} form. */
export interface CapturePacket {
  packetId?: string;
  type: 'VISIT_SUBMIT' | 'EVIDENCE_META' | 'DISPATCH_STATUS' | 'POSITION_PING' | 'ASSIGNMENT_ACK';
  operationId: string;
  deviceId: string;
  deviceSeq: number;
  appVersion?: string;
  assignmentId?: string;
  baseVersion?: number;
  deviceTime?: string;
  answers?: Record<string, unknown>;
  notes?: string;
  media?: MediaRef[];
  fix?: GpsFix;
  payload?: Record<string, unknown>;
  prevHash?: string;
  packetHash?: string;
  signature?: string;
}

type Json = string | boolean | null | Json[] | { [k: string]: Json };

/** An instant as Java's Instant.toString / ISO_INSTANT prints it at millisecond precision (".000" is omitted). */
export function javaInstant(d: Date): string {
  const iso = d.toISOString();
  return iso.endsWith('.000Z') ? `${iso.slice(0, -5)}Z` : iso;
}

/**
 * A finite number as BigDecimal(Double.toString(n)).stripTrailingZeros().toPlainString(). JavaScript and Java both
 * print the shortest round-trip digits, so only the notation differs: expand any exponent and strip trailing zeros.
 */
export function plainDecimal(n: number): string {
  if (!Number.isFinite(n)) throw new Error(`not a finite number: ${n}`);
  if (n === 0) return '0';
  const neg = n < 0;
  const [mant, expPart] = String(Math.abs(n)).toLowerCase().split('e');
  const exp = expPart === undefined ? 0 : Number(expPart);
  const dot = mant.indexOf('.');
  const all = dot < 0 ? mant : mant.slice(0, dot) + mant.slice(dot + 1);
  // point = how many of the digits stand before the decimal point (may be <= 0 or beyond the last digit)
  const lead = all.length - all.replace(/^0+/, '').length;
  const point = (dot < 0 ? mant.length : dot) + exp - lead;
  // stripTrailingZeros: zeros at the end of the significand go; toPlainString pads the integer part back below
  const digits = all.slice(lead).replace(/0+$/, '');
  let out: string;
  if (point <= 0) out = `0.${'0'.repeat(-point)}${digits}`;
  else if (point >= digits.length) out = digits + '0'.repeat(point - digits.length);
  else out = `${digits.slice(0, point)}.${digits.slice(point)}`;
  return neg ? `-${out}` : out;
}

/** PacketHasher.normalise: numbers to plain decimal strings, nulls dropped from objects. */
export function normalise(v: unknown): Json | undefined {
  if (v === null || v === undefined) return null;
  if (typeof v === 'number') return plainDecimal(v);
  if (typeof v === 'string' || typeof v === 'boolean') return v;
  if (v instanceof Date) return javaInstant(v);
  if (Array.isArray(v)) return v.map((x) => normalise(x) ?? null);
  if (typeof v === 'object') {
    const out: { [k: string]: Json } = {};
    for (const [k, x] of Object.entries(v as Record<string, unknown>)) {
      const n = normalise(x);
      if (n !== null && n !== undefined) out[k] = n;
    }
    return out;
  }
  throw new Error(`cannot hash a ${typeof v}`);
}

function str(s: string): string {
  let out = '"';
  for (let i = 0; i < s.length; i++) {
    const ch = s.charCodeAt(i);
    if (ch === 0x22) out += '\\"';
    else if (ch === 0x5c) out += '\\\\';
    else if (ch === 0x08) out += '\\b';
    else if (ch === 0x0c) out += '\\f';
    else if (ch === 0x0a) out += '\\n';
    else if (ch === 0x0d) out += '\\r';
    else if (ch === 0x09) out += '\\t';
    else if (ch < 0x20) out += `\\u${ch.toString(16).padStart(4, '0')}`;
    else out += s[i];
  }
  return `${out}"`;
}

/** CanonicalJson.write over normalised values. */
export function canonicalJson(v: Json): string {
  if (v === null) return 'null';
  if (typeof v === 'string') return str(v);
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (Array.isArray(v)) return `[${v.map(canonicalJson).join(',')}]`;
  const keys = Object.keys(v).sort(); // default sort compares UTF-16 code units, as Java's TreeMap<String> does
  return `{${keys.map((k) => `${str(k)}:${canonicalJson(v[k])}`).join(',')}}`;
}

function fixFields(f: GpsFix): Record<string, unknown> {
  return { lat: f.lat, lon: f.lon, accuracyM: f.accuracyM, altitudeM: f.altitudeM, provider: f.provider, mockLocation: f.mockLocation ?? false, fixTime: f.fixTime };
}

/** CapturePacket.hashedFields() with the record's defaults applied. */
export function hashedFields(p: CapturePacket): Record<string, unknown> {
  return {
    packetId: p.packetId,
    type: p.type,
    operationId: p.operationId,
    deviceId: p.deviceId,
    deviceSeq: p.deviceSeq,
    appVersion: p.appVersion,
    assignmentId: p.assignmentId,
    baseVersion: p.baseVersion,
    deviceTime: p.deviceTime,
    answers: p.answers ?? {},
    notes: p.notes,
    media: (p.media ?? []).map((m) => ({
      evidenceId: m.evidenceId,
      itemId: m.itemId,
      sha256: m.sha256,
      mediaType: m.mediaType,
      sizeBytes: m.sizeBytes,
      capturedAt: m.capturedAt,
      fix: m.fix ? fixFields(m.fix) : undefined,
    })),
    fix: p.fix ? fixFields(p.fix) : undefined,
    payload: p.payload ?? {},
    prevHash: p.prevHash,
  };
}

/** PacketHasher.canonical. */
export function canonicalPacket(p: CapturePacket): string {
  return canonicalJson(normalise(hashedFields(p)) as Json);
}

/** Java's String.getBytes(UTF_8): a lone surrogate becomes '?' (TextEncoder would write U+FFFD). */
export function javaUtf8(s: string): Uint8Array<ArrayBuffer> {
  return new TextEncoder().encode(s.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, '?'));
}

export function hex(b: ArrayBuffer | Uint8Array): string {
  return [...(b instanceof Uint8Array ? b : new Uint8Array(b))].map((x) => x.toString(16).padStart(2, '0')).join('');
}

/** PacketHasher.hash: lower-case hex SHA-256 of the canonical form. */
export async function packetHash(subtle: SubtleCrypto, p: CapturePacket): Promise<string> {
  return hex(await subtle.digest('SHA-256', javaUtf8(canonicalPacket(p))));
}
