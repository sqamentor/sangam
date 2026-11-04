# Government Process Pattern Catalogue (GP) — test basis for the base-engine decision

This catalogue drives experiments **E16** (pattern tests) and **E17** (domain end-to-end models). Every pattern and every domain model runs on **both engines** (Operaton 2.2.0, Flowable 8.0.0) with identical models wherever the standards allow it. Every engine-specific variant or workaround is recorded.

Domains covered:
- IFMS: budget, bills, payments, receipts/challan, pension and salary
- Works / WAMS: estimate → sanctions → tender → work order → measurement → running bills → final bill
- Tender / e-procurement
- e-Recruitment
- e-Office file movement
- Grievance (CPGRAMS-style)
- RTI
- HR / leave
- Scheme / DBT
- Licence / permit
- Inter-department referral

These apply to central ministries, departments and divisions, and to state departments.

| ID | Pattern | Typical government use | Engine features exercised |
|---|---|---|---|
| GP-01 | Maker → checker → approver by seat, with amount bands | Bill passing, sanctions (delegation of financial powers) | User tasks by group, DMN FIRST hit policy |
| GP-02 | Multi-level hierarchy with return to any earlier level | e-Office noting, file movement | Gateways, loops, process variables |
| GP-03 | Committee: all members must sign | Purchase/tender evaluation committee | Parallel multi-instance, completion = all |
| GP-04 | Committee quorum (n of m) | Selection boards, DPC | Multi-instance completionCondition |
| GP-05 | Chained sanctions (administrative approval → technical sanction → expenditure sanction) | Works, projects | Sequential sub-processes / call activities |
| GP-06 | SLA with business-day calendar, holidays, reminders, escalation | Citizen services, RTI, grievances | Timers (duration/date), boundary non-interrupting, business calendar |
| GP-07 | Deadline extension of running instances (corrigendum) | Tender bid-date extension | Modify timers / instance modification |
| GP-08 | Bulk intake: thousands of instances, bulk approve/reject | Recruitment applications, scheme applications | Mass start, batch operations, query performance |
| GP-09 | Large multi-instance (≥ 5,000 items) | Scoring applicants, beneficiary lists | Multi-instance cardinality, history volume |
| GP-10 | Multi-year process with monthly cycles | Works running bills, AMC contracts | Cycle timers, loops, long-lived instances (continue-as-new) |
| GP-11 | Budget availability check + encumbrance, released on cancel | IFMS commitment control | External task with idempotency, compensation |
| GP-12 | Payment with unknown outcome + reconciliation | PFMS/treasury payments | Lease, operationId, reconciliation (VI.3) |
| GP-13 | Refund via compensation | EMD refund, fee refund | Compensation events |
| GP-14 | Withdrawal/cancellation at any stage | Applicant withdraws, tender cancelled | Interrupting event sub-process (message/signal) |
| GP-15 | External portal callbacks, including early arrival | Fee payment callback, bid submission | Message correlation, inbox buffering |
| GP-16 | Document checklist + clarification loop | Licence, scheme scrutiny | Message catch, loop |
| GP-17 | Field inspection scheduling and reassignment | Inspections, verification | User task due dates, reassignment |
| GP-18 | Transfer / additional charge mid-flow | Officer transferred, on leave | Task reassignment by seat, delegation |
| GP-19 | Rule change mid-flight: old instances keep the old rule | New DFPR / fee notification | DMN version binding |
| GP-20 | Migrate running instances to a new process version | Revised procedure (GFR amendment) | Process instance migration |
| GP-21 | Ad-hoc / case handling | Grievance, audit para, court case | CMMN or ad-hoc sub-process |
| GP-22 | Appeal / reopen after closure | RTI first appeal, grievance reopen | Linked new instance, history lookup |
| GP-23 | Two-envelope opening, gated by time and committee | Technical then financial bid opening | Timer gating + committee multi-instance |
| GP-24 | Time-locked action (not allowed before a date) | Bid opening, result declaration | Timer intermediate / date gating |
| GP-25 | Negotiation loop, retender if failed | L1 negotiation | Loops back to an earlier stage |
| GP-26 | Merit list with reservation/roster rules | Recruitment results | DMN COLLECT / rule ordering |
| GP-27 | Bulk document generation (admit cards, orders) | Exams, sanction orders | External tasks in bulk |
| GP-28 | Waitlist promotion when a candidate declines | Recruitment DV, admissions | Event-triggered next instance / signal |
| GP-29 | Auto-approve below threshold, else route | Leave, small purchases | DMN + gateway |
| GP-30 | Periodic generation (monthly pension/salary bills) | Pension, salary | Timer start cycle |
| GP-31 | Bank / treasury file reconciliation | Challan/receipt matching | File-based external work |
| GP-32 | Inter-department referral | Concurrence from Finance/Law | Call activity across tenants / departments |
| GP-33 | Notifications at each step (SMS/e-mail, DLT) | All citizen services | Send tasks / external tasks |
| GP-34 | Digital signature bound to the approval | Orders, certificates | External task + payload hash |
| GP-35 | Statutory clocks (RTI 30 days / 48 hours) with appeal | RTI | Timers + escalation |
| GP-36 | MIS / audit queries over history | CAG audit, dashboards | History queries, completeness |
| GP-37 | Same model, department-specific rule overrides (multi-tenant) | Central model, state variations | Tenants, DMN per tenant |
| GP-38 | Hindi / Unicode data round trip | Bilingual records | Variable storage, queries |
| GP-39 | Officer inbox with ≥ 10k tasks and filters under authorization | Large departments | Task queries + VI.4 predicate |
| GP-40 | Suspend / resume instance (court stay) | Litigation holds | Suspension API |

### Added after validation against official manuals (`results/GP-coverage-validation.md`)

| ID | Pattern | Typical government use | Engine features exercised |
|---|---|---|---|
| GP-41 | Security-instrument lifecycle (BG / performance security / EMD): verify → extend or encash before expiry → release | Contracts, tenders | Date timers re-armed on running instances, boundary events |
| GP-42 | Cumulative-threshold re-sanction (running total crosses the sanctioned limit, e.g. 10 % over AA) | Works, projects | Variable aggregation across iterations, conditional events |
| GP-43 | Live contract amendment (variation, extra item, extension of time, LD) changes a running process | Works contracts | Instance modification, timer reset, parent data update from a child |
| GP-44 | Deemed outcome on timeout | RTI s7(2) deemed refusal, service-guarantee Acts | Interrupting timer → automatic outcome |
| GP-45 | Pausable statutory clock (stop and resume, excluded time) | RTI s7(3), e-Office "park", CPGRAMS interim reply | Suspend/resume timers, remaining-time computation |
| GP-46 | Third-party / objection window | RTI s11, land acquisition s15 | Timer-bounded message collection |
| GP-47 | Provisional result → paid challenge → bulk recompute → final | SSC answer-key cycle | Bulk messages, multi-instance recompute |
| GP-48 | Ledger carried across instances and years | Reservation roster, leave balance, budget balance | Shared state outside instances, concurrency |
| GP-49 | Partial batch failure → route each failed item by reason code → resend only the failures | DBT/PFMS returns | Multi-instance with per-item outcome, retry subsets |
| GP-50 | Return to the originating system under the same ID | Treasury objection → DDO; COMPACT → Bhavishya | Correlation by business key, loop |
| GP-51 | Hearing series with dates set by an outside forum | Court cases (LIMBS), appeals | Externally set timers, repeated events |
| GP-52 | Record lifecycle after closure (retention, review) | CSMOP record review | Post-completion timers / history |
| GP-53 | Physical item tracked alongside the digital flow | Physical files, MB books, original BGs | User tasks + state flags |
| GP-54 | Case split / part / link | e-Office volumes, part files | Linked instances, call activities |
| GP-55 | Periodic verification that opens discrepancy cases | GFR 213/217 stock verification | Timer start cycle + conditional case creation |

Additional domains: stores/inventory, assets/vehicles, court cases (LIMBS), audit paras, land acquisition (RFCTLARR), contract variations/extension of time, price escalation, liquidated damages, bank guarantees, consultancy procurement, inspections, right to public services.

Domain end-to-end models (E17):
- **D-IFMS-BILL** (exists as WF-1)
- **D-BUDGET-REAPPROPRIATION**
- **D-WORKS**: estimate → AA → TS → tender → work order → measurement book → RA bills → final bill → completion
- **D-TENDER**: NIT → corrigendum → bid submission → technical evaluation → financial opening → L1 negotiation → LoA → EMD refund
- **D-RECRUIT**: notification → application + fee → scrutiny → exam → result with reservation → DV with waitlist → appointment
- **D-GRIEVANCE** (case)
- **D-RTI**
- **D-LEAVE**
- **D-PENSION-MONTHLY**
- **D-DBT-SCHEME**
- **D-LICENCE** (exists as WF-3)
- **D-CHALLAN-RECON**
