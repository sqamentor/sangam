// SPDX-License-Identifier: Apache-2.0
use super::*;
use std::collections::VecDeque;

/// Scripted transport: fetch answers from a queue (then []), result calls from scripted statuses (then 204).
#[derive(Default)]
struct Fake {
    fetches: Mutex<VecDeque<String>>,
    results: Mutex<VecDeque<(u16, String)>>,
    calls: Mutex<Vec<(String, Option<String>, Value)>>,
}

impl Transport for Arc<Fake> {
    fn post(&self, path: &str, headers: &[(&str, String)], body: Vec<u8>, _t: Duration) -> Result<HttpResponse, String> {
        let key = headers.iter().find(|(k, _)| *k == "Idempotency-Key").map(|(_, v)| v.clone());
        self.calls.lock().unwrap().push((path.to_string(), key, serde_json::from_slice(&body).unwrap()));
        if path.ends_with("/fetch-and-lock") {
            let b = self.fetches.lock().unwrap().pop_front().unwrap_or_else(|| "[]".into());
            return Ok(HttpResponse { status: 200, body: b.into_bytes() });
        }
        let (s, b) = self.results.lock().unwrap().pop_front().unwrap_or((204, String::new()));
        Ok(HttpResponse { status: s, body: b.into_bytes() })
    }
}

const WORK: &str = r#"[{"id":"wk-1","topic":"pay","lockVersion":3,"attemptId":"att-1","operationId":"op-1","retries":3}]"#;

fn worker(fake: &Arc<Fake>) -> Worker<Arc<Fake>> {
    let mut cfg = Config::new("w-1", "pay");
    cfg.lock_duration = Duration::from_secs(2);
    cfg.async_response_timeout = Duration::from_millis(1);
    Worker::new(cfg, fake.clone())
}

fn run_once(fake: &Arc<Fake>, h: impl Fn(&Task) -> Result<Outcome, String>) {
    let w = worker(fake);
    let n = std::cell::Cell::new(0);
    w.run(h, || { n.set(n.get() + 1); n.get() > 1 });
}

fn calls_to(fake: &Arc<Fake>, suffix: &str) -> Vec<(String, Option<String>, Value)> {
    fake.calls.lock().unwrap().iter().filter(|c| c.0.ends_with(suffix)).cloned().collect()
}

#[test]
fn complete_is_fenced_and_keyed_by_operation() {
    let fake = Arc::new(Fake::default());
    fake.fetches.lock().unwrap().push_back(WORK.into());
    run_once(&fake, |_| {
        let mut v = VariableMap::new();
        v.insert("amount".into(), decimal("1234.50"));
        Ok(Outcome::Complete { variables: v, reference: Some("PAY-1".into()) })
    });
    let c = calls_to(&fake, "/work/wk-1/complete");
    assert_eq!(c.len(), 1);
    assert_eq!(c[0].1.as_deref(), Some("op-1:complete:3"), "R41 key from the stable operationId");
    assert_eq!(c[0].2["lockVersion"], 3);
    assert_eq!(c[0].2["attemptId"], "att-1");
    assert_eq!(c[0].2["workerId"], "w-1");
    assert_eq!(c[0].2["variables"]["amount"]["value"], "1234.50");
    assert_eq!(c[0].2["outcome"]["reference"], "PAY-1");
}

#[test]
fn stale_lease_is_not_retried() {
    let fake = Arc::new(Fake::default());
    fake.fetches.lock().unwrap().push_back(WORK.into());
    fake.results.lock().unwrap().push_back((409, r#"{"status":409,"code":"STALE_LEASE","workId":"wk-1"}"#.into()));
    run_once(&fake, |_| Ok(Outcome::Complete { variables: VariableMap::new(), reference: None }));
    assert_eq!(calls_to(&fake, "/complete").len(), 1, "VI.3 rule 2: a stale answer is final");
}

#[test]
fn server_error_is_retried_with_the_same_key() {
    let fake = Arc::new(Fake::default());
    fake.fetches.lock().unwrap().push_back(WORK.into());
    fake.results.lock().unwrap().push_back((503, r#"{"status":503,"code":"BACKPRESSURE"}"#.into()));
    run_once(&fake, |_| Ok(Outcome::Complete { variables: VariableMap::new(), reference: None }));
    let c = calls_to(&fake, "/complete");
    assert_eq!(c.len(), 2);
    assert_eq!(c[0].1, c[1].1);
}

#[test]
fn handler_error_fails_with_decremented_retries_or_outcome_unknown_when_irreversible() {
    let fake = Arc::new(Fake::default());
    fake.fetches.lock().unwrap().push_back(WORK.into());
    run_once(&fake, |_| Err("boom".into()));
    assert_eq!(calls_to(&fake, "/fail")[0].2["retries"], 2);

    let fake = Arc::new(Fake::default());
    fake.fetches.lock().unwrap().push_back(WORK.into());
    let mut cfg = Config::new("w-1", "pay");
    cfg.irreversible = true;
    cfg.async_response_timeout = Duration::from_millis(1);
    let w = Worker::new(cfg, fake.clone());
    let n = std::cell::Cell::new(0);
    w.run(|_| Err("timeout after send".into()), || { n.set(n.get() + 1); n.get() > 1 });
    assert!(calls_to(&fake, "/fail").is_empty(), "VI.3 rule 5: no blind retry of an irreversible effect");
    assert_eq!(calls_to(&fake, "/outcome-unknown").len(), 1);
}

#[test]
fn wire_names_follow_the_contract() {
    let w: ExternalWork = serde_json::from_str(r#"{"id":"a","lockVersion":1,"operation":{"state":"INTENT","mayDispatch":true}}"#).unwrap();
    assert_eq!(w.lock_version, Some(1));
    assert_eq!(w.operation.unwrap().may_dispatch, Some(true));
    let p: Problem = serde_json::from_str(r#"{"type":"t","code":"STALE_LEASE","workId":"wk-1"}"#).unwrap();
    assert_eq!(p.extensions["workId"], "wk-1");
    let b = serde_json::to_value(WorkUnlockLike { worker_id: "w".into(), lock_version: 2 }).unwrap();
    assert_eq!(b, serde_json::json!({"workerId": "w", "lockVersion": 2}));
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkUnlockLike {
    worker_id: String,
    lock_version: i64,
}

#[test]
fn traceparent_validation() {
    let ok = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    assert!(valid_traceparent(ok));
    assert!(!valid_traceparent(&ok.to_uppercase()));
    assert!(!valid_traceparent("00-00000000000000000000000000000000-00f067aa0ba902b7-01"));
    assert!(!valid_traceparent(""));
    assert_eq!(seg("a b/c"), "a%20b%2Fc");
}

#[test]
fn task_exposes_the_engine_trace_context() {
    let tp = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    let w: ExternalWork = serde_json::from_value(serde_json::json!(
        {"id": "wk-1", "traceContext": {"traceparent": tp, "tracestate": "congo=t61rcWkgMzE"}})).unwrap();
    let t = Task { work: w, cancelled: Arc::new(AtomicBool::new(false)) };
    assert_eq!(t.traceparent(), Some(tp));
    assert_eq!(t.tracestate(), Some("congo=t61rcWkgMzE"));
    let none = Task { work: ExternalWork::default(), cancelled: Arc::new(AtomicBool::new(false)) };
    assert_eq!(none.traceparent(), None);
}
