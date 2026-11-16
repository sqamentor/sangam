// SPDX-License-Identifier: Apache-2.0
// Durable steps (M-025, R37 / E02): the algorithm behind `TaskContext.step`, on the step journal routes
// `GET/PUT /work/{id}/step?stepName=` of the generated surface, fenced by the context's lease.
import { GoneError, SangamApiError, StaleLeaseError } from './errors.js';
import type { WorkStep, WorkStepRequest } from './generated/models.js';
import { fromTypedValue } from './typed.js';
import { OutcomeUnknownError, TaskCancelledError, type TaskContext } from './worker.js';

/** What an earlier attempt recorded for a step: undefined on its first run. */
export type StepPreviousState = 'INTENT' | 'FAILED';

/**
 * The handle a durable step's body and lookup receive (`ctx.step`): the step's name, its stable idempotency key and a
 * slot for the provider's reference.
 */
export class Step {
  /** Records the provider's reference with the outcome (e.g. a payment id); later lookups can use it. */
  providerRef?: string;

  /** @internal */
  constructor(
    /** The step name, unique within the work's operation. */
    readonly name: string,
    /** The work's stable operationId (R36). */
    readonly operationId: string,
    /**
     * What an earlier attempt recorded for this step: undefined on its first run, `'INTENT'` when that attempt's
     * outcome is unknown, `'FAILED'` when it failed without effect.
     */
    readonly previousState: StepPreviousState | undefined,
  ) {}

  /**
   * Idempotency key for the remote call: `operationId + ':' + name`. Stable across retries, redelivery and lease
   * expiry, and different for each step of the same operation.
   */
  get idempotencyKey(): string {
    return `${this.operationId}:${this.name}`;
  }
}

/**
 * The side-effecting body of one durable step. Send `step.idempotencyKey` to the remote API as its idempotency key,
 * and report the provider's reference with `step.providerRef = ...`.
 *
 * - Return (or resolve): the step is recorded SUCCEEDED with the value; later attempts replay it (R33 typed values come
 *   back unwrapped, like `ctx.variable`; `undefined` is recorded without a result and replays as `null`).
 * - Throw `StepNoEffectError`: the call certainly had no effect (refused before it was sent, a business rejection);
 *   the step is recorded FAILED and may run again on a later attempt.
 * - Throw anything else: the outcome is uncertain, the step stays INTENT; a later attempt looks the outcome up
 *   (`options.lookup`), runs again only for an idempotent call (`options.idempotent`), or reports outcome-unknown
 *   (R37: never a blind re-issue).
 */
export type StepCall<T> = (step: Step) => Promise<T> | T;

/**
 * Outcome lookup for a step whose earlier attempt left an INTENT (capability classes (a) and (b), R36/R37): ask the
 * remote system, by `step.idempotencyKey`, whether the call took effect.
 *
 * - A value: the call took effect; it is recorded SUCCEEDED and returned without calling again.
 * - `undefined` / `null`: the call certainly did not take effect; the step runs again.
 * - A thrown error: the outcome is still unknown; the step stays INTENT and the error propagates.
 */
export type StepLookup<T> = (step: Step) => Promise<T | null | undefined> | T | null | undefined;

/**
 * How a durable step recovers when an earlier attempt left it INTENT (the crash window of R37), following the
 * capability class of the remote call (R36):
 *
 * - (a) idempotent, with or without lookup: `idempotent: true` runs the call again with the same `step.idempotencyKey`;
 * - (b) outcome lookup only: `lookup` asks the remote system before any controlled retry;
 * - (c) neither (the default): the step throws `OutcomeUnknownError`, the worker reports outcome-unknown and
 *   reconciliation decides; nothing is repeated automatically.
 *
 * A lookup, when given, is always asked first.
 */
export interface StepOptions<T = unknown> {
  idempotent?: boolean;
  lookup?: StepLookup<T>;
}

/**
 * Thrown by a step body when the remote call certainly had no effect (it was refused before it was sent, or the remote
 * system rejected it as a business failure). The step is recorded FAILED with the message, so a later attempt may run
 * it again; the error then propagates to the handler (R37).
 */
export class StepNoEffectError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'StepNoEffectError';
  }
}

const INTENT = 'INTENT';
const SUCCEEDED = 'SUCCEEDED';
const FAILED = 'FAILED';
type StepState = WorkStepRequest['state'];

/** Throws a TypeError unless `name` is 1-128 printable ASCII characters (0x20-0x7E). */
export function requireStepName(name: unknown): asserts name is string {
  if (typeof name !== 'string' || name.length === 0 || name.length > 128) {
    throw new TypeError('step name must be 1-128 characters');
  }
  for (let i = 0; i < name.length; i++) {
    const c = name.charCodeAt(i);
    if (c < 0x20 || c > 0x7e) throw new TypeError('step name must be printable ASCII');
  }
}

/**
 * The algorithm of `TaskContext.step` (M-025, R37 / E02):
 *
 * 1. read the step: SUCCEEDED replays the recorded result without calling again;
 * 2. INTENT left by an earlier attempt (the crash window): ask the lookup if any (a value is recorded SUCCEEDED and
 *    returned; undefined/null means no effect), else run again only for an idempotent call, else throw
 *    `OutcomeUnknownError`;
 * 3. record INTENT, run the call, record SUCCEEDED with the result, or FAILED on `StepNoEffectError`; any other error
 *    leaves INTENT (uncertain) and propagates.
 *
 * A 409 STALE_LEASE or 410 from the journal marks the lease lost and throws `TaskCancelledError`.
 * NOT exactly-once for remote APIs: a crash between the remote call and the SUCCEEDED record leaves INTENT, which
 * step 2 resolves without a blind re-issue.
 */
export async function runStep<T>(ctx: TaskContext, name: string, call: StepCall<T>, options?: StepOptions<T>): Promise<T> {
  requireStepName(name);
  if (typeof call !== 'function') throw new TypeError('step body must be a function');
  const opts: StepOptions<T> = options ?? {};
  const recorded = await read(ctx, name);
  const previous = recorded?.state;
  const step = new Step(name, ctx.operationId, previous === INTENT || previous === FAILED ? previous : undefined);
  if (previous === SUCCEEDED) {
    ctx.logger.log('info', 'step.replayed', 'step already succeeded; replaying its recorded result', { step: name });
    return fromTypedValue(recorded!.result) as T;
  }
  if (previous === INTENT) {
    if (opts.lookup) {
      const found = await opts.lookup(step);
      if (found !== undefined && found !== null) {
        await write(ctx, name, SUCCEEDED, found, step.providerRef, undefined);
        ctx.logger.log('info', 'step.looked-up', 'earlier attempt took effect; recorded from lookup', { step: name });
        return found;
      }
    } else if (!opts.idempotent) {
      throw new OutcomeUnknownError(
        `step ${name} of operation ${ctx.operationId} was started by an earlier attempt and its outcome is unknown (R37)`,
        { details: `step=${name}`, variables: { step: name, idempotencyKey: step.idempotencyKey } },
      );
    }
  }
  await write(ctx, name, INTENT, undefined, undefined, undefined);
  let value: T;
  try {
    value = await call(step);
  } catch (e) {
    if (e instanceof StepNoEffectError) {
      await write(ctx, name, FAILED, undefined, step.providerRef, String(e.message));
    }
    throw e;
  }
  await write(ctx, name, SUCCEEDED, value, step.providerRef, undefined);
  return value;
}

async function read(ctx: TaskContext, name: string): Promise<WorkStep | undefined> {
  try {
    return await ctx.client.api.work.getWorkStep(ctx.workId, {
      stepName: name, workerId: ctx.workerId, lockVersion: ctx.lockVersion, attemptId: ctx.attemptId,
    });
  } catch (e) {
    if (e instanceof StaleLeaseError || e instanceof GoneError) throw leaseLost(ctx, name, e);
    if (e instanceof SangamApiError && e.status === 404) return undefined; // never recorded: run it
    throw e;
  }
}

async function write(ctx: TaskContext, name: string, state: StepState, result: unknown, providerRef: string | undefined,
  error: string | undefined): Promise<void> {
  const body: WorkStepRequest = { workerId: ctx.workerId, lockVersion: ctx.lockVersion, attemptId: ctx.attemptId, state };
  if (result !== undefined) body.result = result;
  if (providerRef !== undefined) body.providerRef = providerRef;
  if (error !== undefined) body.error = error;
  try {
    await ctx.client.api.work.recordWorkStep(ctx.workId, body, { stepName: name });
  } catch (e) {
    if (e instanceof StaleLeaseError || e instanceof GoneError) throw leaseLost(ctx, name, e);
    throw e;
  }
}

function leaseLost(ctx: TaskContext, name: string, cause: unknown): TaskCancelledError {
  ctx.leaseLostFlag = true;
  ctx.logger.log('warn', 'work.lease.lost', `lease lost while recording step ${name}`, { step: name }, cause);
  ctx.abort.abort(new TaskCancelledError('lease lost'));
  const e = new TaskCancelledError(`lease lost while recording step ${name}`);
  e.cause = cause;
  return e;
}
