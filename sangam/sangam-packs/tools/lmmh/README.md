<!-- SPDX-License-Identifier: Apache-2.0 -->
# LMMH extraction: official heads of account

`extract_lmmh.py` turns the CGA List of Major and Minor Heads of Account (LMMH) into flat data. `../profilegen/lmmh.py` turns that data into the seed files. No head is inferred: every row comes from the PDF and carries its page number.

## Source

| Item | Value |
|---|---|
| Document | List of Major and Minor Heads of Account of Union and States, correction slips up to 1127 dated 09-10-2026 |
| File | `spike/results/sources/lmmh/LMMH-CS1127.pdf` (9,222,852 bytes, 523 pages) |
| SHA-256 | `19878E4E6205B3B78F65701BA3029AD86ECE915B38231C81A631EFFC88956610` |
| Downloaded from | https://cga.gov.in/DownloadPDF.aspx?filenameid=1979 |

## Method

The script runs `pdftotext -simple -enc UTF-8` (the xpdf 4.00 build shipped with Git for Windows). `-layout` and `-raw` misalign codes and names on some pages; `-simple` does not. The script only reads the PDF as data, so run it with `python -I`, keeping the script outside the PDF's directory.

| PDF part | Pages | Gives |
|---|---|---|
| Table of contents | 2-15 | every major head, its grouping (division, part, sector, sub-sector, group) |
| Standard minor head list | 27-28 | standard minor heads |
| Detailed list | 30-522 | sub-major and minor heads under each major head; notes are skipped |

## Output

| File | Content |
|---|---|
| `data/lmmh-heads.csv` | one row per head, with grouping, `nature`, footnote numbers, page and opening rules |
| `data/lmmh-standard-minors.csv` | the standard minor head list |
| `data/lmmh-report.json` | counts, validation results, anomalies, spot-check sample |
| `src/main/resources/profiles/in/seeds/hoa-major-heads.csv` | major heads; `parent` empty |
| `.../hoa-sub-major-heads.csv` | sub-major heads; `parent` = `MMMM` |
| `.../hoa-minor-heads.csv` | minor heads; `parent` = `MMMM-SS` (`00` = directly under the major; `*n` = see below) |
| `.../hoa-standard-minor-heads.csv` | parent-less rows: standard minors, plus an index row for every other official minor and sub-major code |

The hierarchical rows drive validation. The `HOA` scheme declares a `hierarchy` on its `subMajor` and `minor` segments (`profilegen/central.py`), so `ClassificationRegistry` checks each code under its own parent (`MMMM` for a sub-major, `MMMM-SS` for a minor) and refuses a code that exists only under another head with `CODE_NOT_UNDER_PARENT`. The same check applies to a state layout whose segments reference `HOA`. Exceptions:
- a parent-less row flagged `standard=true` (a standard minor) is valid under any parent;
- `00` is valid where the major head has no coded sub-major heads;
- a minor opened by the `minorRule` of its major or sub-major head passes with an INFO finding `CODE_OPENED_BY_RULE`;
- a sub-major opened by rule (a `subMajorRule` block, whose minors have the template parent `MMMM-*n`) passes with the same INFO finding, and its minors must be among those template rows;
- a parent with no coded children and no rule passes with a WARNING `PARENT_HAS_NO_CODES`.

The parent-less index rows (`index=true`) are a flat list of every official code at its level. They do not carry `standard`, so they never make a code valid under a wrong parent. `ProfileDataTest.headOfAccountSeedValidatesFifteenDigitCodes` covers these cases.

## Counts (CS 1127)

| Level | Rows |
|---|---|
| Major (4 digits) | 489 |
| Sub-major (2 digits) | 717 |
| Minor (3 digits) | 5,773 |
| Standard minor table rows | 38 |

| Range | Major | Sub-major | Minor |
|---|---|---|---|
| 0xxx Receipts | 84 | 105 | 905 |
| 1xxx Receipts | 22 | 32 | 368 |
| 2xxx Revenue expenditure | 96 | 153 | 1,501 |
| 3xxx Revenue expenditure | 40 | 99 | 735 |
| 4xxx Capital | 60 | 128 | 685 |
| 5xxx Capital | 24 | 47 | 368 |
| 6xxx Public debt, loans | 58 | 90 | 465 |
| 7xxx Loans, settlement, transfer to Contingency Fund | 22 | 27 | 139 |
| 8000 Contingency Fund | 1 | 0 | 1 |
| 8001-8999 Public Account | 82 | 36 | 606 |

Counts by section and sector are in `data/lmmh-report.json`.

## Validation

Each run reports: codes unique per level, every minor under a real major, no sub-major missing, no empty name, every contents major found in the detailed list and the reverse. All are clean for CS 1127. The 30-row random spot check (seed 1127) passes 30/30. A check of all 6,995 rows finds code and first name words on the row's page, except 2235-60-111 (see below).

## Known limits

- **Slips after 1127 are not included.** The project's research records slips up to CS 1132 of 16-10-2026, said to open 2246 and 4245. Neither code is in this PDF, so neither is seeded. Load the later list or slips.
- **No voted / charged markers.** The LMMH has none at head level; that attribute stays on the budget line.
- **Minor heads opened by rule.** 24 major heads have no coded minor heads in the list (for example 0551, 2416, 7605, 8786-8789, 8793, 8795, 8797); the instruction is kept as the `minorRule` flag.
- **Sub-major heads opened by rule.** 17 "Each ... project will be a sub-major head" blocks (0700, 0701, 2700, 2701, 4700, 4701, 6700, 6701, 8454) have no code. Their minors use parent `MMMM-*n` with a `subMajorRule` flag.
- **Uncoded names under 8797-01** (`Exchange Accounts between ...`) stay as rule text.
- **Repaired PDF defects** (listed in the report): 2235-60-111 and -112 have the code overprinted on the name, 7466-101 is glued to the major title, and the contents prints 6700 as `700`.
- **Name choice.** A major takes the detailed-list title if it is a close variant of the contents title (the contents title is kept as `tocName`); otherwise the contents title. Spelling errors in the source are kept (`Penison`).
- **Name quality is parser-checked, not proofread.** Wrapped names are joined by column position, and only 30 sampled rows plus first words of all rows were compared with the page.

## Re-run for a new correction slip

Put the new PDF in its own directory, then from `sangam-packs/tools/lmmh`:

```
python -I extract_lmmh.py --pdf PATH/LMMH-CSnnnn.pdf --slip nnnn --slip-date YYYY-MM-DD --sha256 EXPECTED
```

1. Read `data/lmmh-report.json`: `validation` must be clean and new `anomalies` explained.
2. Set `SLIP` in `../profilegen/lmmh.py`, the `seedStatus` and source text in `../profilegen/central.py`, and this file.
3. Regenerate and test from `sangam-packs/tools` and `sangam`:

```
python profilegen/main.py
python profilegen/coverage.py
python packgen/main.py --check
mvnw.cmd -q -B -pl sangam-config,sangam-packs -am install
```

`profilegen` needs PyYAML, so run it without `-I`; it does not read the PDF.
