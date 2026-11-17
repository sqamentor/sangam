# Presentation walkthrough: GPF, bill and budget allocation

R8-present, 2026-11-17, base a321c57b (IFT-4 in 005d726d, IFT-1/IFT-5 in 9b73a430). A click path for presenting Sangam next to IFMS. Each step gives the
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
- Hosted under `/sangam/`: the UI base-path change is lane R8-present item 1 (config.json, router base name, font
  paths); check it is merged before the session. Locally at the root path this does not apply.
- Each person signs in once before the session, then the seed runs again (persons are created at first sign-in;
  README section 5).

| Person | Username | Role | Seat |
|---|---|---|---|
| Rakesh Sharma | `ddo.maker` | DDO (maker) | DDO-1 |
| Anita Verma | `hoo.office` | Head of Office | HOO-1 |
| Suresh Iyer | `pao.accounts` | Pay and accounts officer | PAO-1 |
| Farhan Qureshi | `bill.distributor` | Bill distributor | BD-1 |
| Lakshmi Menon | `dealing.hand` | Dealing hand | DH-1 |
| Harpreet Singh | `aao.accounts` | Assistant accounts officer | AAO-1 |
| Deepa Kulkarni | `second.signatory` | Second signatory | SS-1 |
| Pooja Nair | `gpf.subscriber` | GPF subscriber (employee) | none |
| Kavita Rao | `org.admin` | Department administrator | none |
| Manoj Gupta | `section.supervisor` | Supervisor | none |
| Neelam Joshi | `platform.admin` | Platform administrator (platform realm) | none |

## 1. Sign in as each role

| # | Action | Expected screen and result |
|---|---|---|
| 1.1 | Open the Sangam address. Choose Sign in. | The Keycloak sign-in page of the realm. |
| 1.2 | Sign in as `ddo.maker`. | Home. The menu shows Tasks, Start, Cases. The account menu shows Rakesh Sharma and seat DDO-1. |
| 1.3 | Sign out, then sign in as `hoo.office`, then as `pao.accounts`. | The same work menus, seats HOO-1 and PAO-1. |
| 1.4 | Sign in as `org.admin`. | The Administration menu (Org hierarchy, Seats, Assignments, Users, Policies). Seats lists the ten seats of PAO-OFFICE; Assignments shows each presentation person on their seat. |
| 1.5 | Sign in as `section.supervisor`. | Team (team workload) is in the menu and lists the open tasks by seat. |
| 1.6 | Sign in as `platform.admin` (platform realm). | Tenants and the engine administration pages. Without a platform realm in the hosted setup, skip this step (README section 4). |

## 2. Start a GPF application

| # | Action | Expected screen and result |
|---|---|---|
| 2.1 | As `gpf.subscriber`: Start, then "GPF" (`sangam-ifms-gpf`). | The application form. |
| 2.2 | Fill in purpose ADVANCE and amount Rs 50,000. Submit. | A case number. Cases shows the case as open. Configuration and eligibility checks run without a person. |

**AT RISK (payment-loop work, L-packs):** on a321c57b the form still asks for GPF account, purpose, amount, balance and
qualifying service (`ifms-gpf/forms/application.form.json`). The payment-loop change moves the GPF start inputs to
`employeeId` only, with the account, balance, service and payee taken from the employee record. When it is merged, 2.2
becomes: enter the employee id (and purpose and amount, if the merged form still asks for them); the rest is filled
from the record. Check the deployed form before the session.

## 3. DDO scrutiny

| # | Action | Expected screen and result |
|---|---|---|
| 3.1 | As `ddo.maker`: Tasks. | A "Scrutiny" task for the GPF case, offered to seat DDO-1 (pack setting `scrutinySeat=DDO-1`). |
| 3.2 | Open it, read the case panel, choose Approve (forward), add a remark. Complete. | The task leaves the list. Case timeline: scrutiny completed by Rakesh Sharma, DDO-1. |

The subscriber starts the case and the DDO scrutinises it: two different people, as SoD requires.

## 4. HOO sanction

| # | Action | Expected screen and result |
|---|---|---|
| 4.1 | As `hoo.office`: Tasks, then "Sanction / approval". | The sanction task (competent role HOO, `ifms-gpf-authority.dmn`), with the scrutiny remark. |
| 4.2 | Choose Sanction. | A GPF bill is raised (`GPF bill` call); it continues in step 6 from 6.3. |

## 5. A return and its rework

| # | Action | Expected screen and result |
|---|---|---|
| 5.1 | Start a second GPF case (step 2). At the sanction (4.1) choose Return, with a reason (mandatory). | A rework task at DDO-1 (`ifms-gpf-return-target.dmn`: HOO returns to the operator) with the reason. |
| 5.2 | As `ddo.maker`: open the rework task, correct, resubmit. | The case goes back to the HOO sanction (4.1). The timeline shows the return, its reason and the resubmission. |

Unblocked: IFT-4 is merged (005d726d, `gov.ifms.gpf` 1.0.3). Reject stays final.

## 6. A bill from submission to payment

| # | Action | Expected screen and result |
|---|---|---|
| 6.1 | As `ddo.maker`: Start, "Bill" (`sangam-ifms-bill`), choose a bill type, head of account, payee, amount. Submit. | The checks run: bill type, head of account, duplicate claim, payee record. A failed check ends with a stated reason (for example BILL_INVALID). |
| 6.2 | If asked "Confirm payee: account held by several parties": confirm the payee. | The bill continues. |
| 6.3 | PAO chain: `bill.distributor` "Assign bill in FIFO order"; `dealing.hand` "Dealing hand check"; `aao.accounts` "AAO check"; `pao.accounts` "PAO pass, object or return": Pass. | Each task appears for the next seat in turn. On pass, the approval is bound at the PAO pass (IFT-5) and execution starts. |
| 6.3a | If asked, `second.signatory`: "Second signature (DSC)". | Needs the DSC agent; leave it out of the session if no DSC agent is set up. |
| 6.4 | Alternative: at 6.3 the PAO objects or returns. As the initiator seat: "Reply to objection / correct", correct, resubmit. | The bill returns to the PAO chain; on the next pass the approval is bound and execution runs. Unblocked: IFT-1/IFT-5 merged (9b73a430, `gov.ifms.pao-chain` 1.0.3). |
| 6.5 | Payment through the rail. | Expected: payment advice and voucher, case CLOSED. **BLOCKED (payment loop, PFMS field mapping):** the bill sends its own payload, PfmsFileConnector needs bill id, amount and beneficiary name, account and IFSC, answers INVALID_INPUT, and the bill reissues the payment without a human step. Fix in progress in L-packs (`feat/round8-payloop`: pay-correct task, payee resolution, GPF inputs from `employeeId`). Do not show this step live until it is merged. |

## 7. Budget allocation (optional)

| # | Action | Expected screen and result |
|---|---|---|
| 7.1 | As the FA seat holder: Start, "Budget allocation", year, head of account, amount Rs 25 lakh. | A case. |
| 7.2 | Dealing hand enters it; `pao.accounts` checks and approves. | The DDO-1 authorisation rises by Rs 25 lakh; release falls by the same (IfmsPacksIT budgetDistribution). |

The FA and dealing-hand seats have only the pack-seed holders; give them presentation people first if this part is
shown.

## 8. Audit trail

| # | Action | Expected screen and result |
|---|---|---|
| 8.1 | As `org.admin` (or an auditor): Audit, search by the GPF case's business key. | The audit search lists the case's records. |
| 8.2 | Open the timeline (Audit, Timeline). | Every step in order: who (person and seat), when, the decision and remark. The return in step 5 appears once IFT-4 is merged. |
| 8.3 | Audit exports. | An export of the case's records can be requested. |

## 9. Summary of blocked steps

| Step | Defect | State |
|---|---|---|
| Hosting under `/sangam/` | UI base path (config.json, router, fonts) | R8-present item 1; check it is merged |
| 5.1 GPF return and rework | IFT-4 | unblocked (005d726d) |
| 6.4 bill PAO return | IFT-1/IFT-5 | unblocked (9b73a430) |
| 6.5 payment | payment loop, PFMS field mapping | BLOCKED: L-packs payment-loop work |
| 2.2 GPF start inputs | payment-loop work moves them to `employeeId` | at risk: check the deployed form |
