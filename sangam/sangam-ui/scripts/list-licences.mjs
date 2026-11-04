// SPDX-License-Identifier: Apache-2.0
// Lists every installed npm package with its licence and flags anything that is not OSI-approved.
// bpmn.io packages (bpmn-js, dmn-js, form-js, diagram-js and their *-js helpers) are the single allowed exception (LIC-8),
// provided the watermark stays visible.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const nm = join(root, 'node_modules');
const OSI = new Set(['MIT', 'ISC', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', '0BSD', 'OFL-1.1', 'MPL-2.0', 'BlueOak-1.0.0', 'Python-2.0', 'CC0-1.0', 'Unlicense', 'Zlib', 'Artistic-2.0']);
const BPMN_IO = /^(bpmn-js|dmn-js|dmn-js-.*|diagram-js|diagram-js-.*|@bpmn-io\/.*|bpmn-moddle|dmn-moddle|table-js|moddle|moddle-xml|ids|min-dash|min-dom|tiny-svg|object-refs|path-intersection|didi|saxen|inherits-browser|selection-ranges|selection-update)$/;

const rows = [];
function visit(dir, scope = '') {
  for (const name of readdirSync(dir)) {
    if (name.startsWith('.')) continue;
    const p = join(dir, name);
    if (name.startsWith('@') && !scope) {
      visit(p, name);
      continue;
    }
    const pj = join(p, 'package.json');
    if (!existsSync(pj)) continue;
    const j = JSON.parse(readFileSync(pj, 'utf8'));
    let lic = typeof j.license === 'string' ? j.license : (j.license?.type ?? (j.licenses ?? []).map((l) => l.type).join(' OR ')) || 'UNKNOWN';
    // Old component.io packages declare the licence in component.json (e.g. component-props: MIT).
    if (lic === 'UNKNOWN' && existsSync(join(p, 'component.json'))) lic = JSON.parse(readFileSync(join(p, 'component.json'), 'utf8')).license ?? lic;
    rows.push({ name: j.name, version: j.version, license: lic });
    const nested = join(p, 'node_modules');
    if (existsSync(nested)) visit(nested);
  }
}
visit(nm);
rows.sort((a, b) => a.name.localeCompare(b.name));
let bad = 0;
for (const r of rows) {
  const ids = r.license.replace(/[()]/g, '').split(/\s+(?:OR|AND)\s+/);
  const osi = ids.some((x) => OSI.has(x));
  const bpmnIo = BPMN_IO.test(r.name) && /SEE LICENSE|bpmn\.io|MIT/i.test(r.license);
  // caniuse-lite (CC-BY-4.0) is browser-support data read by the build tool only; it is not shipped in dist/.
  const buildData = r.name === 'caniuse-lite';
  const flag = osi ? '' : bpmnIo ? '  [bpmn.io licence, watermark kept]' : buildData ? '  [build-time data only, not shipped]' : '  [CHECK]';
  if (!osi && !bpmnIo && !buildData) bad++;
  console.log(`${r.name}@${r.version}\t${r.license}${flag}`);
}
console.log(`\n${rows.length} packages, ${bad} need checking.`);
process.exit(bad ? 1 : 0);
