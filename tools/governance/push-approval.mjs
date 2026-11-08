#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
/**
 * Sangam's own push-approval gate (owner decision SD-432 "Gate Sangam like IFMS", 2026-11-08).
 * Sangam-owned on purpose: no other project's governance code or ledger is used (project boundaries).
 *
 *   record  --sha <full sha> --remote <url> --ref <refs/heads/x> --words "<the human's verbatim approval>"
 *           --conversation "<session ref>"      → appends one APPROVAL record
 *   check   (called by .githooks/pre-push with git's pre-push stdin)
 *           → every ref update must have an unconsumed APPROVAL for exactly that sha, remote and ref,
 *             recorded within the TTL; the update must be a fast-forward (no force) unless deleting is
 *             refused outright; each approval is consumed (single use) by appending a CONSUMED record.
 *   verify  → checks the whole chain
 *
 * Ledger: <git-dir>/sangam-push-approvals.jsonl (per clone, never committed). Each record carries
 * index, prevSha256 and recordSha256 (SHA-256 over the canonical JSON of the record without its hash).
 * Every append runs under an exclusive lock (atomic mkdir <ledger>.lock, retry with exponential backoff
 * and jitter, stale lock broken only when its owner pid is dead AND it is older than 60 s), re-reads and
 * verifies the chain inside the lock, computes index/prev from the fresh tail, writes the full line in one
 * write + fsync, re-verifies, then releases. A broken chain is never appended to and never auto-healed.
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, rmSync, statSync,
         writeFileSync, writeSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';

const TTL_MS = 30 * 60 * 1000;

function gitDir() {
  if (process.env.SANGAM_PUSH_LEDGER_DIR) return resolve(process.env.SANGAM_PUSH_LEDGER_DIR); // tests only
  return resolve(execFileSync('git', ['rev-parse', '--git-dir'], { encoding: 'utf8' }).trim());
}
const LEDGER = join(gitDir(), 'sangam-push-approvals.jsonl');
const LOCK = LEDGER + '.lock';
// Off-repo mirror (SD-428 review point 1): a clone or a deleted .git must not lose the audit trail.
// One mirror per repository, keyed by the repository's absolute path, outside the working tree.
function mirrorPath() {
  if (process.env.SANGAM_PUSH_MIRROR_DIR) return join(resolve(process.env.SANGAM_PUSH_MIRROR_DIR), 'mirror.jsonl');
  const top = resolve(execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim());
  const base = process.env.LOCALAPPDATA || join(homedir(), '.local', 'share');
  return join(base, 'sangam-governance', sha256(top.toLowerCase()).slice(0, 16), 'push-approvals.mirror.jsonl');
}

const canon = (o) => JSON.stringify(o, Object.keys(o).sort());
const sha256 = (s) => createHash('sha256').update(s).digest('hex');
const hashOf = (rec) => { const { recordSha256, ...rest } = rec; return sha256(canon(rest)); };

function readChain() {
  if (!existsSync(LEDGER)) return [];
  return readFileSync(LEDGER, 'utf8').split('\n').filter(Boolean).map((l, i) => {
    try { return JSON.parse(l); } catch { return { __malformed: true, __line: i + 1 }; }
  });
}

export function verifyChain(chain = readChain()) {
  const issues = [];
  let prev = null;
  chain.forEach((r, i) => {
    if (r.__malformed) { issues.push(`line ${r.__line}: malformed JSON`); prev = null; return; }
    if (r.index !== i) issues.push(`position ${i}: index ${r.index}`);
    if ((r.prevSha256 ?? null) !== prev) issues.push(`position ${i}: prev hash mismatch`);
    if (r.recordSha256 !== hashOf(r)) issues.push(`position ${i}: record hash mismatch (content changed)`);
    prev = r.recordSha256;
  });
  return { ok: issues.length === 0, issues, length: chain.length };
}

function pidAlive(pid) {
  try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; }
}

function sleep(ms) { Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms); }

function withLock(fn) {
  const deadline = Date.now() + 30_000;
  let wait = 10;
  for (;;) {
    try {
      mkdirSync(LOCK);
      writeFileSync(join(LOCK, 'owner.json'), JSON.stringify({ pid: process.pid, at: new Date().toISOString() }));
      break;
    } catch (e) {
      if (e.code !== 'EEXIST') throw e;
      try {
        const o = JSON.parse(readFileSync(join(LOCK, 'owner.json'), 'utf8'));
        const age = Date.now() - statSync(LOCK).mtimeMs;
        if (!pidAlive(o.pid) && age > 60_000) {
          console.error(`  breaking stale push-ledger lock (pid ${o.pid} dead, ${Math.round(age / 1000)} s old)`);
          rmSync(LOCK, { recursive: true, force: true });
          continue;
        }
      } catch { /* owner file not written yet: treat as live */ }
      if (Date.now() > deadline) throw new Error(`push-ledger lock ${LOCK} still held after 30 s; refusing`);
      sleep(wait + Math.floor(Math.random() * wait));
      wait = Math.min(wait * 2, 1000);
    }
  }
  try { return fn(); } finally { rmSync(LOCK, { recursive: true, force: true }); }
}

export function append(entry) {
  return withLock(() => {
    const chain = readChain();
    const v = verifyChain(chain);
    if (!v.ok) throw new Error('push-approval ledger does not verify; refusing to append:\n  ' + v.issues.join('\n  '));
    const prev = chain.length ? chain[chain.length - 1].recordSha256 : null;
    const rec = { ...entry, index: chain.length, prevSha256: prev, at: new Date().toISOString() };
    rec.recordSha256 = hashOf(rec);
    const line = JSON.stringify(rec) + '\n';
    const fd = openSync(LEDGER, 'a');
    try { writeSync(fd, line); fsyncSync(fd); } finally { closeSync(fd); }
    const after = verifyChain();
    if (!after.ok || after.length !== chain.length + 1) {
      throw new Error('push-approval ledger failed verification right after append: ' + after.issues.join('; '));
    }
    // mirror inside the same lock, so ledger and mirror can never interleave differently
    const m = mirrorPath();
    mkdirSync(dirname(m), { recursive: true });
    const mfd = openSync(m, 'a');
    try { writeSync(mfd, line); fsyncSync(mfd); } finally { closeSync(mfd); }
    return rec;
  });
}

function arg(name) {
  const i = process.argv.indexOf('--' + name);
  return i > 0 ? process.argv[i + 1] : undefined;
}

function isAncestor(a, b) {
  try { execFileSync('git', ['merge-base', '--is-ancestor', a, b], { stdio: 'ignore' }); return true; }
  catch { return false; }
}

/**
 * Who said the words (SD-428 review point 2). The tool cannot prove on its own that text passed by an
 * agent was typed by the human, so it records the channel honestly instead of claiming more:
 *   --tty            the human types the approval at an interactive terminal; ttyVerified=true.
 *   --words ...      conversation channel: an agent records the human's own chat message VERBATIM with the
 *                    conversation id; ttyVerified=false. Policy (documented in the hook and README): only
 *                    the human's own message in the conversation, never an agent's paraphrase and never a
 *                    message relayed from another session; the record is auditable against the transcript.
 * A relayed or agent-written approval is refused by policy and by the --source check below.
 */
async function readTtyWords() {
  const { createInterface } = await import('node:readline');
  if (!process.stdin.isTTY) throw new Error('--tty needs an interactive terminal (stdin is not a TTY)');
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const answer = await new Promise((res) => rl.question('  Type your approval in your own words: ', res));
  rl.close();
  return answer.trim();
}

async function cmdRecord() {
  const sha = arg('sha'), remote = arg('remote'), ref = arg('ref'), conv = arg('conversation');
  const tty = process.argv.includes('--tty');
  const source = tty ? 'tty' : arg('source');
  if (!/^[0-9a-f]{40}$/.test(sha || '')) throw new Error('--sha must be a full 40-hex commit id');
  if (!remote || !ref || !conv) throw new Error('--remote, --ref and --conversation are required');
  if (!tty && source !== 'user-message') {
    throw new Error('--source user-message is required for --words (the human\'s own message, verbatim); '
        + 'agent paraphrases and messages relayed from other sessions are not approvals');
  }
  const words = tty ? await readTtyWords() : arg('words');
  if (!words) throw new Error('approval words are empty');
  const rec = append({ kind: 'APPROVAL', sha, remote, ref, words, conversation: conv, source,
      ttyVerified: tty });
  console.log(`  APPROVAL recorded #${rec.index} for ${sha.slice(0, 12)} → ${ref} on ${remote} `
      + `(source=${source}, ttyVerified=${tty}; single use, ${TTL_MS / 60000} min)`);
}

function cmdVerify() {
  const v = verifyChain();
  if (!v.ok) { console.log('  BROKEN:\n  ' + v.issues.join('\n  ')); return 1; }
  const m = mirrorPath();
  const ledger = existsSync(LEDGER) ? readFileSync(LEDGER, 'utf8') : '';
  const mirror = existsSync(m) ? readFileSync(m, 'utf8') : '';
  if (!mirror.startsWith(ledger)) {
    console.log(`  MIRROR MISMATCH: ${m} does not contain the ledger as a prefix (ledger rewritten or mirror lost)`);
    return 1;
  }
  console.log(`  push-approval ledger OK (${v.length} records); mirror OK (${m})`);
  return 0;
}

function cmdCheck(remoteUrl) {
  const input = readFileSync(0, 'utf8').split('\n').filter(Boolean);
  const ZERO = /^0{40}$/;
  for (const line of input) {
    const [, localSha, remoteRef, remoteSha] = line.trim().split(/\s+/);
    if (ZERO.test(localSha)) throw new Error(`deleting ${remoteRef} is not allowed through this gate`);
    if (!ZERO.test(remoteSha) && !isAncestor(remoteSha, localSha)) {
      throw new Error(`${remoteRef}: not a fast-forward (${remoteSha.slice(0, 12)} is not an ancestor of ${localSha.slice(0, 12)}); force pushes are refused`);
    }
    const chain = readChain();
    const v = verifyChain(chain);
    if (!v.ok) throw new Error('push-approval ledger does not verify:\n  ' + v.issues.join('\n  '));
    const consumed = new Set(chain.filter((r) => r.kind === 'CONSUMED').map((r) => r.approvalIndex));
    const now = Date.now();
    const ok = chain.filter((r) => r.kind === 'APPROVAL' && r.sha === localSha && r.ref === remoteRef
        && r.remote === remoteUrl && !consumed.has(r.index) && now - Date.parse(r.at) <= TTL_MS).pop();
    if (!ok) {
      throw new Error(`no unconsumed approval for ${localSha} → ${remoteRef} on ${remoteUrl} within ${TTL_MS / 60000} min.\n`
          + '  Record the human\'s own words first: node tools/governance/push-approval.mjs record --sha <sha> '
          + '--remote <url> --ref <ref> --words "<verbatim>" --conversation "<session>"');
    }
    append({ kind: 'CONSUMED', approvalIndex: ok.index, sha: localSha, ref: remoteRef, remote: remoteUrl });
    console.log(`  push approved by record #${ok.index}: "${ok.words.slice(0, 80)}${ok.words.length > 80 ? '…' : ''}"`);
  }
}

const cmd = process.argv[2];
try {
  if (cmd === 'record') await cmdRecord();
  else if (cmd === 'check') cmdCheck(process.argv[4] || process.argv[3]);
  else if (cmd === 'verify') process.exit(cmdVerify());
  else if (cmd === '--selftest-append') { append({ kind: 'TEST', n: Number(arg('n')) }); }
  else { console.error('usage: push-approval.mjs record|check|verify'); process.exit(2); }
} catch (e) {
  console.error(`\nBLOCKED (Sangam push gate): ${e.message}\n`);
  process.exit(1);
}
