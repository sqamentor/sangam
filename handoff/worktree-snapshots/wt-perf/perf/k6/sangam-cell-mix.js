// SPDX-License-Identifier: Apache-2.0
//
// Sangam load model for the testing phase (SCALE-ARCHITECTURE.md section 11). DO NOT RUN during the code phase.
//
// Reproduces the ADR-D-24 mix per cell: 500 workflow starts/s and 5,000 task/query ops/s (the national 3,000 starts/s
// and 30,000 ops/s divided over 6 loaded cells), with a hospital/department split taken from the platform sizing
// (13,000-19,500 hospitals plus departments, X.0e).
//
//   k6 run -e BASE_URL=http://gateway:8000 -e TENANTS_FILE=./tenants.json -e PROFILE=cell perf/k6/sangam-cell-mix.js
//   PROFILE=cell     one cell at its ADR-D-24 floor (500 starts/s, 5,000 ops/s)         -> per-cell acceptance
//   PROFILE=national 6x cell rates through the gateway over all cells                    -> national acceptance
//   PROFILE=soak     60 % of cell rates for 24 h (R30 soak gate)
//   PROFILE=spike    cell rates x 1.5 for 10 min (back-pressure must answer 503/429, never lose work)
//
// tenants.json: [{"code":"RJ-JPR-H0042","kind":"hospital","token":"<jwt>"}, {"code":"RJ-FIN","kind":"department",...}]
// Tokens are test tokens of the system under test (test IdP realm), never production credentials.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8000';
const PROFILE = __ENV.PROFILE || 'cell';
const CELLS = Number(__ENV.CELLS || (PROFILE === 'national' ? 6 : 1));

const tenants = new SharedArray('tenants', () => JSON.parse(open(__ENV.TENANTS_FILE || './tenants.json')));
const hospitals = tenants.filter((t) => t.kind === 'hospital');
const departments = tenants.filter((t) => t.kind !== 'hospital');

// ---- workload mix (process keys are pack processes; override with -e HOSPITAL_KEYS=a,b,c)
// Hospitals: 75 % of starts (many small, short flows). Departments: 25 % (longer approval chains).
const HOSPITAL_KEYS = (__ENV.HOSPITAL_KEYS ||
  'sangam-sec-health-abdm-consent,sangam-up17-prior-auth,sangam-gem-purchase,sangam-leave').split(',');
const DEPARTMENT_KEYS = (__ENV.DEPARTMENT_KEYS ||
  'sangam-p2-file-movement,sangam-p1-bill-payment,sangam-grievance,sangam-p4-licence').split(',');

// ---- per-cell base rates (ADR-D-24)
const START_RATE = 500;          // starts/s per cell
const OPS_RATE = 5000;           // task + query ops/s per cell
// ops split: 40 % task list (capped count, A-16), 15 % task get, 15 % claim+complete, 15 % worker fetch/complete,
// 10 % instance get / audit timeline, 5 % search
const OPS_SPLIT = { list: 0.40, get: 0.15, complete: 0.15, worker: 0.15, instance: 0.10, search: 0.05 };

function factor() {
  switch (PROFILE) {
    case 'national': return CELLS;
    case 'soak': return 0.6;
    case 'spike': return 1.5;
    default: return 1;
  }
}
function duration() {
  switch (PROFILE) {
    case 'soak': return '24h';
    case 'spike': return '10m';
    default: return '30m';
  }
}
function arrival(rate, maxVUs) {
  return {
    executor: 'ramping-arrival-rate',
    startRate: Math.max(1, Math.round(rate * 0.1)),
    timeUnit: '1s',
    preAllocatedVUs: Math.round(maxVUs / 2),
    maxVUs,
    stages: [
      { target: Math.round(rate), duration: '5m' },   // ramp
      { target: Math.round(rate), duration: duration() }, // steady state (measured window)
      { target: 0, duration: '2m' },
    ],
  };
}

const f = factor();
export const options = {
  discardResponseBodies: false,
  scenarios: {
    hospital_starts: { ...arrival(START_RATE * 0.75 * f, 3000 * f), exec: 'hospitalStart' },
    department_starts: { ...arrival(START_RATE * 0.25 * f, 1500 * f), exec: 'departmentStart' },
    task_list: { ...arrival(OPS_RATE * OPS_SPLIT.list * f, 4000 * f), exec: 'taskList' },
    task_get: { ...arrival(OPS_RATE * OPS_SPLIT.get * f, 1500 * f), exec: 'taskGet' },
    task_complete: { ...arrival(OPS_RATE * OPS_SPLIT.complete * f, 3000 * f), exec: 'taskComplete' },
    worker_cycle: { ...arrival(OPS_RATE * OPS_SPLIT.worker * f, 3000 * f), exec: 'workerCycle' },
    instance_get: { ...arrival(OPS_RATE * OPS_SPLIT.instance * f, 1500 * f), exec: 'instanceGet' },
    search: { ...arrival(OPS_RATE * OPS_SPLIT.search * f, 1000 * f), exec: 'search' },
  },
  // ---- acceptance thresholds (SCALE-ARCHITECTURE.md section 11.2). A failed threshold fails the run.
  thresholds: {
    // throughput: >= 98 % of the offered start rate actually accepted (k6 dropped_iterations stays near 0)
    'sangam_starts_ok': [`rate>=${0.98}`],
    'dropped_iterations': [`count<${Math.round(START_RATE * f * 60)}`],
    // latency (R22 hypotheses, measured with auth + audit + history ON)
    'http_req_duration{op:start}': ['p(95)<150', 'p(99)<300'],
    'http_req_duration{op:list}': ['p(95)<100', 'p(99)<250'],
    'http_req_duration{op:get}': ['p(95)<50', 'p(99)<120'],
    'http_req_duration{op:complete}': ['p(95)<150', 'p(99)<300'],
    'http_req_duration{op:worker}': ['p(95)<120', 'p(99)<250'],
    'http_req_duration{op:instance}': ['p(95)<80', 'p(99)<200'],
    // errors: 5xx other than deliberate back-pressure must stay below 0.1 %; lease conflicts below 1 %
    'sangam_server_errors': ['rate<0.001'],
    'sangam_conflicts': ['rate<0.01'],
    // back-pressure must be the only overload signal: in profile cell/national it must be (almost) absent
    'sangam_backpressure': [PROFILE === 'spike' ? 'rate<0.5' : 'rate<0.005'],
  },
};

const startsOk = new Rate('sangam_starts_ok');
const serverErrors = new Rate('sangam_server_errors');
const conflicts = new Rate('sangam_conflicts');
const backpressure = new Rate('sangam_backpressure');
const startLatency = new Trend('sangam_start_latency', true);
const completed = new Counter('sangam_tasks_completed');

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
function headers(t, extra) {
  return Object.assign({
    'Content-Type': 'application/json',
    Authorization: `Bearer ${t.token}`,
    'X-Sangam-Tenant': t.code,
    'X-Request-Id': `k6-${exec.vu.idInTest}-${exec.scenario.iterationInTest}-${Date.now()}`,
  }, extra || {});
}
function record(res) {
  serverErrors.add(res.status >= 500 && res.status !== 503);
  conflicts.add(res.status === 409);
  backpressure.add(res.status === 503 || res.status === 429);
}
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function start(t, key) {
  const body = JSON.stringify({
    key,
    businessKey: `${t.code}/k6-${uuid()}`,
    variables: { amountMinor: Math.floor(Math.random() * 10000000), category: 'load-test', priority: pick(['low', 'normal', 'high']) },
  });
  const res = http.post(`${BASE}/api/v1/process-instances`, body,
    { headers: headers(t, { 'Idempotency-Key': uuid() }), tags: { op: 'start', tenantKind: t.kind } });
  record(res);
  const ok = check(res, { 'start 2xx': (r) => r.status === 200 || r.status === 201 });
  startsOk.add(ok);
  startLatency.add(res.timings.duration);
}

export function hospitalStart() { start(pick(hospitals), pick(HOSPITAL_KEYS)); }
export function departmentStart() { start(pick(departments), pick(DEPARTMENT_KEYS)); }

export function taskList() {
  const t = pick(tenants);
  const res = http.get(`${BASE}/api/v1/tasks?page=0&size=20`, { headers: headers(t), tags: { op: 'list' } });
  record(res);
  check(res, { 'list 200': (r) => r.status === 200 });
}

function firstTask(t) {
  const res = http.get(`${BASE}/api/v1/tasks?page=0&size=1`, { headers: headers(t), tags: { op: 'list' } });
  record(res);
  if (res.status !== 200) return null;
  const arr = res.json();
  const items = Array.isArray(arr) ? arr : (arr.items || []);
  return items.length ? items[0] : null;
}

export function taskGet() {
  const t = pick(tenants);
  const task = firstTask(t);
  if (!task) return;
  const res = http.get(`${BASE}/api/v1/tasks/${task.id}`, { headers: headers(t), tags: { op: 'get' } });
  record(res);
  check(res, { 'get 200': (r) => r.status === 200 });
}

export function taskComplete() {
  const t = pick(tenants);
  const task = firstTask(t);
  if (!task) return;
  const c = http.post(`${BASE}/api/v1/tasks/${task.id}/claim`, '{}', { headers: headers(t), tags: { op: 'complete' } });
  record(c);
  if (c.status >= 300) return;
  const res = http.post(`${BASE}/api/v1/tasks/${task.id}/complete`,
    JSON.stringify({ variables: { decision: pick(['approve', 'approve', 'approve', 'return']) }, payloadHash: task.payloadHash }),
    { headers: headers(t, { 'Idempotency-Key': uuid() }), tags: { op: 'complete' } });
  record(res);
  if (check(res, { 'complete 2xx': (r) => r.status >= 200 && r.status < 300 })) completed.add(1);
}

export function workerCycle() {
  const t = pick(tenants);
  const workerId = `k6-worker-${exec.vu.idInTest}`;
  const res = http.post(`${BASE}/api/v1/work/fetch-and-lock`, JSON.stringify({
    workerId, maxTasks: 5, asyncResponseTimeoutMs: 0,
    topics: [{ name: __ENV.WORKER_TOPIC || 'sangam.connector', lockDurationMs: 60000 }],
  }), { headers: headers(t), tags: { op: 'worker' } });
  record(res);
  if (res.status !== 200) return;
  for (const w of res.json() || []) {
    const r = http.post(`${BASE}/api/v1/work/${w.id}/complete`,
      JSON.stringify({ workerId, lockVersion: w.lockVersion, attemptId: w.attemptId, variables: { outcome: 'ok' } }),
      { headers: headers(t, { 'Idempotency-Key': w.operationId || uuid() }), tags: { op: 'worker' } });
    record(r);
  }
}

export function instanceGet() {
  const t = pick(tenants);
  const task = firstTask(t);
  if (!task || !task.processInstanceId) return;
  const res = http.get(`${BASE}/api/v1/process-instances/${task.processInstanceId}`, { headers: headers(t), tags: { op: 'instance' } });
  record(res);
}

export function search() {
  const t = pick(tenants);
  const res = http.get(`${BASE}/api/v1/tasks?page=0&size=50&sort=created,desc`, { headers: headers(t), tags: { op: 'list' } });
  record(res);
}
