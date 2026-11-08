// SPDX-License-Identifier: Apache-2.0
// Fails when a translation key used in src/ is missing from en.json or hi.json, or when the two files differ.
// Static keys: t('a.b'), label: 'a.b' (declarative CRUD fields). Dynamic keys (t(`enum.${x}`)) are covered by the
// DYNAMIC list below, which names every value the code can produce.
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
const en = flatten(JSON.parse(readFileSync(join(src, 'i18n', 'locales', 'en.json'), 'utf8')));
const hi = flatten(JSON.parse(readFileSync(join(src, 'i18n', 'locales', 'hi.json'), 'utf8')));

let bad = 0;
const report = (msg, list) => {
  if (list.length) {
    bad += list.length;
    console.error(`${msg} (${list.length}):\n  ${list.sort().join('\n  ')}`);
  }
};
report('Missing in en.json', [...used].filter((k) => !(k in en)));
report('Missing in hi.json', [...used].filter((k) => !(k in hi)));
report('In en.json but not hi.json', Object.keys(en).filter((k) => !(k in hi)));
report('In hi.json but not en.json', Object.keys(hi).filter((k) => !(k in en)));
if (bad) process.exit(1);
console.log(`i18n OK: ${used.size} keys used, ${Object.keys(en).length} keys per locale.`);
