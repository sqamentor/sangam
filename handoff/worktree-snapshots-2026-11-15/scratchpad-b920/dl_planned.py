import sys, datetime
path = sys.argv[1]
ts = sys.argv[2]
raw = open(path, 'rb').read()
nl = b'\r\n' if b'\r\n' in raw else b'\n'
W = 'W5-B/W4-J (M-117 vite 8, decisions 106 and 111)'
REG = 'https://registry.npmjs.org/'
rows = [
 ('RESULT', 'RESULT read-only lookups already made for planning, no install: npm view (vite, @vitejs/plugin-react, vitest, rolldown, @rolldown/pluginutils, lightningcss, @oxc-project/types, detect-libc metadata and publish times), vite.dev/guide/migration and rolldown.rs code-splitting pages; npm install --package-lock-only in the session scratchpad (metadata only, no tarballs)', REG + ' ; https://vite.dev/guide/migration ; https://rolldown.rs', '-'),
 ('PLANNED', 'PLANNED npm install --ignore-scripts --no-audit --no-fund in sangam/sangam-ui, no terms, no login: vite 8.3.5 (MIT, published 2026-10-21) devDependency, replaces vite 6.4.5; integrity sha512-z1YpjieJ9g0ebTmQpLBv/oq9E3F9EIlOe+7LahW8SrE3LTiG3udBRUXQOlYXP9b+IC2q2phHeVxj9xGlw34OEQ==', REG + 'vite/-/vite-8.3.5.tgz', '-'),
 ('PLANNED', 'PLANNED same install: @vitejs/plugin-react 6.1.2 (MIT, published 2026-10-05, peer vite ^8, no babel) replaces 4.7.0; integrity sha512-fhJNGUdscQa4HkI0xFxwonWyhnVReCPDbXI7FH7jmzpnVCrFQI7PRrhWecO263610vOrs9tr2Y4xcf8uAdFxbw==', REG + '@vitejs/plugin-react/-/plugin-react-6.1.2.tgz', '-'),
 ('PLANNED', 'PLANNED same install: rolldown 1.2.15 (MIT, published 2026-10-21, the release paired with vite 8.3.5; pinned by package.json overrides because ~1.2.14 would float to 1.2.19 published 2026-11-11) bundler used by vite 8; integrity sha512-6odND+fel5zVw3I4Yc2e69gvguDnX0CAAGiNDZSq4ik5WUcaLrkD2GLuog0XcODbor31hZ5ayEMgVDno+KPV5g==', REG + 'rolldown/-/rolldown-1.2.15.tgz', '-'),
 ('PLANNED', 'PLANNED same install: @rolldown/binding-win32-x64-msvc 1.2.15 (MIT, optional native binary for this host, no install script) ; integrity sha512-HFZd2QNQYn1JbIi+Hpd7H+r/lkWxcfIu28nirZ6eCn2LafuvmExMlb/P96Sfx5tf7pPArw39IdhmCF8vmV+NhQ==', REG + '@rolldown/binding-win32-x64-msvc/-/binding-win32-x64-msvc-1.2.15.tgz', '-'),
 ('PLANNED', 'PLANNED same install: @rolldown/pluginutils 1.0.1 (MIT, published 2026-05-13; pinned by overrides instead of 1.1.0 published 2026-11-09) replaces 1.0.0-beta.27; integrity sha512-2j9bGt5Jh8hj+vPtgzPtl72j0yRxHAyumoo6TNfAjsLB04UtpSvPbPcDcBMxz7n+9CYB0c1GxQFxYRg2jimqGw==', REG + '@rolldown/pluginutils/-/pluginutils-1.0.1.tgz', '-'),
 ('PLANNED', 'PLANNED same install: @oxc-project/types 0.155.0 (MIT, exact dependency of rolldown 1.2.15, type definitions only) ; integrity sha512-SeE8JPwW1xs1tHeNycjUtUBdhorRa8o4jlvjwk49R7/Z291i+4DQa7JJIgOm/TaYQZx+QrPQbVJ/9c8dMIZN8Q==', REG + '@oxc-project/types/-/types-0.155.0.tgz', '-'),
 ('PLANNED', 'PLANNED same install: lightningcss 1.33.0 (MPL-2.0, weak file-level copyleft, on the list-licences allowlist, published 2026-07-20) CSS minifier, now the vite 8 default; used unmodified at build time only, not shipped in dist; integrity sha512-WkUDrojuJs0xkgGf2udWxa3yGBRxPtxUkB79i6aCZLRgc7PM8fZe9TosfPDcvEpQZbuFASnHYmRLBLUbmLOIIA==', REG + 'lightningcss/-/lightningcss-1.33.0.tgz', '-'),
 ('PLANNED', 'PLANNED same install: lightningcss-win32-x64-msvc 1.33.0 (MPL-2.0, optional native binary for this host, no install script) ; integrity sha512-OlEICDx/Xl0FqSp4bry8zFnCvGpig3Gl4gCquvYwHuqJKEC1+n9NgDniFvqHGmMv1ZkqDJrDqKKSykTDX+ehuA==', REG + 'lightningcss-win32-x64-msvc/-/lightningcss-win32-x64-msvc-1.33.0.tgz', '-'),
 ('PLANNED', 'PLANNED same install: detect-libc 2.1.2 (Apache-2.0, published 2025-10-05) dependency of lightningcss ; integrity sha512-Btj2BOOO83o3WyH59e8MgXsxEQVcarkUOpEYrubB0urwnN10yQ364rsiByU11nZlqWYZm05i/of7io4mzihBtQ==', REG + 'detect-libc/-/detect-libc-2.1.2.tgz', '-'),
 ('PLANNED', 'PLANNED lockfile entries only, not downloaded on this Windows x64 host (npm skips other os/cpu): @rolldown/binding-* 1.2.15 for android-arm-eabi, android-arm64, darwin-arm64, darwin-x64, freebsd-x64, linux-arm-gnueabihf, linux-arm64-gnu, linux-arm64-musl, linux-ppc64-gnu, linux-s390x-gnu, linux-x64-gnu, linux-x64-musl, openharmony-arm64, win32-arm64-msvc (all MIT) and lightningcss-* 1.33.0 for android-arm64, darwin-arm64, darwin-x64, freebsd-x64, linux-arm-gnueabihf, linux-arm64-gnu, linux-arm64-musl, linux-x64-gnu, linux-x64-musl, win32-arm64-msvc (all MPL-2.0); integrity recorded in package-lock.json', REG, '-'),
 ('PLANNED', 'PLANNED removed by the same install (no download): 94 lockfile entries, all of babel (@babel/*, @types/babel__*), esbuild 0.25.12 and @esbuild/*, rollup 4.68.0 and @rollup/*, browserslist, caniuse-lite, react-refresh 0.17.0 and helpers; no new package has an install script (hasInstallScript only on the unchanged @carbon/grid, @carbon/layout, fsevents, inferno, never run under --ignore-scripts)', '-', '-'),
]
out = b''
if not raw.endswith(nl):
    out += nl
for kind, what, src, size in rows:
    out += (' | '.join([ts, W, what, src, size, kind])).encode('utf-8') + nl
open(path, 'ab').write(out)
print('appended', len(rows))
