// Trial 2 SD-428 second check of Sangam signed approvals (SG-32) at 35996f9. LOCAL ONLY, THROWAWAY keys, synthetic text.
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, rmSync, writeFileSync, readFileSync, appendFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const SP = process.argv[2];
const SG = join(SP, 'sg');
const K = join(SP, 'keys'); const BARE = join(SP, 'bare-signed.git'); const MIR = join(SP, 'mirror-signed');
const G = join(SG, 'tools', 'governance', 'push-approval.mjs');
const git = (...a) => execFileSync('git', a, { cwd: SG, encoding: 'utf8' }).trim();
const head = git('rev-parse', 'HEAD');
if (head !== '35996f975ec70bdaa6bc592aa0765ac5e2220a96') { console.log('WRONG_SHA', head); process.exit(9); }
for (const d of [K, BARE, MIR]) rmSync(d, { recursive: true, force: true });
mkdirSync(K); rmSync(join(git('rev-parse', '--absolute-git-dir'), 'sangam-push-approvals.jsonl'), { force: true });
const env = { ...process.env, SANGAM_PUSH_MIRROR_DIR: MIR };
delete env.SANGAM_ALLOWED_SIGNERS;
const run = (args, e = env, input) => spawnSync('node', [G, ...args], { cwd: SG, env: e, encoding: 'utf8', input });
const res = (id, ok, note) => console.log(`RESULT ${id}: ${ok} ${note || ''}`);
const keygen = (n) => { execFileSync('ssh-keygen', ['-q', '-t', 'ed25519', '-N', '', '-C', n, '-f', join(K, n)]); return readFileSync(join(K, n + '.pub'), 'utf8').trim(); };
const sign = (key, file) => execFileSync('ssh-keygen', ['-Y', 'sign', '-f', join(K, key), '-n', 'sangam-push', file], { stdio: 'ignore' });
const owner = keygen('throwaway-owner'); const foreign = keygen('throwaway-foreign');
const signersPath = join(SG, '.githooks', 'allowed_signers');
const orig = readFileSync(signersPath, 'utf8');
writeFileSync(signersPath, orig + `t2-owner namespaces="sangam-push" ${owner}\n`);
execFileSync('git', ['init', '-q', '--bare', BARE]);
try { git('remote', 'remove', 'sbare'); } catch {}
git('remote', 'add', 'sbare', BARE); git('config', 'core.hooksPath', '.githooks');
const text = (sha, ref, words) => run(['text', '--sha', sha, '--remote', BARE, '--ref', ref, '--words', words]).stdout;
const push = (ref) => spawnSync('git', ['push', '-q', 'sbare', `HEAD:${ref}`], { cwd: SG, env, encoding: 'utf8' });
const remoteHas = (ref) => spawnSync('git', ['--git-dir', BARE, 'rev-parse', '-q', '--verify', ref]).status === 0;

// S1 valid signature accepted
const a = join(SP, 'a.txt'); writeFileSync(a, text(head, 'refs/heads/main', 'SYNTHETIC SIGNED APPROVAL ONE')); sign('throwaway-owner', a);
let r = run(['record', '--signed', a, '--sig', a + '.sig', '--conversation', 't2-synthetic']);
res('S1-record', r.status === 0 ? 'accepted' : 'REFUSED', r.stderr.trim().split('\n').pop());
let p = push('refs/heads/main'); res('S1-push', remoteHas('refs/heads/main') ? 'passed' : 'BLOCKED', p.stderr.trim().split('\n').pop());
// S2 unsigned refused once signers listed
r = run(['record', '--sha', head, '--remote', BARE, '--ref', 'refs/heads/s2', '--words', 'SYNTHETIC UNSIGNED', '--conversation', 't2', '--source', 'user-message']);
res('S2', r.status !== 0 ? 'refused' : 'ACCEPTED (gap)');
// S3 foreign key refused
const b = join(SP, 'b.txt'); writeFileSync(b, text(head, 'refs/heads/s3', 'SYNTHETIC FOREIGN')); sign('throwaway-foreign', b);
r = run(['record', '--signed', b, '--sig', b + '.sig', '--conversation', 't2']); res('S3', r.status !== 0 ? 'refused' : 'ACCEPTED (gap)');
// S4 text altered after signing
const c = join(SP, 'c.txt'); writeFileSync(c, text(head, 'refs/heads/s4', 'SYNTHETIC ORIGINAL')); sign('throwaway-owner', c);
writeFileSync(c, readFileSync(c, 'utf8').replace('ORIGINAL', 'ALTERED!'));
r = run(['record', '--signed', c, '--sig', c + '.sig', '--conversation', 't2']); res('S4', r.status !== 0 ? 'refused' : 'ACCEPTED (gap)');
// S5 signature reused for another sha / ref
const d = join(SP, 'd.txt'); writeFileSync(d, text(head, 'refs/heads/other-ref', 'SYNTHETIC SIGNED APPROVAL ONE'));
r = run(['record', '--signed', d, '--sig', a + '.sig', '--conversation', 't2']); res('S5-ref', r.status !== 0 ? 'refused' : 'ACCEPTED (gap)');
const e2 = join(SP, 'e.txt'); writeFileSync(e2, text('0123456789abcdef0123456789abcdef01234567', 'refs/heads/main', 'SYNTHETIC SIGNED APPROVAL ONE'));
r = run(['record', '--signed', e2, '--sig', a + '.sig', '--conversation', 't2']); res('S5-sha', r.status !== 0 ? 'refused' : 'ACCEPTED (gap)');
// S6 (extra) forged ledger record: unkeyed hash chain recomputed; signatureVerified flag set without a signature
const L = join(git('rev-parse', '--absolute-git-dir'), 'sangam-push-approvals.jsonl');
const lines = readFileSync(L, 'utf8').split('\n').filter(Boolean).map(JSON.parse);
const canon = (o) => JSON.stringify(o, Object.keys(o).sort());
const h = (o) => { const { recordSha256, ...rest } = o; return createHash('sha256').update(canon(rest)).digest('hex'); };
const forged = { kind: 'APPROVAL', sha: head, remote: BARE, ref: 'refs/heads/forged', words: 'SYNTHETIC FORGED', conversation: 't2', source: 'ssh-signature', signer: 't2-owner', ttyVerified: false, signatureVerified: true, index: lines.length, prevSha256: lines[lines.length - 1].recordSha256, at: new Date().toISOString() };
forged.recordSha256 = h(forged); const line = JSON.stringify(forged) + '\n';
appendFileSync(L, line); appendFileSync(join(MIR, 'mirror.jsonl'), line);
p = push('refs/heads/forged'); res('S6-forged-record', remoteHas('refs/heads/forged') ? 'PUSHED (gap: check trusts the stored flag, no re-verification)' : 'blocked');
// S7 (extra) env override of the signers file
const evil = join(K, 'evil_signers'); writeFileSync(evil, `t2-foreign namespaces="sangam-push" ${foreign}\n`);
const f = join(SP, 'f.txt'); writeFileSync(f, text(head, 'refs/heads/s7', 'SYNTHETIC ENV OVERRIDE')); sign('throwaway-foreign', f);
r = run(['record', '--signed', f, '--sig', f + '.sig', '--conversation', 't2'], { ...env, SANGAM_ALLOWED_SIGNERS: evil });
const p7 = spawnSync('git', ['push', '-q', 'sbare', 'HEAD:refs/heads/s7'], { cwd: SG, env: { ...env, SANGAM_ALLOWED_SIGNERS: evil }, encoding: 'utf8' });
res('S7-env-signers', remoteHas('refs/heads/s7') ? 'PUSHED (gap: SANGAM_ALLOWED_SIGNERS env selects the trust root)' : 'blocked', `record exit ${r.status}`);
// S8 (extra) tree-resident allowed_signers edited to add a foreign key
writeFileSync(signersPath, orig + `t2-owner namespaces="sangam-push" ${owner}\nt2-intruder namespaces="sangam-push" ${foreign}\n`);
const g = join(SP, 'g.txt'); writeFileSync(g, text(head, 'refs/heads/s8', 'SYNTHETIC INTRUDER')); sign('throwaway-foreign', g);
r = run(['record', '--signed', g, '--sig', g + '.sig', '--conversation', 't2']); push('refs/heads/s8');
res('S8-edited-signers', remoteHas('refs/heads/s8') ? 'PUSHED (gap: trust root is an editable working-tree file)' : 'blocked', `record exit ${r.status}`);
// clean up scratch config
writeFileSync(signersPath, orig); git('config', '--unset', 'core.hooksPath'); git('remote', 'remove', 'sbare');
console.log('verify:', run(['verify']).stdout.trim());
