<!-- SPDX-License-Identifier: Apache-2.0 -->
<!-- M-096 (SANGAM.md A5, P7, governance). Fill every section; write "none" where a section does not apply. -->

## What and why

<!-- One paragraph: the change and the root cause it fixes or the requirement it delivers. -->

## Decision (ADR)

<!-- The decision this change follows or makes, as a link: an ADR-D-n row in the SANGAM.md decision register
     (SANGAM.md "Decision register"), or a new ADR proposed in this PR. A change that contradicts an ADR needs a new ADR first. -->
- ADR:

## Requirements and tests (traceability)

<!-- The register ids this change covers (sangam/docs/records/TRACEABILITY-REGISTER.csv, e.g. R4, FW-07, CMP-012) and the
     tests that prove each one. The governance workflow reports register rows that have no proving test. -->
- Ids:
- Tests:

## Checklist

- [ ] Root-cause fix, no workaround; unit tests added or updated, no assertion weakened or removed
- [ ] Generated files changed only through their generators (packgen `--check`, profilegen, generate_changelogs, gen_openapi: no diff on a second run)
- [ ] New dependencies pass `sangam/deploy/licence/licence-policy.json` and are pinned
- [ ] Every commit carries a `Signed-off-by:` line (DCO, `git commit -s`); the check is report-only until Q-10 is answered
