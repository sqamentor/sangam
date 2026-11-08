# Sangam UI fonts

Owner rule: the UI uses **Government of India fonts**. Hindi and English are mandatory now. For a state language, the
state government's font is used when that state publishes one, otherwise the central government's font. Every file is
served from this host (`font-src 'self'`), so the UI works air-gapped.

Status (2026-11-05): Hindi uses the central-government font **Sakal Bharati** (C-DAC GIST, SIL OFL 1.1), bundled.
English uses **Noto Sans** (OSI, OFL 1.1, already bundled). Sakal Bharati is second in the English stack.

`[U]` = not verified on an official page.

## 1. Font stack and precedence

For every language, `src/fonts.ts` builds the stack in this order:

| # | Tier | Where it comes from | Bundled? |
|---|---|---|---|
| 1 | State-government font | tenant: `/config.json` → `fonts`, or the site drop-in `/fonts/site/fonts.json` | No: added by the site at install time |
| 2 | Central-government font | `public/fonts/gov/central/` (Sakal Bharati) | Yes |
| 3 | OSI fallback | `@fontsource/noto-sans`, `@fontsource/noto-sans-devanagari` (OFL-1.1, npm) | Yes (Latin and Devanagari only) |
| 4 | System | `system-ui, sans-serif` | n/a |

Precedence of the *sources* (a later source replaces a tier for a language):
built-in `LANGUAGE_FONTS` (src/fonts.ts) < `/fonts/site/fonts.json` < `/config.json` `fonts`.

The resolved stacks are written to CSS variables `--font-<lang>` (e.g. `--font-hi`). `--font` (the body font) is
`--font-en`. Every other language gets `:where(:lang(<lang>)) { font-family: var(--font-<lang>) }`, so any element
with `lang="ta"` etc. picks its stack. `src/styles.css` holds static defaults for `en` and `hi` that apply until
`applyFonts()` runs at boot.

Sakal Bharati is declared with a `unicode-range` covering its Indic, Ol Chiki, Meetei Mayek and Arabic blocks, not
Latin. A page in English never downloads the 2.5 MB file. It is fetched once, the first time Indic text appears.

## 2. Bundled government font (downloaded)

| Field | Value |
|---|---|
| Name | Sakal Bharati (`SakalBharati`), Regular only, version 13.02 (name table ID 5) |
| Scripts | 13 scripts in one font: Assamese, Bengali, Devanagari, Gujarati, Kannada, Malayalam, Meetei Mayek, Oriya, Ol Chiki, Punjabi (Gurmukhi), Telugu, Tamil, Urdu (Arabic), plus Latin |
| Publisher | C-DAC GIST, Pune (Centre for Development of Advanced Computing, a MeitY scientific society) |
| Official page | https://www.cdac.in/index.aspx?id=dl_sakal_bharati_font |
| Download URL | https://www.cdac.in/index.aspx?id=dl_SakalBharati_Ship (served as `SakalBharati_Ship.zip`, `application/x-zip-compressed`, valid TLS) |
| Archive | `SakalBharati_Ship.zip`: 1,130,895 bytes, SHA-256 `9cfc6b881bdb06e11a5555e264c673e9f9158f2cea63b808aae5c5c8fd147154`; one member, `SakalBharati.ttf`, dated 2015-05-27. Not kept in the repo. |
| Bundled file | `public/fonts/gov/central/sakal-bharati/SakalBharati.ttf`: 2,530,180 bytes, SHA-256 `f27cf39cfae18b05929792fc2610f5be55d27e31afeeca2a7d6d6040733b0805`, byte-identical to the zip member |
| Licence file | `public/fonts/gov/central/sakal-bharati/OFL.txt`: the font's copyright notice (name ID 0), followed by the standard SIL OFL 1.1 text. The zip ships no licence file. |
| Format | TrueType (`glyf`) with OpenType layout (GSUB/GPOS). **Not converted to WOFF2**: kept byte-identical (see §2.2) |
| Downloaded | 2026-11-04 |

### 2.1 Licence evidence

- Official C-DAC page: "License Type - SIL OFL 1.1".
- Font name ID 13 (licence): "licensed under the SIL Open Font License, Version 1.1". Name ID 14 is the OFL URL.
- Font name ID 0 (copyright): "Copyright (c) 2015, C-DAC, GIST PUNE, INDIA. SakalBharati a trademark of CDAC GIST is
  released under the SIL Open Font License."
- **Conflict.** Font name ID 10 (description) still says "FOR NON-COMMERCIAL FREE USAGE" and asks commercial users to
  contact C-DAC GIST. This looks like a leftover from the 2009 TDIL-era licence.

**Verdict.** We follow the licence that is declared: name ID 13 plus the official page. Under OFL 1.1 the font may be
bundled in an Apache-2.0 product and embedded in a web app. It may be used commercially, but it may not be sold by
itself. It may be modified, but only under OFL, and the name must change if a Reserved Font Name were declared. No
Reserved Font Name is declared, but "SakalBharati" is called a trademark. The name ID 10 text conflicts with this, so we
recommend getting written confirmation from C-DAC GIST (open question Q1).

### 2.2 Why there is no WOFF2

OFL permits a WOFF2 conversion. We left the file unmodified anyway, for three reasons. The trademark note and the
name ID 10 conflict make an unmodified copy the safest form. A byte-identical file can be checked against C-DAC's own
download. And no font tooling is installed on the build host (fontTools/brotli would have to come from PyPI). To add a
WOFF2 later, add it next to the TTF and list it first in `BUNDLED_GOV_FACES` (src/fonts.ts). Keep the TTF and
`OFL.txt` alongside it.

### 2.3 Known limits

- Regular weight only. Bold text in Hindi (headings, `font-weight: 600/700`) is synthesised by the browser. To use real
  bold, set `"languages": { "hi": { "central": [] } }`; Noto Sans Devanagari 600/700 is then used.
- English UI keeps Noto Sans first, because it has real 400/600/700 weights and better Latin. Sakal Bharati's Latin
  is second. To make it first: `"languages": { "en": { "central": ["SakalBharati"] } }`. Note, though, that the
  bundled face's `unicode-range` excludes Latin. To use its Latin glyphs, declare an extra face with your own range.

## 3. Sources researched

| Source | Result |
|---|---|
| Department of Official Language, rajbhasha.gov.in | The current site (Liferay) has no font download page. Site search for "font" returns no results. The site points to Bharati/Kanthasth translation tools, not fonts. No DOL font licence found. [U] whether DOL formally prescribes one font |
| TDIL / ILDC, tdil-dc.in, ildc.gov.in, ildc.in, tdil.meity.gov.in, tdil.mit.gov.in | tdil-dc.in answers only with a **"Maintenance Mode"** page and an **expired TLS certificate**. The other hosts did not respond. Nothing was downloaded from them. C-DAC's Sakal Bharati page links its *source code* to tdil-dc.in (toolid 805), which is unreachable |
| C-DAC GIST, cdac.in | Sakal Bharati (above), OFL 1.1, plain zip with one TTF. The **Unicode Typing Tool** (WinRAR zip, 29.9 MB) bundles "two open type fonts for each language" with a typing tool; its licence is not shown [U]. Not downloaded, and as a tool package it is not suitable to bundle. The GIST-Tirhuta font ships only inside the Tirhuta Typing Tool zip [U] |
| GIGW 3.0, guidelines.india.gov.in | Manual: https://cdnbbsr.s3waas.gov.in/s3c92a10324374fac681719d63979d00fe/uploads/2026/07/2026072438.pdf. No font-specific clause was confirmed [U]. The UI already meets the Unicode-only and no-CDN requirements |
| State governments | See §4 |

Not used: rajbhasha.net. It is a third-party site, not the Department of Official Language, and also hosts proprietary
fonts such as Mangal (Microsoft).

## 4. All 22 Eighth Schedule languages

"Central" = C-DAC Sakal Bharati (OFL 1.1, bundled) unless noted. "State font" lists only what an official state page
offers. **No state font is cleared for bundling:** none shows licence terms. A state deployment can still drop the
file into `/fonts/site/` (§5), after it confirms the licence itself.

| Lang | Code | Script | State-government font (official source) | Licence | Central | OSI fallback (not bundled unless noted) |
|---|---|---|---|---|---|---|
| Assamese | `as` | Bengali-Assamese | none found (assam.gov.in) [U] | n/a | Sakal Bharati | Noto Sans Bengali |
| Bengali | `bn` | Bengali | none found (wb.gov.in; WB Bangla Academy site did not resolve) [U] | n/a | Sakal Bharati | Noto Sans Bengali |
| Bodo | `brx` | Devanagari | none found [U] | n/a | Sakal Bharati | Noto Sans Devanagari (bundled) |
| Dogri | `doi` | Devanagari | none found (jk.gov.in timed out) [U] | n/a | Sakal Bharati | Noto Sans Devanagari (bundled) |
| Gujarati | `gu` | Gujarati | none found (gujaratindia.gov.in) [U] | n/a | Sakal Bharati | Noto Sans Gujarati |
| **Hindi** | `hi` | Devanagari | n/a (central language) | n/a | **Sakal Bharati (bundled, active)** | Noto Sans Devanagari (bundled) |
| Kannada | `kn` | Kannada | **Nudi 6.0**, Centre for e-Governance, Karnataka (DPAR): https://ceg.karnataka.gov.in/, https://dpar.karnataka.gov.in/ → Downloads. **Offered only as `nudi_6.0_setup.exe`**: not downloaded, not run | [U] none stated | Sakal Bharati | Noto Sans Kannada |
| Kashmiri | `ks` | Perso-Arabic | none found (jk.gov.in timed out) [U] | n/a | Sakal Bharati (Arabic block; Kashmiri-specific letters [U]) | Noto Nastaliq Urdu |
| Konkani | `kok` | Devanagari | none found (dol.goa.gov.in) [U] | n/a | Sakal Bharati | Noto Sans Devanagari (bundled) |
| Maithili | `mai` | Devanagari | none found [U] | n/a | Sakal Bharati | Noto Sans Devanagari (bundled) |
| Malayalam | `ml` | Malayalam | **Kerala Govt, fonts per the 2022 script reform**, made by C-DIT: Mandaram, Thumba, Chembakam, Kadambu, Maathalam, Paarijaatham, Pichakam, plus Manjula22, Miya22, Rahna22 (derived from SMC's Manjari, Meera, Rachana). Direct `.ttf`: https://kerala.gov.in/malayalamfont | [U] none on the page. If derived from OFL originals, the derivatives must stay OFL [U] | Sakal Bharati | Noto Sans Malayalam |
| Manipuri | `mni` | Meetei Mayek / Bengali | none found (manipur.gov.in) [U] | n/a | Sakal Bharati (both scripts) | Noto Sans Meetei Mayek |
| Marathi | `mr` | Devanagari | Sakal Marathi (C-DAC GIST for Govt of Maharashtra) [U]: licence host sakalmarathi.cdac.in did not resolve, and the Marathi Bhasha Vibhag sites did not resolve | [U] | Sakal Bharati | Noto Sans Devanagari (bundled) |
| Nepali | `ne` | Devanagari | none found (sikkim.gov.in) [U] | n/a | Sakal Bharati | Noto Sans Devanagari (bundled) |
| Odia | `or` | Odia | none found (ocac.in, odisha.gov.in) [U] | n/a | Sakal Bharati | Noto Sans Oriya |
| Punjabi | `pa` | Gurmukhi | none found (punjab.gov.in; language-department domains did not resolve) [U] | n/a | Sakal Bharati | Noto Sans Gurmukhi |
| Sanskrit | `sa` | Devanagari | none found [U] | n/a | Sakal Bharati (no Vedic Extensions U+1CD0-1CFF) | Noto Sans Devanagari (bundled) |
| Santali | `sat` | Ol Chiki | none found [U] | n/a | Sakal Bharati | Noto Sans Ol Chiki |
| Sindhi | `sd` | Perso-Arabic / Devanagari | none found (NCPSL, central, unreachable) [U] | n/a | Sakal Bharati | Noto Naskh Arabic |
| Tamil | `ta` | Tamil | **TAU-\* Unicode fonts** (TAU-Barathi, Kambar, Valluvar, Kabilar and others; "Marutham" is named in the tab title but not seen as a file [U]), Tamil Virtual Academy (Govt of Tamil Nadu): https://www.tamilvu.org/ta/unicode → https://www.tamilvu.org/ta/tkbd-index-341488. Direct `.ttf` / `tau_fonts.zip` | [U] (page returned 522/timeouts) | Sakal Bharati | Noto Sans Tamil |
| Telugu | `te` | Telugu | none found (telangana.gov.in, it.telangana.gov.in, apit.ap.gov.in) [U] | n/a | Sakal Bharati | Noto Sans Telugu |
| Urdu | `ur` | Perso-Arabic | none found. NCPUL (central, urducouncil.nic.in) has no font download page [U] | n/a | Sakal Bharati (Naskh style) | Noto Nastaliq Urdu |

English (`en`, Latin) is Noto Sans (bundled, OFL), with Sakal Bharati's Latin as the government option (§2.3).

"None found" means none was found on the official pages that were reachable on 2026-11-04/05. It does not mean none
exists. Recheck before adding a language.

## 5. Deployment: state or official fonts at install time (air-gapped)

Use this path when a font may not be redistributed in an open-source repository, but the site holds its own licensed
copy. Examples: a state font with no public licence, or Mangal or another proprietary font the department has licensed.

1. Copy the TTF/OTF/WOFF2 files into the UI's static root under `fonts/site/`. In the UI image that is
   `/usr/share/nginx/html/fonts/site/`; mount it as a read-only volume or ConfigMap. Never copy from a mirror. Record
   the official URL and SHA-256 in the site's own runbook.
2. Declare the files. Choose one:
   - **Drop-in manifest** `/fonts/site/fonts.json`, placed in the same folder. No other change is needed. The UI reads
     it at boot. If the file is missing, the SPA fallback returns HTML, which is ignored.
   - **`/config.json` → `fonts`**, the per-tenant override. It wins over the manifest.
3. Optional: `"useBundledGovFonts": false` stops loading the bundled Sakal Bharati.

Shape (identical in both places):

```json
{
  "fonts": {
    "useBundledGovFonts": true,
    "manifest": "/fonts/site/fonts.json",
    "faces": [
      { "family": "Sakal Marathi", "src": "/fonts/site/SakalMarathi.ttf", "weight": "400", "unicodeRange": "U+0900-097F, U+200C-200D, U+20B9, U+25CC", "tier": "state" },
      { "family": "TAU-Valluvar", "src": "/fonts/site/TAU-Valluvar.ttf", "tier": "state" }
    ],
    "languages": {
      "mr": { "state": ["Sakal Marathi"] },
      "ta": { "state": ["TAU-Valluvar"] },
      "hi": { "central": ["SakalBharati"], "fallback": ["Noto Sans Devanagari", "Noto Sans"] }
    }
  }
}
```

(In `fonts.json`, put the inner object at the top level, without the `"fonts"` wrapper.)

Rules enforced by `src/fonts.ts`:
- `src` and `manifest` must be same-origin paths starting with `/`. Remote URLs are dropped (CSP and air-gapped).
- A `languages.<code>` entry replaces only the tiers it names. Unnamed tiers keep the built-in values.
- Language codes are BCP-47 (`hi`, `mr`, `sd-Deva` …). The lookup falls back from `xx-YY` to `xx`, then to `en`.

**Deploy gap (WS-DEPLOY):** the nginx template (`deploy/docker/ui/default.conf.template`) renders `/config.json`
from env variables and has no `fonts` field. Until it does, use the drop-in manifest. Note also that the template
sends `"locales":"en,hi"` as a *string*, while `normalise()` in `src/config.ts` expects an array.

## 6. Adding a language later (configuration only)

1. Find the official source: state government first (this table), else central.
2. If its licence allows redistribution, add the file under `public/fonts/gov/<state|central>/<font>/` with its
   licence text. Add a row to §2 here (URL, size, SHA-256) and a face to `BUNDLED_GOV_FACES`. Otherwise use §5.
3. Add or adjust the language row in `LANGUAGE_FONTS`. Most of the 22 languages already have one.
4. UI strings for the language are a separate step (i18n), not part of this file.

## 7. Open questions

- **Q1.** Get written confirmation from C-DAC GIST that Sakal Bharati 13.02 is OFL 1.1 for commercial and
  redistributed use. Name ID 10 still says "non-commercial". Also ask whether the "SakalBharati" trademark restricts
  modified (e.g. WOFF2 or subset) versions.
- **Q2.** Does the Department of Official Language formally prescribe a Unicode font for government websites? Nothing
  was found on rajbhasha.gov.in, and TDIL is offline. Recheck tdil-dc.in when it is out of maintenance.
- **Q3.** Licence terms for the Kerala (C-DIT) and Tamil Virtual Academy fonts. Their files are plain TTFs, so the
  embedded name table may settle it. Karnataka Nudi is installer-only (.exe): ask DPAR for a plain TTF and its licence.
- **Q4.** The Sakal Marathi licence page (sakalmarathi.cdac.in) and the Punjab, Odisha, West Bengal and J&K language
  department sites were unreachable. Recheck them.
