// Adds @microsoft/teams-js 2.59.0 (decision 162) to sangam-ui package.json and package-lock.json from the recorded
// tarballs: integrity computed from the tarball bytes and checked against the DOWNLOADS-WAVE4.md record.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');

const [ui, tarDir] = process.argv.slice(2);
const sri = (f) => 'sha512-' + crypto.createHash('sha512').update(fs.readFileSync(path.join(tarDir, f))).digest('base64');
const manifest = (f) => JSON.parse(execFileSync('tar', ['-xOf', path.join(tarDir, f), 'package/package.json'], { encoding: 'utf8' }));

const RECORDED_TEAMS = 'sha512-XoJkSWWcLE/HwunD+eFm8jq7Uv3AF2XCrjbb3e8VD2FRkoMs0F+AJHdTCJ+cjBQj0TZhqggRlAIFroH40y/zbQ==';
const teamsSri = sri('microsoft-teams-js-2.59.0.tgz');
if (teamsSri !== RECORDED_TEAMS) throw new Error('teams-js integrity differs from the record: ' + teamsSri);
const b64 = manifest('base64-js-1.5.1.tgz');
const teams = manifest('microsoft-teams-js-2.59.0.tgz');

const lockPath = path.join(ui, 'package-lock.json');
const lock = JSON.parse(fs.readFileSync(lockPath, 'utf8'));
const pkgs = lock.packages;
for (const [name, ver, f] of [['debug', '4.4.3', 'debug-4.4.3.tgz'], ['ms', '2.1.3', 'ms-2.1.3.tgz']]) {
  const e = pkgs[`node_modules/${name}`];
  if (!e || e.version !== ver) throw new Error(`${name} ${ver} not in the lockfile`);
  if (e.integrity !== sri(f)) throw new Error(`${name} integrity differs from the recorded tarball`);
  delete e.dev; // now a production dependency through teams-js
}
const added = {
  'node_modules/@microsoft/teams-js': {
    version: teams.version,
    resolved: 'https://registry.npmjs.org/@microsoft/teams-js/-/teams-js-2.59.0.tgz',
    integrity: teamsSri,
    license: teams.license,
    dependencies: teams.dependencies,
  },
  'node_modules/base64-js': {
    version: b64.version,
    resolved: 'https://registry.npmjs.org/base64-js/-/base64-js-1.5.1.tgz',
    integrity: sri('base64-js-1.5.1.tgz'),
    ...(b64.funding ? { funding: b64.funding } : {}),
    license: b64.license,
  },
};
for (const k of Object.keys(added)) if (pkgs[k]) throw new Error(k + ' already present');
const out = {};
const keys = Object.keys(pkgs);
const pending = Object.keys(added).sort((a, b) => a.localeCompare(b, 'en'));
for (const k of keys) {
  while (k !== '' && pending.length && pending[0].localeCompare(k, 'en') < 0) {
    const n = pending.shift();
    out[n] = added[n];
  }
  out[k] = pkgs[k];
}
for (const n of pending) out[n] = added[n];
lock.packages = out;
const root = lock.packages[''];
root.dependencies = Object.fromEntries(Object.entries({ ...root.dependencies, '@microsoft/teams-js': '2.59.0' }).sort(([a], [b]) => a.localeCompare(b, 'en')));
fs.writeFileSync(lockPath, JSON.stringify(lock, null, 2) + '\n');

const pjPath = path.join(ui, 'package.json');
const pj = JSON.parse(fs.readFileSync(pjPath, 'utf8'));
pj.dependencies = Object.fromEntries(Object.entries({ ...pj.dependencies, '@microsoft/teams-js': '2.59.0' }).sort(([a], [b]) => a.localeCompare(b, 'en')));
fs.writeFileSync(pjPath, JSON.stringify(pj, null, 2) + '\n');
console.log('ok', teamsSri, added['node_modules/base64-js'].integrity);
