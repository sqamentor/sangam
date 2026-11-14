# Sangam UI fonts

Owner decisions 120 and 121 (2026-11-12, C-05 and C-06, RM-24):
- The UI serves the fonts already approved for `sangam/deploy/fonts` (owner decisions 70 and 95): **Noto Sans** and
  **Noto Sans Devanagari**, Regular, SIL OFL 1.1, unmodified, each with its OFL text.
- `@fontsource/noto-sans` and `@fontsource/noto-sans-devanagari` are no longer dependencies.
- **Sakal Bharati is no longer bundled.** Hindi uses Noto Sans Devanagari.

Every file is served from this host (`font-src 'self'`), so the UI works air-gapped. A site may still add a state or
central-government font that it is licensed to use (§5).

`[U]` = not verified on an official page.

## 1. Where the bundled files come from

There is one copy of each font in the repository: `sangam/deploy/fonts`. Its `README.md` records the source release and
the SHA-256 of every file. The server image uses the same folder for the PDF renderer (M-081).

At build time the Vite plugin `scripts/deploy-fonts.mjs` copies the four files into the UI's public folder of the build:

| Built file | Source |
|---|---|
| `dist/fonts/noto/NotoSans-Regular.ttf` | `deploy/fonts/NotoSans-Regular.ttf` |
| `dist/fonts/noto/OFL-NotoSans.txt` | `deploy/fonts/OFL-NotoSans.txt` |
| `dist/fonts/noto/NotoSansDevanagari-Regular.ttf` | `deploy/fonts/NotoSansDevanagari-Regular.ttf` |
| `dist/fonts/noto/OFL-NotoSansDevanagari.txt` | `deploy/fonts/OFL-NotoSansDevanagari.txt` |

- The dev server (`npm run dev`) serves the same paths from the same files.
- Each file must match the SHA-256 in `deploy/fonts/README.md`. A missing file, a missing hash row or a different hash
  **fails the build**, so the UI never ships an unapproved font and never ships silently without one.
- The folder is `../deploy/fonts` relative to `sangam-ui`. A container build that copies the folder elsewhere sets
  `SANGAM_UI_FONTS_DIR`.
- To replace a font, follow `deploy/fonts/README.md` (new release, both hashes recorded, OFL text kept). The UI picks it
  up on the next build. Any other font needs a new owner decision.

## 2. Font stack and precedence

For every language, `src/fonts.ts` builds the stack in this order:

| # | Tier | Where it comes from | Bundled? |
|---|---|---|---|
| 1 | State-government font | tenant: `/config.json` → `fonts`, or the site drop-in `/fonts/site/fonts.json` | No: added by the site at install time |
| 2 | Central-government font | the same two sources | No (decision 121) |
| 3 | Noto fallback | `/fonts/noto/` (§1) for Latin and Devanagari; other Noto families when installed on the device | Latin and Devanagari only |
| 4 | System | `system-ui, sans-serif` | n/a |

Precedence of the *sources* (a later source replaces a tier for a language):
built-in `LANGUAGE_FONTS` (src/fonts.ts) < `/fonts/site/fonts.json` < `/config.json` `fonts`.

The resolved stacks are written to CSS variables `--font-<lang>` (e.g. `--font-hi`). `--font` (the body font) is
`--font-en`. Every other language gets `:where(:lang(<lang>)) { font-family: var(--font-<lang>) }`, so any element
with `lang="ta"` etc. picks its stack. `src/styles.css` holds static defaults for `en` and `hi` that apply until
`applyFonts()` runs at boot.

Noto Sans Devanagari is declared with a `unicode-range` limited to Devanagari (U+0900-097F), Vedic Extensions,
Devanagari Extended, ZWNJ/ZWJ, the rupee signs and the dotted circle. A page in English never downloads it.

### Known limits

- Regular weight only (decision 95 approved the Regular files). Bold text (`font-weight: 600/700`) is synthesised by the
  browser. Real bold weights need the matching static files added to `deploy/fonts` under a new owner decision.
- Only Latin and Devanagari are bundled. The other 21 scheduled languages use a Noto family installed on the device,
  or a font the site adds (§5).

## 3. Sources researched for government fonts (2026-11-04/05)

Kept as the record for sites that want to add a government font themselves (§5). Nothing here is bundled.

| Source | Result |
|---|---|
| Department of Official Language, rajbhasha.gov.in | No font download page; no DOL font licence found. [U] whether DOL formally prescribes one font |
| TDIL / ILDC (tdil-dc.in and related hosts) | Maintenance page with an expired TLS certificate, or no response. Nothing downloaded |
| C-DAC GIST, cdac.in | Sakal Bharati is offered under SIL OFL 1.1, but the font's own description (name ID 10) still says "non-commercial" and calls the name a trademark. **Not bundled (decision 121).** A site that has its own written confirmation from C-DAC may add it under §5 |
| GIGW 3.0, guidelines.india.gov.in | No font-specific clause confirmed [U]. The UI meets the Unicode-only and no-CDN requirements |

Not used: rajbhasha.net (a third-party site that also hosts proprietary fonts).

## 4. All 22 Eighth Schedule languages

"State font" lists only what an official state page offers. **No state font is cleared for bundling:** none shows
licence terms. A state deployment can drop the file into `/fonts/site/` (§5) after it confirms the licence itself.

| Lang | Code | Script | State-government font (official source) | Noto fallback |
|---|---|---|---|---|
| Assamese | `as` | Bengali-Assamese | none found [U] | Noto Sans Bengali |
| Bengali | `bn` | Bengali | none found [U] | Noto Sans Bengali |
| Bodo | `brx` | Devanagari | none found [U] | Noto Sans Devanagari (bundled) |
| Dogri | `doi` | Devanagari | none found [U] | Noto Sans Devanagari (bundled) |
| Gujarati | `gu` | Gujarati | none found [U] | Noto Sans Gujarati |
| **Hindi** | `hi` | Devanagari | n/a | **Noto Sans Devanagari (bundled)** |
| Kannada | `kn` | Kannada | Nudi 6.0 (Karnataka CeG/DPAR), offered only as an `.exe` installer; licence not stated [U] | Noto Sans Kannada |
| Kashmiri | `ks` | Perso-Arabic | none found [U] | Noto Nastaliq Urdu |
| Konkani | `kok` | Devanagari | none found [U] | Noto Sans Devanagari (bundled) |
| Maithili | `mai` | Devanagari | none found [U] | Noto Sans Devanagari (bundled) |
| Malayalam | `ml` | Malayalam | Kerala Govt fonts (C-DIT, 2022 script reform), direct `.ttf` at kerala.gov.in/malayalamfont; licence not stated [U] | Noto Sans Malayalam |
| Manipuri | `mni` | Meetei Mayek / Bengali | none found [U] | Noto Sans Meetei Mayek |
| Marathi | `mr` | Devanagari | Sakal Marathi (C-DAC GIST for Maharashtra); licence host unreachable [U] | Noto Sans Devanagari (bundled) |
| Nepali | `ne` | Devanagari | none found [U] | Noto Sans Devanagari (bundled) |
| Odia | `or` | Odia | none found [U] | Noto Sans Oriya |
| Punjabi | `pa` | Gurmukhi | none found [U] | Noto Sans Gurmukhi |
| Sanskrit | `sa` | Devanagari | none found [U] | Noto Sans Devanagari (bundled) |
| Santali | `sat` | Ol Chiki | none found [U] | Noto Sans Ol Chiki |
| Sindhi | `sd` | Perso-Arabic / Devanagari | none found [U] | Noto Naskh Arabic, then Noto Sans Devanagari (bundled) |
| Tamil | `ta` | Tamil | TAU-\* Unicode fonts, Tamil Virtual Academy (tamilvu.org); licence not stated [U] | Noto Sans Tamil |
| Telugu | `te` | Telugu | none found [U] | Noto Sans Telugu |
| Urdu | `ur` | Perso-Arabic | none found [U] | Noto Nastaliq Urdu |

English (`en`, Latin) uses Noto Sans (bundled).

"None found" means none was found on the official pages that were reachable on 2026-11-04/05. Recheck before adding a
language.

## 5. Deployment: state or official fonts at install time (air-gapped)

Use this path when a font may not be redistributed in an open-source repository, but the site holds its own licensed
copy (a state font, Sakal Bharati with the site's own C-DAC confirmation, or a proprietary font the department has
licensed). The site, not Sangam, is then responsible for that font's licence.

1. Copy the TTF/OTF/WOFF2 files into the UI's static root under `fonts/site/`. In the UI image that is
   `/usr/share/nginx/html/fonts/site/`; mount it as a read-only volume or ConfigMap. Never copy from a mirror. Record
   the official URL and SHA-256 in the site's own runbook.
2. Declare the files. Choose one:
   - **Drop-in manifest** `/fonts/site/fonts.json`, placed in the same folder. No other change is needed. The UI reads
     it at boot. If the file is missing, the SPA fallback returns HTML, which is ignored.
   - **`/config.json` → `fonts`**, the per-tenant override. It wins over the manifest.

Shape (identical in both places):

```json
{
  "fonts": {
    "manifest": "/fonts/site/fonts.json",
    "faces": [
      { "family": "Sakal Marathi", "src": "/fonts/site/SakalMarathi.ttf", "weight": "400", "unicodeRange": "U+0900-097F, U+200C-200D, U+20B9, U+25CC", "tier": "state" },
      { "family": "TAU-Valluvar", "src": "/fonts/site/TAU-Valluvar.ttf", "tier": "state" }
    ],
    "languages": {
      "mr": { "state": ["Sakal Marathi"] },
      "ta": { "state": ["TAU-Valluvar"] }
    }
  }
}
```

(In `fonts.json`, put the inner object at the top level, without the `"fonts"` wrapper.)

Rules enforced by `src/fonts.ts`:
- `src` and `manifest` must be same-origin paths starting with `/`. Remote URLs are dropped (CSP and air-gapped).
- A `languages.<code>` entry replaces only the tiers it names. Unnamed tiers keep the built-in values.
- Language codes are BCP-47 (`hi`, `mr`, `sd-Deva` …). The lookup falls back from `xx-YY` to `xx`, then to `en`.
- The bundled Noto faces are always declared; a site font placed in the `state` or `central` tier comes before them.
- The former key `useBundledGovFonts` is no longer read: there is no bundled government font to switch off.

**Deploy gap (WS-DEPLOY):** the nginx template (`deploy/docker/ui/default.conf.template`) renders `/config.json`
from env variables and has no `fonts` field. Until it does, use the drop-in manifest.

## 6. Adding a language later (configuration only)

1. Find the official source: state government first (§4), else central.
2. Add the font as a site font (§5). Bundling a new font in Sangam itself needs an owner decision and goes through
   `deploy/fonts` (§1), never through `public/`.
3. Add or adjust the language row in `LANGUAGE_FONTS`. Most of the 22 languages already have one.
4. UI strings for the language are a separate step (i18n), not part of this file. 0.1.0 is English-only (Q-06).

## 7. Open questions (site-level, not blocking 0.1.0)

- **Q2.** Does the Department of Official Language formally prescribe a Unicode font for government websites? Nothing
  was found on rajbhasha.gov.in, and TDIL was offline.
- **Q3.** Licence terms for the Kerala (C-DIT) and Tamil Virtual Academy fonts; Karnataka Nudi is installer-only.
- **Q4.** The Sakal Marathi licence page and several state language-department sites were unreachable.
