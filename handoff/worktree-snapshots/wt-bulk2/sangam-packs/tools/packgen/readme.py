# SPDX-License-Identifier: Apache-2.0
"""Writes sangam-packs/README.md (pack catalogue and coverage map) from the generated packs."""
import os

GP = {
    1: 'Maker > checker > approver by seat with amount bands', 2: 'Multi-level hierarchy, return to any level',
    3: 'Committee: all members sign', 4: 'Committee quorum (n of m)', 5: 'Chained sanctions',
    6: 'SLA with business calendar, reminders, escalation', 7: 'Deadline extension of a running case (corrigendum)',
    8: 'Bulk intake and bulk decisions', 9: 'Large sets: one instance per item + aggregation (no large MI)',
    10: 'Multi-year process with monthly cycles', 11: 'Budget check + encumbrance, released on cancel',
    12: 'Payment with unknown outcome + reconciliation', 13: 'Refund via compensation', 14: 'Withdrawal / cancellation at any stage',
    15: 'External callbacks incl. early arrival', 16: 'Document checklist + clarification loop',
    17: 'Field inspection scheduling and reassignment', 18: 'Transfer / additional charge mid-flow',
    19: 'Rule change mid-flight: old cases keep the old rule', 20: 'Migrate running cases to a new version',
    21: 'Ad-hoc / case handling', 22: 'Appeal / reopen after closure', 23: 'Two-envelope opening by time and committee',
    24: 'Time-locked action', 25: 'Negotiation loop, retender', 26: 'Merit list with reservation',
    27: 'Bulk document generation', 28: 'Waitlist promotion', 29: 'Auto-approve below threshold',
    30: 'Periodic generation (timer start cycle)', 31: 'Bank / treasury file reconciliation', 32: 'Inter-department referral',
    33: 'Notifications at each step (DLT)', 34: 'Digital signature bound to the approval', 35: 'Statutory clocks with appeal',
    36: 'MIS / audit over history', 37: 'Department-specific rule override (tenant DMN)', 38: 'Hindi / Unicode round trip',
    39: 'Large inbox under authorization', 40: 'Suspend / resume (court stay)', 41: 'Security-instrument lifecycle',
    42: 'Cumulative-threshold re-sanction', 43: 'Live contract amendment', 44: 'Deemed outcome on timeout',
    45: 'Pausable statutory clock', 46: 'Third-party / objection window', 47: 'Provisional result > challenge > bulk recompute',
    48: 'Ledger carried across instances and years', 49: 'Partial batch failure, resend only failures',
    50: 'Return to the originating system under the same id', 51: 'Hearing series set by an outside forum',
    52: 'Record lifecycle after closure', 53: 'Physical item tracked alongside', 54: 'Case split / part / link',
    55: 'Periodic verification opening discrepancy cases'}

UP = {1: 'High-throughput straight-through', 2: 'Saga across services', 3: 'Event-stream correlation',
      4: 'SLA / skills-based routing', 5: 'Four-eyes / dual control', 6: 'Human-in-the-loop AI review',
      7: 'Process-mining improvement loop', 8: 'Long-lived entity lifecycle', 9: 'Dynamic case with external experts',
      10: 'Tiered escalation and swarming', 11: 'Change approval with window and rollback',
      12: 'Order decomposition and amendment', 13: 'Returns, refunds, partial fulfilment', 14: 'Three-way match',
      15: 'Periodic recertification at scale', 16: 'Fan-out provisioning with deadline and evidence',
      17: 'Regulated clinical / benefit pathway', 18: 'Non-conformance to CAPA', 19: 'Contract lifecycle',
      20: 'RPA step against a legacy UI', 21: 'IoT storm control', 22: 'External-party collaboration'}

AC = {1: 'Initiator > selected checker > approver by band; reconciles; audit shows seat, person, rule version',
      2: 'Change after approval invalidates the approval', 3: 'Lost payment response > UNKNOWN_OUTCOME > reconciled, no duplicate',
      4: 'File up three, back two, resubmitted, approved; acted-for', 5: 'Ineligible after verification; partial disbursement; account failure',
      6: 'Licence SLA escalation; lost fee callback reconciled; signed certificate', 7: 'Same pilot on Oracle, SQL Server, PostgreSQL',
      8: 'Workers in five languages with the same semantics', 9: 'Tenant isolation', 10: 'Hindi and English UI',
      11: 'Air-gapped install'}

AC_SUPPORT = {
    7: 'Packs contain no SQL and no dialect-specific code; models and delegates use the Operaton public API only. Proof: WS-ENGINE DB matrix (T18, T31).',
    8: 'External-task topics declared by the packs (`worker/PackWorkers`: sangam.docs.bulk, sangam.rpa, sangam.pfms.paymentFile, sangam.ai.extract) with stable operationIds; class (c) topics never auto-retry. Proof: WS-SDK contract tests (T01-T04).',
    9: '`PackDeployer.deploy(pack, tenantId)` deploys per tenant; cross-department work goes through `referral.send` to the other tenant inbox (core referral, J-P2-E09); same-tenant messages only via `CorrelationDelegate`. Proof: WS-SECURITY (T13, T14).',
    10: 'Every form JSON carries English and Hindi labels for the title, every field and every decision option; output templates are requested with languages `hi,en`. Proof: WS-UI (T32).',
    11: 'Every port has an in-process default (simulator, in-memory seats, calendar, ledgers, audit log); no outbound call unless WS-INTEGRATION installs real adapters. Proof: WS-DEPLOY (T50).'}

JAVA = [
    ('spi/SangamPack.java, spi/PackDescriptor.java, spi/ManifestPack.java', 'Pack SPI descriptor: id, version, models, forms, seeds, connectors, covers, acceptance', 'R43, K10'),
    ('spi/PackRegistry.java', 'Discovery (packs.idx + ServiceLoader), dependency order, lookup by covered id', 'R43'),
    ('spi/PackDeployer.java', 'Deployment per tenant with duplicate filtering, forms and seeds in the same deployment', 'R43, R47, GP-19, GP-37, AC-09'),
    ('spi/ModelGuard.java', 'Deploy-time guard: multi-instance <= 50 with sangam.maxCardinality, historyTimeToLive', 'GP-09, ARCHITECTURE 1.6'),
    ('spi/PackMigrator.java', 'Migration of running cases to a new pack version', 'GP-20'),
    ('port/ConnectorGateway.java (+ ConnectorRequest, ConnectorResult, OperationStatus, ReconciliationRequest)', 'The only route to external systems; outbox contract; result / cancel / reconciliation messages', 'VI.1 r5, VI.3, A-2, K13, R41'),
    ('port/SeatDirectory.java, port/SeatHolding.java', 'Seat / hierarchy / acting-for model used by delegates', 'IX.5, K7'),
    ('port/BusinessCalendar.java', 'Working-day calendar and SLA specifications', 'R31, GP-06'),
    ('port/PackAuditSink.java, port/PackAuditEvent.java', 'X.4.0.3 audit events', 'R42'),
    ('port/BatchLedger.java', 'Aggregation store for the per-item pattern', 'GP-09, GP-49'),
    ('port/LedgerStore.java', 'Ledgers across instances and years', 'GP-11, GP-48'),
    ('port/PackRuntime.java', 'Port holder with simulator defaults; install() for production adapters', 'ADR-D-13, R56'),
    ('sim/*', 'Simulator gateway with failure injection, in-memory seats, calendar, ledgers, audit', 'ADR-D-13, AC-11'),
    ('core/PackStartListener.java', 'DRAFT to SUBMITTED: package binding, initiator, material hash, case clock', 'R43, K4, K5, K10, R31'),
    ('core/LifecycleStateDelegate.java, core/CaseState.java', 'IX.3 states and their audit events', 'IX.3, X.4.2.1'),
    ('core/ActorRecorderListener.java, core/TaskActor.java', 'Who acted as which seat; ACTED_FOR', 'IX.5 r1/r4, R42'),
    ('core/SodGuardListener.java', 'Segregation of duties by person and seat, controlled exception', 'IX.5 r5, K5, CX-8, R19'),
    ('core/PowerLimitGuardListener.java', 'Delegated power on the action date (DMN)', 'IX.5 r3, K6'),
    ('core/ApprovalBindingListener.java', 'Approval bound to the payload hash; joint approvals', 'VI.4 r5, K4, AC-02'),
    ('core/RecommendationLevelDelegate.java', 'n-level recommendation, return to any level, comments, marking', 'GP-02, J-P2-E01/E02/E05'),
    ('core/SeatResolutionDelegate.java', 'Role to seat by hierarchy, eligible candidates, power forward', 'IX.5 r2, S07, J-P1-E07, J-P3-E05'),
    ('core/SlaDelegate.java', 'SLA plan, warning, breach (escalate / deemed approval / deemed refusal), pause / resume, deadlines', 'R31, R79, GP-06, GP-44, GP-45, J-P4-E01, J-P4-E07'),
    ('core/ExternalOperationDelegate.java', 'Execution, acknowledgement, lookup, UNKNOWN_OUTCOME, reconciliation, cancel', 'VI.3, VI.5, A-2, T35, AC-03'),
    ('core/ConnectorCallDelegate.java', 'Generic query / exactly-once submit through the gateway', 'VI.3, K13'),
    ('core/DecisionDelegate.java, core/Decisions.java', 'DMN with tenant override and deployment binding; RULE_EVALUATED', 'GP-19, GP-37, R42'),
    ('core/DocumentDelegate.java', 'Outputs from the approved data only', 'K4, R54'),
    ('core/SignatureDelegate.java', 'DSC / eSign over the approved hash', 'R57, GP-34'),
    ('core/NotifyDelegate.java', 'DLT / e-mail / in-app notifications, never failing the step', 'R60, GP-33'),
    ('core/CaseRecordDelegate.java', 'Reject with reasons, resubmit versions, retention, appeal exclusions, deemed binding, legal hold', 'R43, R61, GP-22, GP-52'),
    ('core/LedgerDelegate.java', 'Encumber / release / credit / balance', 'GP-11, GP-48'),
    ('core/CumulativeThresholdDelegate.java', 'Running total against the sanction', 'GP-42'),
    ('core/BatchDelegate.java, core/BatchItemOutcomeListener.java, core/BatchJoinListener.java', 'One instance per item, chunked fan-out, aggregation, retry rounds, broadcast', 'GP-08, GP-09, GP-27, GP-47, GP-49'),
    ('core/LinkedInstanceDelegate.java', 'Linked cases: appeal, part file, discrepancy, continue-as-new, renewal', 'GP-22, GP-54, GP-55, UP-08'),
    ('core/CorrelationDelegate.java', 'Same-tenant message to a linked case', 'GP-28, GP-41, GP-43'),
    ('core/CommitteeDelegate.java', 'Committee constitution, votes, tally', 'GP-03, GP-04, GP-23'),
    ('core/AssignmentStrategyDelegate.java', 'Round robin, least loaded, skills, priority', 'R82, GP-17, UP-04'),
    ('core/SuspensionDelegate.java', 'Court stay: suspend / resume the impugned case', 'GP-40'),
    ('core/SetVariablesDelegate.java, core/Vars.java', 'Pack configuration defaults; variable and hash conventions', 'R33, K4'),
    ('gov/MeritListDelegate.java, gov/WaitlistDelegate.java', 'Merit list with reservation and roster; waitlist promotion', 'GP-26, GP-28, GP-48'),
    ('gov/ReconciliationMatchDelegate.java', 'Scroll and stock matching', 'GP-31, GP-55'),
    ('gov/HearingScheduleDelegate.java', 'Hearing dates from the court', 'GP-51'),
    ('universal/EventWindowDelegate.java', 'Windowed, de-duplicated, bounded event correlation', 'UP-03, UP-21, R83'),
    ('worker/PackWorker.java, worker/PackWorkers.java, worker/EmbeddedWorkerRunner.java', 'External-task topics and the developer-profile runner', 'AC-08, GP-27, UP-20, R73'),
]


def write(path, packs):
    files = {}
    for pk in packs:
        f = []
        f += [f'packs/{pk.dir}/{p.pid}.bpmn' for p in pk.procs]
        f += [f'packs/{pk.dir}/{d}.dmn' for d, _ in pk.dmns]
        f += [f'packs/{pk.dir}/{c}.cmmn' for c, _ in pk.cmmns]
        files[pk.id] = f
    cov = {}
    for pk in packs:
        for c in pk.covers:
            cov.setdefault(c, []).append(pk)
    o = []
    w = o.append
    w('<!-- SPDX-License-Identifier: Apache-2.0 -->')
    w('# sangam-packs: government approval core, government domain packs, universal packs and sector packs')
    w('')
    w('Owner: WS-PACKS. Base package `org.sangam.packs`. Binding rules: `../ARCHITECTURE.md`. Requirements: '
      '`../../SANGAM.md` IX.3-IX.5, IX.8, X.4, GOV-1..12; patterns: `../../spike/GOV-PATTERNS.md` (GP-01..GP-55) and '
      '`../../spike/results/PREMIUM-AND-UNIVERSAL-SCOPE.md` (UP-01..UP-22); sector packs: '
      '`../../spike/results/SECTOR-WORKFLOWS.md` (SEC-<SECTOR>-nnn).')
    w('')
    nproc = sum(len(p.procs) for p in packs)
    ndmn = sum(len(p.dmns) for p in packs)
    ncmmn = sum(len(p.cmmns) for p in packs)
    nforms = sum(len({f['key'] for pr in p.procs for f in pr.forms}) for p in packs)
    w(f'**Contents:** {len(packs)} packs, {nproc} BPMN processes, {ndmn} DMN decisions, {ncmmn} CMMN cases, {nforms} '
      'bilingual (English / Hindi) forms, seed seats and roles per pack, 65 Java source files (SPI, ports, simulator, '
      'delegates, workers). All models deploy on Operaton 2.2.0 with XSD validation and history-TTL enforcement on, '
      'every call activity resolves, every startable process reaches its first wait state, and the four pilot packs '
      'run end to end on the simulator (see "Tests").')
    w('')
    w('## 1. Layout')
    w('')
    w('```')
    w('src/main/java/org/sangam/packs/')
    w('  spi/       SangamPack, PackDescriptor, ManifestPack, PackRegistry, PackDeployer, ModelGuard, PackMigrator')
    w('  port/      ConnectorGateway (+ request/result), SeatDirectory, BusinessCalendar, PackAuditSink, BatchLedger,')
    w('             LedgerStore, PackRuntime (port holder with simulator defaults)')
    w('  sim/       SimulatorConnectorGateway, InMemorySeatDirectory, WorkingDayCalendar, LoggingAuditSink,')
    w('             InMemoryBatchLedger, InMemoryLedgerStore')
    w('  core/      delegates and listeners of the approval core (used by every pack)')
    w('  gov/       domain delegates (merit list, waitlist, scroll / stock matching, hearings)')
    w('  universal/ EventWindowDelegate')
    w('  worker/    PackWorker, PackWorkers (external-task topics), EmbeddedWorkerRunner')
    w('src/main/resources/')
    w('  META-INF/sangam/packs.idx           index of pack manifests')
    w('  packs/<pack>/pack.json              SangamPack descriptor (manifest)')
    w('  packs/<pack>/*.bpmn | *.dmn | *.cmmn models (BPMN with diagram layout for the modeller)')
    w('  packs/<pack>/forms/*.form.json      bilingual forms (formKey "sangam:<pack>/<name>")')
    w('  packs/<pack>/seed.json              offices, seats (with reporting seat), holdings, roles')
    w('  packs/<pack>/overrides/<tenant>/    tenant-specific DMN overrides (GP-37)')
    w('tools/packgen/                         generator of the resources above and of this README (tools/README.md)')
    w('```')
    w('')
    w('The models, forms, seeds, manifests and this README are generated by `tools/packgen` and must not be edited by '
      'hand: change the generator, run it, and check with `python tools/packgen/main.py --check` (see `tools/README.md`).')
    w('')
    w('## 2. Pack SPI and deployment')
    w('')
    w('- A pack is a `SangamPack` whose `PackDescriptor` gives id, version, display names (en / hi), domain, main '
      'processes, resources (BPMN, DMN, CMMN, FORM, SEED), seed roles and seats, connectors with capability class and '
      'government tag, covered ids (GP / UP / AC / J / R / K) and acceptance scenarios (given / when / then, start '
      'variables, expected `caseState`).')
    w('- `PackRegistry.discover()` reads `META-INF/sangam/packs.idx` manifests (`ManifestPack`) and `ServiceLoader` '
      'packs (department jars). `deploymentOrder()` sorts by `dependsOn` (every government pack depends on `sangam.core`).')
    w('- `new PackDeployer(engine, registry).deployAll(tenantId)` deploys one engine deployment per pack '
      '(`pack:<id>@<version>`, duplicate filtering on), forms and seeds included, after `ModelGuard` checks. '
      '`tenantId = null` deploys shared definitions; a tenant id deploys a department\'s overrides, which '
      '`Decisions` prefers (GP-37); the manifest\'s `tenantOverrides` for that tenant replace the shared tables of the '
      'same key in that deployment. Deploy tenants with `deploy(packIds, tenantId)`, which includes the dependencies: '
      'Operaton resolves the call activities of a tenant process only within the same tenant. With the in-memory seat '
      'directory the seeds are loaded too.')
    w('- `PackMigrator.migrateAll(processKey, fromVersion, toVersion, tenant)` migrates running cases (GP-20); '
      'activity ids are kept stable across pack versions for this.')
    w('- A case is bound to `packVersion = <id>@<version>` at start (R43, K10).')
    w('')
    w('## 3. The government approval core (IX.3)')
    w('')
    w('Every pack composes these call activities (`in variables="all"`, business key passed, `out variables="all"`).')
    w('')
    w('| Block | IX.3 states | Inputs (defaults) | Output | Ids |')
    w('|---|---|---|---|---|')
    rows = [
        ('sangam-core-scrutiny', 'SCRUTINY, CLARIFICATION_SOUGHT', 'scrutinySeat, scrutinySla (5WD), scrutinyOnBreach, clarificationWindow (P15D), pauseOnClarification (true)', 'scrutinyOutcome ACCEPTED / REJECTED / LAPSED', 'GP-16, GP-45, R43, K5'),
        ('sangam-core-recommendation', 'RECOMMENDATION 1..n', 'recommendationSeats (comma list), recommendationSla, resubmitTo, outOfHierarchyAllowed, signEachLevel, commentsWindow', 'recOutcome RECOMMENDED / RETURNED / REJECTED', 'GP-02, GP-34, J-P2-E01/E02/E04/E05/E06'),
        ('sangam-core-sanction', 'SANCTION, APPROVED', 'routingDecisionKey, powersDecisionKey, caseType, amount, sanctionSla, sanctionOnBreach (ESCALATE / DEEMED_APPROVAL), signatureRequired', 'sanctionOutcome SANCTIONED / REJECTED / RETURNED; approvedPayloadHash', 'GP-01, GP-34, GP-44, K4, K5, K6, J-P3-E05, J-P4-E07'),
        ('sangam-core-execution', 'EXECUTION, ACKNOWLEDGED, RECONCILED, UNKNOWN_OUTCOME', 'executionConnector, executionOperation, executionStep, executionPayloadFields, verifyApproval, reconcileRequired, ackWindow', 'executionOutcome DONE / FAILED; opStatus, opReasonCode, operationId', 'VI.3, A-2, T35, AC-03, J-P1-E01/E04/E12'),
        ('sangam-core-closure', 'CLOSED', 'outputTemplate, issueOutput, closureTemplate, retentionCategory, retentionYears', 'caseState CLOSED, retention classification', 'R61, R60, R54'),
        ('sangam-core-rejection', 'REJECTED', 'rejectionReasonCodes, appealAllowed, appealWindow, appellateSeat', 'rejectionOutcome FINAL / APPEAL_ALLOWED', 'J-P3-E01, GP-22'),
        ('sangam-core-appeal', 'APPEAL', 'appellateSeat, appealSla', 'appealOutcome ALLOWED / DISMISSED', 'GP-22, J-P3-E04, J-P4-E06'),
        ('sangam-core-cancellation', 'CANCEL_REQUESTED, WITHDRAWN, CANCELLED', 'cancelKind, cancelReason, encumbranceLedger, releaseConnector, cancelAckWindow', 'caseState WITHDRAWN / CANCELLED', 'GP-14, GP-11, VI.5, J-P1-E13'),
        ('sangam-core-reconciliation', 'UNKNOWN_OUTCOME', 'operationId, opConnector, reconKind', 'resolution applied', 'OJ-02, J-P1-E14'),
        ('sangam-core-committee', '-', 'committeeSeats (<= 50), quorumRule ALL / MAJORITY / n, committeeWindow', 'committeeApproved', 'GP-03, GP-04, GP-23'),
        ('sangam-core-referral / -inbound', '-', 'referToTenant, referralQuestion, referralSla', 'referralDecision, referralReply', 'GP-32, AC-09, J-P2-E09'),
    ]
    for r in rows:
        w('| `' + r[0] + '` | ' + ' | '.join(r[1:]) + ' |')
    w('')
    w('Side exits in every pack: interrupting event sub-processes on `sangam.withdrawCase` (initiator) and '
      '`sangam.cancelCase` (authorised) call `sangam-core-cancellation`. SLA-guarded tasks carry a 75 % warning timer '
      'and a breach timer (escalate up the reporting seats, or deemed approval / refusal by the system principal on '
      'behalf of the seat). Approval tasks carry, in order, the SoD guard, the power guard, the payload binding and the '
      'actor recorder.')
    w('')
    w('## 4. Process variable and message contract (for WS-REST, WS-UI, WS-INTEGRATION)')
    w('')
    w('| Variable / message | Set by | Meaning |')
    w('|---|---|---|')
    for a, b, c in [
        ('initiator / initiatorPerson, actingSeat / initiatorSeat', 'REST at start', 'initiator person and seat (SoD, K5)'),
        ('actingPerson, actingSeat (task-local)', 'REST at task completion', 'who completes the task as which seat (K7)'),
        ('presentedPayloadHash (task)', 'UI', 'hash of the payload the officer saw; a mismatch refuses the approval (K4)'),
        ('selectedSeat', 'S07', 'chosen checker, re-validated against the eligible list'),
        ('sodExceptionId', 'admin route', 'controlled SoD exception granted by a higher authority'),
        ('materialFields, approvedPayloadHash, approvedPayload', 'pack start / approval', 'material payload and its approved hash'),
        ('caseState, caseVersion, versionHistory, packVersion', 'core', 'IX.3 state, R43 versions, bound package'),
        ('caseSla, caseDueAt, casePausedAt, <task>DueAt, <task>WarnAt', 'SLA', 'statutory and stage clocks (UTC dates)'),
        ('operationId, opStatus, opReasonCode, opExternalRef, reconItemId', 'execution', 'VI.3 operation state'),
        ('sim_<connector> (e.g. sim_pfms_payment)', 'tests / demo', 'simulator directive: COMPLETED, FAILED:<REASON>, UNKNOWN, PENDING, UNKNOWN_THEN_COMPLETED, key=value ...'),
        ('message sangam.operationResult (resultStatus, resultReasonCode, resultExternalRef)', 'inbox', 'asynchronous outcome, correlated on operationId'),
        ('message sangam.reconciliationResolved (resolution, resolutionEvidence, resolvedBy)', 'O04 console', 'CONFIRMED_SUCCESS / CONFIRMED_FAILURE / RETRY_SAME_OPERATION (refused for class c)'),
        ('message sangam.cancelAck (cancelResult), sangam.signatureResult (signatureResult)', 'inbox', 'VI.5 cancel acknowledgement, ESP result'),
        ('message sangam.clarificationReceived, sangam.appealFiled', 'portal', 'applicant response, appeal'),
        ('message sangam.withdrawCase, sangam.cancelCase (cancelReason)', 'REST', 'side exits'),
        ('pack messages (sangam.corrigendum, sangam.feePaid, sangam.recruit.*, sangam.hearingDateSet, ...)', 'portal / inbox', 'listed in each BPMN definitions header'),
    ]:
        w(f'| {a} | {b} | {c} |')
    w('')
    w('## 5. Pack catalogue')
    w('')
    w('| Pack | Domain | Models (in `src/main/resources/`) | Covers |')
    w('|---|---|---|---|')
    for pk in packs:
        w(f'| `{pk.id}` {pk.name_en} / {pk.name_hi} | {pk.domain} | ' + '<br>'.join('`' + x + '`' for x in files[pk.id])
          + ' | ' + ', '.join(c for c in pk.covers if c[:3] in ('GP-', 'UP-', 'AC-', 'D-B', 'D-C', 'D-D', 'D-G', 'D-I',
                                                                    'D-L', 'D-P', 'D-R', 'D-T', 'D-W', 'SEC', 'IFC', 'IFS', 'GA-'))
          + ' |')
    w('')
    w('## 6. Coverage map')
    w('')
    w('### 6.1 GP-01 ... GP-55')
    w('')
    w('| Id | Pattern | Packs (files) |')
    w('|---|---|---|')
    for i in range(1, 56):
        k = f'GP-{i:02d}'
        ps = cov.get(k, [])
        note = ''
        if i == 20:
            note = ' `spi/PackMigrator.java`'
        if i == 38:
            note = ' every `forms/*.form.json` (en + hi), Unicode variables'
        if i == 39:
            note = ' (seat-group assignment; the 10k-task query performance is WS-SECURITY / WS-ENGINE)'
        if i == 9:
            note = ' `core/BatchDelegate.java`, `spi/ModelGuard.java`'
        w(f'| {k} | {GP[i]} | ' + ', '.join(f'`{p.id}`' for p in ps) + note + ' |')
    w('')
    w('### 6.2 UP-01 ... UP-22')
    w('')
    w('| Id | Pattern | Pack | Files |')
    w('|---|---|---|---|')
    for i in range(1, 23):
        k = f'UP-{i:02d}'
        own = [p for p in cov.get(k, []) if p.id.startswith('universal.')]
        other = [p for p in cov.get(k, []) if not p.id.startswith('universal.')]
        w(f'| {k} | {UP[i]} | ' + ', '.join(f'`{p.id}`' for p in own + other) + ' | '
          + '<br>'.join('`' + x + '`' for p in own for x in files[p.id]) + ' |')
    w('')
    w('### 6.3 Pilot acceptance criteria AC-01 ... AC-11 (IX.8)')
    w('')
    w('| Id | Criterion | Packs / files | Scenario / test |')
    w('|---|---|---|---|')
    tests = {1: 'PilotScenarioTest.p1HappyPathClosesReconciled; pack.json AC-01', 2: 'pack.json AC-02',
             3: 'PilotScenarioTest.p1UnknownPaymentOutcomeIsReconciledWithoutRepeat; pack.json AC-03',
             4: 'PilotScenarioTest.p2FileMovesUpAndIsDisposed; pack.json AC-04',
             5: 'PilotScenarioTest.p3GrantSanctionedAndDisbursedInInstalments; pack.json AC-05',
             6: 'PilotScenarioTest.p4LicenceIssuedWithCertificateAndPush; pack.json AC-06'}
    for i in range(1, 12):
        k = f'AC-{i:02d}'
        ps = cov.get(k, [])
        extra = AC_SUPPORT.get(i, '')
        w(f'| {k} | {AC[i]} | ' + ', '.join(f'`{p.id}`' for p in ps) + (' ' + extra if extra else '') + ' | '
          + tests.get(i, 'other workstream (see column 3)') + ' |')
    w('')
    w('### 6.4 Journeys (X.4.3-X.4.6) and domain models (E17)')
    w('')
    js = sorted({c for pk in packs for c in pk.covers if c.startswith('J-') or c.startswith('D-')})
    w('| Id | Pack |')
    w('|---|---|')
    for j in js:
        w(f'| {j} | ' + ', '.join(f'`{p.id}`' for p in cov[j]) + ' |')
    w('')
    w('Journey details per pack: P1 bill (`p1-bill-payment`): duplicate bill review (E03), budget shortfall return (E11), '
      'encumbrance and release on cancel (GP-11), eligible-checker selection with fallback (E07), SoD (E08), joint '
      'approval above the threshold (E09), approval invalidated before payment (E01), treasury rejection with corrected '
      'details, new version and new operationId (E12), lost response to UNKNOWN_OUTCOME (E04), cancel mid-call (E13), '
      'transfer and expired delegation handled by seat assignment (E05, E06). P2 file (`p2-file-movement`): put-up-to '
      'chain by DMN, signed noting at every level with retry (E06/E07), returns to any level and resubmission policy '
      '(E02), parallel comments with deadline (E05), out-of-hierarchy marking with reason or refusal (E01), acted-for '
      'and SoD through additional charge (E03/E04), dispatch partial success (E08), referral (E09), RTI legal hold '
      '(E10), part file (GP-54), physical custody (GP-53). P3 grant: DPDP child flag (E09), DigiLocker fallback (E07), '
      'duplicate beneficiary (E06), field verification, ineligible after verification (E01), entitlement override with '
      'reason, power forward (E05), instalments (E02), account failure with re-verification and re-sanction (E03), '
      'appeal with a different verifier (E04), rules bound to the deployment (E08). P4 licence: fee by DMN and gateway '
      'with lookup (E02/E03/E04 via reconciliation), statutory clock from fee confirmation, clarification pause, '
      'inspection with applicant-requested pause on reschedule (E05), approval SLA escalation or deemed approval (E01, '
      'E07), signed certificate from the approved data (E09), DigiLocker push partial success (E08), appeal (E06), '
      'withdrawal with refund (E10); CMMN variant `sangam-p4-licence-case`.')
    w('')
    w('### 6.5 Sector requirements SEC-* (`../../spike/results/SECTOR-WORKFLOWS.md`)')
    w('')
    w('Sector packs have ids `sec.<sector>.<slug>` (directories `packs/sec-<sector>-<slug>/`), separate from the '
      'government (`gov.*`) and universal (`universal.*`) packs. Each reuses the approval core and reaches external '
      'systems only through `ConnectorGateway`. The full workflow-to-pack map with covered / partial / missing status '
      'and the regulatory sources is in `SECTOR-WORKFLOWS.md`.')
    w('')
    w('| Id | Packs |')
    w('|---|---|')
    secs = sorted({c for pk in packs for c in pk.covers if c.startswith('SEC-')})
    for c in secs:
        w(f'| {c} | ' + ', '.join(f'`{p.id}`' for p in cov[c]) + ' |')
    w('')
    w('### 6.6 Public finance (IFMS) requirements IFC-*, IFS-*, GA-* (`../../spike/results/IFMS-*-REQUIREMENTS.md`)')
    w('')
    w('The `gov.ifms.*` packs are built on the generic services: `sangam-config` (profiles, classification, '
      'document-type catalogue, number series; connectors `config.*`) and `sangam-ledger` (limit / fund ledger; '
      'connectors `ledger.*`). The government data (GOI, its department profiles, all 36 States / UTs, the head-of-account '
      'seeds and the bill catalogue) is in `src/main/resources/profiles/`; its README has the full coverage map of every '
      'IFC / IFS / GA id with the generic capability, the data file, the pack and the status.')
    w('')
    w('| Id | Packs |')
    w('|---|---|')
    ids = sorted({c for pk in packs for c in pk.covers if c.startswith('IFC-') or c.startswith('IFS-') or c.startswith('GA-')})
    for c in ids:
        w(f'| {c} | ' + ', '.join(f'`{p.id}`' for p in cov[c]) + ' |')
    w('')
    w('## 7. Java classes and the requirement ids they cover')
    w('')
    w('| File (`src/main/java/org/sangam/packs/`) | Purpose | Ids |')
    w('|---|---|---|')
    for f, p_, ids in JAVA:
        w(f'| `{f}` | {p_} | {ids} |')
    w('')
    w('## 8. Integration notes (ports the integrator wires in `sangam-server`)')
    w('')
    w('Install the production adapters once at start-up: `PackRuntime.install(PackRuntime.defaults().withGateway(..)'
      '.withSeats(..).withCalendar(..).withAudit(..).withBatches(..).withLedgers(..).withClock(..))`. Until then every '
      'port runs on its simulator / in-memory default.')
    w('')
    w('| Port | Implementer | Contract |')
    w('|---|---|---|')
    for a, b, c in [
        ('`port.ConnectorGateway`', 'WS-INTEGRATION', '`submit` writes the outbox in the caller\'s transaction and returns PENDING (or a stored first result for a repeated operationId; a different payload hash is IDEMPOTENCY_PAYLOAD_MISMATCH); the relay delivers `sangam.operationResult` correlated on `operationId`; `query` is read-only; `lookup` for classes a/b; `cancel` for VI.5; `openReconciliation` creates the O04 item; `capabilityOf` per connector (A-8 default c). Connector ids used by the packs are listed in each `pack.json`.'),
        ('`port.SeatDirectory`', 'WS-SECURITY', 'effective-dated postings, additional charge and delegation; seat ids are the candidate groups; `seatForRole` walks the office hierarchy; `escalationSeatOf` is the reporting seat.'),
        ('`port.BusinessCalendar`', 'WS-ENGINE (SangamClock + tenant holidays)', 'specs `nWD`, `nD`, `nH`, `nWH`, ISO durations; UTC instants.'),
        ('`port.PackAuditSink`', 'integrator (audit chain, same transaction)', 'X.4.0.3 event names and fields.'),
        ('`port.BatchLedger`', 'WS-ENGINE / persistence (product table)', 'idempotent per (batch, round, item), first outcome wins.'),
        ('`port.LedgerStore`', 'WS-ENGINE / persistence (product table)', 'postings idempotent by reference; reversal by reference.'),
        ('`worker.PackWorkers`', 'WS-REST worker API / WS-SDK', 'topics sangam.docs.bulk, sangam.rpa, sangam.pfms.paymentFile, sangam.ai.extract; capability class per topic.'),
    ]:
        w(f'| {a} | {b} | {c} |')
    w('')
    w('Requests to other workstreams: (1) WS-REST should set `actingPerson` / `actingSeat` task-locally and '
      '`presentedPayloadHash` on approval completions, and map `422 SOD_VIOLATION`, `422 POWER_LIMIT_EXCEEDED`, '
      '`409 APPROVAL_INVALIDATED` from the listener messages to problem+json; (2) WS-INTEGRATION should honour the '
      'message names above; (3) the engine configuration may keep `enforceHistoryTimeToLive = true` (every model sets '
      'a TTL); (4) WS-ENGINE\'s deploy-time multi-instance threshold and `ModelGuard.MAX_MULTI_INSTANCE` (50) should '
      'agree.')
    w('')
    w('## 9. Dependencies')
    w('')
    w('| Dependency | Licence | Scope |')
    w('|---|---|---|')
    w('| org.sangam:sangam-core | Apache-2.0 | compile |')
    w('| org.operaton.bpm:operaton-engine 2.2.0 (BOM) | Apache-2.0 | compile |')
    w('| com.fasterxml.jackson.core:jackson-databind 2.22.3 (BOM) | Apache-2.0 | compile |')
    w('| org.slf4j:slf4j-api 2.0.17 | MIT | compile |')
    w('| org.junit.jupiter:junit-jupiter 5.11.4 | EPL-2.0 | test |')
    w('| com.h2database:h2 2.3.232 | MPL-2.0 / EPL-1.0 | test |')
    w('')
    w('No new third-party dependency beyond the parent BOM.')
    w('')
    w('## 10. Tests (fast, in-memory H2, no Docker)')
    w('')
    w('- `PackDeploymentTest`: every pack passes `ModelGuard`, deploys with TTL enforcement on, every call activity '
      'resolves, redeploy is filtered as a duplicate.')
    w('- `PilotScenarioTest`: P1 happy path to CLOSED / MATCHED; P1 lost payment response to UNKNOWN_OUTCOME, class (c) '
      'retry refused, success with evidence closes; P2 two levels, disposal, dispatch, closure; P3 three instalments; '
      'P4 fee, scrutiny, approval, signed certificate, DigiLocker push; works order of sanctions TS_FIRST by default, '
      'AA_FIRST by a department row and by the state-b tenant override.')
    w('- `AllPacksSmokeTest`: every process with a none start reaches its first wait state with no incident.')
    w('')
    w('Run: `mvnw.cmd -q -B -pl sangam-packs -am install` (tests included) or with `-DskipTests` to compile only.')
    w('')
    w('## 11. Notes and known gaps')
    w('')
    for g in [
        'Thresholds, fee tables, power limits, timelines and roles in the DMN tables and seeds are SYNTHETIC pilot values '
        '(marked in each table); departments replace them with their delegation-of-powers orders and notified timelines.',
        'Works sanctions follow estimate > technical sanction > administrative approval by default; the order is pack '
        'configuration in the DMN `works-config` (output `worksSanctionOrder` TS_FIRST / AA_FIRST): a row per office '
        'for one department, or a tenant override deployment (sample: `overrides/state-b/works-config.dmn`, AA first).',
        'Event-subprocess timers that are armed at scope start use literal durations (UP-16 leaver deadline PT4H, UP-19 '
        'obligation cycle R/P30D) because no variable exists yet at that moment.',
        'CMMN timer listeners are not used (Operaton 2.2.0 limitation found in the spike); CMMN variants run their SLA in '
        'a blocking BPMN process task.',
        'The simulator answers queries with plausible defaults (budget 1 crore, L1 bidder, confidence 0.82); real '
        'adapters must return the same data keys (see each `ConnectorCallDelegate` result prefix).',
        'Statutory durations that depend on day arithmetic not expressible in JUEL (for example "decide within 40 days of '
        'receipt" after a third-party window) are approximated by resetting the case clock to the remaining period.',
        'Sector packs (`sec.*`) carry SYNTHETIC grids (credit policy, underwriting, delegated powers, commissions, '
        'licences by category) and the statutory timelines found in SECTOR-WORKFLOWS.md; timelines marked "verify" '
        'there must be checked against the regulator\'s current text before go-live and are changed by a tenant DMN '
        'override, not by editing the models.',
        'Deep exception paths beyond the five pilot scenarios and the first-wait-state smoke test are implemented in the '
        'models but not yet executed by a test (owner rule: testing comes last).',
    ]:
        w('- ' + g)
    w('')
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(o))
