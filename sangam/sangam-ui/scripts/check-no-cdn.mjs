// SPDX-License-Identifier: Apache-2.0
// Fails if index.html or the built bundle references a remote origin for scripts, styles or fonts (air-gapped rule).
// Usage: node scripts/check-no-cdn.mjs [uiRoot]   (uiRoot defaults to this package; the rule itself is in no-cdn.mjs)
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkNoCdn } from './no-cdn.mjs';

const root = process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '..');
const { targets, bad } = checkNoCdn(root);
if (bad.length) {
  console.error(`Remote references found:\n  ${bad.join('\n  ')}`);
  process.exit(1);
}
console.log(`No CDN references (${targets.length} files checked).`);
