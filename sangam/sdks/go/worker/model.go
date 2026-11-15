// SPDX-License-Identifier: Apache-2.0

// Package worker is the minimal Tier-2 worker loop for Go (ADR-D-4, R18, M-022): fetch-and-lock, heartbeats at
// lease/3, fenced reports with an Idempotency-Key derived from the stable operationId, and STALE_LEASE handling
// (VI.3). It needs only the standard library. The full REST surface is the generated client
// (sdks/tier2/generate_tier2.py).
package worker

// The work API wire types, field for field from sangam-rest/api/sangam-api.yaml (components.schemas). Optional
// numbers are pointers so that "absent" and "0" stay different.

// TypedValue is the R33 typed JSON value: Decimal, BigInteger, Long, Date, LocalDate and Bytes travel as strings.
type TypedValue struct {
	Type  string `json:"type"`
	Value any    `json:"value"`
}

// VariableMap holds plain JSON values or TypedValue objects by name.
type VariableMap map[string]any

// WorkOperation is the operation-ledger entry of a leased work item (VI.3 rules 4-5, R37).
type WorkOperation struct {
	State           string         `json:"state,omitempty"`
	Attempts        *int32         `json:"attempts,omitempty"`
	CapabilityClass string         `json:"capabilityClass,omitempty"`
	MayDispatch     *bool          `json:"mayDispatch,omitempty"`
	StoredOutcome   map[string]any `json:"storedOutcome,omitempty"`
}

// ExternalWork is a locked unit of external work.
type ExternalWork struct {
	ID                   string         `json:"id,omitempty"`
	Topic                string         `json:"topic,omitempty"`
	TenantID             string         `json:"tenantId,omitempty"`
	ProcessInstanceID    string         `json:"processInstanceId,omitempty"`
	ProcessDefinitionKey string         `json:"processDefinitionKey,omitempty"`
	ActivityID           string         `json:"activityId,omitempty"`
	ActivityInstanceID   string         `json:"activityInstanceId,omitempty"`
	BusinessKey          string         `json:"businessKey,omitempty"`
	Variables            VariableMap    `json:"variables,omitempty"`
	LockVersion          *int64         `json:"lockVersion,omitempty"`
	AttemptID            string         `json:"attemptId,omitempty"`
	OperationID          string         `json:"operationId,omitempty"`
	LockExpiresAt        string         `json:"lockExpiresAt,omitempty"`
	Retries              *int32         `json:"retries,omitempty"`
	Priority             *int64         `json:"priority,omitempty"`
	ErrorMessage         string         `json:"errorMessage,omitempty"`
	Operation            *WorkOperation `json:"operation,omitempty"`
	// TraceContext is the W3C trace context the engine stored for the task (M-062); nil when none was stored.
	TraceContext *TraceContext `json:"traceContext,omitempty"`
}

// TraceContext is the W3C Trace Context of the request that created the work (R13); Traceparent is required.
type TraceContext struct {
	Traceparent string `json:"traceparent"`
	Tracestate  string `json:"tracestate,omitempty"`
}

// TopicRequest is one topic subscription of fetch-and-lock.
type TopicRequest struct {
	Name                 string   `json:"name"`
	LockDurationMs       *int64   `json:"lockDurationMs,omitempty"`
	Variables            []string `json:"variables,omitempty"`
	LocalVariables       *bool    `json:"localVariables,omitempty"`
	ProcessDefinitionKey string   `json:"processDefinitionKey,omitempty"`
	BusinessKey          string   `json:"businessKey,omitempty"`
}

// FetchAndLockRequest is the long-poll fetch-and-lock body.
type FetchAndLockRequest struct {
	WorkerID               string         `json:"workerId"`
	Topics                 []TopicRequest `json:"topics"`
	MaxTasks               *int32         `json:"maxTasks,omitempty"`
	AsyncResponseTimeoutMs *int64         `json:"asyncResponseTimeoutMs,omitempty"`
	UsePriority            *bool          `json:"usePriority,omitempty"`
}

// LockState is the heartbeat response; CancelRequested tells the worker to stop and acknowledge.
type LockState struct {
	ID              string `json:"id,omitempty"`
	LockVersion     *int64 `json:"lockVersion,omitempty"`
	LockExpiresAt   string `json:"lockExpiresAt,omitempty"`
	CancelRequested *bool  `json:"cancelRequested,omitempty"`
}

// ExtendLockRequest is the heartbeat body.
type ExtendLockRequest struct {
	WorkerID      string `json:"workerId"`
	LockVersion   int64  `json:"lockVersion"`
	AttemptID     string `json:"attemptId,omitempty"`
	NewDurationMs int64  `json:"newDurationMs"`
}

// WorkOutcome is the downstream result recorded as the ledger outcome.
type WorkOutcome struct {
	Reference string         `json:"reference,omitempty"`
	Details   map[string]any `json:"details,omitempty"`
}

// WorkCompleteRequest completes locked work (fenced).
type WorkCompleteRequest struct {
	WorkerID       string       `json:"workerId"`
	LockVersion    int64        `json:"lockVersion"`
	AttemptID      string       `json:"attemptId,omitempty"`
	Variables      VariableMap  `json:"variables,omitempty"`
	LocalVariables VariableMap  `json:"localVariables,omitempty"`
	Outcome        *WorkOutcome `json:"outcome,omitempty"`
}

// WorkFailRequest reports a failure; retries=0 raises an incident.
type WorkFailRequest struct {
	WorkerID       string `json:"workerId"`
	LockVersion    int64  `json:"lockVersion"`
	AttemptID      string `json:"attemptId,omitempty"`
	ErrorMessage   string `json:"errorMessage,omitempty"`
	ErrorDetails   string `json:"errorDetails,omitempty"`
	Retries        *int32 `json:"retries,omitempty"`
	RetryTimeoutMs *int64 `json:"retryTimeoutMs,omitempty"`
}

// WorkBpmnErrorRequest throws a BPMN error routed by the model.
type WorkBpmnErrorRequest struct {
	WorkerID     string      `json:"workerId"`
	LockVersion  int64       `json:"lockVersion"`
	AttemptID    string      `json:"attemptId,omitempty"`
	ErrorCode    string      `json:"errorCode"`
	ErrorMessage string      `json:"errorMessage,omitempty"`
	Variables    VariableMap `json:"variables,omitempty"`
}

// WorkUnlockRequest releases a lease (LOCKED -> AVAILABLE).
type WorkUnlockRequest struct {
	WorkerID    string `json:"workerId"`
	LockVersion int64  `json:"lockVersion"`
	AttemptID   string `json:"attemptId,omitempty"`
}

// OutcomeUnknownRequest reports that the downstream effect may or may not have happened (CX-5).
type OutcomeUnknownRequest struct {
	WorkerID    string      `json:"workerId"`
	LockVersion int64       `json:"lockVersion"`
	AttemptID   string      `json:"attemptId,omitempty"`
	Detail      string      `json:"detail,omitempty"`
	Evidence    VariableMap `json:"evidence,omitempty"`
}

// Problem is the RFC 9457 error body; Code is the stable machine code (for example STALE_LEASE).
type Problem struct {
	Type     string `json:"type,omitempty"`
	Title    string `json:"title,omitempty"`
	Status   int32  `json:"status,omitempty"`
	Detail   string `json:"detail,omitempty"`
	Instance string `json:"instance,omitempty"`
	Code     string `json:"code,omitempty"`
	TraceID  string `json:"traceId,omitempty"`
	// PowerLimit is present with POWER_LIMIT_EXCEEDED: the decision that refused the action (decision 142), kept as the
	// server sent it (schema PowerLimitDecision).
	PowerLimit map[string]any `json:"powerLimit,omitempty"`
}
