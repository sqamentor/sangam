// SPDX-License-Identifier: Apache-2.0
// Fails if index.html or the built bundle references a remote origin for scripts, styles or fonts (air-gapped rule).
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const targets = [join(root, 'index.html')];
const dist = join(root, 'dist');
if (existsSync(dist)) {
  (function walk(d) {
    for (const f of readdirSync(d)) {
      const p = join(d, f);
      if (statSync(p).isDirectory()) walk(p);
      else if (/\.(html|css)$/.test(f)) targets.push(p);
    }
  })(dist);
}
const bad = [];
const re = /(?:<script[^>]+src|<link[^>]+href|@import\s+url\(|url\()\s*=?\s*["']?(https?:)?\/\/[^"')\s]+/gi;
for (const f of targets) {
  const s = readFileSync(f, 'utf8');
  for (const m of s.matchAll(re)) {
    if (/\/\/(www\.w3\.org)/.test(m[0])) continue; // XML namespaces, not fetched
    bad.push(`${f}: ${m[0].slice(0, 120)}`);
  }
}
if (bad.length) {
  console.error(`Remote references found:\n  ${bad.join('\n  ')}`);
  process.exit(1);
}
console.log(`No CDN references (${targets.length} files checked).`);
