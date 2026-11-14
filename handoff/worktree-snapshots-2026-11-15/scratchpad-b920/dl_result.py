import sys, json, os
path, ts, lock, nm, lockhash, pkghash = sys.argv[1:7]
raw = open(path, 'rb').read()
nl = b'\r\n' if b'\r\n' in raw else b'\n'
W = 'W5-B/W4-J (M-117 vite 8, RESULT)'
pk = json.load(open(lock, encoding='utf-8'))['packages']
names = ['vite', '@vitejs/plugin-react', 'rolldown', '@rolldown/binding-win32-x64-msvc', '@rolldown/pluginutils',
         '@oxc-project/types', 'lightningcss', 'lightningcss-win32-x64-msvc', 'detect-libc']
parts = []
for n in names:
    e = pk['node_modules/' + n]
    inst = json.load(open(os.path.join(nm, *n.split('/'), 'package.json'), encoding='utf-8'))
    assert inst['version'] == e['version'], n
    assert not e.get('hasInstallScript'), n
    parts.append(f"{n}@{e['version']} ({e.get('license')}) {e['integrity']}")
rows = [
    ('RESULT', 'RESULT npm install --ignore-scripts --no-audit --no-fund in sangam/sangam-ui exit 0 (added 6, removed 45, changed 3 installed packages on this host; npm verified each tarball against the lockfile integrity); no terms accepted, no login, no install script run, none needed by the new packages (no hasInstallScript among them). package-lock.json SHA-256 ' + lockhash + ' is byte for byte the one resolved beforehand in the scratchpad; package.json SHA-256 ' + pkghash + '. Installed and verified: ' + ' ; '.join(parts), 'https://registry.npmjs.org/', '-'),
    ('RESULT', 'RESULT comparison only, no network: vite 6.4.5 and @vitejs/plugin-react 4.7.0 installed with npm install --offline --ignore-scripts from the local npm cache into a session scratchpad folder, used once to build the embed bundle with the old config for comparison; not part of sangam-ui', 'local npm cache (offline)', '-'),
]
out = b''
if not raw.endswith(nl):
    out += nl
for kind, what, src, size in rows:
    out += ' | '.join([ts, W, what, src, size, kind]).encode('utf-8') + nl
open(path, 'ab').write(out)
print('appended', len(rows))
