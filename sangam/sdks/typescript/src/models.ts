// SPDX-License-Identifier: Apache-2.0
// Wire types of the Sangam API /api/v1, reconciled against sangam-rest/api/sangam-api.yaml 0.1.0.
// Times are ISO-8601 UTC strings; ids are strings; int64 fields are numbers (safe below 2^53).

import type { TypedValue } from './typed.js';

/**
 * Variables by name (VariableMap). Plain JSON values pass through; typed values use the R33 TypedValue form,
 * e.g. `{ amount: { type: 'Decimal', value: '1234.50' } }`. Build typed values with `TypedValues.*`.
 */
export type Variables = Record<string, TypedValue | unknown>;

// ---------------------------------------------------------------- worker API (/work)

/**
 * A locked unit of external work (VI.3). The handler runs only after this LOCKED confirmation with its
 * `lockVersion` (A-1). `operationId` is stable across retries and lease expiry (R36) and is the idempotency key
 * for downstream calls; `attemptId` changes per lease (A-2).
 */
export interface ExternalWork {
  id: string;
  topic: string;
  tenantId?: string;
  processInstanceId?: string;
  processDefinitionKey?: string;
  activityId?: string;
  activityInstanceId?: string;
  businessKey?: string;
  variables?: Variables;
  lockVersion: number;
  attemptId: string;
  operationId?: string;
  lockExpiresAt?: string;
  retries?: number;
  /** int64 */
  priority?: number;
  /** Message of the previous failure, if any. */
  errorMessage?: string;
  /**
   * Not part of contract 0.1.0. Honoured if a future server sends `irreversible`; irreversibility otherwise comes
   * from the subscription's `irreversible` flag.
   */
  effect?: string;
}

/** Topic subscription for fetch-and-lock (lease default 5 min, maximum 24 h). */
export interface TopicRequest {
  name: string;
  lockDurationMs?: number;
  variables?: string[];
  localVariables?: boolean;
  processDefinitionKey?: string;
  businessKey?: string;
}

/** @deprecated alias of TopicRequest */
export type FetchTopic = TopicRequest;

export interface FetchAndLockRequest {
  workerId: string;
  topics: TopicRequest[];
  maxTasks?: number;
  asyncResponseTimeoutMs?: number;
  usePriority?: boolean;
}

export interface ExtendLockRequest {
  workerId: string;
  lockVersion: number;
  attemptId?: string;
  newDurationMs: number;
}

/** Heartbeat response; `cancelRequested` tells the worker to stop and acknowledge (VI.3). */
export interface LockState {
  id?: string;
  lockVersion?: number;
  lockExpiresAt?: string;
  cancelRequested?: boolean;
}

/** @deprecated alias of LockState */
export type ExtendLockResult = LockState;

export interface CompleteWorkRequest {
  workerId: string;
  lockVersion: number;
  attemptId?: string;
  variables?: Variables;
  localVariables?: Variables;
}

/** `retries` left after this failure; 0 raises an incident. */
export interface FailWorkRequest {
  workerId: string;
  lockVersion: number;
  attemptId?: string;
  errorMessage?: string;
  errorDetails?: string;
  retries?: number;
  retryTimeoutMs?: number;
}

export interface BpmnErrorRequest {
  workerId: string;
  lockVersion: number;
  attemptId?: string;
  errorCode: string;
  errorMessage?: string;
  variables?: Variables;
}

/** Body of `/unlock` and `/cancel-ack`. */
export interface UnlockRequest {
  workerId: string;
  lockVersion: number;
  attemptId?: string;
}

/** The downstream effect may or may not have happened (VI.3.2 rule 4). */
export interface OutcomeUnknownRequest {
  workerId: string;
  lockVersion: number;
  attemptId?: string;
  detail?: string;
  evidence?: Variables;
}

/** 202: the work moved to UNKNOWN_OUTCOME and a reconciliation item was opened. */
export interface OutcomeUnknownResult {
  workId?: string;
  operationId?: string;
  reconciliationItemId?: string;
  state?: string;
}

// ---------------------------------------------------------------- common

/**
 * One page of a list: `X-Total-Count`, and `X-Total-Count-Capped: true` when the count was capped (A-16),
 * in which case `totalCount` is a lower bound ("more than N").
 */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalCount?: number;
  totalCountCapped: boolean;
}

/** Response objects carry `Idempotent-Replayed: true` when the server answered from its idempotency store (R41). */
export interface Replayable {
  /** Set by the SDK from the `Idempotent-Replayed` response header; not a wire field. */
  idempotentReplayed?: boolean;
}

export type JsonObject = Record<string, unknown>;

// ---------------------------------------------------------------- tasks

export interface TaskView {
  id: string;
  name?: string;
  description?: string;
  taskDefinitionKey?: string;
  processInstanceId?: string;
  processDefinitionKey?: string;
  caseInstanceId?: string;
  businessKey?: string;
  assignee?: string;
  owner?: string;
  seatId?: string;
  tenantId?: string;
  created?: string;
  due?: string;
  followUp?: string;
  lastUpdated?: string;
  claimTime?: string;
  state?: string;
  priority?: number;
  formKey?: string;
  delegationState?: string;
}

export interface TaskQuery {
  assignee?: string;
  mine?: boolean;
  unassigned?: boolean;
  candidateGroup?: string;
  seatId?: string;
  processInstanceId?: string;
  caseInstanceId?: string;
  businessKey?: string;
  processDefinitionKey?: string;
  taskDefinitionKey?: string;
  state?: string;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

/** `payloadHash` must equal the hash the user approved (VI.4 rule 5). */
export interface CompleteTaskRequest {
  payloadHash: string;
  /** int64 optimistic case version */
  caseVersion?: number;
  decision?: string;
  remarks?: string;
  formVersion?: string;
  variables?: Variables;
}

export interface CompleteTaskResult extends Replayable {
  taskId?: string;
  processInstanceId?: string;
  decision?: string;
  payloadHash?: string;
  instanceEnded?: boolean;
  nextTaskIds?: string[];
}

export interface ReturnTaskRequest {
  reason: string;
  reasonCode?: string;
  targetActivityId?: string;
  variables?: Variables;
}

export interface DelegateTaskRequest {
  toUserId?: string;
  toSeatId?: string;
  reason: string;
}

// ---------------------------------------------------------------- processes, messages, signals, cases

/** The tenant comes from the token, never from the request (VI.4 rule 6). */
export interface StartProcessRequest {
  key: string;
  businessKey?: string;
  variables?: Variables;
  version?: number;
  packageHash?: string;
  formVersion?: string;
  startBeforeActivityIds?: string[];
}

export interface ProcessInstance extends Replayable {
  id: string;
  processDefinitionId?: string;
  processDefinitionKey?: string;
  processDefinitionVersion?: number;
  businessKey?: string;
  tenantId?: string;
  state?: string;
  ended?: boolean;
  startTime?: string;
  endTime?: string;
  activeActivityIds?: string[];
  caseVersion?: number;
  startedBy?: string;
  businessStatus?: string;
  superProcessInstanceId?: string;
  rootProcessInstanceId?: string;
}

export interface ProcessInstanceQuery {
  key?: string;
  businessKey?: string;
  state?: string;
  startedAfter?: string;
  startedBefore?: string;
  withIncidents?: boolean;
  superProcessInstanceId?: string;
  page?: number;
  size?: number;
}

/** Business cancel: reason mandatory (VI.5, R23). */
export interface TerminateRequest {
  reason: string;
  reasonCode?: string;
}

export interface TerminationResult extends Replayable {
  processInstanceId?: string;
  state?: string;
  openOperationIds?: string[];
  cancelRequestedWork?: unknown;
  tombstones?: unknown;
}

export interface MessageRequest {
  messageName: string;
  businessKey?: string;
  processInstanceId?: string;
  correlationKeys?: Variables;
  variables?: Variables;
  all?: boolean;
}

/** @deprecated alias of MessageRequest */
export type CorrelateMessageRequest = MessageRequest;

export interface MessageResult extends Replayable {
  messageName?: string;
  processInstanceIds?: string[];
  executionIds?: string[];
}

export interface SignalRequest {
  name: string;
  executionId?: string;
  variables?: Variables;
}

export interface StartCaseRequest {
  key: string;
  businessKey?: string;
  variables?: Variables;
}

export interface CaseInstance extends Replayable {
  id: string;
  caseDefinitionId?: string;
  caseDefinitionKey?: string;
  businessKey?: string;
  tenantId?: string;
  state?: string;
  created?: string;
  executions?: unknown;
  reactivatedFrom?: string;
}

// ---------------------------------------------------------------- decisions

export interface DecisionEvaluateRequest {
  variables?: Variables;
  version?: number;
  purpose?: 'ROUTING' | 'AUTHORITY' | 'OTHER';
  businessKey?: string;
}

/** @deprecated alias of DecisionEvaluateRequest */
export type EvaluateDecisionRequest = DecisionEvaluateRequest;

/** Decision output rows (R33 typed). */
export interface DecisionEvaluateResult {
  decisionDefinitionId?: string;
  decisionKey?: string;
  version?: number;
  result: Variables[];
  outputDigest?: string;
  auditEventId?: string;
}

/** @deprecated alias of DecisionEvaluateResult */
export type DecisionResult = DecisionEvaluateResult;

// ---------------------------------------------------------------- audit

export interface AuditEntry {
  eventId: string;
  eventClass?: string;
  chainId?: string;
  seq?: number;
  type?: string;
  occurredAt?: string;
  actor?: string;
  seatId?: string;
  actedForSeatId?: string;
  processInstanceId?: string;
  activityId?: string;
  taskId?: string;
  payload?: unknown;
  erased?: boolean;
  hash?: string;
  evidence?: unknown;
}

export interface AuditVerifyResult {
  tenantId?: string;
  ok: boolean;
  verifiedAt?: string;
  chains?: unknown[];
}

// ---------------------------------------------------------------- ops / reconciliation

export interface ReconciliationItem {
  id: string;
  kind?: 'UNKNOWN_OUTCOME' | 'LATE_RESULT_AFTER_DELETE' | string;
  operationId?: string;
  rootId?: string;
  taskId?: string;
  state?: 'OPEN' | 'RESOLVED' | string;
  details?: unknown;
  action?: string;
  resolvedBy?: string;
  resolution?: unknown;
  createdAt?: string;
  resolvedAt?: string;
}

export interface ReconciliationQuery {
  state?: 'OPEN' | 'RESOLVED';
  kind?: 'UNKNOWN_OUTCOME' | 'LATE_RESULT_AFTER_DELETE';
  page?: number;
  size?: number;
}

/** `CONFIRM_SUCCESS` (with result variables), `RETRY_SAME_OPERATION` or `MARK_FAILED`; evidence mandatory. */
export interface ResolveReconciliationRequest {
  action: 'CONFIRM_SUCCESS' | 'RETRY_SAME_OPERATION' | 'MARK_FAILED';
  evidence: string;
  result?: Variables;
  reason?: string;
}

export interface IncidentAction {
  reason: string;
  retries?: number;
  annotation?: string;
}

export interface JobRetriesRequest {
  retries: number;
  reason: string;
}

export interface OutboxRepairRequest {
  reason: string;
  destination?: string;
}

// ---------------------------------------------------------------- repository / events

export interface Deployment extends Replayable {
  id: string;
  name?: string;
  source?: string;
  tenantId?: string;
  deploymentTime?: string;
  resources?: string[];
  definitions?: JsonObject[];
}

/** CloudEvents 1.0 structured JSON (R38 inbox dedupe by tenant, source, id). */
export interface CloudEvent<D = unknown> {
  id: string;
  source: string;
  type: string;
  specversion: '1.0' | string;
  subject?: string;
  time?: string;
  datacontenttype?: string;
  dataschema?: string;
  data?: D;
  extensions?: JsonObject;
}

export interface PublishResult extends Replayable {
  eventId?: string;
  status?: 'ACCEPTED' | 'DUPLICATE' | 'BUFFERED';
  eventDefinitionKey?: string;
  startedInstanceIds?: string[];
  correlatedInstanceIds?: string[];
}
