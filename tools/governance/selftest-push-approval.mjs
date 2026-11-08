#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Self-test of the Sangam push gate (SG-32/SG-34). Hermetic: a temporary git repository with its own committed
// allowed_signers and throwaway keys (generated here, deleted at the end; never the owner's key), a temporary
// mirror and a temporary owner-held trust-root path. The real tool is run with cwd inside that repository.
import { execFileSync, spawn } from 'node:child_process';
import { appendFileSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { approvalText, hashOf, sshKeygen } from './push-approval.mjs';

const TOOL = join(dirname(fileURLToPath(import.meta.url)), 'push-approval.mjs');
const base = mkdtempSync(join(tmpdir(), 'sangam-push-selftest-'));
const repo = join(base, 'repo'), kdir = join(base, 'keys'), mirror = join(base, 'mirror', 'mirror.jsonl');
const ownerFile = join(base, 'owner-home', '.ssh', 'sangam_allowed_signers');
const wrapper = join(base, 'run.mjs');
const REMOTE = 'https://example.invalid/sangam.git';
const REF = 'refs/heads/main';
const N = 25;

let failed = 0;
const check = (cond, what) => { console.log(`  ${cond ? 'PASS' : 'FAIL'}  ${what}`); if (!cond) failed++; };
const g = (...a) => execFileSync('git', ['-C', repo, ...a], { encoding: 'utf8' }).trim();
const keygen = (...a) => execFileSync(sshKeygen(), a, { stdio: 'ignore' });

// The wrapper passes test-only options (temporary mirror / owner-held path) through main()'s parameter;
// the production entry point reads no trust input from the environment.
const run = (args, { stdin = '', env = {}, owner = false } = {}) => new Promise((res) => {
  const opts = { mirrorPath: mirror, ownerSignersPath: owner ? ownerFile : join(base, 'no-owner-file') };
  const p = spawn(process.execPath, [wrapper, ...args], { cwd: repo, stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, ...env, SANGAM_SELFTEST_OPTS: JSON.stringify(opts) } });
  let out = '';
  p.stdout.on('data', (d) => { out += d; });
  p.stderr.on('data', (d) => { out += d; });
  const timer = setTimeout(() => { console.log(`  [hung 60 s] ${args.join(' ')}: ${out}`); p.kill(); res({ code: -1, out }); }, 60_000);
  p.on('close', (code) => { clearTimeout(timer); res({ code, out }); });
  p.stdin.end(stdin);
});
const ledgerFile = () => join(repo, '.git', 'sangam-push-approvals.jsonl');
const readLedger = () => readFileSync(ledgerFile(), 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
function forge(entry) { // append a record with correctly recomputed chain hashes (what an attacker can do)
  const chain = readLedger();
  const rec = { ...entry, index: chain.length, prevSha256: chain.length ? chain.at(-1).recordSha256 : null,
      at: new Date().toISOString() };
  rec.recordSha256 = hashOf(rec);
  appendFileSync(ledgerFile(), JSON.stringify(rec) + '\n');
  appendFileSync(mirror, JSON.stringify(rec) + '\n');
}
const signerLine = (k, principal) => {
  const pub = readFileSync(join(kdir, k + '.pub'), 'utf8').trim().split(' ');
  return `${principal} namespaces="sangam-push" ${pub[0]} ${pub[1]}\n`;
};
function sign(k, text, name) {
  const f = join(kdir, name);
  writeFileSync(f, text);
  keygen('-Y', 'sign', '-f', join(kdir, k), '-n', 'sangam-push', f);
  return f;
}
const fresh = (sha, words = 'push it', ref = REF, remote = REMOTE, mins = 10) =>
  approvalText({ sha, remote, ref, words, expires: new Date(Date.now() + mins * 60000).toISOString() });
const pushLine = (local, remote = c1, ref = REF) => `refs/heads/main ${local} ${ref} ${remote}\n`;
let c1, c2;

try {
  mkdirSync(kdir); mkdirSync(repo); mkdirSync(dirname(ownerFile), { recursive: true });
  writeFileSync(wrapper, `import { main } from ${JSON.stringify(pathToFileURL(TOOL).href)};\n`
      + 'process.exit(main(process.argv.slice(2), JSON.parse(process.env.SANGAM_SELFTEST_OPTS)));\n');
  for (const k of ['owner', 'other', 'owner2']) keygen('-q', '-t', 'ed25519', '-N', '', '-C', k, '-f', join(kdir, k));

  g('init', '-q', '-b', 'main');
  g('config', 'user.email', 'selftest@sangam.invalid'); g('config', 'user.name', 'selftest');
  g('config', 'commit.gpgsign', 'false'); g('config', 'core.autocrlf', 'false');
  mkdirSync(join(repo, '.githooks'));
  writeFileSync(join(repo, '.githooks', 'allowed_signers'), '# test signers\n' + signerLine('owner', 'owner@sangam'));
  g('add', '.'); g('commit', '-q', '-m', 'c1: signers');
  c1 = g('rev-parse', 'HEAD');
  writeFileSync(join(repo, 'a.txt'), 'a\n'); g('add', '.'); g('commit', '-q', '-m', 'c2');
  c2 = g('rev-parse', 'HEAD');
  g('remote', 'add', 'origin', REMOTE);
  g('update-ref', 'refs/remotes/origin/main', c1); // the remote "already accepted" c1

  // ── ledger: concurrency, chain, mirror ──
  const results = await Promise.all(Array.from({ length: N }, (_, i) => run(['--selftest-append', '--n', String(i)])));
  check(results.every((r) => r.code === 0), `${N} concurrent appends all succeeded`);
  const lines = readFileSync(ledgerFile(), 'utf8').split('\n').filter(Boolean);
  check(lines.length === N, `ledger has exactly ${N} records (found ${lines.length})`);
  check(lines.map((l) => JSON.parse(l).index).every((v, i) => v === i), 'indexes are contiguous 0..N-1');
  check((await run(['verify'])).code === 0, 'chain verifies after concurrent appends');
  const mirrorText = readFileSync(mirror, 'utf8');
  check(mirrorText === lines.join('\n') + '\n', 'off-repo mirror holds exactly the same records in the same order');
  writeFileSync(mirror, '');
  check((await run(['verify'])).code !== 0, 'a lost or rewritten mirror is reported');
  writeFileSync(mirror, mirrorText);

  // ── recording ──
  const unsigned = await run(['record', '--sha', c2, '--remote', REMOTE, '--ref', REF, '--words', 'x',
      '--source', 'user-message', '--conversation', 'c']);
  check(unsigned.code !== 0, 'unsigned approval is refused (never authorises a push)');
  const t1 = sign('other', fresh(c2), 'other.txt');
  check((await run(['record', '--signed', t1, '--sig', t1 + '.sig', '--conversation', 'c'])).code !== 0,
      'approval signed with a key not in the trusted signers is refused');
  const t2 = join(kdir, 'tampered.txt');
  const goodText = fresh(c2);
  const tg = sign('owner', goodText, 'good.txt');
  writeFileSync(t2, goodText.replace('push it', 'push something else'));
  writeFileSync(t2 + '.sig', readFileSync(tg + '.sig'));
  check((await run(['record', '--signed', t2, '--sig', t2 + '.sig', '--conversation', 'c'])).code !== 0,
      'signed text changed after signing is refused');

  // ── S6: check re-verifies; forged records authorise nothing ──
  forge({ kind: 'APPROVAL', sha: c2, remote: REMOTE, ref: REF, words: 'forged', source: 'ssh-signature',
      signer: 'owner@sangam', signatureVerified: true, ttyVerified: false });
  check((await run(['verify'])).code === 0, 'forged record with recomputed hashes still verifies as a chain (unkeyed)');
  check((await run(['check', 'origin', REMOTE], { stdin: pushLine(c2) })).code !== 0,
      'S6: forged record flagged signatureVerified=true without a signature does not authorise the push');
  forge({ kind: 'APPROVAL', sha: c2, remote: REMOTE, ref: REF, words: 'x', signedText: fresh('b'.repeat(40)),
      signature: readFileSync(sign('owner', fresh('b'.repeat(40)), 'b.txt') + '.sig', 'utf8') });
  check((await run(['check', 'origin', REMOTE], { stdin: pushLine(c2) })).code !== 0,
      'S6: a genuine signature over a different sha does not authorise this sha');
  const expired = approvalText({ sha: c2, remote: REMOTE, ref: REF, words: 'old', expires: new Date(Date.now() - 60000).toISOString() });
  forge({ kind: 'APPROVAL', sha: c2, remote: REMOTE, ref: REF, words: 'old', signedText: expired,
      signature: readFileSync(sign('owner', expired, 'exp.txt') + '.sig', 'utf8') });
  check((await run(['check', 'origin', REMOTE], { stdin: pushLine(c2) })).code !== 0,
      'S6: a genuinely signed but expired approval does not authorise the push');
  const otherRef = fresh(c2, 'push it', 'refs/heads/release');
  forge({ kind: 'APPROVAL', sha: c2, remote: REMOTE, ref: REF, words: 'x', signedText: otherRef,
      signature: readFileSync(sign('owner', otherRef, 'ref.txt') + '.sig', 'utf8') });
  check((await run(['check', 'origin', REMOTE], { stdin: pushLine(c2) })).code !== 0,
      'S6: a signature binding another ref does not authorise this ref');

  // ── genuine flow ──
  const rec = await run(['record', '--signed', tg, '--sig', tg + '.sig', '--conversation', 'c']);
  check(rec.code === 0 && /SIGNED APPROVAL/.test(rec.out), 'owner-signed approval is verified and recorded');
  const stored = readLedger().at(-1);
  check(stored.signedText === goodText && /BEGIN SSH SIGNATURE/.test(stored.signature),
      'record stores the signed text and the signature');
  const ok = await run(['check', 'origin', REMOTE], { stdin: pushLine(c2) });
  check(ok.code === 0 && /signed by owner@sangam/.test(ok.out), 'check re-verifies the stored signature and allows the push');
  check((await run(['check', 'origin', REMOTE], { stdin: pushLine(c2) })).code !== 0, 'an approval is single use');
  check((await run(['check', 'origin', 'https://elsewhere.invalid/x.git'], { stdin: pushLine(c2) })).code !== 0,
      'an approval for one remote does not authorise another remote');

  // ── S7: the environment cannot swap the trust root ──
  const envSigners = join(kdir, 'env_signers');
  writeFileSync(envSigners, signerLine('other', 'evil@x'));
  const te = sign('other', fresh(c2), 'env.txt');
  const envRun = { env: { SANGAM_ALLOWED_SIGNERS: envSigners } };
  check((await run(['record', '--signed', te, '--sig', te + '.sig', '--conversation', 'c'], envRun)).code !== 0,
      'S7: SANGAM_ALLOWED_SIGNERS cannot make a foreign key acceptable at record');
  forge({ kind: 'APPROVAL', sha: c2, remote: REMOTE, ref: REF, words: 'x', signedText: readFileSync(te, 'utf8'),
      signature: readFileSync(te + '.sig', 'utf8') });
  check((await run(['check', 'origin', REMOTE], { ...envRun, stdin: pushLine(c2) })).code !== 0,
      'S7: SANGAM_ALLOWED_SIGNERS cannot make a foreign key acceptable at push time');

  // ── S8: the working tree and the pushed commits cannot add a signer ──
  const wtFile = join(repo, '.githooks', 'allowed_signers');
  const committed = readFileSync(wtFile, 'utf8');
  writeFileSync(wtFile, committed + signerLine('other', 'evil@x'));
  const tw = sign('other', fresh(c2), 'wt.txt');
  check((await run(['record', '--signed', tw, '--sig', tw + '.sig', '--conversation', 'c'])).code !== 0,
      'S8: a key added in the working tree is not trusted at record');
  forge({ kind: 'APPROVAL', sha: c2, remote: REMOTE, ref: REF, words: 'x', signedText: readFileSync(tw, 'utf8'),
      signature: readFileSync(tw + '.sig', 'utf8') });
  const wtCheck = await run(['check', 'origin', REMOTE], { stdin: pushLine(c2) });
  check(wtCheck.code !== 0 && /working-tree .*differs/.test(wtCheck.out),
      'S8: a working tree that differs from the trusted signers is reported and refused');
  writeFileSync(wtFile, committed);
  check((await run(['check', 'origin', REMOTE], { stdin: pushLine(c2) })).code !== 0,
      'S8: with the working tree restored, the foreign-key approval still authorises nothing');
  writeFileSync(wtFile, committed + signerLine('other', 'evil@x'));
  g('add', '.'); g('commit', '-q', '-m', 'c3: introduce own signer');
  const c3 = g('rev-parse', 'HEAD');
  const t3 = sign('other', fresh(c3), 'c3.txt');
  forge({ kind: 'APPROVAL', sha: c3, remote: REMOTE, ref: REF, words: 'x', signedText: readFileSync(t3, 'utf8'),
      signature: readFileSync(t3 + '.sig', 'utf8') });
  check((await run(['check', 'origin', REMOTE], { stdin: pushLine(c3) })).code !== 0,
      'S8: a pushed commit cannot introduce its own signer (trust comes from the remote commit)');
  g('reset', '-q', '--hard', c2);

  // ── bootstrap / owner-held trust root ──
  const tn = sign('owner', fresh(c2, 'new', 'refs/heads/feature'), 'new.txt');
  forge({ kind: 'APPROVAL', sha: c2, remote: REMOTE, ref: 'refs/heads/feature', words: 'new',
      signedText: readFileSync(tn, 'utf8'), signature: readFileSync(tn + '.sig', 'utf8') });
  const zero = '0'.repeat(40);
  check((await run(['check', 'origin', REMOTE], { stdin: pushLine(c2, zero, 'refs/heads/feature') })).code !== 0,
      'a new ref without an owner-held trust root is refused (no remote commit to trust)');
  writeFileSync(ownerFile, signerLine('owner', 'owner@sangam'));
  check((await run(['check', 'origin', REMOTE], { owner: true, stdin: pushLine(c2, zero, 'refs/heads/feature') })).code === 0,
      'with the owner-held trust root present, a new ref with a valid owner signature is allowed');
  writeFileSync(ownerFile, signerLine('owner2', 'owner2@sangam'));
  writeFileSync(wtFile, '# rotated\n' + signerLine('owner2', 'owner2@sangam'));
  const tr = sign('owner', fresh(c2, 'stale key'), 'rot1.txt');
  forge({ kind: 'APPROVAL', sha: c2, remote: REMOTE, ref: REF, words: 'x', signedText: readFileSync(tr, 'utf8'),
      signature: readFileSync(tr + '.sig', 'utf8') });
  check((await run(['check', 'origin', REMOTE], { owner: true, stdin: pushLine(c2) })).code !== 0,
      'the owner-held file is preferred over the remote tree (a key only in the tree is not trusted)');
  const t4 = sign('owner2', fresh(c2, 'rotated'), 'rot2.txt');
  check((await run(['record', '--signed', t4, '--sig', t4 + '.sig', '--conversation', 'c'], { owner: true })).code === 0
      && (await run(['check', 'origin', REMOTE], { owner: true, stdin: pushLine(c2) })).code === 0,
      'a key in the owner-held file signs a push that is allowed');

  // ── tamper detection ──
  const all = readFileSync(ledgerFile(), 'utf8').split('\n').filter(Boolean);
  const r3 = JSON.parse(all[3]); r3.n += 1000; all[3] = JSON.stringify(r3);
  writeFileSync(ledgerFile(), all.join('\n') + '\n');
  check((await run(['verify'])).code !== 0, 'tampered record is detected');
  check((await run(['--selftest-append', '--n', '1'])).code !== 0, 'append to a broken chain is refused (never healed)');
} catch (e) {
  console.log(`  FAIL  selftest crashed: ${e.stack || e}`);
  failed++;
} finally {
  rmSync(base, { recursive: true, force: true });
}
console.log(failed ? `\n  ${failed} check(s) FAILED` : '\n  all checks passed');
process.exit(failed ? 1 : 0);
