// SPDX-License-Identifier: Apache-2.0
// English is the default and the fallback (owner decision Q-06, register SL-147). This check fails when:
// - a translation key used in src/ is missing from en.json (English must be complete);
// - another catalogue in src/i18n/locales/ (hi.json today) has a key en.json does not have (a stale key);
// - such a catalogue has an empty entry, or an entry whose {{placeholders}} differ from the English one.
// Other catalogues are optional: a key they lack is shown in English (i18next fallbackLng 'en'), so it is only counted.
// Static keys: t('a.b'), label: 'a.b' (declarative CRUD fields). Dynamic keys (t(`enum.${x}`)) are covered by the
// DYNAMIC list in i18n-dynamic.json, which names every value the code can produce.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const src = join(root, 'src');
const files = [];
(function walk(d) {
  for (const f of readdirSync(d)) {
    const p = join(d, f);
    if (statSync(p).isDirectory()) walk(p);
    else if (/\.(tsx?|mjs)$/.test(f)) files.push(p);
  }
})(src);

const used = new Set();
const keyRe = /\bt\(\s*'([a-zA-Z0-9_.]+)'/g;
const labelRe = /\b(?:label|description):\s*'([a-z][a-zA-Z0-9]*\.[a-zA-Z0-9_.]+)'/g;
for (const f of files) {
  const s = readFileSync(f, 'utf8');
  for (const m of s.matchAll(keyRe)) used.add(m[1]);
  for (const m of s.matchAll(labelRe)) used.add(m[1]);
  // Declarative module fields: f.text('x') and c('x') default to fld.x; f.select options to opt.VALUE.
  for (const m of s.matchAll(/\bf\.(?:text|num|money|date|dt|area|json|bool)\('([\w.]+)'(?:\)|, undefined)/g)) used.add('fld.' + m[1]);
  for (const m of s.matchAll(/\bc\('(\w+)'\)/g)) used.add('fld.' + m[1]);
  for (const m of s.matchAll(/\bf\.select\('(\w+)', \[([^\]]*)\](, undefined)?/g)) {
    if (m[3] !== undefined || !/\[[^\]]*\], '/.test(m[0] + s.slice(m.index + m[0].length, m.index + m[0].length + 3))) used.add('fld.' + m[1]);
    for (const v of m[2].matchAll(/'([^']+)'/g)) used.add('opt.' + v[1]);
  }
  for (const m of s.matchAll(/\b(?:label|hint|description|emptyHint|title):\s*'([a-z]+\.[\w.]+)'/g)) used.add(m[1]);
  for (const m of s.matchAll(/\bf\.\w+\('[\w.]+', '([a-z]+\.[\w.]+)'/g)) used.add(m[1]);
  for (const m of s.matchAll(/\bc\('\w+', '([a-z]+\.[\w.]+)'/g)) used.add(m[1]);
  for (const m of s.matchAll(/title="([a-z]+\.[\w.]+)"|description="([a-z]+\.[\w.]+)"/g)) used.add(m[1] ?? m[2]);
}

const dyn = JSON.parse(readFileSync(join(root, 'scripts', 'i18n-dynamic.json'), 'utf8'));
for (const [prefix, values] of Object.entries(dyn)) for (const v of values) used.add(`${prefix}${v}`);

function flatten(o, p = '', out = {}) {
  for (const [k, v] of Object.entries(o)) {
    const key = p ? `${p}.${k}` : k;
    if (v && typeof v === 'object') flatten(v, key, out);
    else out[key] = v;
  }
  return out;
}
const placeholders = (s) => (String(s).match(/\{\{\s*[\w.]+\s*\}\}/g) ?? []).map((x) => x.replace(/\s/g, '')).sort().join();

const dir = join(src, 'i18n', 'locales');
const read = (f) => flatten(JSON.parse(readFileSync(join(dir, f), 'utf8')));
const en = read('en.json');
const others = readdirSync(dir).filter((f) => f.endsWith('.json') && f !== 'en.json').sort();

let bad = 0;
const report = (msg, list) => {
  if (list.length) {
    bad += list.length;
    console.error(`${msg} (${list.length}):\n  ${list.sort().join('\n  ')}`);
  }
};
report('Missing in en.json (English must be complete)', [...used].filter((k) => !(k in en)));
report('Empty in en.json', Object.keys(en).filter((k) => String(en[k]).trim() === ''));
const notes = [];
for (const f of others) {
  const cat = read(f);
  report(`In ${f} but not en.json (stale key)`, Object.keys(cat).filter((k) => !(k in en)));
  report(`Empty in ${f} (remove it; English is the fallback)`, Object.keys(cat).filter((k) => k in en && String(cat[k]).trim() === ''));
  report(`Placeholders differ from en.json in ${f}`, Object.keys(cat).filter((k) => k in en && placeholders(cat[k]) !== placeholders(en[k])));
  const fallback = Object.keys(en).filter((k) => !(k in cat)).length;
  notes.push(`${f}: ${Object.keys(cat).length} keys, ${fallback} shown in English`);
}
if (bad) process.exit(1);
console.log(`i18n OK: ${used.size} keys used, ${Object.keys(en).length} keys in en.json${notes.length ? `; ${notes.join('; ')}` : ''}.`);
