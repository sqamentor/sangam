// SPDX-License-Identifier: Apache-2.0
/**
 * AC-08 conformance worker (SANGAM.md IX.8 AC-08, M-023) for the TypeScript SDK.
 *
 * The AC-08 orchestrator runs the same scenario with the worker of every Tier-1 SDK against one server and compares
 * the results. CLI contract (identical in every SDK):
 *   env SANGAM_BASE_URL, SANGAM_TOKEN (optional), AC08_TOPIC, AC08_WORKER_ID,
 *       AC08_SCENARIO = complete | bpmn-error | fail-retry | outcome-unknown | stale-lease,
 *       AC08_TASKS (default 1), AC08_TIMEOUT_S (default 60), AC08_LOCK_MS (lock duration, default 30000)
 *   stdout: one JSON line per task {"lang","workId","operationId","lockVersion","attemptId","scenario","result","detail"}
 *   exit 0 when AC08_TASKS results were printed, 1 on timeout.
 *
 * Per task the variables amount (Decimal), count (Long), when (Date), day (LocalDate) and note (String or null) are
 * read and, in the complete scenarios, written back unchanged as out_amount, out_count, out_when, out_day, out_note.
 * stale-lease uses the work API directly: the runtime's heartbeat would keep the lease alive, so the worker fetches,
 * lets the lease expire (AC08_LOCK_MS + 2 s) and then completes, expecting 409 STALE_LEASE.
 *
 * Run: `tsc -p examples/tsconfig.json && node build-examples/examples/ac08-worker.js`
 */
import {
  BpmnError,
  fromTypedValue,
  OutcomeUnknownError,
  SangamClient,
  SangamWorker,
  StaleLeaseError,
  TaskFailedError,
  type Logger,
  type TaskContext,
  type Variables,
} from '../src/index.js';

const LANG = 'typescript';
const NAMES = ['amount', 'count', 'when', 'day', 'note'] as const;

function env(name: string, dflt?: string): string {
  const v = process.env[name] ?? dflt;
  if (v === undefined) throw new Error(`${name} is required`);
  return v;
}

/** undefined when the R33 inputs decode to the expected types, else what is wrong. */
function checkInputs(raw: Variables): string | undefined {
  const v = Object.fromEntries(NAMES.map((n) => [n, fromTypedValue(raw[n])]));
  if (typeof v.amount !== 'string') return `amount is ${typeof v.amount}, not Decimal`;
  if (typeof v.count !== 'bigint') return `count is ${typeof v.count}, not Long`;
  if (!(v.when instanceof Date)) return 'when is not a Date';
  if (typeof v.day !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(v.day)) return 'day is not a LocalDate';
  if (v.note !== null && v.note !== undefined && typeof v.note !== 'string') return `note is ${typeof v.note}, not String`;
  return undefined;
}

function outputs(raw: Variables): Variables {
  return Object.fromEntries(NAMES.map((n) => [`out_${n}`, raw[n] ?? null]));
}

async function main(): Promise<number> {
  const baseUrl = env('SANGAM_BASE_URL');
  const token = process.env.SANGAM_TOKEN;
  const topic = env('AC08_TOPIC');
  const workerId = env('AC08_WORKER_ID', `ac08-${LANG}`);
  const scenario = env('AC08_SCENARIO', 'complete');
  const wanted = Number.parseInt(env('AC08_TASKS', '1'), 10);
  const timeoutMs = Number.parseFloat(env('AC08_TIMEOUT_S', '60')) * 1000;
  const lockMs = Number.parseInt(env('AC08_LOCK_MS', '30000'), 10);
  let count = 0;
  let resolveDone!: () => void;
  const done = new Promise<void>((r) => { resolveDone = r; });
  const details = new Map<string, string>();

  function emit(ctx: { workId: string; operationId?: string; lockVersion?: number; attemptId?: string }, result: string, detail?: string): void {
    if (count >= wanted) return;
    process.stdout.write(`${JSON.stringify({
      lang: LANG, workId: ctx.workId, operationId: ctx.operationId ?? null, lockVersion: ctx.lockVersion ?? null,
      attemptId: ctx.attemptId ?? null, scenario, result, detail: detail ?? null,
    })}\n`);
    if (++count >= wanted) resolveDone();
  }

  if (scenario === 'stale-lease') {
    const client = new SangamClient({ baseUrl, token, retryPolicy: { maxAttempts: 1, initialDelayMs: 0, maxDelayMs: 0, multiplier: 1 } });
    const deadline = Date.now() + timeoutMs;
    while (count < wanted && Date.now() < deadline) {
      const items = await client.work.fetchAndLock({ workerId, topics: [{ name: topic, lockDurationMs: lockMs }], maxTasks: 1, asyncResponseTimeoutMs: 5_000 });
      for (const w of items) {
        const ref = { workId: w.id!, operationId: w.operationId, lockVersion: w.lockVersion, attemptId: w.attemptId };
        await new Promise((r) => setTimeout(r, lockMs + 2_000));
        try {
          await client.work.complete(w.id!, { workerId, lockVersion: w.lockVersion!, attemptId: w.attemptId, variables: outputs(w.variables ?? {}) });
          emit(ref, 'error', 'complete after lease expiry was accepted');
        } catch (e) {
          if (e instanceof StaleLeaseError) emit(ref, 'stale-lease', String(e.problem.reason ?? ''));
          else throw e;
        }
      }
    }
    return count >= wanted ? 0 : 1;
  }

  const failedOnce = new Set<string>();
  const pending = new Map<string, TaskContext>();
  const logger: Logger = {
    log(_level, event, _message, fields, error) {
      const wid = fields?.workId;
      const ctx = typeof wid === 'string' ? pending.get(wid) : undefined;
      if (!ctx) return;
      const ref = { workId: ctx.workId, operationId: ctx.operationId, lockVersion: ctx.lockVersion, attemptId: ctx.attemptId };
      if (event === 'work.complete') {
        const problem = details.get(ctx.workId);
        if (problem) emit(ref, 'error', problem);
        else emit(ref, scenario === 'fail-retry' ? 'failed-then-completed' : 'completed');
      } else if (event === 'work.bpmn-error') {
        emit(ref, 'bpmn-error');
      } else if (event === 'work.outcome-unknown') {
        emit(ref, 'outcome-unknown');
      } else if (event === 'work.report.failed' || event === 'work.report.gave-up' || event === 'work.abandoned') {
        emit(ref, 'error', `${event}: ${String(error)}`);
      }
    },
  };
  const client = new SangamClient({ baseUrl, token, logger });
  const worker = new SangamWorker(client, { workerId, maxConcurrency: 1, maxTasksPerFetch: 1, logger });
  worker.subscribe(topic, (ctx) => {
    pending.set(ctx.workId, ctx);
    const problem = checkInputs(ctx.variables);
    if (problem) details.set(ctx.workId, problem);
    if (scenario === 'bpmn-error') throw new BpmnError('AC08_ERROR', 'AC-08 business error');
    if (scenario === 'outcome-unknown') throw new OutcomeUnknownError('AC-08 outcome unknown');
    if (scenario === 'fail-retry' && !failedOnce.has(ctx.workId)) {
      failedOnce.add(ctx.workId);
      throw new TaskFailedError('AC-08 transient failure', { retries: 1, retryTimeoutMs: 0 });
    }
    return outputs(ctx.variables);
  }, { lockDurationMs: lockMs, retries: 1 });
  worker.start();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timedOut = new Promise<boolean>((r) => { timer = setTimeout(() => r(true), timeoutMs); });
  const late = await Promise.race([done.then(() => false), timedOut]);
  if (timer) clearTimeout(timer);
  await worker.stop(10_000);
  return late ? 1 : 0;
}

main().then((code) => { process.exitCode = code; }, (e: unknown) => {
  process.stderr.write(`${String(e)}\n`);
  process.exitCode = 1;
});
