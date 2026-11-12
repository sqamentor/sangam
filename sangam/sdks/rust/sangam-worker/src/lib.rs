// SPDX-License-Identifier: Apache-2.0
//! Minimal Tier-2 worker loop for Rust (ADR-D-4, R18, M-022): fetch-and-lock, heartbeats at lease/3, fenced reports
//! with an `Idempotency-Key` derived from the stable `operationId`, and `STALE_LEASE` handling (VI.3).
//!
//! The crate has no HTTP client of its own: plug the one you already use (the generated `sangam-client` crate's
//! reqwest client, ureq, hyper, ...) through [`Transport`]. Wire types follow `sangam-rest/api/sangam-api.yaml`
//! field for field.

use serde::Serialize;
use serde_json::{Map, Value};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

pub mod model;
pub use model::*;

/// One HTTP answer: status and body bytes.
pub struct HttpResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

/// The HTTP seam. `post` sends `POST <base>/api/v1<path>` with a JSON body and the given headers (Content-Type and
/// Accept are added by the worker). Return `Err` only for transport failures (no answer); any HTTP status is `Ok`.
pub trait Transport: Send + Sync {
    fn post(&self, path: &str, headers: &[(&str, String)], body: Vec<u8>, timeout: Duration)
        -> Result<HttpResponse, String>;
}

/// Why a call failed.
#[derive(Debug, Clone, PartialEq)]
pub enum CallError {
    /// 409 STALE_LEASE: the lease moved on, the server changed nothing (VI.3 rule 2). Never retried.
    StaleLease(String),
    /// 410: the instance of the work was deleted; the late result was recorded. Never retried.
    Gone(String),
    /// Any other HTTP error, with its RFC 9457 problem.
    Api(u16, Problem),
    /// No answer (connection, timeout).
    Transport(String),
    /// The answer was not the expected JSON.
    Decode(String),
}

impl CallError {
    fn retryable(&self) -> bool {
        match self {
            CallError::Transport(_) => true,
            CallError::Api(s, _) => *s >= 500 || *s == 429,
            _ => false,
        }
    }
}

/// Outcome of a handler.
#[derive(Debug, Clone)]
pub enum Outcome {
    /// Complete with output variables and an optional downstream reference (stored as providerRef).
    Complete { variables: VariableMap, reference: Option<String> },
    /// Throw a BPMN error routed by the model.
    BpmnError { code: String, message: Option<String>, variables: VariableMap },
    /// The downstream effect may or may not have happened: opens a reconciliation item (VI.3 rule 4).
    OutcomeUnknown { detail: String },
    /// Report a failure; the configured retries decide between a retry and an incident.
    Fail { message: String },
}

/// Worker configuration.
pub struct Config {
    pub worker_id: String,
    pub topic: String,
    /// Lease length (default 5 min, server maximum 24 h). Heartbeats run at a third of it.
    pub lock_duration: Duration,
    pub max_tasks: i32,
    pub async_response_timeout: Duration,
    /// Retries left after a handler failure (None: the item's retries - 1, at least 0).
    pub retries: Option<i32>,
    pub retry_timeout: Duration,
    /// A topic with a non-idempotent effect: a failure is reported as outcome-unknown, never retried (VI.3 rule 5).
    pub irreversible: bool,
    /// Extra headers on every call, e.g. ("Authorization", "Bearer ..."), or a W3C traceparent (R13).
    pub headers: Vec<(&'static str, String)>,
}

impl Config {
    pub fn new(worker_id: impl Into<String>, topic: impl Into<String>) -> Self {
        Config {
            worker_id: worker_id.into(),
            topic: topic.into(),
            lock_duration: Duration::from_secs(300),
            max_tasks: 1,
            async_response_timeout: Duration::from_secs(20),
            retries: None,
            retry_timeout: Duration::from_secs(10),
            irreversible: false,
            headers: Vec::new(),
        }
    }
}

/// A locked work item handed to the handler, after the LOCKED confirmation with its lockVersion (A-1).
pub struct Task {
    pub work: ExternalWork,
    cancelled: Arc<AtomicBool>,
}

impl Task {
    /// True after the server requested a cancel (heartbeat `cancelRequested`) or the lease was lost.
    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::SeqCst)
    }

    /// W3C traceparent the engine stored for this task (ExternalWork.traceContext, M-062), if any.
    pub fn traceparent(&self) -> Option<&str> {
        self.work.trace_context.as_ref().map(|t| t.traceparent.as_str())
    }

    /// W3C tracestate of the task's trace context, if any.
    pub fn tracestate(&self) -> Option<&str> {
        self.work.trace_context.as_ref().and_then(|t| t.tracestate.as_deref())
    }
}

/// The worker loop over a [`Transport`].
pub struct Worker<T: Transport> {
    cfg: Config,
    transport: Arc<T>,
}

impl<T: Transport + 'static> Worker<T> {
    pub fn new(cfg: Config, transport: T) -> Self {
        Worker { cfg, transport: Arc::new(transport) }
    }

    /// Fetches and processes work until `stop` returns true (checked between fetches).
    pub fn run<H, S>(&self, handler: H, stop: S)
    where
        H: Fn(&Task) -> Result<Outcome, String>,
        S: Fn() -> bool,
    {
        let mut backoff = Duration::from_millis(200);
        while !stop() {
            match self.fetch() {
                Ok(items) => {
                    backoff = Duration::from_millis(200);
                    for w in items {
                        self.process(w, &handler);
                    }
                }
                Err(_) => {
                    thread::sleep(backoff);
                    backoff = (backoff * 2).min(Duration::from_secs(10));
                }
            }
        }
    }

    /// One long-poll fetch-and-lock.
    pub fn fetch(&self) -> Result<Vec<ExternalWork>, CallError> {
        let body = FetchAndLockRequest {
            worker_id: self.cfg.worker_id.clone(),
            topics: vec![TopicRequest {
                name: self.cfg.topic.clone(),
                lock_duration_ms: Some(self.cfg.lock_duration.as_millis() as i64),
                ..TopicRequest::default()
            }],
            max_tasks: Some(self.cfg.max_tasks),
            async_response_timeout_ms: Some(self.cfg.async_response_timeout.as_millis() as i64),
            use_priority: None,
        };
        let timeout = self.cfg.async_response_timeout + Duration::from_secs(15);
        let v = call(&*self.transport, &self.cfg.headers, "/work/fetch-and-lock", None, &body, timeout)?;
        serde_json::from_value(v.unwrap_or(Value::Array(vec![]))).map_err(|e| CallError::Decode(e.to_string()))
    }

    fn process<H>(&self, work: ExternalWork, handler: &H)
    where
        H: Fn(&Task) -> Result<Outcome, String>,
    {
        let (id, lv0, attempt) = match (work.id.clone(), work.lock_version, work.attempt_id.clone()) {
            (Some(i), Some(l), Some(a)) => (i, l, a),
            // without the fencing token the item cannot be reported; its lease expires on its own (A-1)
            _ => return,
        };
        let op = work.operation_id.clone().unwrap_or_default();
        let lock_version = Arc::new(AtomicI64::new(lv0));
        let cancelled = Arc::new(AtomicBool::new(false));
        let lost = Arc::new(AtomicBool::new(false));
        let done = Arc::new(Mutex::new(false));
        let hb = {
            let (t, headers, worker_id, lease) =
                (self.transport.clone(), self.cfg.headers.clone(), self.cfg.worker_id.clone(), self.cfg.lock_duration);
            let (lock_version, cancelled, lost, done) = (lock_version.clone(), cancelled.clone(), lost.clone(), done.clone());
            let (id, attempt, op) = (id.clone(), attempt.clone(), op.clone());
            thread::spawn(move || {
                let interval = (lease / 3).max(Duration::from_secs(1));
                let mut seq = 0u64;
                let mut next = Instant::now() + interval;
                loop {
                    thread::sleep(Duration::from_millis(50));
                    if *done.lock().unwrap() {
                        return;
                    }
                    if Instant::now() < next {
                        continue;
                    }
                    next = Instant::now() + interval;
                    seq += 1;
                    let lv = lock_version.load(Ordering::SeqCst);
                    let key = format!("{op}:extend-lock:{lv}:{seq}");
                    let body = ExtendLockRequest {
                        worker_id: worker_id.clone(),
                        lock_version: lv,
                        attempt_id: Some(attempt.clone()),
                        new_duration_ms: lease.as_millis() as i64,
                    };
                    match call(&*t, &headers, &format!("/work/{}/extend-lock", seg(&id)), Some(&key), &body, lease) {
                        Ok(Some(v)) => {
                            if let Ok(st) = serde_json::from_value::<LockState>(v) {
                                if let Some(l) = st.lock_version {
                                    lock_version.store(l, Ordering::SeqCst);
                                }
                                if st.cancel_requested == Some(true) {
                                    cancelled.store(true, Ordering::SeqCst);
                                }
                            }
                        }
                        Err(CallError::StaleLease(_)) | Err(CallError::Gone(_)) => {
                            lost.store(true, Ordering::SeqCst);
                            cancelled.store(true, Ordering::SeqCst);
                            return;
                        }
                        _ => {}
                    }
                }
            })
        };
        let task = Task { work: work.clone(), cancelled: cancelled.clone() };
        let result = handler(&task);
        *done.lock().unwrap() = true;
        let _ = hb.join();
        if lost.load(Ordering::SeqCst) {
            return; // the server already moved on; nothing may be reported
        }
        let lv = lock_version.load(Ordering::SeqCst);
        let w = self.cfg.worker_id.clone();
        if cancelled.load(Ordering::SeqCst) {
            let body = CancelAckRequest { worker_id: w, lock_version: lv, attempt_id: Some(attempt) };
            let _ = self.report(&id, &op, "cancel-ack", lv, &body);
            return;
        }
        let outcome = match result {
            Ok(o) => o,
            Err(e) if self.cfg.irreversible => Outcome::OutcomeUnknown { detail: e },
            Err(e) => Outcome::Fail { message: e },
        };
        match outcome {
            Outcome::Complete { variables, reference } => {
                let body = WorkCompleteRequest {
                    worker_id: w,
                    lock_version: lv,
                    attempt_id: Some(attempt),
                    variables: Some(variables),
                    local_variables: None,
                    outcome: reference.map(|r| WorkOutcome { reference: Some(r), details: None }),
                };
                let _ = self.report(&id, &op, "complete", lv, &body);
            }
            Outcome::BpmnError { code, message, variables } => {
                let body = WorkBpmnErrorRequest {
                    worker_id: w,
                    lock_version: lv,
                    attempt_id: Some(attempt),
                    error_code: code,
                    error_message: message,
                    variables: Some(variables),
                };
                let _ = self.report(&id, &op, "bpmn-error", lv, &body);
            }
            Outcome::OutcomeUnknown { detail } => {
                let body = OutcomeUnknownRequest {
                    worker_id: w,
                    lock_version: lv,
                    attempt_id: Some(attempt),
                    detail: Some(detail),
                    evidence: None,
                };
                let _ = self.report(&id, &op, "outcome-unknown", lv, &body);
            }
            Outcome::Fail { message } => {
                let retries = self.cfg.retries.unwrap_or_else(|| (work.retries.unwrap_or(1) - 1).max(0));
                let message: String = message.chars().take(1000).collect();
                let body = WorkFailRequest {
                    worker_id: w,
                    lock_version: lv,
                    attempt_id: Some(attempt),
                    error_message: Some(message),
                    error_details: None,
                    retries: Some(retries),
                    retry_timeout_ms: Some(self.cfg.retry_timeout.as_millis() as i64),
                };
                let _ = self.report(&id, &op, "fail", lv, &body);
            }
        }
    }

    /// Sends a fenced result with the Idempotency-Key `operationId:action:lockVersion`, so a repeat after a lost
    /// answer is the same request (R41); retries transport errors, 429 and 5xx until the lease would have expired.
    fn report<B: Serialize>(&self, id: &str, op: &str, action: &str, lv: i64, body: &B) -> Result<(), CallError> {
        let key = format!("{op}:{action}:{lv}");
        let deadline = Instant::now() + self.cfg.lock_duration;
        let mut delay = Duration::from_millis(200);
        loop {
            let path = format!("/work/{}/{}", seg(id), action);
            match call(&*self.transport, &self.cfg.headers, &path, Some(&key), body, Duration::from_secs(30)) {
                Ok(_) => return Ok(()),
                Err(e) if e.retryable() && Instant::now() < deadline => {
                    thread::sleep(delay);
                    delay = (delay * 2).min(Duration::from_secs(5));
                }
                Err(e) => return Err(e),
            }
        }
    }
}

fn call<B: Serialize>(
    t: &dyn Transport,
    extra: &[(&'static str, String)],
    path: &str,
    key: Option<&str>,
    body: &B,
    timeout: Duration,
) -> Result<Option<Value>, CallError> {
    let data = serde_json::to_vec(body).map_err(|e| CallError::Decode(e.to_string()))?;
    let mut headers: Vec<(&str, String)> = vec![
        ("Content-Type", "application/json".to_string()),
        ("Accept", "application/json, application/problem+json".to_string()),
        ("User-Agent", "sangam-sdk-rust-worker/0.1.0".to_string()),
    ];
    if let Some(k) = key {
        headers.push(("Idempotency-Key", k.to_string()));
    }
    for (k, v) in extra {
        headers.push((k, v.clone()));
    }
    let r = t.post(path, &headers, data, timeout).map_err(CallError::Transport)?;
    if (200..300).contains(&r.status) {
        if r.body.is_empty() {
            return Ok(None);
        }
        return serde_json::from_slice(&r.body).map(Some).map_err(|e| CallError::Decode(e.to_string()));
    }
    let p: Problem = serde_json::from_slice(&r.body).unwrap_or_default();
    let detail = p.detail.clone().unwrap_or_default();
    Err(match (r.status, p.code.as_deref()) {
        (409, Some("STALE_LEASE")) => CallError::StaleLease(detail),
        (410, _) => CallError::Gone(detail),
        _ => CallError::Api(r.status, p),
    })
}

/// Percent-encodes one path segment (RFC 3986 unreserved characters stay).
pub fn seg(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// R33 typed value of an exact decimal given as a string (money is never a binary float).
pub fn decimal(v: &str) -> Value {
    let mut m = Map::new();
    m.insert("type".into(), Value::String("Decimal".into()));
    m.insert("value".into(), Value::String(v.into()));
    Value::Object(m)
}

/// A well-formed version-00 W3C traceparent with non-zero trace-id and parent-id (R13).
pub fn valid_traceparent(v: &str) -> bool {
    let b = v.as_bytes();
    if b.len() != 55 || &v[..3] != "00-" || b[35] != b'-' || b[52] != b'-' {
        return false;
    }
    let hex = |s: &str| s.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c));
    hex(&v[3..35]) && hex(&v[36..52]) && hex(&v[53..]) && v[3..35].bytes().any(|c| c != b'0')
        && v[36..52].bytes().any(|c| c != b'0')
}

#[cfg(test)]
mod tests;
