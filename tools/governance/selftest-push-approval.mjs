#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Self-test of the Sangam push gate: concurrent appends keep one contiguous, verifiable chain;
// a tampered record is detected and never healed. Hermetic: a temporary ledger directory.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const TOOL = join(dirname(fileURLToPath(import.meta.url)), 'push-approval.mjs');
const dir = mkdtempSync(join(tmpdir(), 'sangam-push-gate-'));
const mirrorDir = mkdtempSync(join(tmpdir(), 'sangam-push-mirror-'));
const env = { ...process.env, SANGAM_PUSH_LEDGER_DIR: dir, SANGAM_PUSH_MIRROR_DIR: mirrorDir };
const N = 25;

const run = (args) => new Promise((res) => {
  const p = spawn(process.execPath, [TOOL, ...args], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '';
  p.stdout.on('data', (d) => { out += d; });
  p.stderr.on('data', (d) => { out += d; });
  p.on('close', (code) => res({ code, out }));
});

let failed = 0;
const check = (cond, what) => { console.log(`  ${cond ? 'PASS' : 'FAIL'}  ${what}`); if (!cond) failed++; };

try {
  const results = await Promise.all(Array.from({ length: N }, (_, i) => run(['--selftest-append', '--n', String(i)])));
  check(results.every((r) => r.code === 0), `${N} concurrent appends all succeeded`);
  const lines = readFileSync(join(dir, 'sangam-push-approvals.jsonl'), 'utf8').split('\n').filter(Boolean);
  const idx = lines.map((l) => JSON.parse(l).index);
  check(lines.length === N, `ledger has exactly ${N} records (found ${lines.length})`);
  check(idx.every((v, i) => v === i), 'indexes are contiguous 0..N-1 (no duplicate tail read)');
  check((await run(['verify'])).code === 0, 'chain verifies after concurrent appends');
  const mirror = readFileSync(join(mirrorDir, 'mirror.jsonl'), 'utf8');
  check(mirror === lines.join('\n') + '\n', 'off-repo mirror holds exactly the same records in the same order');
  const sha = '0'.repeat(39) + '1';
  const noSource = await run(['record', '--sha', sha, '--remote', 'r', '--ref', 'refs/heads/main', '--words', 'ok',
      '--conversation', 'c']);
  check(noSource.code !== 0 && /--source user-message/.test(noSource.out),
      'approval without --source user-message is refused (no agent-written approvals)');
  writeFileSync(join(mirrorDir, 'mirror.jsonl'), '');
  check((await run(['verify'])).code !== 0, 'a lost or rewritten mirror is reported');
  writeFileSync(join(mirrorDir, 'mirror.jsonl'), mirror);

  // Change the content of record 3 (whatever its n is), keep its stored hashes: a forged edit.
  const tampered = lines.map((l, i) => {
    if (i !== 3) return l;
    const r = JSON.parse(l);
    r.n = r.n + 1000;
    return JSON.stringify(r);
  }).join('\n') + '\n';
  check(tampered !== lines.join('\n') + '\n', 'tamper step really changed the ledger');
  writeFileSync(join(dir, 'sangam-push-approvals.jsonl'), tampered);
  check((await run(['verify'])).code !== 0, 'tampered record is detected');
  check((await run(['--selftest-append', '--n', '1'])).code !== 0, 'append to a broken chain is refused (never healed)');
} finally {
  rmSync(dir, { recursive: true, force: true });
  rmSync(mirrorDir, { recursive: true, force: true });
}
console.log(failed ? `\n  ${failed} check(s) FAILED` : '\n  all checks passed');
process.exit(failed ? 1 : 0);
