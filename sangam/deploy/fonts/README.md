<!-- SPDX-License-Identifier: Apache-2.0 -->
# PDF fonts (M-081)

These fonts are embedded in generated PDF/A-2b and PDF/UA-1 documents by the M-081 renderer. Point `SANGAM_PDF_FONTS` at
this folder, or mount it as the font volume in compose and Helm. The renderer never reads system fonts.

Owner decisions 70 and 95 (2026-11-12): fonts only, OFL-1.1, unmodified, each shipped with its OFL text, never sold on
its own and never renamed. Any other font needs a new owner decision.

| File | Source release | SHA-256 |
|---|---|---|
| `NotoSansDevanagari-Regular.ttf` | notofonts/devanagari `NotoSansDevanagari-v2.007` (zip, `full/ttf`) | 573E525D9F4923D34645FE752475419F45F8DEC5A8E43F3E83C5C5F43D1F2FF9 |
| `OFL-NotoSansDevanagari.txt` | same zip, `OFL.txt` | A216F6F8D85C7228093E0EE5E258D9D377E6671F68ACB4DB1930B29583D0F331 |
| `NotoSans-Regular.ttf` | notofonts/latin-greek-cyrillic `NotoSans-v2.015` (zip, `full/ttf`) | F5F552C8C5EDB61FE6EFB824BAF4D4DE47B1A8689AB4925FF43F7BD6A4EBECE5 |
| `OFL-NotoSans.txt` | same zip, `OFL.txt` | CEE9892F9F0CC8FE882C9E9537EE6A89621D86EE7CEAF70B02E2B2B1C25C061A |

Release archives: `NotoSansDevanagari-v2.007.zip` SHA-256 820C7DA45B1E63562CB41C0A8CAC5D9A4202312043A3A040ED1325857EF469B1;
`NotoSans-v2.015.zip` SHA-256 0C34DF072A3FA7EFBB7CBF34950E1F971A4447CFFE365D3A359E2D4089B958F5.

To replace a font, fetch the new release, record both hashes here, and keep the OFL text next to it.
