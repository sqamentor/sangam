// SPDX-License-Identifier: Apache-2.0

package worker

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Config of one worker. BaseURL is the server root (a trailing /api/v1 is accepted).
type Config struct {
	BaseURL  string
	Token    string // static bearer token (dev profile); use TokenFunc for OIDC
	TokenFunc func(ctx context.Context) (string, error)
	WorkerID string
	Topic    string
	// LockDuration of the lease (default 5 min, server maximum 24 h). Heartbeats run at a third of it.
	LockDuration time.Duration
	MaxTasks     int32
	// AsyncResponseTimeout of the long-poll fetch (default 20 s).
	AsyncResponseTimeout time.Duration
	// Retries left after a handler error (default: the work item's retries - 1, at least 0).
	Retries *int32
	// RetryTimeout before a failed item is offered again (default 10 s).
	RetryTimeout time.Duration
	// Irreversible marks a topic with a non-idempotent effect: a handler error is reported as outcome-unknown
	// instead of fail, so it is never retried blindly (VI.3 rule 5).
	Irreversible bool
	HTTPClient   *http.Client
	// TraceContext returns the caller's W3C traceparent (and tracestate), sent on every call (R13, M-062). Optional.
	TraceContext func() (traceparent, tracestate string)
	// Log receives one line per event; nil discards.
	Log func(event, message string, fields map[string]any)
}

// Task is one locked work item handed to the handler, after the LOCKED confirmation with its lockVersion (A-1).
type Task struct {
	Work ExternalWork
	// Cancelled is closed when the server requested a cancel (heartbeat cancelRequested) or the lease was lost.
	Cancelled <-chan struct{}
}

// Traceparent returns the W3C traceparent the engine stored for this task (ExternalWork.traceContext, M-062), or "".
func (t *Task) Traceparent() string {
	if t.Work.TraceContext == nil {
		return ""
	}
	return t.Work.TraceContext.Traceparent
}

// Tracestate returns the W3C tracestate of the task's trace context, or "".
func (t *Task) Tracestate() string {
	if t.Work.TraceContext == nil {
		return ""
	}
	return t.Work.TraceContext.Tracestate
}

// Result of a handler. Return Complete(...) on success; BpmnError, OutcomeUnknown or Fail otherwise.
type Result struct {
	kind      string
	variables VariableMap
	reference string
	errorCode string
	message   string
}

// Complete the work with output variables and an optional downstream reference (stored as providerRef).
func Complete(variables VariableMap, reference string) Result {
	return Result{kind: "complete", variables: variables, reference: reference}
}

// BpmnError throws a BPMN error routed by the model.
func BpmnError(code, message string, variables VariableMap) Result {
	return Result{kind: "bpmn-error", errorCode: code, message: message, variables: variables}
}

// OutcomeUnknown reports that the downstream effect may or may not have happened: a reconciliation item is opened and
// the work is not offered again until it is resolved (VI.3 rule 4).
func OutcomeUnknown(detail string) Result {
	return Result{kind: "outcome-unknown", message: detail}
}

// Fail reports a failure; the configured retries decide between a retry and an incident.
func Fail(message string) Result {
	return Result{kind: "fail", message: message}
}

// Handler processes one task. A returned error is reported as Fail (or OutcomeUnknown on an irreversible topic).
type Handler func(ctx context.Context, task *Task) (Result, error)

// ErrStaleLease marks a 409 STALE_LEASE: the lease moved on, the server changed nothing (VI.3 rule 2).
var ErrStaleLease = errors.New("sangam: stale lease")

// ErrGone marks a 410: the instance of the work was deleted; the late result was recorded.
var ErrGone = errors.New("sangam: gone")

// APIError is any other non-2xx answer.
type APIError struct {
	Status  int
	Problem Problem
}

func (e *APIError) Error() string {
	return fmt.Sprintf("sangam: HTTP %d %s %s: %s", e.Status, e.Problem.Code, e.Problem.Title, e.Problem.Detail)
}

// Worker runs the fetch / handle / report loop for one topic.
type Worker struct {
	cfg  Config
	base string
	http *http.Client
}

// New validates the configuration.
func New(cfg Config) (*Worker, error) {
	if cfg.BaseURL == "" || cfg.WorkerID == "" || cfg.Topic == "" {
		return nil, errors.New("sangam: BaseURL, WorkerID and Topic are required")
	}
	if cfg.LockDuration <= 0 {
		cfg.LockDuration = 5 * time.Minute
	}
	if cfg.MaxTasks <= 0 {
		cfg.MaxTasks = 1
	}
	if cfg.AsyncResponseTimeout <= 0 {
		cfg.AsyncResponseTimeout = 20 * time.Second
	}
	if cfg.RetryTimeout <= 0 {
		cfg.RetryTimeout = 10 * time.Second
	}
	h := cfg.HTTPClient
	if h == nil {
		h = &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	base := strings.TrimRight(cfg.BaseURL, "/")
	base = strings.TrimSuffix(base, "/api/v1")
	return &Worker{cfg: cfg, base: base + "/api/v1", http: h}, nil
}

func (w *Worker) log(event, message string, fields map[string]any) {
	if w.cfg.Log != nil {
		w.cfg.Log(event, message, fields)
	}
}

// Run fetches and processes work until ctx is cancelled. Items run one after another (MaxTasks per fetch).
func (w *Worker) Run(ctx context.Context, handler Handler) error {
	backoff := 200 * time.Millisecond
	for ctx.Err() == nil {
		items, err := w.fetch(ctx)
		if err != nil {
			if ctx.Err() != nil {
				break
			}
			w.log("work.fetch.failed", err.Error(), nil)
			sleep(ctx, backoff)
			if backoff < 10*time.Second {
				backoff *= 2
			}
			continue
		}
		backoff = 200 * time.Millisecond
		for i := range items {
			w.process(ctx, &items[i], handler)
		}
	}
	return ctx.Err()
}

func (w *Worker) fetch(ctx context.Context) ([]ExternalWork, error) {
	lock := w.cfg.LockDuration.Milliseconds()
	wait := w.cfg.AsyncResponseTimeout.Milliseconds()
	max := w.cfg.MaxTasks
	body := FetchAndLockRequest{WorkerID: w.cfg.WorkerID, Topics: []TopicRequest{{Name: w.cfg.Topic, LockDurationMs: &lock}},
		MaxTasks: &max, AsyncResponseTimeoutMs: &wait}
	var out []ExternalWork
	// the long poll gets its own timeout: the async wait plus a margin; it is never replayed by key
	cctx, cancel := context.WithTimeout(ctx, w.cfg.AsyncResponseTimeout+15*time.Second)
	defer cancel()
	err := w.call(cctx, "/work/fetch-and-lock", "", body, &out)
	return out, err
}

func (w *Worker) process(ctx context.Context, work *ExternalWork, handler Handler) {
	if work.ID == "" || work.LockVersion == nil || work.AttemptID == "" {
		// without the fencing token the item cannot be reported; its lease expires on its own (A-1)
		w.log("work.invalid", "work item without id, lockVersion or attemptId", map[string]any{"id": work.ID})
		return
	}
	var mu sync.Mutex
	lockVersion := *work.LockVersion
	cancelled := make(chan struct{})
	var once sync.Once
	cancel := func() { once.Do(func() { close(cancelled) }) }
	hctx, stop := context.WithCancel(ctx)
	defer stop()
	done := make(chan struct{})
	go func() { // heartbeat at lease/3 (VI.3 rule 3)
		interval := w.cfg.LockDuration / 3
		if interval < time.Second {
			interval = time.Second
		}
		seq := 0
		for {
			select {
			case <-done:
				return
			case <-time.After(interval):
			}
			seq++
			mu.Lock()
			lv := lockVersion
			mu.Unlock()
			var st LockState
			key := fmt.Sprintf("%s:extend-lock:%d:%d", work.OperationID, lv, seq)
			err := w.call(hctx, "/work/"+url.PathEscape(work.ID)+"/extend-lock", key,
				ExtendLockRequest{WorkerID: w.cfg.WorkerID, LockVersion: lv, AttemptID: work.AttemptID,
					NewDurationMs: w.cfg.LockDuration.Milliseconds()}, &st)
			if errors.Is(err, ErrStaleLease) || errors.Is(err, ErrGone) {
				w.log("work.lease.lost", err.Error(), map[string]any{"workId": work.ID})
				cancel()
				stop()
				return
			}
			if err == nil {
				if st.LockVersion != nil {
					mu.Lock()
					lockVersion = *st.LockVersion
					mu.Unlock()
				}
				if st.CancelRequested != nil && *st.CancelRequested {
					w.log("work.cancel.requested", "server requested cancel", map[string]any{"workId": work.ID})
					cancel()
				}
			}
		}
	}()
	res, herr := handler(hctx, &Task{Work: *work, Cancelled: cancelled})
	close(done)
	mu.Lock()
	lv := lockVersion
	mu.Unlock()
	select {
	case <-cancelled:
		if hctx.Err() != nil { // lease lost: the server already moved on; nothing may be reported
			return
		}
		// cancel requested: acknowledge instead of reporting a result (VI.3, CANCEL_REQUESTED -> CANCELLED)
		w.report(ctx, work, "cancel-ack", lv, map[string]any{"workerId": w.cfg.WorkerID, "lockVersion": lv,
			"attemptId": work.AttemptID})
		return
	default:
	}
	if herr != nil {
		if w.cfg.Irreversible {
			res = OutcomeUnknown(herr.Error())
		} else {
			res = Fail(herr.Error())
		}
	}
	switch res.kind {
	case "complete":
		var outcome *WorkOutcome
		if res.reference != "" {
			outcome = &WorkOutcome{Reference: res.reference}
		}
		w.report(ctx, work, "complete", lv, WorkCompleteRequest{WorkerID: w.cfg.WorkerID, LockVersion: lv,
			AttemptID: work.AttemptID, Variables: res.variables, Outcome: outcome})
	case "bpmn-error":
		w.report(ctx, work, "bpmn-error", lv, WorkBpmnErrorRequest{WorkerID: w.cfg.WorkerID, LockVersion: lv,
			AttemptID: work.AttemptID, ErrorCode: res.errorCode, ErrorMessage: res.message, Variables: res.variables})
	case "outcome-unknown":
		w.report(ctx, work, "outcome-unknown", lv, OutcomeUnknownRequest{WorkerID: w.cfg.WorkerID, LockVersion: lv,
			AttemptID: work.AttemptID, Detail: res.message})
	default:
		retries := w.cfg.Retries
		if retries == nil {
			r := int32(0)
			if work.Retries != nil && *work.Retries > 0 {
				r = *work.Retries - 1
			}
			retries = &r
		}
		timeout := w.cfg.RetryTimeout.Milliseconds()
		msg := res.message
		if len(msg) > 1000 {
			msg = msg[:1000]
		}
		w.report(ctx, work, "fail", lv, WorkFailRequest{WorkerID: w.cfg.WorkerID, LockVersion: lv,
			AttemptID: work.AttemptID, ErrorMessage: msg, Retries: retries, RetryTimeoutMs: &timeout})
	}
}

// report sends a fenced result with the Idempotency-Key operationId:action:lockVersion, so a repeat after a lost
// answer is the same request (R41); it retries transport errors and 5xx until the lease would have expired.
func (w *Worker) report(ctx context.Context, work *ExternalWork, action string, lockVersion int64, body any) {
	key := fmt.Sprintf("%s:%s:%d", work.OperationID, action, lockVersion)
	deadline := time.Now().Add(w.cfg.LockDuration)
	delay := 200 * time.Millisecond
	for {
		err := w.call(ctx, "/work/"+url.PathEscape(work.ID)+"/"+action, key, body, nil)
		if err == nil {
			w.log("work."+action, "reported", map[string]any{"workId": work.ID, "lockVersion": lockVersion})
			return
		}
		var api *APIError
		if errors.Is(err, ErrStaleLease) || errors.Is(err, ErrGone) || (errors.As(err, &api) && api.Status < 500 &&
			api.Status != 429) {
			// the server changed nothing (stale) or recorded the late result (gone): never retried
			w.log("work.abandoned", err.Error(), map[string]any{"workId": work.ID, "action": action})
			return
		}
		if ctx.Err() != nil || time.Now().After(deadline) {
			w.log("work.report.failed", err.Error(), map[string]any{"workId": work.ID, "action": action})
			return
		}
		sleep(ctx, delay)
		if delay < 5*time.Second {
			delay *= 2
		}
	}
}

// call POSTs a JSON body to /api/v1<path> and decodes a JSON answer into out (nil = ignore).
func (w *Worker) call(ctx context.Context, path, idempotencyKey string, body, out any) error {
	data, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, w.base+path, bytes.NewReader(data))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, application/problem+json")
	req.Header.Set("User-Agent", "sangam-sdk-go-worker/0.1.0")
	if idempotencyKey != "" {
		req.Header.Set("Idempotency-Key", idempotencyKey)
	}
	token := w.cfg.Token
	if w.cfg.TokenFunc != nil {
		if token, err = w.cfg.TokenFunc(ctx); err != nil {
			return err
		}
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if w.cfg.TraceContext != nil {
		if tp, ts := w.cfg.TraceContext(); ValidTraceparent(tp) {
			req.Header.Set("traceparent", tp)
			if ts != "" && len(ts) <= 512 {
				req.Header.Set("tracestate", ts)
			}
		}
	}
	resp, err := w.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode/100 == 2 {
		if out != nil && len(raw) > 0 {
			return json.Unmarshal(raw, out)
		}
		return nil
	}
	var p Problem
	_ = json.Unmarshal(raw, &p)
	switch {
	case resp.StatusCode == http.StatusConflict && p.Code == "STALE_LEASE":
		return fmt.Errorf("%w: %s", ErrStaleLease, p.Detail)
	case resp.StatusCode == http.StatusGone:
		return fmt.Errorf("%w: %s %s", ErrGone, p.Code, p.Detail)
	}
	return &APIError{Status: resp.StatusCode, Problem: p}
}

// ValidTraceparent accepts a well-formed version-00 W3C traceparent with non-zero trace-id and parent-id.
func ValidTraceparent(v string) bool {
	if len(v) != 55 || v[:3] != "00-" || v[35] != '-' || v[52] != '-' {
		return false
	}
	hex := v[3:35] + v[36:52] + v[53:]
	for _, c := range hex {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return strings.Trim(v[3:35], "0") != "" && strings.Trim(v[36:52], "0") != ""
}

func sleep(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}

// Decimal returns the R33 typed value of an exact decimal given as a string (money is never a binary float).
func Decimal(v string) TypedValue { return TypedValue{Type: "Decimal", Value: v} }

// Long returns the R33 typed value of a 64-bit integer, sent as a string.
func Long(v int64) TypedValue { return TypedValue{Type: "Long", Value: strconv.FormatInt(v, 10)} }
