import sys
board, claims, ts = sys.argv[1:4]
H = dict(a.split('=', 1) for a in sys.argv[4:])
L = 'W4-J/B6A as W5-B'

req = ('M-117 licence and deploy follow-ups for vite 8 (sangam-ui lockfile ' + H['lock'][:8] + '), your files, please take what is yours: '
       '(1) licence inventory and decision-register rows for the new npm packages: vite 8.3.5 MIT, @vitejs/plugin-react 6.1.2 MIT, rolldown 1.2.15 MIT and its @rolldown/binding-* 1.2.15 platform binaries MIT, '
       '@rolldown/pluginutils 1.0.1 MIT, @oxc-project/types 0.155.0 MIT, lightningcss 1.33.0 MPL-2.0 and its lightningcss-* platform binaries MPL-2.0 (build-time CSS minifier, unmodified, not shipped in dist), detect-libc 2.1.2 Apache-2.0; '
       'and removal of the rows for the packages that left the tree (@babel/core and its plugins, esbuild 0.25.12 and @esbuild/*, rollup 4.68.0 and @rollup/*, browserslist, caniuse-lite, react-refresh). '
       '(2) MIT-0 is in licence-policy.json but not in the allow list of sangam-ui/scripts/list-licences.mjs, so npm run licences exits 1 on @csstools/color-helpers 5.1.0 (jsdom test dependency, present before M-117). Please rule whether that script follows policy (add MIT-0) and who edits it. '
       '(3) npm rebuild esbuild is now obsolete because esbuild is no longer installed (npm rebuild with no matching node is a no-op, exit 0, checked against the npm 11.1.0 source, so nothing breaks): deploy/docker/ui.Dockerfile lines 26-32, .github/workflows/ui.yml lines 5, 47 and 145, .github/workflows/release.yml lines 178-179, deploy/README.md lines 969 and 974. '
       'No package in the new tree needs an install script. The Linux musl binaries (@rolldown/binding-linux-x64-musl, lightningcss-linux-x64-musl) are optional lockfile entries that npm ci picks on alpine. '
       '(4) deploy/licence/VULNERABILITY-TRIAGE.md rows 17 and 19 still say vite 6.4.5 and plugin-react 4.7.0; airgap/generate-sbom.sh line 46 mentions esbuild. '
       'The lockfile was resolved without --legacy-peer-deps. npm audit was not run because it was not in the download plan.')

info = ('M-117 notes for the coordinator, evidence in the scratchpad build logs: '
        '(a) vite 8 raised the default build.target from the vite 6 modules set (chrome87, edge88, firefox78, safari14) to baseline-widely-available (chrome111, edge111, firefox114, safari16.4). No browser floor is written down anywhere in sangam, so I kept the vite 8 default rather than guess. If older government browsers must work, the owner should name a floor and I will set build.target. Below 97% on intent, so INFO. '
        '(b) The production build passes (9.5 s against 68 s on vite 6). The chunk set matches the baseline: Editors (bpmn-js 162, diagram-js 197 sources, form-js editor and viewer), dmn-embedded, engine, react, i18n, main. The source maps show the same node_modules file counts per package, and nothing is missing. '
        'The one difference is that the big shared app chunk that vite 6 named ui is now named preload-helper by rolldown (373 kB, same content). This is cosmetic and nothing references chunk names. '
        'The react package is now wholly in the react chunk; on vite 6 part of it sat in the i18n and ui chunks. '
        '(c) The embed library build passes on vite 8. Built side by side with vite 6.4.5 and the old config, it gives the same output files (sangam-elements.js plus ui.css, which vite 6 emitted too), the same custom element names and the same set of absolute URLs (namespace strings only). '
        '(d) TESTING-GAP-REGISTER row UI-UPGRADE-1 can move to CODED, but that is not my file. '
        '(e) PR #3 and the stale branch are untouched, as decision 111 requires. No commit, no push.')

done = ('M-117 vite 8 CODED, tests NOT_RUN (vitest not run): vite 6.4.5 to 8.3.5 and @vitejs/plugin-react 4.7.0 to 6.1.2, exact pins. '
        'React stays 18.3.1 and vitest 4.1.12 (its peer range includes vite 8). '
        'package.json overrides pin rolldown 1.2.15 and @rolldown/pluginutils 1.0.1. These are the releases paired with vite 8.3.5; otherwise the ranges float to builds published 2 to 4 days ago. '
        'Lockfile regenerated with npm install --ignore-scripts: 30 entries added, 94 removed (babel, esbuild, rollup, browserslist), 3 changed. No new package has an install script. Every new or changed package was licence-checked: MIT, Apache-2.0 and MPL-2.0, all OSI. '
        'DOWNLOADS-WAVE4.md has the PLANNED lines before the install and the RESULT lines with integrity after it. '
        'vite.config.ts: rollupOptions becomes rolldownOptions, and the object manualChunks becomes output.codeSplitting groups react (priority 20) and i18n (priority 10), with the same packages plus their runtime-only dependencies. vite.embed.config.ts: rollupOptions becomes rolldownOptions. '
        'README: the direct table now shows vite 8.3.5, plugin-react 6.1.2 and the rolldown pins, and the full licence table is regenerated (280 packages: 151 runtime, 129 build, dated 2026-11-13). '
        'Gates: tsc --noEmit exit 0; vite build (production, into the scratchpad) exit 0 with the bpmn-js, diagram-js, form-js and dmn-js chunks present; vite build --config vite.embed.config.ts exit 0; check-i18n exit 0; check-no-cdn exit 0. '
        'npm run licences exits 1 only on the pre-existing MIT-0 @csstools/color-helpers; a REQUEST for that went to W4-C. '
        'SHA-256: sangam/sangam-ui/package.json ' + H['pkg'] + ' ; sangam/sangam-ui/package-lock.json ' + H['lock'] + ' ; sangam/sangam-ui/vite.config.ts ' + H['cfg'] + ' ; sangam/sangam-ui/vite.embed.config.ts ' + H['embed'] + ' ; sangam/sangam-ui/README.md ' + H['readme'] + '. '
        'node_modules has been reinstalled. Claim released. PR #3 untouched.')

lines = [
    ' | '.join([ts, L, 'REQUEST', req, 'W4-C']),
    ' | '.join([ts, L, 'INFO', info, 'coordinator']),
    ' | '.join([ts, L, 'DONE', done, 'coordinator, W4-C']),
]
for l in lines:
    assert l.count(' | ') == 4 and l.count('|') == 4, l[:120]


def app(path, items):
    raw = open(path, 'rb').read()
    nl = b'\r\n' if b'\r\n' in raw else b'\n'
    out = b'' if raw.endswith(nl) else nl
    for x in items:
        out += x.encode('utf-8') + nl
    open(path, 'ab').write(out)


app(board, lines)
rel = (ts + ' W4-J/B6A as W5-B RELEASE M-117 vite 8 (claim 2026-11-13T07:18:08Z): sangam/sangam-ui/package.json ' + H['pkg'] + ' ; package-lock.json ' + H['lock']
       + ' ; vite.config.ts ' + H['cfg'] + ' ; vite.embed.config.ts ' + H['embed'] + ' ; README.md ' + H['readme'] + ' ; node_modules reinstalled with --ignore-scripts')
app(claims, [rel])
print('ok')
