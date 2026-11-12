// SPDX-License-Identifier: Apache-2.0
/**
 * Bulk ingest rows (M-029, SL-44): parse a file into contract rows and split them into chunks the server accepts.
 *
 * Contract: PUT /bulk/jobs/{id}/chunks/{seq} takes `BulkChunk {rows: [BulkRowInput {externalId, data}]}` as JSON
 * (sangam-api.yaml bulkUploadChunk, BulkChunk, BulkRowInput). There is no client hash: the server hashes each chunk
 * itself (SHA-256 over externalId \0 canonical(data) \1 per row, BulkIngest.java:273-278), so re-sending the same chunk
 * under the same seq is a replay and different content under a used seq is 409 CHUNK_CONFLICT (BulkIngest.java:629-639).
 *
 * Limits (BulkIngest.Limits.DEFAULTS, BulkIngest.java:142; overridable per server, OpsWiring.java:162-168): at most
 * 1000 rows per chunk and 4 MiB of row data, counted as the UTF-8 bytes of the canonical (key-sorted) JSON of `data`
 * plus the externalId (BulkIngest.java:262-271). The client sizes chunks with the same measure; the server stays the
 * judge (413 BULK_LIMIT), and the operator can lower both numbers.
 */

export interface BulkRow {
  externalId: string;
  data: Record<string, unknown>;
}

export interface ParseResult {
  rows: BulkRow[];
  /** 1-based line (CSV / NDJSON) or item (JSON array) number with the reason it was skipped. */
  errors: { at: number; message: string }[];
}

export type BulkFormat = 'json' | 'ndjson' | 'csv';

export const DEFAULT_MAX_ROWS = 1000;
export const DEFAULT_MAX_BYTES = 4 * 1024 * 1024;
/** externalId limit in UTF-16 units, as Java String.length() counts (BulkIngest.java:172, :665). */
export const MAX_EXTERNAL_ID = 255;

const isObject = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);

/** One parsed item → a row: `{externalId, data}`, or a flat object whose other keys are the data. */
function toRow(v: unknown): BulkRow | string {
  if (!isObject(v)) return 'not an object';
  const id = v.externalId;
  if (typeof id !== 'string' && typeof id !== 'number') return 'externalId is missing';
  const externalId = String(id);
  if (!externalId.trim()) return 'externalId is empty';
  if (externalId.length > MAX_EXTERNAL_ID) return `externalId is longer than ${MAX_EXTERNAL_ID}`;
  // Character.isISOControl, as the server checks (BulkIngest.java:668-671)
  if (/[\u0000-\u001f\u007f-\u009f]/.test(externalId)) return 'externalId contains control characters';
  if ('data' in v) {
    if (!isObject(v.data)) return 'data is not an object';
    return { externalId, data: v.data };
  }
  const { externalId: _drop, ...data } = v;
  void _drop;
  return { externalId, data };
}

/** RFC 4180 CSV: quoted fields, doubled quotes, commas and line breaks inside quotes; CRLF or LF records. */
export function parseCsv(text: string): string[][] {
  const out: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;
  let i = 0;
  const s = text.replace(/^﻿/, '');
  while (i < s.length) {
    const ch = s[i];
    if (quoted) {
      if (ch === '"' && s[i + 1] === '"') {
        field += '"';
        i += 2;
        continue;
      }
      if (ch === '"') {
        quoted = false;
        i++;
        continue;
      }
      field += ch;
      i++;
      continue;
    }
    if (ch === '"' && field === '') {
      quoted = true;
      i++;
    } else if (ch === ',') {
      row.push(field);
      field = '';
      i++;
    } else if (ch === '\r' || ch === '\n') {
      row.push(field);
      out.push(row);
      row = [];
      field = '';
      i += ch === '\r' && s[i + 1] === '\n' ? 2 : 1;
    } else {
      field += ch;
      i++;
    }
  }
  if (field !== '' || row.length) {
    row.push(field);
    out.push(row);
  }
  return out.filter((r) => !(r.length === 1 && r[0] === ''));
}

export function parseRows(text: string, format: BulkFormat): ParseResult {
  const rows: BulkRow[] = [];
  const errors: ParseResult['errors'] = [];
  const add = (v: unknown, at: number) => {
    const r = toRow(v);
    if (typeof r === 'string') errors.push({ at, message: r });
    else rows.push(r);
  };
  const body = text.replace(/^﻿/, '');
  if (format === 'json') {
    let v: unknown;
    try {
      v = JSON.parse(body);
    } catch (e) {
      return { rows, errors: [{ at: 0, message: (e as Error).message }] };
    }
    const items = Array.isArray(v) ? v : isObject(v) && Array.isArray(v.rows) ? v.rows : null;
    if (!items) return { rows, errors: [{ at: 0, message: 'expected an array of rows' }] };
    items.forEach((x, i) => add(x, i + 1));
  } else if (format === 'ndjson') {
    body.split(/\r?\n/).forEach((line, i) => {
      if (!line.trim()) return;
      try {
        add(JSON.parse(line), i + 1);
      } catch (e) {
        errors.push({ at: i + 1, message: (e as Error).message });
      }
    });
  } else {
    const table = parseCsv(body);
    if (!table.length) return { rows, errors };
    const header = table[0].map((h) => h.trim());
    const idCol = header.indexOf('externalId');
    if (idCol < 0) return { rows, errors: [{ at: 1, message: 'the header has no externalId column' }] };
    table.slice(1).forEach((cells, i) => {
      if (cells.length !== header.length) {
        errors.push({ at: i + 2, message: `expected ${header.length} fields, found ${cells.length}` });
        return;
      }
      const o: Record<string, unknown> = {};
      header.forEach((h, j) => {
        if (h) o[h] = cells[j];
      });
      add(o, i + 2);
    });
  }
  return { rows, errors };
}

/** JSON with object keys sorted at every level, as the server canonicalises row data (ORDER_MAP_ENTRIES_BY_KEYS). */
export function canonicalJson(v: unknown): string {
  return JSON.stringify(v, (_k, x: unknown) =>
    isObject(x) ? Object.fromEntries(Object.keys(x).sort().map((k) => [k, x[k]])) : x,
  );
}

const utf8 = new TextEncoder();

/** The bytes the server counts for a row: UTF-8 of the canonical data plus the externalId. */
export function rowBytes(r: BulkRow): number {
  return utf8.encode(canonicalJson(r.data)).length + utf8.encode(r.externalId).length;
}

export interface Chunking {
  chunks: BulkRow[][];
  /** Rows bigger than the byte limit on their own; the server would refuse their chunk (413 BULK_LIMIT). */
  oversize: BulkRow[];
}

/** Greedy split keeping each chunk within both limits and the rows in file order. */
export function chunkRows(rows: BulkRow[], maxRows = DEFAULT_MAX_ROWS, maxBytes = DEFAULT_MAX_BYTES): Chunking {
  const chunks: BulkRow[][] = [];
  const oversize: BulkRow[] = [];
  let cur: BulkRow[] = [];
  let bytes = 0;
  for (const r of rows) {
    const b = rowBytes(r);
    if (b > maxBytes) {
      oversize.push(r);
      continue;
    }
    if (cur.length && (cur.length >= maxRows || bytes + b > maxBytes)) {
      chunks.push(cur);
      cur = [];
      bytes = 0;
    }
    cur.push(r);
    bytes += b;
  }
  if (cur.length) chunks.push(cur);
  return { chunks, oversize };
}

/** Duplicate externalIds in one upload: each would be a separate row on the server, usually a mistake. */
export function duplicateIds(rows: BulkRow[]): string[] {
  const seen = new Set<string>();
  const dup = new Set<string>();
  for (const r of rows) (seen.has(r.externalId) ? dup : seen).add(r.externalId);
  return [...dup];
}
