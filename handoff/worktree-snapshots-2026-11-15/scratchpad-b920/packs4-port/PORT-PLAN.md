# PACKS4 forward-port plan (B1, prepared 2026-11-12; nothing applied to the integration tree)

Status: WAITING for B3 APPROVE-401 2b (coordinator DECISION). Start only on the coordinator's signal.

## 0. Source and why a straight merge is not possible

- Source: `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-packs4` (HEAD 9c831d0; copies under `sangam-handoff\2026-11-08\scratchpad\wt-packs4` and `handoff\worktree-snapshots\wt-packs4`).
- Its working tree = 9c831d0 + uncommitted work of OTHER streams at cut time (FC-02/03 `EVT_DEFINITION_SEQ`, SL-45 `EngineRestTenantFilter`, TrustedProxy, FWT, api lifecycle, ~70 flowable-ports tests, ...) + packs4. Evidence: `git ls-files --others` lists ~130 untracked non-pack files; `generate_changelogs.py` diff vs 9c831d0 is only FC-02/03 (no money). So the port is HUNK-SELECTIVE: only money hunks (+ their tests) move. Saved inputs:
  - `diffs\*.diff` = wt-packs4 vs 9c831d0 per in-scope file (52 files)
  - `new-files\*` = packs4-only files (untracked in wt-packs4)
  - `it-vs-packs4\*.diff` = current IT vs wt-packs4 for files that exist on both sides
- Persistence (`generate_changelogs.py`, baselines, `SangamSchema.java`): NOT in scope (packs4 has no money change there; SG-20 BIGINT columns already at 15e4135). No claim needed.

## 1. Order of work (after the 2b signal)

1. Re-diff every target file in the IT just before editing; claim shared files (none expected beyond `pom.xml`: none needed).
2. Generators first: add `money.py`; port money hunks into `lib.py` (DMN `'money'` column type -> `number` + minor literals), `packs.py` (setv/acceptance conversion, `money.json` writer, pack.json `money` block), `main.py`, `core.py`, `common.py`, `billing_pack.py`, `gov1/2/3.py`, `gov_ifms.py` (on top of 2b), `sec_fin.py` (SL-50 bank bands), `sec_more.py`, `sec_other.py`, `sec_ushealth.py`, `sector.py`, `universal.py`, `acceptance_runs.py`; profilegen `skeletons.py`, `goi.py` (+ `states.py`/`central.py` only if money hunks exist: diffs show 0 money lines there -> skip).
3. Java runtime: `LedgerConnector`, `LedgerDelegate`, `SetVariablesDelegate`, `BatchDelegate`, `SimulatorConnectorGateway`, `X12Decoders` (+ `PowerLimitGuardListener` money hunks if still needed).
4. Regenerate: packgen, then profilegen, each `--check` twice (second run no diff).
5. Tests: `PackMoneyGuardTest`, `ProfileMoneyUnitsTest`, money hunks of `BankPaymentConcurrenceTest`, `SectorConformanceTest`, `FormAnswers`, `ChangeAdoptionTest`, `AllPacksSmokeTest`, `PilotScenarioTest`, `LedgerJdbcH2Test`, `Remittance835FieldsTest`, `RemittanceAndSeedsTest`, `IfmsPacksIT` (money hunks only).
6. Compile gates: `-pl sangam-packs,sangam-ledger,sangam-connectors-health,sangam-config -am clean test-compile`, then `-pl sangam-server -am`.
7. Draft `[CONTRACT] PACKS4-MINOR-UNITS` (section 4) for the coordinator.

## 2. File list (target = same path under `sangam/` in the IT)

Money lines = changed lines matching minor/paise/money/M(/is_money/long in the wt-packs4 diff (money/total).
`ours changed` = the IT file changed since 9c831d0 (needs a hunk-level port, not a copy).

### 2a. New files (copy, then adjust)
| File | Action | Conf. |
|---|---|---|
| sangam-packs/tools/packgen/money.py | copy; re-check `EXPONENT` / suffix lists against current pack variable names | 98% |
| sangam-packs/src/main/resources/META-INF/sangam/money.json | regenerate (written by packgen), never copy | 99% |
| sangam-packs/src/test/java/org/sangam/packs/PackMoneyGuardTest.java | copy | 98% |
| sangam-config/src/test/java/org/sangam/config/ProfileMoneyUnitsTest.java | copy (signature band 10 lakh = 100,000,000 paise) | 97% |
| sangam-packs/tools/packgen/gov_intl.py, sec_eu.py | SL-33 (PFM/GDPR packs), NOT money: excluded unless B3/integrator assigns SL-33 to this port | n/a (question Q-P3) |

### 2b. Generators (hunk port; money/total changed lines)
| File | money/total | ours changed | Conf. |
|---|---|---|---|
| tools/packgen/lib.py | 27/68 | yes (1 conflict in trial merge) | 97% |
| tools/packgen/packs.py | 24/77 | yes (IFMS-WF pin_cross_pack_calls landed; 1 conflict) | 97% |
| tools/packgen/gov2.py | 26/73 | yes | 97% |
| tools/packgen/gov_ifms.py | 11/86 | yes; 2b pending -> port after 2b | 97% after 2b |
| tools/packgen/gov1.py | 10/31 | yes | 97% |
| tools/packgen/sec_fin.py (SL-50 bands) | 10/71 | yes (2 conflicts) | 97% |
| tools/packgen/gov3.py | 8/23 | no | 98% |
| tools/packgen/sec_more.py | 8/31 | yes | 97% |
| tools/packgen/universal.py | 7/17 | no | 98% |
| tools/packgen/sector.py | 6/34 | yes (2 conflicts) | 97% |
| tools/packgen/core.py | 3/9 | yes | 98% |
| tools/packgen/sec_other.py | 3/9 | no | 98% |
| tools/packgen/main.py | 2/3 | no | 98% |
| tools/packgen/sec_ushealth.py | 2/5 | no | 98% |
| tools/packgen/common.py, billing_pack.py | 1/1 each | no | 99% |
| tools/packgen/acceptance_runs.py | 0/5 vs IT (packs4 version is converted by money.convert_run at generation) | yes | 98% |
| tools/profilegen/skeletons.py | 5/12 (powers/procurement DMN columns `money`, `minor(lim)`) | no | 98% |
| tools/profilegen/goi.py | 2/9 | no | 97% |
| tools/profilegen/states.py, central.py, main.py, coverage.py | 0 money lines -> not part of PACKS4 | - | 99% |

### 2c. Java main
| File | money/total | ours changed | Conf. |
|---|---|---|---|
| sangam-ledger/.../LedgerConnector.java | 9/31 | no | 98% |
| sangam-packs/.../core/LedgerDelegate.java | 16/31 | no | 98% |
| sangam-connectors-health/.../x12/X12Decoders.java | 18/52 | no | 98% |
| sangam-packs/.../core/SetVariablesDelegate.java | 2/6 | no | 98% |
| sangam-packs/.../core/BatchDelegate.java | 1/10 | yes | 97% |
| sangam-packs/.../sim/SimulatorConnectorGateway.java | 2/4 | yes | 97% |
| sangam-packs/.../core/PowerLimitGuardListener.java | 2/9 vs base; 0 vs IT (already equal) | - | 99% skip |
| Vars.java, PackDeployer, ManifestPack, PackDescriptor, WorkingDayCalendar, *Delegate (0 money lines) | other streams, already in IT or not packs4 | - | 99% skip |

### 2d. Tests (money hunks only)
| File | money/total vs IT | Conf. |
|---|---|---|
| sangam-packs/.../BankPaymentConcurrenceTest.java (SL-50) | 14/22 | 98% |
| sangam-packs/.../SectorConformanceTest.java | 17/107 | 97% |
| sangam-packs/.../ChangeAdoptionTest.java | 6/12 | 97% |
| sangam-packs/.../FormAnswers.java | 2/3 | 98% |
| sangam-packs/.../AllPacksSmokeTest.java, PilotScenarioTest.java | 1/11, 3/14 | 97% |
| sangam-ledger/.../LedgerJdbcH2Test.java | 6/28 | 98% |
| sangam-connectors-health/.../Remittance835FieldsTest.java | 2/10 | 98% |
| sangam-server/.../bridge/RemittanceAndSeedsTest.java | 1/26 | 97% |
| sangam-server/.../ifms/IfmsPacksIT.java | 10/354 (rest is other streams) | 97% |
| sangam-config/.../ProfileDataTest.java | 1/48 (IT version owned by IFMS-WF/SL-97) | port only if the money line still applies | 97% |

### 2e. Generated outputs
All `src/main/resources/packs/**`, `profiles/in/dmn/**`, `packs.idx`, `money.json`: regenerate only, never copy (ADR-D-20). Confidence 99%.

## 3. Free-text profile amounts ('2CR', '5L', '1CR', ...)

Readers checked: `ResolvedProfile.amount(...)` has NO `src/main` caller (only `ProfileDataTest:120`). `ConfigConnector` `profile.get` returns raw values for requested paths; no pack BPMN/DMN/form/JSON asks for any key below (grep of *.java, *.bpmn, *.dmn, *.cmmn, *.json, *.py excluding profilegen and the profiles themselves). `Tree.decimal` (DocumentTypeCatalogue) does not parse unit suffixes (Tree.java:130).

| Key (example file:line, value) | Read by | Duplicate elsewhere | Conf. |
|---|---|---|---|
| objectGroupLimits EST_2CR/NONEST_5CR/OTHER_15CR (GOI.yaml:184-186; 2CR/5CR/15CR) | nobody (the names EST_2CR... are read as group CODES in ifms-reapp-rules.dmn) | thresholds hard-coded in gov_ifms.py:530-532 ('> 20000000' etc., rupees) | 98% |
| parliamentReport.amountAbove (GOI.yaml:210; 100CR) | nobody (`parliamentReport` in the reappropriation pack is a DMN output) | gov_ifms.py:533 ('> 1000000000') | 98% |
| appraisal.ladder[].upTo (GOI.yaml:267; 100CR/500CR/1000CR) | only ProfileDataTest:120 via ResolvedProfile.amount (asserts RUPEES 1000000000) | gov_ifms.py:1105-1109 ("Thresholds mirror the GOI profile appraisal.ladder") | 98% |
| appraisal.preInvestmentUpTo (GOI.yaml:286; 100CR) | nobody | - | 98% |
| cna.model1Threshold (GOI.yaml:348; 100CR) | nobody | - | 98% |
| gpf.annualCeiling (GOI.yaml:391; 5L) | nobody (gpf packs use their own DMN) | - | 97% |
| procurement.lpcUpTo (GOI.yaml:436; 5L), limitedTenderUpTo.general/scientificDepartments (GOI.yaml:448-449; 50L/1CR) | nobody | GOI_PROC rows in profilegen skeletons.py (ifms-procurement-mode DMN) | 98% |
| overpaymentWaiver.departmentUpTo (GOI.yaml:454; 2L) | nobody | - | 98% |
| procurementThresholds gemL1/limitedTender/limitedTenderScientific (GOI.yaml:1327-1329; 10L/50L/1CR) | nobody (skeleton rows data) | GOI_PROC | 98% |
| administratorDelegation governorOrLg/other (IN-PRESET-UT-NOLEG.yaml:18-19; 50CR/25CR) | nobody | - | 98% |
| budget.be2026_27Gross (DH.yaml:201; 2835.20CR) | nobody | - | 98% |
| publicAccounts...cfTransferAlert (RJ.yaml:209; 10CR) | nobody | - | 98% |
| procurement vmsBelow/gemAbove (KA.yaml:100-101; 5L) | nobody | - | 98% |
| receipts.channelThresholds[].atOrAbove (TN.yaml:79; 1CR) | nobody (DocumentTypeCatalogue reads `atOrAbove` only under documentTypes.*.signatures, which are numeric and ARE converted to paise by packs4: ProfileMoneyUnitsTest) | - | 97% |
| dbtBurst.maxPerDay (TG.yaml:108; 6000CR) | nobody | - | 98% |

Proposed resolution (needs integrator confirmation, Q-P1): keep these strings as authored major-unit data (they are statutory text, read by nobody), and state in the contract that `ResolvedProfile.amount` returns MAJOR units; any future reader converts once with `Money.ofMajor`. The root-cause alternative (packgen reads the thresholds from the profile instead of duplicating them in gov_ifms.py / skeletons.py) is a separate item, not PACKS4.

## 4. Draft: [CONTRACT] PACKS4-MINOR-UNITS (for the coordinator to send to IFMS)

> [CONTRACT] PACKS4-MINOR-UNITS (Sangam -> IFMS). Breaking for any caller that passes or reads money in pack process variables.
> 1. Every money value a shipped Sangam pack reads or writes is an integer number of ISO 4217 minor units (paise for INR) of type Long: start variables, task form answers, DMN inputs and outputs, SetVariables defaults, acceptance scenarios. Example: Rs 25 lakh = 250000000. A money variable is named by the convention in `META-INF/sangam/money.json` (suffixes such as amount, limit, ceiling, bandMax; listed extras and exclusions).
> 2. Money DMN columns are typeRef `number` with integer literals; money output expressions round once to a whole minor unit. No `double` money columns remain (`PackMoneyGuardTest`).
> 3. Forms: money fields are type `money` with the currency and its exponent.
> 4. Connectors: the ledger connector (`LedgerConnector`) and the X12 835 decoders take and return minor units; `config chain.signatures` compares the bill amount in paise with profile signature bands in paise (10 lakh = 100000000).
> 5. Delegation of powers: `ifms-financial-powers` maxAmount and the compared amount are paise (DmnPowerEvaluator).
> 6. Profiles: numeric money bands that code reads are paise; free-text statutory amounts ('2CR', '5L') are unchanged major-unit data, and `ResolvedProfile.amount` returns major units.
> 7. REST money fields (ledger, billing) are unchanged: decimal strings in major units (OpenAPI MoneyAmount), converted exactly at the API boundary (SD-398).
> Action for IFMS: send pack money in paise (Long); a value with a fraction or a binary float is refused. Effective from the Sangam commit that carries PACKS4 (sha in the BATCH line).

Confidence that the draft matches the packs4 code: 97% (items 1-5 from money.py, PackMoneyGuardTest, skeletons.py, ProfileMoneyUnitsTest; item 7 from BillingController/LedgerController `money(...)` boundary).

## 5a. Coordinator decisions (2026-11-12)
- Q-P1 YES: free-text strings stay major-unit data; the contract states `ResolvedProfile.amount` returns major units. Removing the duplicate DMN literals (gov_ifms.py:530-533, :1105-1109) is SL-102, NOT PACKS4.
- Q-P2 YES: `ProfileDataTest:120` rupee assertion stays.
- Q-P3: SL-33 (`gov_intl.py`, `sec_eu.py`, PFM/GDPR) belongs to B3: DO NOT port.
- Q-P4: IfmsPacksIT on PostgreSQL in the test phase, after the push.
- Plan accepted: money hunks only, never whole files. Start only on the coordinator's "2b landed" signal; the coordinator sends the [CONTRACT] text when PACKS4 lands.

## 5. Open questions for the integrator (answered in 5a)
- Q-P1: confirm section 3 resolution (keep free-text strings in major units; document `ResolvedProfile.amount` as major).
- Q-P2: `ProfileDataTest:120` asserts rupees for `appraisal.ladder.0.upTo`; keep (consistent with Q-P1)?
- Q-P3: SL-33 (`gov_intl.py`, `sec_eu.py`, PFM/GDPR packs) was bundled in wt-packs4. Port it with PACKS4 (owner B3?) or leave it out?
- Q-P4: running IfmsPacksIT on PostgreSQL is test-phase only (after push), per the code-complete plan.
