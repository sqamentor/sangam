#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
/**
 * Sangam's own push-approval gate (owner decision SD-432 "Gate Sangam like IFMS", 2026-11-08; hardened SG-34).
 * Sangam-owned on purpose: no other project's governance code or ledger is used (project boundaries).
 *
 *   text    --sha <full sha> --remote <url> --ref <refs/heads/x> --words "<owner's words>" [--ttl-minutes N<=30]
 *           → prints the canonical v2 approval text (binds sha, remote, ref and an absolute expiry)
 *   record  --signed <text file> --sig <text file>.sig --conversation "<session ref>"
 *           → appends one APPROVAL record that STORES the signed text and the signature
 *   check   (called by .githooks/pre-push with git's pre-push stdin)
 *   verify  → checks the whole chain and the off-repo mirror
 *
 * TRUST MODEL (SG-34). The ledger is only a single-use / audit log; it is never trusted on its own.
 *  1. At push time `check` re-verifies every candidate approval: the stored signed text is re-parsed, must bind
 *     exactly the sha, remote URL and ref being pushed and be unexpired, and its stored signature is re-verified
 *     with `ssh-keygen -Y verify` (namespace sangam-push). A record without a valid signature never authorises
 *     a push, whatever flags it carries; a forged record with recomputed chain hashes therefore authorises nothing.
 *  2. Trust root (allowed signers), strictest first, never taken from the environment or the working tree:
 *       a. the owner-held file <user profile>/.ssh/sangam_allowed_signers (profile from the OS account record,
 *          not from %USERPROFILE%/$HOME) — preferred whenever it exists;
 *       b. otherwise `.githooks/allowed_signers` as committed in the REMOTE's current commit of the ref being
 *          updated (the remote sha git reports on pre-push stdin), so a push can never introduce its own signer.
 *     New refs (remote sha all zeros) and remotes whose commit carries no signer need (a).
 *  3. The working-tree `.githooks/allowed_signers` must list exactly the trusted signers; a difference is reported
 *     and the push refused (an edited working tree is never a way to add a key).
 *
 * Ledger: <git-dir>/sangam-push-approvals.jsonl (per clone, never committed), hash-chained; every append runs
 * under an exclusive lock (atomic mkdir, backoff+jitter, stale lock broken only when its pid is dead AND it is
 * older than 60 s), re-verifies inside the lock, one write + fsync, re-verifies, mirrors, then releases.
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, existsSync, fsyncSync, mkdirSync, mkdtempSync, openSync, readFileSync, rmSync, statSync,
         writeFileSync, writeSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { homedir, tmpdir, userInfo } from 'node:os';
import { fileURLToPath } from 'node:url';

const NAMESPACE = 'sangam-push';
const MAX_TTL_MS = 30 * 60 * 1000;
const ZERO = /^0{40}$/;

const canon = (o) => JSON.stringify(o, Object.keys(o).sort());
const sha256 = (s) => createHash('sha256').update(s).digest('hex');
const hashOf = (rec) => { const { recordSha256, ...rest } = rec; return sha256(canon(rest)); };
const git = (args, opts = {}) => execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...opts });

/**
 * ssh-keygen resolution. On Windows the fixed system path of Windows OpenSSH comes first: Git Bash's MSYS
 * ssh-keygen (first on PATH inside Git Bash, i.e. inside the pre-push hook) dies with 0xC0000142 when spawned
 * from node. Elsewhere, or when Windows OpenSSH is absent, the PATH one.
 */
export function sshKeygen() {
  if (process.platform === 'win32') {
    const sys = 'C:\\Windows\\System32\\OpenSSH\\ssh-keygen.exe';
    if (existsSync(sys)) return sys;
  }
  return 'ssh-keygen';
}

/** Owner-held trust root: profile directory from the OS account record, not from environment variables. */
export function defaultOwnerSignersPath() {
  let home;
  try { home = userInfo().homedir; } catch { home = homedir(); }
  return join(home, '.ssh', 'sangam_allowed_signers');
}

function context(opts = {}) {
  const top = resolve(git(['rev-parse', '--show-toplevel']).trim());
  const ledger = join(resolve(git(['rev-parse', '--git-dir']).trim()), 'sangam-push-approvals.jsonl');
  const mirror = opts.mirrorPath
      || join(process.env.LOCALAPPDATA || join(homedir(), '.local', 'share'), 'sangam-governance',
              sha256(top.toLowerCase()).slice(0, 16), 'push-approvals.mirror.jsonl');
  return { top, ledger, lock: ledger + '.lock', mirror,
           ownerSigners: opts.ownerSignersPath || defaultOwnerSignersPath() };
}

function readChain(ctx) {
  if (!existsSync(ctx.ledger)) return [];
  return readFileSync(ctx.ledger, 'utf8').split('\n').filter(Boolean).map((l, i) => {
    try { return JSON.parse(l); } catch { return { __malformed: true, __line: i + 1 }; }
  });
}

export function verifyChain(chain) {
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
export { hashOf };

function pidAlive(pid) {
  try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; }
}
function sleep(ms) { Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms); }

function withLock(ctx, fn) {
  const deadline = Date.now() + 30_000;
  let wait = 10;
  for (;;) {
    try {
      mkdirSync(ctx.lock);
      writeFileSync(join(ctx.lock, 'owner.json'), JSON.stringify({ pid: process.pid, at: new Date().toISOString() }));
      break;
    } catch (e) {
      if (e.code !== 'EEXIST') throw e;
      try {
        const o = JSON.parse(readFileSync(join(ctx.lock, 'owner.json'), 'utf8'));
        const age = Date.now() - statSync(ctx.lock).mtimeMs;
        if (!pidAlive(o.pid) && age > 60_000) {
          console.error(`  breaking stale push-ledger lock (pid ${o.pid} dead, ${Math.round(age / 1000)} s old)`);
          rmSync(ctx.lock, { recursive: true, force: true });
          continue;
        }
      } catch { /* owner file not written yet: treat as live */ }
      if (Date.now() > deadline) throw new Error(`push-ledger lock ${ctx.lock} still held after 30 s; refusing`);
      sleep(wait + Math.floor(Math.random() * wait));
      wait = Math.min(wait * 2, 1000);
    }
  }
  try { return fn(); } finally { rmSync(ctx.lock, { recursive: true, force: true }); }
}

function append(ctx, entry) {
  return withLock(ctx, () => {
    const chain = readChain(ctx);
    const v = verifyChain(chain);
    if (!v.ok) throw new Error('push-approval ledger does not verify; refusing to append:\n  ' + v.issues.join('\n  '));
    const prev = chain.length ? chain[chain.length - 1].recordSha256 : null;
    const rec = { ...entry, index: chain.length, prevSha256: prev, at: new Date().toISOString() };
    rec.recordSha256 = hashOf(rec);
    const line = JSON.stringify(rec) + '\n';
    const fd = openSync(ctx.ledger, 'a');
    try { writeSync(fd, line); fsyncSync(fd); } finally { closeSync(fd); }
    const after = verifyChain(readChain(ctx));
    if (!after.ok || after.length !== chain.length + 1) {
      throw new Error('push-approval ledger failed verification right after append: ' + after.issues.join('; '));
    }
    mkdirSync(dirname(ctx.mirror), { recursive: true });
    const mfd = openSync(ctx.mirror, 'a');
    try { writeSync(mfd, line); fsyncSync(mfd); } finally { closeSync(mfd); }
    return rec;
  });
}

// ── canonical approval text (v2 binds an absolute expiry) ──
export function approvalText({ sha, remote, ref, expires, words }) {
  return `sangam-push-approval v2\nsha=${sha}\nremote=${remote}\nref=${ref}\nexpires=${expires}\n`
      + `words=${String(words).replace(/[\r\n]+/g, ' ')}\n`;
}
export function parseApprovalText(text) {
  const m = /^sangam-push-approval v2\nsha=([0-9a-f]{40})\nremote=([^\n]+)\nref=([^\n]+)\nexpires=(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z)\nwords=([^\n]*)\n$/
      .exec(text);
  if (!m) throw new Error('signed text is not a canonical sangam-push-approval v2 text');
  return { sha: m[1], remote: m[2], ref: m[3], expires: m[4], words: m[5] };
}

// ── trust root ──
const signerLines = (text) => text.replace(/\r/g, '').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
const sameSigners = (a, b) => canon([...signerLines(a)].sort()) === canon([...signerLines(b)].sort());

/** Returns { text, source } of the trusted allowed_signers for updating `remoteSha`, or throws. */
function trustRoot(ctx, remoteSha) {
  if (existsSync(ctx.ownerSigners)) {
    const text = readFileSync(ctx.ownerSigners, 'utf8');
    if (!signerLines(text).length) throw new Error(`owner-held ${ctx.ownerSigners} lists no signer`);
    return { text, source: `owner-held ${ctx.ownerSigners}` };
  }
  const how = `create the owner-held trust root ${ctx.ownerSigners} (see .githooks/allowed_signers)`;
  if (!remoteSha || ZERO.test(remoteSha)) {
    throw new Error(`no owner-held trust root and the remote ref does not exist yet; ${how}`);
  }
  let text;
  try { text = git(['show', `${remoteSha}:.githooks/allowed_signers`]); }
  catch { throw new Error(`remote commit ${remoteSha.slice(0, 12)} carries no .githooks/allowed_signers (or is not fetched); ${how}`); }
  if (!signerLines(text).length) throw new Error(`remote commit ${remoteSha.slice(0, 12)} lists no signer; ${how}`);
  return { text, source: `.githooks/allowed_signers @ remote ${remoteSha.slice(0, 12)}` };
}

function assertWorkingTreeMatches(ctx, trust) {
  const f = join(ctx.top, '.githooks', 'allowed_signers');
  const wt = existsSync(f) ? readFileSync(f, 'utf8') : '';
  if (!sameSigners(wt, trust.text)) {
    throw new Error(`working-tree .githooks/allowed_signers differs from the trusted signers (${trust.source}); `
        + 'a key is only trusted through the owner-held file or a commit already on the remote');
  }
}

/** Verifies `signature` over `text` against the trusted signers text; returns the principal or null. */
export function verifySignature(text, signature, signersText) {
  const dir = mkdtempSync(join(tmpdir(), 'sangam-verify-'));
  try {
    const sf = join(dir, 'allowed_signers'), sig = join(dir, 'approval.sig');
    writeFileSync(sf, signerLines(signersText).join('\n') + '\n');
    writeFileSync(sig, String(signature).replace(/\r/g, ''));
    for (const id of new Set(signerLines(signersText).map((l) => l.split(/\s+/)[0]))) {
      try {
        execFileSync(sshKeygen(), ['-Y', 'verify', '-f', sf, '-I', id, '-n', NAMESPACE, '-s', sig],
            { input: Buffer.from(text, 'utf8'), stdio: ['pipe', 'ignore', 'ignore'] });
        return id;
      } catch { /* next principal */ }
    }
    return null;
  } finally { rmSync(dir, { recursive: true, force: true }); }
}

// ── commands ──
function makeArg(argv) {
  return (name) => { const i = argv.indexOf('--' + name); return i >= 0 ? argv[i + 1] : undefined; };
}

function cmdText(arg) {
  const sha = arg('sha'), remote = arg('remote'), ref = arg('ref'), words = arg('words');
  const ttl = Number(arg('ttl-minutes') ?? 30);
  if (!/^[0-9a-f]{40}$/.test(sha || '') || !remote || !ref || !words) {
    throw new Error('text needs --sha (40 hex) --remote --ref --words');
  }
  if (!(ttl > 0 && ttl * 60000 <= MAX_TTL_MS)) throw new Error(`--ttl-minutes must be 1..${MAX_TTL_MS / 60000}`);
  process.stdout.write(approvalText({ sha, remote, ref, words,
      expires: new Date(Date.now() + ttl * 60000).toISOString() }));
}

function cmdRecord(ctx, arg) {
  const signedFile = arg('signed'), sigFile = arg('sig'), conv = arg('conversation');
  if (!signedFile || !sigFile || !conv) {
    throw new Error('record needs --signed <text> --sig <text>.sig --conversation <id>; '
        + 'unsigned approvals never authorise a push and are not recorded');
  }
  const text = readFileSync(signedFile, 'utf8').replace(/\r\n/g, '\n');
  const signature = readFileSync(sigFile, 'utf8');
  const a = parseApprovalText(text);
  if (Date.parse(a.expires) <= Date.now()) throw new Error(`approval expired at ${a.expires}`);
  // Early feedback only (the authoritative verification happens again in `check`): the remote's current sha
  // is not known yet, so the local tracking ref of the target stands in for it here.
  let remoteSha = null;
  try {
    const name = git(['remote']).split('\n').map((s) => s.trim()).filter(Boolean)
        .find((n) => git(['remote', 'get-url', n]).trim() === a.remote);
    if (name) remoteSha = git(['rev-parse', '--verify', '-q', `refs/remotes/${name}/${a.ref.replace(/^refs\/heads\//, '')}`]).trim();
  } catch { /* unknown: owner-held root required */ }
  const trust = trustRoot(ctx, remoteSha);
  const signer = verifySignature(text, signature, trust.text);
  if (!signer) throw new Error(`signature ${sigFile} does not verify against ${trust.source} (namespace ${NAMESPACE})`);
  const rec = append(ctx, { kind: 'APPROVAL', ...a, conversation: conv, source: 'ssh-signature', signer,
      signedText: text, signature, approvalTextSha256: sha256(text) });
  console.log(`  SIGNED APPROVAL recorded #${rec.index} by ${signer} for ${a.sha.slice(0, 12)} → ${a.ref} on ${a.remote} `
      + `(expires ${a.expires}; re-verified at push time)`);
}

function cmdVerify(ctx) {
  const v = verifyChain(readChain(ctx));
  if (!v.ok) { console.log('  BROKEN:\n  ' + v.issues.join('\n  ')); return 1; }
  const ledger = existsSync(ctx.ledger) ? readFileSync(ctx.ledger, 'utf8') : '';
  const mirror = existsSync(ctx.mirror) ? readFileSync(ctx.mirror, 'utf8') : '';
  if (!mirror.startsWith(ledger)) {
    console.log(`  MIRROR MISMATCH: ${ctx.mirror} does not contain the ledger as a prefix (ledger rewritten or mirror lost)`);
    return 1;
  }
  console.log(`  push-approval ledger OK (${v.length} records); mirror OK (${ctx.mirror})`);
  return 0;
}

function isAncestor(a, b) {
  try { execFileSync('git', ['merge-base', '--is-ancestor', a, b], { stdio: 'ignore' }); return true; }
  catch { return false; }
}

function cmdCheck(ctx, remoteUrl, stdinText) {
  if (!remoteUrl) throw new Error('check needs the remote URL (pre-push $2)');
  for (const line of stdinText.split('\n').map((l) => l.trim()).filter(Boolean)) {
    const [, localSha, remoteRef, remoteSha] = line.split(/\s+/);
    if (ZERO.test(localSha)) throw new Error(`deleting ${remoteRef} is not allowed through this gate`);
    if (!ZERO.test(remoteSha) && !isAncestor(remoteSha, localSha)) {
      throw new Error(`${remoteRef}: not a fast-forward (${remoteSha.slice(0, 12)} is not an ancestor of ${localSha.slice(0, 12)}); force pushes are refused`);
    }
    const trust = trustRoot(ctx, remoteSha);
    assertWorkingTreeMatches(ctx, trust);
    const chain = readChain(ctx);
    const v = verifyChain(chain);
    if (!v.ok) throw new Error('push-approval ledger does not verify:\n  ' + v.issues.join('\n  '));
    const consumed = new Set(chain.filter((r) => r.kind === 'CONSUMED').map((r) => r.approvalIndex));
    const now = Date.now();
    let ok = null, signer = null;
    for (const r of [...chain].reverse()) {
      if (r.kind !== 'APPROVAL' || consumed.has(r.index) || typeof r.signedText !== 'string' || !r.signature) continue;
      let a;
      try { a = parseApprovalText(r.signedText); } catch { continue; }
      if (a.sha !== localSha || a.ref !== remoteRef || a.remote !== remoteUrl) continue;
      if (!(Date.parse(a.expires) > now)) continue;
      if (r.sha !== a.sha || r.ref !== a.ref || r.remote !== a.remote) continue; // record must not misstate its text
      signer = verifySignature(r.signedText, r.signature, trust.text);
      if (signer) { ok = { r, a }; break; }
    }
    if (!ok) {
      throw new Error(`no unconsumed, unexpired approval with a valid owner signature (${trust.source}) for `
          + `${localSha} → ${remoteRef} on ${remoteUrl}.\n  Owner: push-approval.mjs text … | ssh-keygen -Y sign -n ${NAMESPACE}; `
          + 'then push-approval.mjs record --signed <txt> --sig <txt>.sig --conversation <id>');
    }
    append(ctx, { kind: 'CONSUMED', approvalIndex: ok.r.index, approvalTextSha256: sha256(ok.r.signedText),
        sha: localSha, ref: remoteRef, remote: remoteUrl, signer, trust: trust.source });
    console.log(`  push approved by record #${ok.r.index}, signed by ${signer} (${trust.source}): `
        + `"${ok.a.words.slice(0, 80)}${ok.a.words.length > 80 ? '…' : ''}"`);
  }
}

/** Entry point. `opts` exists for the hermetic self-test only; nothing here reads trust from the environment. */
export function main(argv, opts = {}) {
  const arg = makeArg(argv);
  const cmd = argv[0];
  try {
    if (cmd === 'text') { cmdText(arg); return 0; }
    const ctx = context(opts);
    if (cmd === 'record') { cmdRecord(ctx, arg); return 0; }
    if (cmd === 'check') { cmdCheck(ctx, argv[2] || argv[1], opts.stdin ?? readFileSync(0, 'utf8')); return 0; }
    if (cmd === 'verify') return cmdVerify(ctx);
    if (cmd === '--selftest-append') { append(ctx, { kind: 'TEST', n: Number(arg('n')) }); return 0; }
    console.error('usage: push-approval.mjs text|record|check|verify');
    return 2;
  } catch (e) {
    console.error(`\nBLOCKED (Sangam push gate): ${e.message}\n`);
    return 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)));
}
