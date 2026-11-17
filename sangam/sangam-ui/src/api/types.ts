// SPDX-License-Identifier: Apache-2.0
/**
 * Domain types for the Sangam API. Written against ARCHITECTURE §4 while sangam-rest/api/sangam-api.yaml was not yet
 * published; see README "API reconciliation" for the list of paths and fields to check against the OpenAPI file.
 */
import type { TenantLocale } from '../i18n';

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
  /**
   * M-045: the tenant's locale (resolved profile `locale`: currency, zone, grouping). Requested as an additive `/me`
   * field (WorkspacePort.Me has none yet); while absent, money shows the record's own currency or a plain number.
   */
  locale?: TenantLocale;
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
  /**
   * M-042 task index (contract Task.amount, MoneyAmount; owner decisions 138 and 211): decimal text in MAJOR units of
   * `currency`, e.g. "1234.56". Show with fmtMoneyAmount; never parse it into a number.
   */
  amount?: string | null;
  /** M-042: ISO 4217 code of `amount`; absent with it. */
  currency?: string;
  created: Iso;
  received?: Iso;
  due?: Iso;
  slaDue?: Iso;
  slaState?: 'OK' | 'WARNING' | 'BREACHED' | 'PAUSED';
  priority?: number;
  claimable?: boolean;
  returned?: boolean;
  /** P45 card variables, only when the list was asked for them (GET /tasks?variableNames=). */
  variables?: Record<string, unknown>;
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
  /** Read only (decision 236, TaskDscDocument): the document a DSC step signs, as captured when the task was created. */
  dscDocument?: TaskDscDocument | null;
}

/** The slot (case variable) of a DSC step's document and the document id it held at task creation (null: empty). */
export interface TaskDscDocument {
  slot: string;
  documentId?: Id | null;
}

/** Decision 267: document types the packs declare slots for (W4-K packs: NOTING, SANCTION_NOTE); other types are metadata. */
export const DOCUMENT_SLOT_TYPES = ['NOTING', 'SANCTION_NOTE'] as const;

/** Decision 265 DataObjectRecordOperation: a record create on a REST-backed data object, through the operation ledger. */
export type RecordOperationStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'UNKNOWN';
export interface DataObjectRecordOperation {
  operationId: string;
  dataObjectKey: string;
  status: RecordOperationStatus;
  /** COMPLETED only. */
  record?: Record<string, unknown>;
  /** FAILED only: the external system's failure code. */
  failureCode?: string;
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
  /** Generic module screens: the value only fills a {placeholder} of the action path; it is not sent. */
  inPath?: boolean;
  /**
   * Generic module screens: a checkbox the user did not touch is left out of the body, so the server applies its
   * documented default for a missing value (e.g. a committee kind's chairRequired). Without it a body checkbox is always
   * sent as true / false (its defaultValue when untouched, else false): a request member bound to a Java primitive is
   * refused when missing (Jackson 3, 400 VALIDATION_FAILED).
   */
  omitUntouched?: boolean;
  /**
   * UI-DEMO-1 (decision 400 (3)): a pack money field. The person enters major units; the value is sent as integer minor
   * units of the currency (value x 10^exponent, exact), the pack contract (packgen money.py).
   */
  money?: { exponent: number; currency?: string };
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
  /**
   * M-044: W3C trace id (32 lowercase hex) of the command that started the instance; absent when none was recorded.
   * Requested from W3-M as ProcessInstance.traceId (WAVE3-SYNC-BOARD 2026-11-12T11:28:00Z).
   */
  traceId?: string;
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

/** P14 activity-instance state filter (GET /history/activity-instances?state=). */
export type ActivityState = 'running' | 'completed' | 'canceled';

/** GET /history/activity-instances row. */
export interface HistoricActivityInstance {
  id: Id;
  activityId: string;
  activityName?: string;
  activityType?: string;
  processInstanceId?: Id;
  startTime?: Iso;
  endTime?: Iso;
  durationMs?: number;
  canceled?: boolean;
}

/** P13 selection in the activity tree: filters the variables, incidents and history tabs. */
export interface ActivitySelection {
  activityInstanceId: Id;
  activityId: string;
  activityType?: string;
  label: string;
  /** The process-instance root: no filter. */
  root?: boolean;
}

/** GET /history/incidents row. */
export interface HistoricIncident {
  id: Id;
  type?: string;
  message?: string;
  processInstanceId?: Id;
  activityId?: string;
  createTime?: Iso;
  endTime?: Iso;
  state?: string;
}

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

/** A view of components.schemas.HistoricDecision (adapters.normDecisionInstance): inputs and outputs are name/value maps there. */
export interface DecisionInstance {
  id: Id;
  decisionKey: string;
  decisionDefinitionId?: Id;
  evaluationTime: Iso;
  processInstanceId?: Id;
  activityId?: string;
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

/** M-125 diffModels levels (sangam-api.yaml ModelDiffSummary / ModelDiffChange.level). */
export const MODEL_DIFF_LEVELS = ['ELEMENT', 'FLOW', 'EXPRESSION', 'PROPERTY'] as const;
export type ModelDiffLevel = (typeof MODEL_DIFF_LEVELS)[number];

/** POST /models/diff body (ModelDiffRequest). resource required, at most 255; each side at most 5 MiB; null or empty = new / deleted file. */
export interface ModelDiffRequest {
  resource: string;
  before: string | null;
  after: string | null;
}

/** One semantic change (ModelDiffChange). Layout (BPMNDI, DMNDI, CMMNDI) is never a change. */
export interface ModelDiffChange {
  level: ModelDiffLevel;
  kind: 'ADDED' | 'REMOVED' | 'CHANGED';
  elementId: string;
  /** BPMN/DMN/CMMN local name, or the form component's type. */
  elementType: string;
  name: string;
  /** The changed property or expression inside the element; empty for the element itself. */
  path: string;
  /** Null when added. */
  before?: string | null;
  /** Null when removed. */
  after?: string | null;
}

/** 200 of POST /models/diff (ModelDiffReport); changes sorted by level, elementId, path, kind. */
export interface ModelDiffReport {
  resource: string;
  format: 'BPMN' | 'DMN' | 'CMMN' | 'FORM';
  changed: boolean;
  summary: Record<ModelDiffLevel, number>;
  changes: ModelDiffChange[];
}

/** M-101 one user who has the model file open (ModelViewer); editing = holds the file's edit lease. */
export interface ModelViewer {
  userId: string;
  displayName?: string;
  editing: boolean;
  /** date-time: when this user opened the file. */
  since: string;
}

/** Data of the 'presence' event of GET /models/by-file/presence/stream?file= (ModelPresence). */
export interface ModelPresence {
  fileId: string;
  viewers: ModelViewer[];
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

/** components.schemas.Policy: one (definition, principal) row; several rows can share a definition id. */
export interface Policy {
  id: Id;
  tenantId?: Id;
  effect: 'GRANT' | 'DENY';
  principalType: 'USER' | 'GROUP' | 'SEAT';
  principalId: Id;
  resourceType: string;
  resourceId: string;
  actions: string[];
  conditionId?: string;
  policyVersion?: number;
  createdBy?: Id;
  createdAt?: Iso;
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
  /** The hash chain the event belongs to (AuditEvent.chainId); seq and prevHash link within one chain only. */
  chainId?: string;
}

/** The screen's reading of AuditVerifyResult (adapters.normVerify). */
export interface AuditVerifyResult {
  /** Every chain verified and at least one event checked; never true for a check over nothing. */
  valid: boolean;
  /** Events checked over all chains (sum of ChainVerification.eventsChecked). */
  checked: number;
  /** Chains the server checked. */
  chains: number;
  firstBrokenSeq?: number;
  brokenChainId?: string;
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
  /** Absent for a service without an application form (PortalPort.PortalService). */
  formKey?: string;
  documentsRequired?: string[];
  /** The consent notice of the service: a submission carries the consent on its current published version (decision 339). */
  noticeKey?: string;
}

/** PortalCompliancePort.NoticePurpose: one purpose of a notice, named in the notice's language. */
export interface NoticePurpose {
  code: string;
  name: string;
  description?: string;
}

/** PortalCompliancePort.PortalNotice: the current published version of a service's consent notice (decisions 339, 341, 343). */
export interface PortalNotice {
  noticeKey: string;
  version: number;
  language: string;
  text?: string;
  html?: string;
  contentHash?: string;
  purposes: NoticePurpose[];
  /** The jurisdiction pack's age of majority; absent when the pack sets none. */
  childAgeYears?: number;
}

/** PortalPort.PortalConsent: the consent sent with a submission, purposes ticked one by one (decision 341). */
export interface PortalConsent {
  noticeKey: string;
  noticeVersion: number;
  language: string;
  purposeCodes: string[];
  ageDeclaration?: 'ADULT' | 'CHILD';
  guardianVerificationId?: string;
}

/** PortalCompliancePort.NoticeItem: one item of a notice to a data principal (IN: DPDP Rules r.7(1)(a)-(e)). */
export interface NoticeItem {
  key: string;
  label: string;
  citation?: string;
  text: string;
}

/** PortalCompliancePort.PortalNotification: a notice in the caller's portal account (decision 347). */
export interface PortalNotification {
  id: Id;
  kind: 'BREACH_NOTICE';
  reference: string;
  at?: Iso;
  subject?: string;
  items: NoticeItem[];
}

/** ConsentService.Consent (ConsentService.java:57-59), as GET /compliance/consents returns the caller's own. */
export interface MyConsent {
  consentId: Id;
  principalRef?: string;
  noticeKey?: string;
  noticeVersion: number;
  language?: string;
  status: string;
  givenAt?: Iso;
  withdrawnAt?: Iso;
  legacy?: boolean;
  activePurposes: string[];
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

/** M-068 contract ApiContractSummary: one stored contract version, without its document. */
export interface ApiContractSummary {
  tenantId?: string;
  packageId: string;
  contractId: string;
  version: number;
  format: 'OPENAPI' | 'ASYNCAPI';
  specVersion?: string;
  sha256: string;
}

/** M-068 contract ContractOperation: method and path (OpenAPI) or action and channel (AsyncAPI). */
export interface ContractOperation {
  operationId: string;
  format: 'OPENAPI' | 'ASYNCAPI';
  method?: string;
  path?: string;
  action?: string;
  channel?: string;
  summary?: string;
  requestSchema?: Record<string, unknown>;
  responseSchemas?: Record<string, unknown>;
  servers?: string[];
}

/** M-021 contract TestClock: the instant the engine and business clocks read, their offset from the wall clock. */
export interface TestClock {
  now: Iso;
  /** int64 milliseconds; negative when the clock was set into the past. */
  offsetMs: number;
  /** Set on a change only. */
  action?: 'ADVANCE' | 'SET' | 'RESET';
}

/** M-021 contract TestClockChange: exactly one of advanceBy, set or reset; reason goes to TEST_CLOCK_CHANGED. */
export type TestClockChange = ({ advanceBy: string } | { set: Iso } | { reset: true }) & { reason?: string };

/**
 * Decision 165 / 236-238 contract (sangam-rest/api/routes/dsc.py, DscPrepareResponse): what the server prepared for
 * one DSC signature of a task document. prepareId goes back with the signature; the signature is bound server-side to
 * the approved enrolment of the certificate (decisions 227, 241) and to this prepared document version (decision 228).
 */
export interface DscPrepareResponse {
  prepareId: string;
  signedAttributes: string;
  preparedDigestSha256: string;
  algorithm: 'SHA256withRSA' | 'SHA256withECDSA';
  expiresAt: Iso;
  documentId: Id;
  documentVersion: number;
}

/** DscCompleteResponse: the verification outcome (decision 140). Completing the task is a separate call. */
export interface DscCompleteResponse {
  outcome: 'VERIFIED_VALID' | 'VERIFIED_INVALID' | 'INDETERMINATE';
  reasonCode?: string | null;
  retryable: boolean;
  evidence?: Record<string, unknown>;
  documentId: Id;
  signedDocumentVersion?: number | null;
}

/** Decision 227: one DSC certificate enrolment (DscCertificate). Only APPROVED binds a signer. */
export type DscEnrolmentStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED';
export interface DscEnrolment {
  enrolmentId: Id;
  principalId: string;
  certificateSha256: string;
  issuer: string;
  serialHex: string;
  subject: string;
  notBefore: Iso;
  notAfter: Iso;
  status: DscEnrolmentStatus;
  enrolledAt: Iso;
  decidedBy?: string | null;
  decidedAt?: Iso | null;
  decisionReason?: string | null;
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
