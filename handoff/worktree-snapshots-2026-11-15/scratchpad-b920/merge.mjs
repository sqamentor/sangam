// Merge scratchpad keys-*.json into en.json (new keys only; refuses to change an existing value).
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const enPath = process.argv[2];
const raw = readFileSync(enPath, 'utf8');
const crlf = raw.includes('\r\n');
const en = JSON.parse(raw);
let added = 0;
function merge(dst, src, p) {
  for (const [k, v] of Object.entries(src)) {
    const key = p ? `${p}.${k}` : k;
    if (v && typeof v === 'object') {
      if (dst[k] === undefined) dst[k] = {};
      else if (typeof dst[k] !== 'object') throw new Error(`${key}: string exists, object given`);
      merge(dst[k], v, key);
    } else {
      if (dst[k] === undefined) { dst[k] = v; added++; }
      else if (dst[k] !== v) throw new Error(`${key}: exists with a different value: ${JSON.stringify(dst[k])}`);
    }
  }
}
for (const f of readdirSync(here).filter((f) => /^keys-.*\.json$/.test(f)).sort()) merge(en, JSON.parse(readFileSync(join(here, f), 'utf8')), '');
let out = JSON.stringify(en, null, 2) + '\n';
if (crlf) out = out.replace(/\n/g, '\r\n');
writeFileSync(enPath, out);
console.log(`added ${added} keys`);
