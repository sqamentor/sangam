// SPDX-License-Identifier: Apache-2.0
/**
 * Domain types for the Sangam API. Written against ARCHITECTURE §4 while sangam-rest/api/sangam-api.yaml was not yet
 * published; see README "API reconciliation" for the list of paths and fields to check against the OpenAPI file.
 */

export type Id = string;
export type Iso = string;

// ---------------------------------------------------------------- identity, seats (IX.5, S02)

export type Role =
  | 'TASK_USER'
  | 'OPERATOR'
  | 'PLATFORM_OPERATOR'
  | 'ADMIN'
  | 'PLATFORM_ADMIN'
  | 'MODELLER'
  | 'REVIEWER'
  | 'ANALYST'
  | 'AUDITOR'
  | 'INTEGRATION'
  | 'CITIZEN';

export type SeatHoldingKind = 'SUBSTANTIVE' | 'ADDITIONAL_CHARGE' | 'LEAVE_SUBSTITUTE';

export interface SeatHolding {
  seatId: Id;
  seatName: string;
  designation: string;
  orgUnitName: string;
  kind: SeatHoldingKind;
  /** For ADDITIONAL_CHARGE / LEAVE_SUBSTITUTE: the person normally holding the seat ("acted for"). */
  actingForPersonName?: string;
  validFrom: Iso;
  validTo?: Iso;
  pendingApproval?: boolean;
}

export interface Me {
  userId: Id;
  displayName: string;
  tenantId: Id;
  tenantName?: string;
  roles: Role[];
  seats: SeatHolding[];
  permissions?: string[];
  preferredLanguage?: 'en' | 'hi';
}

// ---------------------------------------------------------------- tasks (S03, S08, S09, R47, VI.4 rule 5)

export type TaskSection = 'MY_SEAT' | 'ACTING_FOR' | 'POOL' | 'ESCALATED' | 'RETURNED' | 'ALL';

export interface TaskSummary {
  id: Id;
  name: string;
  reference?: string;
  subject?: string;
  businessKey?: string;
  processInstanceId?: Id;
  caseInstanceId?: Id;
  processDefinitionKey?: string;
  packKey?: string;
  section: TaskSection;
  assignee?: string;
  assigneeName?: string;
  seatId?: Id;
  seatName?: string;
  actingForSeatName?: string;
  fromSeatName?: string;
  amount?: number;
  currency?: string;
  created: Iso;
  received?: Iso;
  due?: Iso;
  slaDue?: Iso;
  slaState?: 'OK' | 'WARNING' | 'BREACHED' | 'PAUSED';
  priority?: number;
  claimable?: boolean;
  returned?: boolean;
}

export type TaskAction =
  | 'APPROVE'
  | 'FORWARD'
  | 'RETURN'
  | 'SEEK_CLARIFICATION'
  | 'REJECT'
  | 'REASSIGN'
  | 'DELEGATE'
  | 'RESUBMIT'
  | 'COMPLETE';

export interface FieldChange {
  field: string;
  label?: string;
  from?: unknown;
  to?: unknown;
  changedBy?: string;
  changedAt?: Iso;
}

export interface CaseVersion {
  version: number;
  createdAt: Iso;
  createdBy: string;
  seatName?: string;
  actedFor?: string;
  reason?: string;
  kind: 'SUBMITTED' | 'RETURNED' | 'RESUBMITTED' | 'AMENDED';
  payloadHash: string;
}

export interface ReturnTarget {
  level: number;
  /** Contract ReturnTaskRequest.targetActivityId. */
  activityId?: string;
  seatId: Id;
  seatName: string;
  holderName?: string;
}

export interface CandidateSeat {
  seatId: Id;
  seatName: string;
  holderName?: string;
  workload?: number;
  onLeave?: boolean;
  outOfHierarchy?: boolean;
  proposed?: boolean;
}

export interface SodCheck {
  allowed: boolean;
  /** Reason codes, e.g. SAME_PERSON_AS_INITIATOR, SAME_SEAT_AS_CHECKER, SELF_APPROVAL_VIA_DELEGATION. */
  reasons: string[];
  initiatorName?: string;
  initiatorSeatName?: string;
  exceptionId?: Id;
}

export interface Attachment {
  id: Id;
  name: string;
  contentType: string;
  size: number;
  sha256: string;
  version: number;
  uploadedBy: string;
  uploadedAt: Iso;
  scanStatus?: 'PENDING' | 'CLEAN' | 'INFECTED' | 'SKIPPED';
}

export interface Comment {
  id: Id;
  author: string;
  seatName?: string;
  actedFor?: string;
  createdAt: Iso;
  text: string;
  version?: number;
}

export interface CodedReason {
  code: string;
  label: string;
  labelHi?: string;
}

export interface TaskDetail extends TaskSummary {
  description?: string;
  formKey?: string;
  form?: FormSchema;
  formVersion?: number;
  variables: Record<string, unknown>;
  /** Hash of the material payload the decision is bound to (VI.4 rule 5). */
  payloadHash: string;
  /** Case/payload version the decision is bound to (R43). */
  version: number;
  allowedActions: TaskAction[];
  returnTargets: ReturnTarget[];
  candidates?: CandidateSeat[];
  rejectReasons?: CodedReason[];
  returnReasons?: CodedReason[];
  changedSinceLastSeen?: FieldChange[];
  sod?: SodCheck;
  ruleEvidence?: { rule: string; version: string; result: string }[];
  materialFields?: { key: string; label: string; value: unknown }[];
  delegatedFrom?: string;
}

export interface CompleteTaskRequest {
  action: TaskAction;
  variables?: Record<string, unknown>;
  payloadHash: string;
  version: number;
  reasonCode?: string;
  remarks?: string;
  nextSeatId?: Id;
  nextSeatReason?: string;
}

export interface CompleteTaskResult {
  reference?: string;
  state: string;
  nextSeatName?: string;
  slaDue?: Iso;
  /** Part VII §8.1: done / pending / uncertain parts of a partial success. */
  done?: string[];
  pending?: string[];
  uncertain?: string[];
  newVersion?: number;
}

export interface ReturnTaskRequest {
  targetLevel: number;
  targetSeatId: Id;
  reasonCodes: string[];
  remarks: string;
  payloadHash: string;
  version: number;
}

export interface DelegateTaskRequest {
  toSeatId?: Id;
  toUserId?: Id;
  reason: string;
  until?: Iso;
}

export interface BulkResult {
  succeeded: Id[];
  failed: { id: Id; reason: string }[];
}

// ---------------------------------------------------------------- forms (R47)

/** GET /tasks/{id}/form (SL-37): the task's deployed form, read with the task-level check. */
export interface TaskForm {
  taskId: string;
  formKey?: string;
  kind: 'EMBEDDED' | 'FORM' | 'EXTERNAL' | 'OTHER' | 'NONE';
  contentType?: string;
  content?: string;
}

export type FormFieldType =
  | 'textfield'
  | 'textarea'
  | 'number'
  | 'select'
  | 'radio'
  | 'checkbox'
  | 'checklist'
  | 'datetime'
  | 'text'
  | 'group'
  | 'filepicker'
  | 'separator';

export interface FormField {
  id?: string;
  key?: string;
  type: FormFieldType;
  label?: string;
  description?: string;
  text?: string;
  readonly?: boolean;
  disabled?: boolean;
  defaultValue?: unknown;
  validate?: { required?: boolean; min?: number; max?: number; minLength?: number; maxLength?: number; pattern?: string };
  values?: { label: string; value: string }[];
  components?: FormField[];
  subtype?: 'date' | 'time' | 'datetime';
  /** Sangam extension: per-language labels. */
  i18n?: { hi?: { label?: string; description?: string; text?: string } };
  sensitive?: boolean;
  /** Generic module screens: parse the value as JSON before sending. */
  json?: boolean;
  /** Suggest values from this GET path (rows' id and name), as a searchable list. */
  lookup?: string;
  /** Generic module screens: send the value as a query parameter instead of in the body. */
  inQuery?: boolean;
}

export interface FormSchema {
  id?: string;
  key?: string;
  version?: number;
  type?: 'default';
  schemaVersion?: number;
  components: FormField[];
}

// ---------------------------------------------------------------- processes, cases, decisions (R63)

export interface ProcessDefinition {
  id: Id;
  key: string;
  name?: string;
  version: number;
  versionTag?: string;
  deploymentId: Id;
  tenantId?: string;
  suspended: boolean;
  runningInstances?: number;
  incidents?: number;
  packageVersion?: string;
}

export interface ActivityStatistic {
  activityId: string;
  activityName?: string;
  /** Tokens currently waiting (runtime) or executions (history mode). */
  count: number;
  incidents: number;
  canceled?: number;
  finished?: number;
  avgDurationMs?: number;
  p95DurationMs?: number;
}

export interface FlowStatistic {
  flowId: string;
  count: number;
}

export interface DefinitionStatistics {
  definitionId: Id;
  mode: 'runtime' | 'history';
  activities: ActivityStatistic[];
  flows?: FlowStatistic[];
}

export type InstanceState = 'ACTIVE' | 'SUSPENDED' | 'COMPLETED' | 'CANCELED' | 'INCIDENT' | 'UNKNOWN_OUTCOME';

export interface ProcessInstance {
  id: Id;
  definitionId: Id;
  definitionKey: string;
  definitionVersion?: number;
  businessKey?: string;
  state: InstanceState;
  startTime: Iso;
  endTime?: Iso;
  tenantId?: string;
  incidents?: number;
  superInstanceId?: Id;
  lifecycleState?: string;
}

export interface ActivityInstance {
  id: Id;
  activityId: string;
  activityName?: string;
  activityType: string;
  startTime?: Iso;
  endTime?: Iso;
  canceled?: boolean;
  children?: ActivityInstance[];
  incidentIds?: Id[];
}

export interface VariableEntry {
  name: string;
  type: string;
  value: unknown;
  masked?: boolean;
  scope?: string;
}

export type HistoryLogKind = 'audit' | 'jobs' | 'external-tasks' | 'variables' | 'user-tasks' | 'decisions' | 'called';

export interface HistoryLogEntry {
  id: Id;
  time: Iso;
  kind: HistoryLogKind;
  activityId?: string;
  activityName?: string;
  actor?: string;
  operation?: string;
  property?: string;
  oldValue?: unknown;
  newValue?: unknown;
  message?: string;
  linkId?: Id;
}

export type ModificationKind = 'startBeforeActivity' | 'startAfterActivity' | 'startTransition' | 'cancel';

export interface ModificationInstruction {
  type: ModificationKind;
  activityId?: string;
  transitionId?: string;
  activityInstanceId?: string;
  variables?: Record<string, unknown>;
}

export interface ConsequencePreview {
  summary: string[];
  openTasks?: { state: 'OFFERED' | 'LOCKED' | 'UNKNOWN'; count: number }[];
  openOperationIds?: string[];
  tombstones?: number;
  blocked?: boolean;
  blockedReason?: string;
  requiresFourEyes?: boolean;
  affectedCount?: number;
  limitExceeded?: boolean;
}

export interface CaseInstance {
  id: Id;
  definitionKey: string;
  businessKey?: string;
  state: 'ACTIVE' | 'COMPLETED' | 'TERMINATED' | 'CLOSED' | 'SUSPENDED';
  created: Iso;
  planItems?: CasePlanItem[];
}

export interface CasePlanItem {
  id: Id;
  definitionId: string;
  name?: string;
  type: 'STAGE' | 'HUMAN_TASK' | 'PROCESS_TASK' | 'CASE_TASK' | 'MILESTONE' | 'DECISION_TASK';
  state: 'AVAILABLE' | 'ENABLED' | 'DISABLED' | 'ACTIVE' | 'COMPLETED' | 'TERMINATED' | 'SUSPENDED';
  required?: boolean;
  repetition?: boolean;
  discretionary?: boolean;
  children?: CasePlanItem[];
}

export interface DecisionDefinition {
  id: Id;
  key: string;
  name?: string;
  version: number;
  decisionRequirementsKey?: string;
}

export interface DecisionInstance {
  id: Id;
  decisionKey: string;
  decisionVersion: number;
  evaluationTime: Iso;
  processInstanceId?: Id;
  businessKey?: string;
  inputs: { name: string; value: unknown }[];
  outputs: { rule?: string; name: string; value: unknown }[];
}

export interface Deployment {
  id: Id;
  name: string;
  source?: string;
  deploymentTime: Iso;
  tenantId?: string;
  resources?: { id: Id; name: string }[];
  packageId?: Id;
}

// ---------------------------------------------------------------- packages and modeller (R43, R69, A06/A07)

export type PackageState = 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'DEPLOYED' | 'ACTIVE' | 'RETIRED';

export interface ModelArtifact {
  id: Id;
  name: string;
  kind: 'BPMN' | 'DMN' | 'FORM' | 'CMMN';
  content: string;
  sha256?: string;
}

export interface ModelPackage {
  id: Id;
  key: string;
  name: string;
  version: string;
  state: PackageState;
  author: string;
  reviewer?: string;
  updatedAt: Iso;
  effectiveFrom?: Iso;
  artifacts: ModelArtifact[];
  hash?: string;
  bindingMode?: 'DEPLOYMENT' | 'LATEST';
}

export interface ValidationIssue {
  severity: 'ERROR' | 'WARNING' | 'INFO';
  artifact?: string;
  elementId?: string;
  message: string;
  code?: string;
}

export interface PackageDiff {
  fromVersion?: string;
  toVersion: string;
  artifacts: { name: string; change: 'ADDED' | 'REMOVED' | 'MODIFIED' | 'UNCHANGED'; summary?: string[] }[];
  inFlightCases?: number;
}

// ---------------------------------------------------------------- operations (O01–O09, R63, R64)

export interface OpsDashboard {
  nodes: { id: string; status: 'UP' | 'DEGRADED' | 'DOWN'; jobExecutorActive: boolean; lastHeartbeat: Iso }[];
  backlogByTopic: { topic: string; count: number; oldestAgeSec: number }[];
  outbox: { used: number; budget: number; drainTimeSec?: number };
  openIncidents: number;
  openReconciliationByAge: { bucket: string; count: number }[];
  slaBreaches: number;
  notificationFailureRate?: number;
  securityIncidentClocks?: { id: Id; title: string; deadline: Iso }[];
  metrics?: { name: string; value: number; unit?: string }[];
}

export interface Incident {
  id: Id;
  type: 'failedJob' | 'failedExternalTask' | 'parkedDelivery' | 'poisonEvent' | string;
  message: string;
  errorClass?: string;
  processInstanceId?: Id;
  businessKey?: string;
  activityId?: string;
  definitionKey?: string;
  packKey?: string;
  tenantId?: string;
  firstSeen: Iso;
  lastSeen: Iso;
  attempts?: number;
  owner?: string;
  lockVersion?: number;
  externalEffectStatus?: string;
  note?: string;
  jobId?: Id;
}

export type BatchType =
  | 'RETRY'
  | 'CANCEL'
  | 'SUSPEND'
  | 'ACTIVATE'
  | 'MIGRATE'
  | 'MODIFY'
  | 'SET_VARIABLES'
  | 'DELETE'
  | 'RESTART';

export type BatchStatus =
  | 'AWAITING_APPROVAL'
  | 'QUEUED'
  | 'RUNNING'
  | 'PAUSED'
  | 'COMPLETED'
  | 'COMPLETED_WITH_FAILURES'
  | 'CANCELED'
  | 'REJECTED';

export interface Batch {
  id: Id;
  type: BatchType;
  status: BatchStatus;
  createdBy: string;
  createdAt: Iso;
  reason: string;
  total: number;
  succeeded: number;
  failed: number;
  remaining: number;
  approvedBy?: string;
  operationId?: string;
}

export interface BatchItem {
  id: Id;
  targetId: Id;
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';
  message?: string;
}

export interface InstanceQuery {
  definitionId?: Id;
  definitionKey?: string;
  businessKey?: string;
  state?: InstanceState;
  activityId?: string;
  startedAfter?: Iso;
  startedBefore?: Iso;
  instanceIds?: Id[];
  withIncidents?: boolean;
}

export interface BatchRequest {
  type: BatchType;
  query: InstanceQuery;
  payload?: Record<string, unknown>;
  reason: string;
}

export interface MigrationInstruction {
  sourceActivityIds: string[];
  targetActivityIds: string[];
  updateEventTrigger?: boolean;
}

export interface MigrationPlan {
  sourceDefinitionId: Id;
  targetDefinitionId: Id;
  instructions: MigrationInstruction[];
  variables?: Record<string, unknown>;
}

export interface MigrationValidation {
  valid: boolean;
  instructionReports: { instruction: MigrationInstruction; failures: string[] }[];
  variableReports?: { name: string; failures: string[] }[];
}

export interface Job {
  id: Id;
  type: string;
  processInstanceId?: Id;
  activityId?: string;
  dueDate?: Iso;
  retries: number;
  exceptionMessage?: string;
  suspended: boolean;
  priority?: number;
  partition?: number;
  lockOwner?: string;
  lockExpiresAt?: Iso;
}

export interface JobSummary {
  executable: number;
  failed: number;
  timersDue: number;
  locked: number;
  byPartition?: { partition: number; owner?: string; count: number }[];
}

export interface OutboxEntry {
  id: Id;
  destination: string;
  rootKey: string;
  status: 'PENDING' | 'IN_FLIGHT' | 'PARKED' | 'DELIVERED' | 'SKIPPED';
  attempts: number;
  lastError?: string;
  createdAt: Iso;
  operationId: string;
  ordered: boolean;
}

export interface InboxEntry {
  id: Id;
  source: string;
  messageId: string;
  status: 'RECEIVED' | 'BUFFERED' | 'CORRELATED' | 'EXPIRED' | 'DUPLICATE';
  receivedAt: Iso;
  correlationKey?: string;
}

export type ReconStatus = 'UNKNOWN_OUTCOME' | 'LATE_RESULT_AFTER_DELETE' | 'CANCEL_ACK_MISSING';

export interface ReconciliationItem {
  id: Id;
  status: ReconStatus;
  resolution?: 'CONFIRMED_SUCCESS' | 'RETRIED' | 'MARKED_FAILED';
  operationId: string;
  capabilityClass: 'a' | 'b' | 'c';
  amount?: number;
  currency?: string;
  businessKey?: string;
  processInstanceId?: Id;
  destination?: string;
  ageSec: number;
  createdAt: Iso;
  owner?: string;
  evidenceCount: number;
  fourEyesRequired?: boolean;
}

export interface ReconciliationDetail extends ReconciliationItem {
  requestFingerprint: string;
  attempts: { at: Iso; attemptId: string; outcome: string; message?: string }[];
  evidence: { id: Id; kind: string; name: string; addedBy: string; addedAt: Iso; sha256?: string }[];
  ledgerEntry?: Record<string, unknown>;
  initiator?: string;
  approver?: string;
  lookupAvailable?: boolean;
  lookupResult?: string;
}

export interface WorkerInfo {
  workerId: string;
  sdk: string;
  sdkVersion?: string;
  language?: string;
  topics: string[];
  activeLeases: number;
  heartbeatAgeSec: number;
  errorRate?: number;
  staleLeaseCount?: number;
}

export interface TopicInfo {
  name: string;
  capabilityClass?: 'a' | 'b' | 'c';
  paused: boolean;
  backlog: number;
  lockDurationMs?: number;
}

export interface CleanupStatus {
  enabled: boolean;
  window?: string;
  lastRun?: Iso;
  nextRun?: Iso;
  batchSize?: number;
  definitions: { key: string; version: number; ttlDays?: number; finished: number; cleanable: number }[];
  decisions?: { key: string; ttlDays?: number; finished: number; cleanable: number }[];
  legalHolds?: number;
}

// ---------------------------------------------------------------- admin (A01–A11, IX.5, VI.4)

export interface Tenant {
  id: Id;
  name: string;
  status: 'DRAFT' | 'ACTIVE' | 'SUSPENDED';
  hostingProfile?: string;
  database?: string;
  languages: string[];
  calendar?: string;
  quotas?: Record<string, number>;
  admins?: string[];
  createdAt?: Iso;
}

export type OrgLevel = 'MINISTRY' | 'DEPARTMENT' | 'DIRECTORATE' | 'REGION' | 'DISTRICT' | 'OFFICE' | 'SECTION';

export interface OrgUnit {
  id: Id;
  name: string;
  nameHi?: string;
  level: OrgLevel;
  parentId?: Id;
  validFrom: Iso;
  validTo?: Iso;
  active: boolean;
  children?: OrgUnit[];
}

export interface Seat {
  id: Id;
  designation: string;
  designationHi?: string;
  orgUnitId: Id;
  orgUnitName?: string;
  active: boolean;
  holderName?: string;
  validFrom: Iso;
  validTo?: Iso;
}

export interface Assignment {
  id: Id;
  personId: Id;
  personName?: string;
  seatId: Id;
  seatName?: string;
  kind: SeatHoldingKind;
  validFrom: Iso;
  validTo?: Iso;
  actingForPersonName?: string;
  approvedBy?: string;
  status: 'PENDING' | 'ACTIVE' | 'ENDED' | 'REJECTED';
  reason?: string;
}

export interface AssignmentImpact {
  openTasks: number;
  sodConflicts: { taskId: Id; reason: string }[];
  summary: string[];
}

export interface DelegationTable {
  id: Id;
  key: string;
  name: string;
  version: number;
  state: 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'ACTIVE' | 'SUPERSEDED' | 'REJECTED';
  effectiveFrom?: Iso;
  author: string;
  approver?: string;
  dmnXml: string;
  testCases?: { id: string; inputs: Record<string, unknown>; expected: Record<string, unknown> }[];
}

export interface DelegationCheckResult {
  gaps: string[];
  overlaps: string[];
  hitPolicy: string;
  tests: { id: string; passed: boolean; actual?: Record<string, unknown> }[];
}

export interface Policy {
  id: Id;
  effect: 'ALLOW' | 'DENY';
  principalType: 'USER' | 'GROUP' | 'ROLE' | 'SEAT' | 'SERVICE';
  principalId: Id;
  resourceType: string;
  resourceId: string;
  permissions: string[];
  condition?: string;
  scope?: string;
  validTo?: Iso;
  policyVersion?: number;
  note?: string;
}

export interface SodException {
  id: Id;
  rule: string;
  personId: Id;
  personName?: string;
  reason: string;
  expiresAt: Iso;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
  approvedBy?: string;
}

export interface User {
  id: Id;
  username: string;
  displayName: string;
  email?: string;
  active: boolean;
  groups?: string[];
  source?: 'LOCAL' | 'OIDC' | 'LDAP' | 'SCIM';
  lastLogin?: Iso;
}

export interface Group {
  id: Id;
  name: string;
  type?: string;
  members?: number;
  source?: 'LOCAL' | 'OIDC' | 'LDAP' | 'SCIM';
}

export interface IdentityProvider {
  id: Id;
  name: string;
  protocol: 'OIDC' | 'SAML2' | 'LDAP';
  issuer?: string;
  metadataUrl?: string;
  attributeMapping: Record<string, string>;
  mfaRequired: boolean;
  sessionTimeoutMin: number;
  status: 'DRAFT' | 'TESTED' | 'ACTIVE';
  lastTestAt?: Iso;
}

export interface ServicePrincipal {
  id: Id;
  name: string;
  scopes: string[];
  topics: string[];
  expiresAt?: Iso;
  lastUsedAt?: Iso;
  status: 'PENDING' | 'ACTIVE' | 'REVOKED';
}

// ---------------------------------------------------------------- analytics and mining (R65, R66, R79)

export interface TimePoint {
  t: Iso;
  started: number;
  completed: number;
}

export interface Kpi {
  id: Id;
  name: string;
  value: number;
  target?: number;
  unit?: string;
  trend?: number;
  status?: 'OK' | 'WARNING' | 'BREACHED';
}

export interface SlaRow {
  key: string;
  name?: string;
  total: number;
  withinSla: number;
  warning: number;
  breached: number;
  avgBusinessDays?: number;
}

export interface Bottleneck {
  activityId: string;
  activityName?: string;
  avgWaitMs: number;
  avgServiceMs: number;
  p95Ms: number;
  count: number;
}

export interface Variant {
  id: Id;
  path: string[];
  count: number;
  share: number;
  avgDurationMs: number;
  conformant?: boolean;
}

export interface ConformanceResult {
  fitness: number;
  precision?: number;
  deviations: { activityId?: string; kind: 'SKIPPED' | 'INSERTED' | 'ORDER'; count: number }[];
  fixtureId?: string;
}

export interface DurationReportRow {
  period: string;
  count: number;
  avgMs: number;
  minMs: number;
  maxMs: number;
}

export interface AlertRule {
  id: Id;
  name: string;
  kpi: string;
  operator: '>' | '<' | '>=' | '<=';
  threshold: number;
  channel: 'IN_APP' | 'EMAIL' | 'WEBHOOK';
  recipients: string[];
  active: boolean;
}

// ---------------------------------------------------------------- audit (R42, S11, U01–U03)

export interface AuditEvent {
  seq: number;
  id: Id;
  time: Iso;
  type: string;
  businessKey: string;
  actor: string;
  seatName?: string;
  actedFor?: string;
  packageVersion?: string;
  ruleVersion?: string;
  payloadHash?: string;
  operationId?: string;
  downstreamStatus?: 'KNOWN' | 'PENDING' | 'UNCERTAIN';
  state?: string;
  details?: Record<string, unknown>;
  prevHash: string;
  hash: string;
}

export interface AuditVerifyResult {
  valid: boolean;
  checked: number;
  headHash?: string;
  firstBrokenSeq?: number;
  anchoredAt?: Iso;
  message?: string;
}

export interface AuditExportJob {
  id: Id;
  status: 'QUEUED' | 'RUNNING' | 'READY' | 'FAILED';
  rowCount?: number;
  requestedAt: Iso;
  format: 'CSV' | 'JSON' | 'PDFA';
  scope: Record<string, unknown>;
}

// ---------------------------------------------------------------- citizen portal (C01–C06, UP-22)

export interface PortalService {
  key: string;
  name: string;
  nameHi?: string;
  description?: string;
  descriptionHi?: string;
  slaDays?: number;
  fee?: number;
  formKey: string;
  documentsRequired?: string[];
}

export interface Clarification {
  id: Id;
  question: string;
  askedAt: Iso;
  askedBySeat?: string;
  dueAt?: Iso;
  reply?: string;
  repliedAt?: Iso;
}

export interface Application {
  id: Id;
  reference: string;
  serviceKey: string;
  serviceName: string;
  submittedAt?: Iso;
  stage: string;
  stageLabel?: string;
  stageLabelHi?: string;
  slaDate?: Iso;
  status: 'DRAFT' | 'SUBMITTED' | 'IN_PROGRESS' | 'CLARIFICATION_SOUGHT' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
  clarifications: Clarification[];
  messages?: { at: Iso; text: string }[];
  outcomeDocumentId?: Id;
  appealDeadline?: Iso;
  paymentStatus?: 'NOT_REQUIRED' | 'DUE' | 'PAID' | 'UNKNOWN_OUTCOME';
}

export interface Notification {
  id: Id;
  at: Iso;
  title: string;
  body?: string;
  read: boolean;
  category: 'GOVERNMENT_MESSAGE' | 'TASK' | 'SYSTEM';
  link?: string;
}
