// SPDX-License-Identifier: Apache-2.0
// M-029 (SL-44 UI): file → contract rows → chunks within the server limits. Written, not run in this phase.
import { describe, expect, it } from 'vitest';
import { canonicalJson, chunkRows, duplicateIds, parseCsv, parseRows, rowBytes } from './bulkRows';

describe('parseRows', () => {
  it('reads a JSON array of contract rows and flat objects', () => {
    const r = parseRows(JSON.stringify([{ externalId: 'a', data: { value: 1 } }, { externalId: 'b', value: 2, reason: 'x' }]), 'json');
    expect(r.errors).toEqual([]);
    expect(r.rows).toEqual([
      { externalId: 'a', data: { value: 1 } },
      { externalId: 'b', data: { value: 2, reason: 'x' } },
    ]);
  });
  it('accepts {rows: [...]} as written by an earlier export', () => {
    expect(parseRows('{"rows":[{"externalId":"a","data":{}}]}', 'json').rows).toHaveLength(1);
  });
  it('reports bad items with their position and keeps the good ones', () => {
    const r = parseRows(JSON.stringify([{ data: {} }, { externalId: ' ' }, { externalId: 'ok', data: [] }, { externalId: 'x\u0001' }, { externalId: 'fine' }]), 'json');
    expect(r.rows.map((x) => x.externalId)).toEqual(['fine']);
    expect(r.errors.map((e) => e.at)).toEqual([1, 2, 3, 4]);
  });
  it('reads NDJSON and skips blank lines', () => {
    const r = parseRows('{"externalId":"a","data":{"v":1}}\r\n\n{"externalId":"b"}\nnot json', 'ndjson');
    expect(r.rows.map((x) => x.externalId)).toEqual(['a', 'b']);
    expect(r.errors).toHaveLength(1);
    expect(r.errors[0].at).toBe(4);
  });
  it('reads CSV with an externalId column; other columns become data', () => {
    const r = parseRows('﻿externalId,value,reason\r\nv1,"10,5","said ""hi"""\nv2,राम,\n', 'csv');
    expect(r.errors).toEqual([]);
    expect(r.rows).toEqual([
      { externalId: 'v1', data: { value: '10,5', reason: 'said "hi"' } },
      { externalId: 'v2', data: { value: 'राम', reason: '' } },
    ]);
  });
  it('refuses CSV without an externalId column and short records', () => {
    expect(parseRows('id,value\n1,2', 'csv').errors[0].message).toMatch(/externalId/);
    expect(parseRows('externalId,value\n1', 'csv').errors[0].at).toBe(2);
  });
});

describe('parseCsv', () => {
  it('keeps line breaks inside quotes', () => {
    expect(parseCsv('a,b\n"x\ny",z')).toEqual([['a', 'b'], ['x\ny', 'z']]);
  });
});

describe('sizes and chunks', () => {
  it('canonicalises with sorted keys at every level', () => {
    expect(canonicalJson({ b: 1, a: { d: 2, c: 3 } })).toBe('{"a":{"c":3,"d":2},"b":1}');
  });
  it('counts UTF-8 bytes of canonical data plus the externalId', () => {
    // {"v":"राम"} = 8 ASCII bytes + 3 Devanagari letters × 3 bytes; externalId "id" = 2
    expect(rowBytes({ externalId: 'id', data: { v: 'राम' } })).toBe(8 + 9 + 2);
  });
  it('splits by rows and by bytes, in order', () => {
    const rows = Array.from({ length: 5 }, (_, i) => ({ externalId: `r${i}`, data: { v: 'x'.repeat(10) } }));
    expect(chunkRows(rows, 2).chunks.map((c) => c.map((r) => r.externalId))).toEqual([['r0', 'r1'], ['r2', 'r3'], ['r4']]);
    const one = rowBytes(rows[0]);
    expect(chunkRows(rows, 1000, one * 2).chunks.map((c) => c.length)).toEqual([2, 2, 1]);
  });
  it('sets apart a row that is bigger than the byte limit by itself', () => {
    const big = { externalId: 'big', data: { v: 'x'.repeat(100) } };
    const r = chunkRows([{ externalId: 'a', data: {} }, big], 1000, 50);
    expect(r.oversize).toEqual([big]);
    expect(r.chunks).toEqual([[{ externalId: 'a', data: {} }]]);
  });
  it('finds duplicate externalIds', () => {
    expect(duplicateIds([{ externalId: 'a', data: {} }, { externalId: 'b', data: {} }, { externalId: 'a', data: {} }])).toEqual(['a']);
  });
});
