# Presentation walkthrough: GPF, bill and budget allocation

R8-present, 2026-11-17, base 9fff7ef6 (IFT-4 in 005d726d, IFT-1/IFT-5 in 9b73a430). The people are the Jaipur
personas of IFMS E-67 (one login in realm `oneifms` for both systems; joint ledger [ANSWER] SANGAM-PERSONA-SEATS). A click path for presenting Sangam next to IFMS. Each step gives the
screen you should see and the expected result. Steps that a known open defect affects are marked **BLOCKED** or
**AT RISK**, with the defect.

## 0. Before you start

- Setup: `deploy/presentation/README.md` (hosting under `/sangam/`, Keycloak, seed). Locally, the users come from
  `python -I deploy/compose/keycloak/generate_realm.py --presentation`.
- Passwords are never written here. They are in the environment variables `SANGAM_PRESENTATION_PASSWORD_<USERNAME>`
  or in the git-ignored `deploy/presentation/presentation-users.local.json`.
- The tenant is `GOI.FIN`, office `PAO-OFFICE`. Deployed packs: `sangam.core`, `gov.ifms.gpf`, `gov.ifms.bill`,
  `gov.ifms.budget-allocation`, `gov.ifms.pao-chain`.
- Use a private browser window per person, so two sign-ins do not share one session.
- Hosted under `/sangam/`: the UI reads config.json, fonts and the OIDC callback under its base, and its router uses
  the base as base name (`sangam-ui/src/basePath.ts`); the front image builds it with `SANGAM_UI_BASE=/sangam/`. Locally at the root path this does not apply.
- Seed order (settled from code): run the seed, then each person signs in once (just-in-time creation is on for the
  tenant, person id = username), then run the seed again so the seat holders get their postings (README section 5).
- After sign-in a seat holder lands on Tasks, the inbox of their seat; a person without a seat lands on Welcome.
- Jaipur only: Kishangarh personas wait until seats are picked per office (a gap after 0.1.0).

| Person (E-67 id) | Username | Sangam role | Seat |
|---|---|---|---|
| Kavita Sharma (PRS-RJ-0005) | `kavita.sharma` | DDO (bill maker) | DDO-1 |
| Ramesh Meena (PRS-RJ-0006) | `ramesh.meena` | Head of Office (DDO approver) | HOO-1 |
| Deepak Saini (PRS-RJ-0012) | `deepak.saini` | Dealing hand (treasury scrutiny) | DH-1 |
| Sunita Rathore (PRS-RJ-0013) | `sunita.rathore` | Pay and accounts officer (passing officer) | PAO-1 |
| Harish Mathur (PRS-RJ-0001) | `harish.mathur` | Financial adviser (FD allotment), State HQ (Jaipur, INFERRED) | FA-1 |
| Govind Khandelwal (PRS-RJ-0024) | `govind.khandelwal` | Bill distributor, TR-JPR-01 Treasury Jaipur City | BD-1 |
| Mukesh Swami (PRS-RJ-0025) | `mukesh.swami` | Assistant accounts officer, TR-JPR-01 | AAO-1 |
| Sarita Champawat (PRS-RJ-0026) | `sarita.champawat` | Second signatory, TR-JPR-01 | SS-1 |
| Anil Choudhary (PRS-RJ-0019) | `anil.choudhary` | Employee, GPF subscriber | none |
| Pradeep Saxena (PRS-RJ-0007) | `pradeep.saxena` | Auditor (read only), State HQ (Jaipur, INFERRED) | none |
| Rajendra Prasad Sharma (PRS-RJ-0022) | `rajendra.sharma` | Department administrator, State HQ (Jaipur, INFERRED) | none |
| Meenakshi Kanwar (PRS-RJ-0023) | `meenakshi.kanwar` | Department administrator, State HQ (Jaipur, INFERRED) | none |
| Neelam Joshi | `platform.admin` | Platform administrator (platform realm) | none |

## 1. Sign in as each role

| # | Action | Expected screen and result |
|---|---|---|
| 1.1 | Open the Sangam address. Choose Sign in. | The Keycloak sign-in page of realm `oneifms`. |
| 1.2 | Sign in as `kavita.sharma`. | Tasks (the DDO-1 inbox). The account menu shows Kavita Sharma and seat DDO-1. |
| 1.3 | Sign out, then sign in as `ramesh.meena`, `deepak.saini`, `sunita.rathore`, `harish.mathur`. | Each lands on Tasks for HOO-1, DH-1, PAO-1, FA-1. |
| 1.4 | Sign in as `anil.choudhary`. | Welcome (no seat); Start lists the GPF application. |
| 1.5 | Sign in as `rajendra.sharma` (or `meenakshi.kanwar`). | Welcome; the Administration menu (Org hierarchy, Seats, Assignments, Users). Seats lists the ten seats of office Jaipur (PAO-OFFICE); Assignments shows each seat holder. |
| 1.6 | Sign in as `pradeep.saxena`. | Welcome; Audit is in the menu, nothing to act on. |
| 1.7 | Sign in as `platform.admin` (platform realm). | Tenants and the engine administration pages. Without a platform realm in the hosted setup, skip this step (README section 4). |

## 2. Start a GPF application

| # | Action | Expected screen and result |
|---|---|---|
| 2.1 | As `anil.choudhary`: Start, then "GPF" (`sangam-ifms-gpf`). | The application form. |
| 2.2 | Fill in purpose ADVANCE and amount Rs 50,000. Submit. | A case number. Cases shows the case as open. Configuration and eligibility checks run without a person. |

**AT RISK (payment-loop work, L-packs):** on 9fff7ef6 the form still asks for GPF account, purpose, amount, balance and
qualifying service (`ifms-gpf/forms/application.form.json`). The payment-loop change moves the GPF start inputs to
`employeeId` only, with the account, balance, service and payee taken from the employee record. When it is merged, 2.2
becomes: enter the employee id (and purpose and amount, if the merged form still asks for them); the rest is filled
from the record. Check the deployed form before the session.

## 3. DDO scrutiny

| # | Action | Expected screen and result |
|---|---|---|
| 3.1 | As `kavita.sharma`: Tasks. | A "Scrutiny" task for the GPF case, offered to seat DDO-1 (pack setting `scrutinySeat=DDO-1`). |
| 3.2 | Open it, read the case panel, choose Approve (forward), add a remark. Complete. | The task leaves the list. Case timeline: scrutiny completed by Kavita Sharma, DDO-1. |

Anil Choudhary starts the case and the DDO scrutinises it: two different people, as SoD requires.

## 4. HOO sanction

| # | Action | Expected screen and result |
|---|---|---|
| 4.1 | As `ramesh.meena`: Tasks, then "Sanction / approval". | The sanction task (competent role HOO, `ifms-gpf-authority.dmn`), with the scrutiny remark. |
| 4.2 | Choose Sanction. | A GPF bill is raised (`GPF bill` call); it continues in step 6 from 6.3. |

## 5. A return and its rework

| # | Action | Expected screen and result |
|---|---|---|
| 5.1 | Start a second GPF case (step 2). At the sanction (4.1) choose Return, with a reason (mandatory). | A rework task at DDO-1 (`ifms-gpf-return-target.dmn`: HOO returns to the operator) with the reason. |
| 5.2 | As `kavita.sharma`: open the rework task, correct, resubmit. | The case goes back to the HOO sanction (4.1). The timeline shows the return, its reason and the resubmission. |

Unblocked: IFT-4 is merged (005d726d, `gov.ifms.gpf` 1.0.3). Reject stays final.

## 6. A bill from submission to payment

| # | Action | Expected screen and result |
|---|---|---|
| 6.1 | As `kavita.sharma`: Start, "Bill" (`sangam-ifms-bill`), choose a bill type, head of account, payee, amount. Submit. | The checks run: bill type, head of account, duplicate claim, payee record. A failed check ends with a stated reason (for example BILL_INVALID). |
| 6.2 | If asked "Confirm payee: account held by several parties": confirm the payee (PAO-1, `sunita.rathore`; INFERRED, the pack hard-codes this seat). | The bill continues. **AT RISK (pack separation-of-duties gap):** the same PAO-1 seat then gets the PAO pass in 6.3, and its guard refuses it (422 SOD_VIOLATION). Use a payee with a single account holder so this step does not arise. |
| 6.3 | PAO chain: "Assign bill in FIFO order" (BD-1); `deepak.saini` "Dealing hand check" (DH-1); "AAO check" (AAO-1); `sunita.rathore` "PAO pass, object or return" (PAO-1): Pass. | Each task appears for the next seat in turn. On pass, the approval is bound at the PAO pass (IFT-5) and execution starts. BD-1 `govind.khandelwal`, AAO-1 `mukesh.swami` (joint ledger SANGAM-PERSONA-ADD). |
| 6.3a | If asked: as `sarita.champawat`, "Second signature (DSC)" (SS-1). | Needs the DSC agent on the presenting machine. |
| 6.4 | Alternative: at 6.3 the PAO objects or returns. As `kavita.sharma` (the initiator seat): "Reply to objection / correct", correct, resubmit. | The bill returns to the PAO chain; on the next pass the approval is bound and execution runs. Unblocked: IFT-1/IFT-5 merged (9b73a430, `gov.ifms.pao-chain` 1.0.3). |
| 6.5 | Payment through the rail. | Expected: payment advice and voucher, case CLOSED. **BLOCKED (payment loop, PFMS field mapping):** the bill sends its own payload, PfmsFileConnector needs bill id, amount and beneficiary name, account and IFSC, answers INVALID_INPUT, and the bill reissues the payment without a human step. Fix in progress in L-packs (`feat/round8-payloop`: pay-correct task, payee resolution, GPF inputs from `employeeId`). Do not show this step live until it is merged. |

## 7. Budget allocation (optional)

| # | Action | Expected screen and result |
|---|---|---|
| 7.1 | As `harish.mathur` (FA-1): Start, "Budget allocation", year, head of account, amount Rs 25 lakh. | A case. |
| 7.2 | `deepak.saini` (DH-1) enters it; `sunita.rathore` (PAO-1) checks and approves. | The DDO-1 authorisation rises by Rs 25 lakh; release falls by the same (IfmsPacksIT budgetDistribution). |

FA-1 as the starting seat is INFERRED in the pack (SL-57, IFMS R-99 v0.2 s6.1); E-67's FD allotment checker
(`nisha.agarwal`) has no Sangam seat.

## 8. Audit trail

| # | Action | Expected screen and result |
|---|---|---|
| 8.1 | As `pradeep.saxena` (auditor) or a department administrator: Audit, search by the GPF case's business key. | The audit search lists the case's records. |
| 8.2 | Open the timeline (Audit, Timeline). | Every step in order: who (person and seat), when, the decision and remark. The return in step 5 appears once IFT-4 is merged. |
| 8.3 | Audit exports. | An export of the case's records can be requested. |

## 9. Summary of blocked steps

| Step | Defect | State |
|---|---|---|
| Hosting under `/sangam/` | UI base path (config.json, router, fonts) | unblocked (`basePath.ts`) |
| 5.1 GPF return and rework | IFT-4 | unblocked (005d726d) |
| 6.4 bill PAO return | IFT-1/IFT-5 fixed (9b73a430); reached only through 6.3 | waits on the 6.3 persona gap |
| 6.5 payment | payment loop, PFMS field mapping | BLOCKED: L-packs payment-loop work |
| 6.2 payee review then 6.3 PAO pass | both on seat PAO-1; the PAO pass guard refuses the same seat | AT RISK: pack gap reported, avoid the payee-review path |
| 2.2 GPF start inputs | payment-loop work moves them to `employeeId` | at risk: check the deployed form |
