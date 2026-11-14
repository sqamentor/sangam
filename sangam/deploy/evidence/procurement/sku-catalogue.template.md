<!-- SPDX-License-Identifier: Apache-2.0 -->
# SKU catalogue (template, M-089)

Template for the procurement evidence area (SANGAM.md R62 / GOV-12, ADR-D-11). **Nothing here is listed by code:**
the owner picks the SKUs and signs the local-content declaration at listing time (owner decision 109). This file makes
no statement that any SKU qualifies under a law, order or procurement rule.

## How to fill it

1. Copy this file outside the repository for a listing, and keep commercial figures there.
2. For each SKU the owner decides to list, fill one row below and one copy of `local-content-input.template.json`.
3. Run `python -I local_content_dossier.py <filled input>.json --out <dir>` for each SKU and attach the draft dossier.
4. The owner reviews, signs and lists. The signed declaration is not stored in this repository.

## Categories (ADR-D-11)

| Category | What it covers | Notes |
|---|---|---|
| Implementation and migration | Installation, configuration, pack setup, data migration, go-live support | Effort-based; per site or per department |
| AMC and support | Annual maintenance and support of an installed release | Support tiers and response times are the owner's choice |
| Hosted | Sangam operated for the buyer | Cloud offer only through an empanelled cloud service provider (R62) |
| Training and certification | Training of administrators, modellers and officers; certification of trainers | Per batch or per participant |

The software itself is Apache-2.0 and carries no licence fee; a SKU sells the service around it.

## SKUs selected for a listing (filled by the owner)

| SKU id | Category | Title | Unit of measure | Local-content dossier (file) | Calculated class | Declaration signed by | Date |
|---|---|---|---|---|---|---|---|
| | | | | | | | |
