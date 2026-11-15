// SPDX-License-Identifier: Apache-2.0
// Lists every installed npm package (production and build/test tooling) with its licence and flags anything the
// licence policy does not accept. The rules are read from the ONE policy file, deploy/licence/licence-policy.json
// (RM-51: this script keeps no allow-list of its own):
//   - "allowed": the accepted SPDX ids;
//   - "conditional": a licence accepted only for the named components (the bpmn.io licence, LIC-8, watermark kept);
//   - "reviewed_licences": exact versions whose package.json names no licence (nothing, or a pointer such as
//     "SEE LICENSE IN LICENSE"), with the licence read from the package's own files.
// The CI gate for what ships is check_licences.py on the CycloneDX SBOM (ui.yml); this listing is the developer's view of
// the whole node_modules tree and is stricter on one point: a licence that is not accepted is flagged in build and
// test tooling too.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const nm = join(root, 'node_modules');
const policyPath = join(root, '..', 'deploy', 'licence', 'licence-policy.json');
const policy = JSON.parse(readFileSync(policyPath, 'utf8'));
const allowed = new Set(policy.allowed);
// purls are compared decoded, so the policy's SBOM spelling (pkg:npm/%40scope/name) matches @scope/name
const conditional = (policy.conditional ?? [])
  .filter((c) => !c.allowed_in || c.allowed_in.includes('ui'))
  .map((c) => ({ license: c.license, condition: c.condition, prefixes: c.components.map(decodeURIComponent) }));
const reviewed = new Map();
for (const e of policy.reviewed_licences ?? []) for (const p of e.purls) reviewed.set(decodeURIComponent(p), e);

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
    const declared = typeof j.license === 'string' ? j.license : (j.license?.type ?? (j.licenses ?? []).map((l) => l.type).join(' OR ')) || null;
    rows.push({ name: j.name, version: j.version, declared });
    const nested = join(p, 'node_modules');
    if (existsSync(nested)) visit(nested);
  }
}

/** '' when the package passes, otherwise the reason it needs a check; `note` says why a non-plain case passed. */
function judge(r) {
  const purl = `pkg:npm/${r.name}@${r.version}`;
  let expr = r.declared;
  let note = '';
  const rev = reviewed.get(purl);
  if (rev) {
    const want = Array.isArray(rev.declared) ? rev.declared : [rev.declared];
    if (!want.includes(r.declared)) return { bad: `reviewed as ${rev.licence} when it declared ${want.join(' or ')}; read its files again` };
    expr = rev.licence;
    note = `reviewed: ${rev.licence}`;
  }
  if (!expr) return { bad: 'no licence data' };
  const status = (term) => {
    const c = conditional.find((x) => x.license === term && x.prefixes.some((p) => purl.startsWith(p)));
    if (c) return c.condition;
    return allowed.has(term) ? '' : null;
  };
  // A flat SPDX expression: OR separates alternatives, AND joins terms that must all pass. A nested one is not guessed.
  const flat = expr.replace(/^\((.*)\)$/, '$1');
  if (/[()]/.test(flat) && /\sAND\s/.test(flat) && /\sOR\s/.test(flat)) return { bad: 'nested licence expression' };
  for (const alt of flat.replace(/[()]/g, '').split(/\s+OR\s+/)) {
    const results = alt.split(/\s+AND\s+/).map(status);
    if (results.every((s) => s !== null)) {
      const cond = results.find((s) => s);
      return { note: [note, cond].filter(Boolean).join('; ') };
    }
  }
  return { bad: 'not accepted by licence-policy.json' };
}

visit(nm);
rows.sort((a, b) => a.name.localeCompare(b.name) || a.version.localeCompare(b.version));
let bad = 0;
for (const r of rows) {
  const v = judge(r);
  if (v.bad) bad++;
  const flag = v.bad ? `  [CHECK: ${v.bad}]` : v.note ? `  [${v.note}]` : '';
  console.log(`${r.name}@${r.version}\t${r.declared ?? 'UNKNOWN'}${flag}`);
}
console.log(`\n${rows.length} packages, ${bad} need checking.`);
process.exit(bad ? 1 : 0);
