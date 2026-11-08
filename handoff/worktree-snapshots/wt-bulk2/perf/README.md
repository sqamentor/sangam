# Sangam load model (testing phase only)

Scripts only; nothing here runs during the code phase. Thresholds and the claims they prove are in
`../SCALE-ARCHITECTURE.md` section 11 (T-SCALE-1 … T-SCALE-11).

* `k6/sangam-cell-mix.js`: ADR-D-24 mix per cell (500 starts/s, 5,000 task/query ops/s; 75 % hospital, 25 % department
  starts) with profiles `cell`, `national`, `soak`, `spike`. Inputs: `BASE_URL` (gateway), `TENANTS_FILE`
  (`[{code, kind: hospital|department, token}]` with test-realm tokens), optional `HOSPITAL_KEYS`, `DEPARTMENT_KEYS`,
  `WORKER_TOPIC`, `CELLS`.
* Run on dedicated load generators (one k6 instance handles ~2,000–4,000 req/s; the national profile needs k6
  distributed / several generators). Wrap any local run in the shared heavy-build lock.
* k6 (AGPL-3.0) is an external test tool: never shipped, never linked.
* Publish raw results and limits with every run (R22), including failed thresholds.
