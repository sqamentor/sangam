# Presentation walkthrough: GPF, bill and budget allocation

R8-present, 2026-11-17, base 66239726. A click path for presenting Sangam next to IFMS. Each step gives the
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
- **Hosted under `/sangam/`: BLOCKED** until the UI reads `config.json` and its routes relative to its base path
  (README section 6 item 1). Locally at the root path, everything below works as described.

| Person | Username | Role | Seat |
|---|---|---|---|
| Rakesh Sharma | `ddo.maker` | DDO (maker) | DDO-1 |
| Anita Verma | `hoo.office` | Head of Office | HOO-1 |
| Suresh Iyer | `pao.accounts` | Pay and accounts officer | PAO-1 |
| Kavita Rao | `org.admin` | Department administrator | none |
| Manoj Gupta | `section.supervisor` | Supervisor | none |
| Neelam Joshi | `platform.admin` | Platform administrator (platform realm) | none |

## 1. Sign in as each role

| # | Action | Expected screen and result |
|---|---|---|
| 1.1 | Open the Sangam address. Choose Sign in. | The Keycloak sign-in page of the realm. |
| 1.2 | Sign in as `ddo.maker`. | Home. The menu shows Tasks, Start, Cases. The account menu shows Rakesh Sharma and seat DDO-1. |
| 1.3 | Sign out, then sign in as `hoo.office`, then as `pao.accounts`. | The same work menus, seats HOO-1 and PAO-1. |
| 1.4 | Sign in as `org.admin`. | The Administration menu (Org hierarchy, Seats, Assignments, Users, Policies). Seats lists DDO-1, HOO-1, PAO-1 in PAO-OFFICE. |
| 1.5 | Sign in as `section.supervisor`. | Team (team workload) is in the menu and lists the open tasks by seat. |
| 1.6 | Sign in as `platform.admin` (platform realm). | Tenants and the engine administration pages. Without a platform realm in the hosted setup, skip this step (README section 4). |

## 2. Start a GPF application

| # | Action | Expected screen and result |
|---|---|---|
| 2.1 | As `ddo.maker`: Start, then "GPF" (`sangam-ifms-gpf`). | The start form: GPF account, purpose (advance), amount, balance, qualifying service, employee, payee and bank details. |
| 2.2 | Fill in: purpose ADVANCE, amount Rs 50,000, balance Rs 4,00,000, qualifying service 12 years. Submit. | A case number. Cases shows the case as open. The pack's configuration and eligibility checks run without a person. |

**AT RISK:** the start inputs of GPF change on `feat/round8-payloop` (employee id only, the rest from the employee
record). Use the form as the deployed pack version shows it.

## 3. DDO approval (scrutiny)

| # | Action | Expected screen and result |
|---|---|---|
| 3.1 | As the scrutiny seat holder: Tasks. | A "Scrutiny" task for the GPF case, offered to its seat. |
| 3.2 | Open it, read the case panel, choose Approve (forward), add a remark. Complete. | The task leaves the list. Case timeline: scrutiny completed by the seat holder. |

The scrutiny and sanction seats come from the pack's configuration table (`scrutinySeat`, `sanctionSeat`), not from
this document. Check the seat on the task before the session; Sangam refuses the same person at two steps (SoD).

## 4. HOO sanction

| # | Action | Expected screen and result |
|---|---|---|
| 4.1 | As `hoo.office`: Tasks, then "Sanction / approval". | The sanction task with the scrutiny remark. |
| 4.2 | Choose Sanction. | "Prepare the document to sign" goes to the initiator seat; after it, a GPF bill is raised (step 6 applies). |

## 5. A return and its rework

| # | Action | Expected screen and result |
|---|---|---|
| 5.1 | Start a second GPF case (step 2). At the sanction (4.1) choose Return, with a reason. | Expected: a rework task at the preparer with the reason; after resubmission, the case goes back to sanction. |

**BLOCKED on 66239726 (IFT-4):** on the base, a GPF sanction RETURNED goes to rejection and the case ends. The fix
(rework task at the preparer, `gprReturnTarget`, resubmit loops to sanction; `gov.ifms.gpf` 1.0.3) is on
`feat/round8-ifms-tests`, not yet in integration. Until it is merged, show the return in the bill instead (6.4),
which is itself at risk.

## 6. A bill from submission to payment

| # | Action | Expected screen and result |
|---|---|---|
| 6.1 | As `ddo.maker`: Start, "Bill" (`sangam-ifms-bill`), choose a bill type, head of account, payee, amount. Submit. | The checks run: bill type, head of account, duplicate claim, payee record. A failed check ends with a stated reason (for example BILL_INVALID). |
| 6.2 | If asked "Confirm payee: account held by several parties": confirm the payee. | The bill continues. |
| 6.3 | As `pao.accounts`: Tasks, the PAO / treasury chain task. Pass the bill. | The bill moves to payment. |
| 6.4 | Alternative: raise an objection. As `ddo.maker`: "Reply to objection / correct", correct, resubmit. | Expected: the bill returns to the PAO chain. **BLOCKED (IFT-1 pattern, R8-ifms-tests):** after a PAO return, no task binds the approval, so preparation throws APPROVAL_INVALIDATED and the case ends without an execution outcome. Fix in progress (bind at the PAO pass). |
| 6.5 | Payment through the rail. | Expected: payment advice and voucher, case CLOSED. **BLOCKED (payment loop, PFMS field mapping):** the bill sends its own payload, PfmsFileConnector needs bill id, amount and beneficiary name, account and IFSC, answers INVALID_INPUT, and the bill reissues the payment without a human step. Fix in progress on `feat/round8-payloop` (pay-correct task, payee resolution). Do not show this step live until it is merged. |

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
| Hosting under `/sangam/` | UI base path (config.json, router) | needs a UI change, not started |
| 5.1 GPF return and rework | IFT-4 | fixed on `feat/round8-ifms-tests`, not merged |
| 6.4 bill PAO return | IFT-1 pattern (bill and SNA-SPARSH) | fix in progress |
| 6.5 payment | payment loop, PFMS field mapping | fix in progress (`feat/round8-payloop`) |
| 2.1 GPF start inputs | payment loop work changes them | at risk |
