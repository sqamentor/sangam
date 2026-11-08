// SPDX-License-Identifier: Apache-2.0
// R33 typed JSON values (sangam-api.yaml TypedValue). Money is never a binary float: Decimal, BigInteger, Long,
// Date, LocalDate and Bytes (base64) travel as strings.

export type TypedValueType =
  | 'Decimal'
  | 'BigInteger'
  | 'Long'
  | 'Integer'
  | 'Boolean'
  | 'String'
  | 'Date'
  | 'LocalDate'
  | 'Json'
  | 'Bytes';

export interface TypedValue {
  type: TypedValueType;
  value: unknown;
}

const TYPES: ReadonlySet<string> = new Set<TypedValueType>([
  'Decimal', 'BigInteger', 'Long', 'Integer', 'Boolean', 'String', 'Date', 'LocalDate', 'Json', 'Bytes',
]);

export function isTypedValue(v: unknown): v is TypedValue {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return false;
  const o = v as Record<string, unknown>;
  const keys = Object.keys(o);
  return keys.length === 2 && 'value' in o && typeof o.type === 'string' && TYPES.has(o.type);
}

function localDate(d: Date | string): string {
  if (typeof d === 'string') {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) throw new RangeError(`not a LocalDate (YYYY-MM-DD): ${d}`);
    return d;
  }
  return d.toISOString().substring(0, 10);
}

/** Builders for typed variables. */
export const TypedValues = {
  /** Exact decimal; pass a string (`'1234.50'`) for money. A number is converted with `String()`. */
  decimal(v: string | number | bigint): TypedValue {
    const s = String(v);
    if (!/^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$/.test(s)) throw new RangeError(`not a decimal: ${s}`);
    return { type: 'Decimal', value: s };
  },
  bigInteger(v: bigint | string): TypedValue {
    return { type: 'BigInteger', value: BigInt(v).toString() };
  },
  long(v: bigint | number | string): TypedValue {
    const b = BigInt(v);
    if (b > 9223372036854775807n || b < -9223372036854775808n) throw new RangeError('out of Long range');
    return { type: 'Long', value: b.toString() };
  },
  integer(v: number): TypedValue {
    if (!Number.isInteger(v) || v > 2147483647 || v < -2147483648) throw new RangeError('not a 32-bit Integer');
    return { type: 'Integer', value: v };
  },
  boolean(v: boolean): TypedValue {
    return { type: 'Boolean', value: v };
  },
  string(v: string): TypedValue {
    return { type: 'String', value: v };
  },
  /** Zoned instant, sent as ISO-8601 UTC. */
  date(v: Date | string): TypedValue {
    return { type: 'Date', value: typeof v === 'string' ? v : v.toISOString() };
  },
  /** Unzoned calendar date `YYYY-MM-DD`. */
  localDate(v: Date | string): TypedValue {
    return { type: 'LocalDate', value: localDate(v) };
  },
  json(v: unknown): TypedValue {
    return { type: 'Json', value: v };
  },
  bytes(v: Uint8Array): TypedValue {
    return { type: 'Bytes', value: Buffer.from(v).toString('base64') };
  },
} as const;

/**
 * Unwraps a TypedValue into a JavaScript value; plain JSON passes through unchanged.
 *
 * | type | JS value |
 * |---|---|
 * | Decimal | `string` (exact; JS has no decimal type) |
 * | BigInteger, Long | `bigint` |
 * | Integer | `number` |
 * | Boolean / String / Json | as is |
 * | Date | `Date` |
 * | LocalDate | `string` `YYYY-MM-DD` |
 * | Bytes | `Uint8Array` |
 */
export function fromTypedValue(v: unknown): unknown {
  if (!isTypedValue(v)) return v;
  const val = v.value;
  switch (v.type) {
    case 'Decimal':
      return val === null || val === undefined ? val : String(val);
    case 'BigInteger':
    case 'Long':
      return val === null || val === undefined ? val : BigInt(val as string | number);
    case 'Integer':
      return val === null || val === undefined ? val : Number(val);
    case 'Date':
      return val === null || val === undefined ? val : new Date(String(val));
    case 'LocalDate':
      return val === null || val === undefined ? val : String(val);
    case 'Bytes':
      return val === null || val === undefined ? val : new Uint8Array(Buffer.from(String(val), 'base64'));
    default:
      return val;
  }
}

/** Unwraps every value of a VariableMap. */
export function unwrapVariables(vars: Record<string, unknown> | undefined): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(vars ?? {})) out[k] = fromTypedValue(v);
  return out;
}

/** JSON replacer: bigint → string (so plain bigint variables never crash `JSON.stringify`). */
export function jsonReplacer(_key: string, value: unknown): unknown {
  return typeof value === 'bigint' ? value.toString() : value;
}
