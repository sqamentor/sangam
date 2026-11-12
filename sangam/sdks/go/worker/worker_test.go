// SPDX-License-Identifier: Apache-2.0

package worker

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

type recorded struct {
	path, key string
	body      map[string]any
}

// fakeServer answers one fetch with a single item, then empty lists; result calls get the scripted status.
func fakeServer(t *testing.T, resultStatus int, resultBody string) (*httptest.Server, func() []recorded) {
	t.Helper()
	var mu sync.Mutex
	var calls []recorded
	fetched := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		var b map[string]any
		_ = json.Unmarshal(raw, &b)
		mu.Lock()
		calls = append(calls, recorded{strings.TrimPrefix(r.URL.Path, "/api/v1"), r.Header.Get("Idempotency-Key"), b})
		first := !fetched
		if strings.HasSuffix(r.URL.Path, "/fetch-and-lock") {
			fetched = true
		}
		mu.Unlock()
		if strings.HasSuffix(r.URL.Path, "/fetch-and-lock") {
			w.Header().Set("Content-Type", "application/json")
			if first {
				_, _ = io.WriteString(w, `[{"id":"wk-1","topic":"pay","lockVersion":3,"attemptId":"att-1","operationId":"op-1","retries":3}]`)
			} else {
				_, _ = io.WriteString(w, `[]`)
			}
			return
		}
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(resultStatus)
		_, _ = io.WriteString(w, resultBody)
	}))
	return srv, func() []recorded { mu.Lock(); defer mu.Unlock(); return append([]recorded(nil), calls...) }
}

func run(t *testing.T, srv *httptest.Server, h Handler) {
	t.Helper()
	w, err := New(Config{BaseURL: srv.URL + "/api/v1/", WorkerID: "w-1", Topic: "pay", LockDuration: 30 * time.Second,
		AsyncResponseTimeout: 10 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	_ = w.Run(ctx, h)
}

func TestCompleteIsFencedAndKeyedByOperation(t *testing.T) {
	srv, calls := fakeServer(t, http.StatusNoContent, "")
	defer srv.Close()
	run(t, srv, func(ctx context.Context, task *Task) (Result, error) {
		return Complete(VariableMap{"amount": Decimal("1234.50")}, "PAY-1"), nil
	})
	var complete *recorded
	for _, c := range calls() {
		if c.path == "/work/wk-1/complete" {
			c := c
			complete = &c
		}
	}
	if complete == nil {
		t.Fatal("no complete call")
	}
	if complete.key != "op-1:complete:3" {
		t.Fatalf("Idempotency-Key = %q, want op-1:complete:3 (R41, stable operationId)", complete.key)
	}
	if complete.body["lockVersion"].(float64) != 3 || complete.body["attemptId"] != "att-1" || complete.body["workerId"] != "w-1" {
		t.Fatalf("not fenced by (workerId, lockVersion, attemptId): %v", complete.body)
	}
	amount := complete.body["variables"].(map[string]any)["amount"].(map[string]any)
	if amount["type"] != "Decimal" || amount["value"] != "1234.50" {
		t.Fatalf("money must travel as a Decimal string (R33): %v", amount)
	}
}

func TestStaleLeaseIsNotRetried(t *testing.T) {
	srv, calls := fakeServer(t, http.StatusConflict,
		`{"type":"https://sangam.dev/problems/stale-lease","title":"Stale lease","status":409,"code":"STALE_LEASE"}`)
	defer srv.Close()
	run(t, srv, func(ctx context.Context, task *Task) (Result, error) { return Complete(nil, ""), nil })
	n := 0
	for _, c := range calls() {
		if c.path == "/work/wk-1/complete" {
			n++
		}
	}
	if n != 1 {
		t.Fatalf("complete sent %d times; a STALE_LEASE answer must not be retried (VI.3 rule 2)", n)
	}
}

func TestServerErrorIsRetriedWithTheSameKey(t *testing.T) {
	srv, calls := fakeServer(t, http.StatusServiceUnavailable, `{"status":503,"code":"BACKPRESSURE"}`)
	defer srv.Close()
	run(t, srv, func(ctx context.Context, task *Task) (Result, error) { return Complete(nil, ""), nil })
	keys := map[string]bool{}
	n := 0
	for _, c := range calls() {
		if c.path == "/work/wk-1/complete" {
			n++
			keys[c.key] = true
		}
	}
	if n < 2 || len(keys) != 1 {
		t.Fatalf("want >=2 complete attempts with one key, got %d attempts and keys %v", n, keys)
	}
}

func TestHandlerErrorFailsWithDecrementedRetries(t *testing.T) {
	srv, calls := fakeServer(t, http.StatusNoContent, "")
	defer srv.Close()
	run(t, srv, func(ctx context.Context, task *Task) (Result, error) { return Result{}, io.ErrUnexpectedEOF })
	for _, c := range calls() {
		if c.path == "/work/wk-1/fail" {
			if c.body["retries"].(float64) != 2 {
				t.Fatalf("retries = %v, want 2 (item retries 3 - 1)", c.body["retries"])
			}
			return
		}
	}
	t.Fatal("no fail call")
}

func TestValidTraceparent(t *testing.T) {
	ok := "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
	if !ValidTraceparent(ok) {
		t.Fatal("valid traceparent rejected")
	}
	for _, bad := range []string{"", strings.ToUpper(ok), "00-" + strings.Repeat("0", 32) + "-00f067aa0ba902b7-01",
		"00-4bf92f3577b34da6a3ce929d0e0e4736-" + strings.Repeat("0", 16) + "-01", ok + "-x"} {
		if ValidTraceparent(bad) {
			t.Fatalf("invalid traceparent accepted: %q", bad)
		}
	}
}

func TestTaskExposesTheEngineTraceContext(t *testing.T) {
	var w ExternalWork
	tp := "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
	if err := json.Unmarshal([]byte(`{"id":"wk-1","traceContext":{"traceparent":"`+tp+`","tracestate":"congo=t61rcWkgMzE"}}`), &w); err != nil {
		t.Fatal(err)
	}
	task := &Task{Work: w}
	if task.Traceparent() != tp || task.Tracestate() != "congo=t61rcWkgMzE" {
		t.Fatalf("trace context not exposed: %q %q", task.Traceparent(), task.Tracestate())
	}
	if (&Task{}).Traceparent() != "" {
		t.Fatal("absent trace context must give an empty traceparent")
	}
}
