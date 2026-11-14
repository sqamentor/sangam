# W3-I (worker B50) SDK spec for M-025 / M-026 / M-027 (Sangam 0.1.0, Wave 3)

Tree (the ONLY tree): `D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524`
SDK root: `<tree>\sangam\sdks\<lang>`. The Java SDK is the finished REFERENCE implementation; mirror its semantics exactly
in your language, idiomatically (naming, async style, error types of that SDK).

Reference files (read them first):
- `sangam/sdks/java/src/main/java/org/sangam/sdk/worker/DurableSteps.java` (the step algorithm)
- `.../worker/Step.java`, `StepCall.java`, `StepLookup.java`, `StepOptions.java`, `StepNoEffectException.java`
- `.../worker/TaskContext.java` (two new `step(...)` overloads near the end)
- `.../worker/Saga.java`, `SagaRecord.java`, `SagaAction.java`, `SagaCompensation.java`, `CompensationContext.java`
- `.../testing/EngineSteps.java` + the `steps(...)` method and dispatch line in `.../testing/SangamTestEngine.java`
- Contract: `sangam/sangam-rest/api/routes/work_steps.py` (schemas WorkStep / WorkStepRequest, routes getWorkStep,
  recordWorkStep, listWorkSteps). Your SDK's GENERATED surface already has these three calls and the two models
  (generated dirs: java `generated/`, ts `src/generated/`, python `src/sangam_sdk/generated/`, dotnet
  `src/Sangam.Sdk/Generated/`, php `src/Generated/`). NEVER edit generated files; call them.

## M-025 `context.step(name, fn, options?)` semantics (identical in every SDK)
1. Validate name: 1-128 printable ASCII (0x20-0x7E), else a usage error (IllegalArgument/TypeError/ValueError/ArgumentException).
2. GET `/work/{id}/steps/{name}?workerId&lockVersion&attemptId` (fenced with the context's current lease).
   404 => never recorded (null). 409 STALE_LEASE or 410 => mark the context's lease lost and raise that SDK's
   "task cancelled / lease lost" error (Java: set ctx.leaseLost=true, throw TaskCancelledException with the cause).
3. Recorded SUCCEEDED => do NOT call fn; return the recorded `result` decoded (R33 typed values unwrapped the way that
   SDK already unwraps variables). Log event `step.replayed`.
4. Recorded INTENT (crash window of an earlier attempt):
   - if options.lookup given: call it with the Step handle. A value => PUT SUCCEEDED (result=value,
     providerRef=step.providerRef) and return it (log `step.looked-up`). Empty/None/null/undefined => no effect,
     continue to 5. An exception propagates (step stays INTENT).
   - else if options.idempotent: continue to 5 (re-run with the same idempotency key).
   - else (class c, the default): raise that SDK's OutcomeUnknown error ("step X of operation Y was started by an
     earlier attempt and its outcome is unknown (R37)", evidence/variables {step, idempotencyKey}) so the worker
     reports outcome-unknown.
   Recorded FAILED => continue to 5 (a failure without effect may run again).
5. PUT INTENT (result null). Run fn(step).
   - fn returns value => PUT SUCCEEDED with result=value, providerRef=step.providerRef; return value.
   - fn raises the SDK's StepNoEffect error => PUT FAILED (error=message, providerRef) and re-raise it.
   - fn raises anything else => leave INTENT (uncertain), re-raise unchanged.
   Every PUT body: {workerId, lockVersion (current), attemptId, state, result, providerRef, error}. STALE/410 on PUT => lease lost as in 2.
Step handle: name, operationId (ctx operationId), idempotencyKey = `operationId + ":" + name`, previousState
(null | "INTENT" | "FAILED"), settable providerRef.
Options: default = class (c); `idempotent` = class (a); `lookup(fn)` = class (b); both allowed (lookup asked first).
Doc comment (MUST): "NOT exactly-once for remote APIs" + the crash-window explanation (R37).

## Test engine (M-021 fake) must serve the journal
Each SDK's in-process test engine gets the step routes with the SAME transitions as the server:
INTENT allowed on new / INTENT / FAILED step; SUCCEEDED/FAILED require INTENT (409 STEP_INTENT_REQUIRED);
SUCCEEDED final (409 STEP_ALREADY_RECORDED) unless an identical repeat (same result + providerRef); an identical repeat of
INTENT/FAILED by the same attempt is a no-op; keyed by (operationId, name); fenced with the engine's existing fence
function (order NO_LEASE, STALE_VERSION, NOT_OWNER, EXPIRED, ATTEMPT_MISMATCH); missing attemptId or bad name 400
VALIDATION_FAILED; unknown work 404; work whose instance was deleted 410 GONE_INSTANCE_DELETED; GET list = steps of the
work's operation in recording order (404 when the work is unknown and has no rows). Put it in a NEW file when the
engine file would pass ~760 lines.

## M-026 Saga helper (same shape as Java Saga)
`Saga.step(name)` (name matches `[A-Za-z0-9._-]{1,64}`) `.action(topic, handler)` `.compensation(topic, handler)`
options lockDuration / retries / irreversible / variables -> build -> two subscriptions; `registerOn(worker)`.
- Forward handler result variables + variable `sangamSaga_<name with . and - replaced by _>` =
  R33 Json typed value of {step, operationId (ctx.operationId), providerRef (ctx outcome reference), workId}.
- Compensation handler gets a CompensationContext: task (the TaskContext), forward record (or null when the variable
  is absent), forwardOperationId, forwardProviderRef, idempotencyKey = `forward.operationId + ":compensate:" + name`
  (own operationId when no record).
- action topic == compensation topic => error at build. When `variables` is restricted, add the record variable to the
  compensation's fetch list.

## M-027 framework adapter (a SEPARATE package/project next to the SDK; the core SDK gets NO new dependency)
Start/stop the SDK worker with the framework's lifecycle, register handlers/sagas from configuration or DI, graceful
shutdown using the SDK worker's existing close/stop. Compile it ONLY against what is already installed locally.
If the framework is not installed: still write nothing that cannot be checked unless you are >=97% sure it is right;
otherwise mark it BLOCKED and give an approval line `group:artifact:version`, licence, approx size, why.

## House rules (binding)
- SPDX header `Apache-2.0` on every new file (same comment style as neighbours). No file over 800 lines.
- Write unit tests for every change in that SDK's existing test style; DO NOT RUN tests (no npm test, pytest, dotnet
  test). Gates are compile/typecheck ONLY with installed toolchains. NO downloads, no npm/pip/nuget/composer install.
- Never commit, push, stash, switch branches or reset. Edit only inside `sangam/sdks/<your lang>` (+ your new adapter
  dir under `sangam/sdks/`). Do not touch `sangam/sdks/codegen`, generated dirs, other SDKs, or files outside sdks.
- No consumer or programme ids (IFMS, PHED, DOIT) in files. Cite requirements as M-025 / R37 / R36 like the Java files.
- Update that SDK's README.md with a short "Durable steps", "Sagas" and "Framework adapter" section.
- Report at the end: files created/changed (absolute paths) each with SHA-256 (`python -c "import hashlib,sys;..."`),
  tests written, exact gate commands + exit codes, anything BLOCKED with approval lines, any question below 97%.
