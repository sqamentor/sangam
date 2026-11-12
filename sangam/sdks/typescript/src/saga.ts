// SPDX-License-Identifier: Apache-2.0
// Saga and compensation worker helper (M-026, §3.2, R36).
import type { Variables } from './models.js';
import { fromTypedValue, TypedValues } from './typed.js';
import { topicSubscription, type SangamWorker, type TaskContext, type TopicSubscription } from './worker.js';

/**
 * What a saga's forward action leaves for its compensation (M-026, R36): the forward work's stable operationId, its
 * provider reference and its work id. Stored as the process variable `Saga.recordVariable(step)` when the forward action
 * completes.
 */
export interface SagaRecord {
  step: string | null;
  operationId: string;
  providerRef: string | null;
  workId: string | null;
}

/** A saga's forward action (M-026): a TaskHandler whose completion also records a `SagaRecord`. */
export type SagaAction = (ctx: TaskContext) => Promise<Variables | void | undefined> | Variables | void | undefined;

/**
 * Undoes a saga's forward action (M-026). Runs as the external task of the BPMN compensation handler
 * (`isForCompensation="true"`); result variables, errors and outcome-unknown follow `TaskHandler`.
 */
export type SagaCompensation = (ctx: CompensationContext) => Promise<Variables | void | undefined> | Variables | void | undefined;

/**
 * What a saga compensation handler sees (M-026): the compensation work's own `TaskContext` and the record of the
 * forward action it undoes. The undo call reuses the forward operationId (R36): send `idempotencyKey` so that
 * compensating the same forward action twice undoes it once.
 */
export class CompensationContext {
  /** @internal */
  constructor(
    /** The compensation work item (lease, variables, durable steps, logger, client). */
    readonly task: TaskContext,
    /**
     * The forward action's record, or null when the instance holds none (the forward action did not complete through
     * `Saga`, so there is nothing this helper knows to undo).
     */
    readonly forward: SagaRecord | null,
    private readonly step: string,
  ) {}

  /** The forward action's operationId (R36), or null without a record. */
  get forwardOperationId(): string | null { return this.forward ? this.forward.operationId : null; }

  /** The forward action's provider reference (e.g. the payment to refund), or null. */
  get forwardProviderRef(): string | null { return this.forward ? this.forward.providerRef : null; }

  /**
   * Idempotency key of the undo call: `forwardOperationId + ':compensate:' + step`; without a record, the compensation
   * work's own operationId.
   */
  get idempotencyKey(): string {
    return this.forward ? `${this.forward.operationId}:compensate:${this.step}` : this.task.operationId;
  }
}

const NAME = /^[A-Za-z0-9._-]{1,64}$/;

function str(o: unknown): string | null {
  return o === undefined || o === null ? null : String(o);
}

/**
 * Saga and compensation worker helper (M-026, §3.2, R36): registers one forward action and its compensation as two
 * external-task subscriptions, and carries the forward action's identity to the compensation.
 *
 * In the model, the forward step is a service task with topic `actionTopic`, and its compensation handler (attached
 * through a compensation boundary event, `isForCompensation="true"`) is a service task with topic `compensationTopic`.
 * When the forward action completes, this helper adds the process variable `Saga.recordVariable(name)` holding a
 * `SagaRecord` (the forward operationId, provider reference and work id) as an R33 Json typed value. When the engine
 * compensates, the compensation handler gets that record in its `CompensationContext` and reuses the forward
 * operationId: the undo call's idempotency key is derived from it, so compensating the same forward action twice undoes
 * it once.
 *
 * One record per step name and process scope: a step repeated in a loop or multi-instance body needs its own name per
 * iteration, or a compensation that reads the iteration's data from local variables.
 *
 * ```ts
 * Saga.step('charge')
 *   .action('payment.charge', async (ctx) => ({ chargeId: await charge(ctx) }))
 *   .compensation('payment.refund', async (c) => { await refund(c.forwardProviderRef, c.idempotencyKey); })
 *   .build()
 *   .registerOn(worker);
 * ```
 */
export class Saga {
  /** Prefix of the record variables. */
  static readonly RECORD_PREFIX = 'sangamSaga_';

  private constructor(
    readonly name: string,
    /** The forward action's subscription. */
    readonly action: TopicSubscription,
    /** The compensation's subscription. */
    readonly compensation: TopicSubscription,
  ) {}

  /** Starts a saga step named `name` (letters, digits, '.', '-', '_'; at most 64 characters). */
  static step(name: string): SagaBuilder {
    return new SagaBuilder(name);
  }

  /** The process variable that holds the record of step `name`: `sangamSaga_<name>` with '.' and '-' as '_'. */
  static recordVariable(name: string): string {
    return Saga.RECORD_PREFIX + name.replace(/[.-]/g, '_');
  }

  /** @internal */
  static create(name: string, action: TopicSubscription, compensation: TopicSubscription): Saga {
    return new Saga(name, action, compensation);
  }

  /** Both subscriptions, forward action first. */
  subscriptions(): TopicSubscription[] {
    return [this.action, this.compensation];
  }

  /** Subscribes both handlers on `worker`. */
  registerOn(worker: SangamWorker): SangamWorker {
    return worker.subscribe(this.action).subscribe(this.compensation);
  }

  /** The handler's variables plus the record variable of `step` (forward action completion). */
  static withRecord(step: string, ctx: TaskContext, vars: Variables | void | undefined): Variables {
    const out: Variables = { ...(vars ?? {}) };
    const record: SagaRecord = {
      step, operationId: ctx.operationId, providerRef: ctx.outcomeReference ?? null, workId: ctx.workId,
    };
    out[Saga.recordVariable(step)] = TypedValues.json(record);
    return out;
  }

  /** The record of `step` in the work's variables, or null when absent or without an operationId. */
  static recordOf(step: string, ctx: TaskContext): SagaRecord | null {
    const raw = ctx.variables[Saga.recordVariable(step)];
    if (raw === undefined || raw === null) return null;
    let m = fromTypedValue(raw);
    if (typeof m === 'string') {
      try {
        m = JSON.parse(m) as unknown;
      } catch {
        return null;
      }
    }
    if (!m || typeof m !== 'object' || Array.isArray(m)) return null;
    const o = m as Record<string, unknown>;
    if (o.operationId === undefined || o.operationId === null) return null;
    return { step: str(o.step), operationId: String(o.operationId), providerRef: str(o.providerRef), workId: str(o.workId) };
  }
}

/** Builder of a `Saga` step (M-026). */
export class SagaBuilder {
  private actionTopic?: string;
  private actionHandler?: SagaAction;
  private compensationTopic?: string;
  private compensationHandler?: SagaCompensation;
  private lockMs = 300_000;
  private retryCount = 3;
  private irreversibleFlag = false;
  private variableNames?: string[];

  /** @internal */
  constructor(readonly name: string) {
    if (typeof name !== 'string' || !NAME.test(name)) {
      throw new TypeError(`saga step name must be 1-64 of [A-Za-z0-9._-]: ${String(name)}`);
    }
  }

  /** The forward action: topic and handler. */
  action(topic: string, handler: SagaAction): this {
    if (!topic) throw new TypeError('topic is required');
    if (typeof handler !== 'function') throw new TypeError('handler is required');
    this.actionTopic = topic;
    this.actionHandler = handler;
    return this;
  }

  /** The compensation: topic and handler. */
  compensation(topic: string, handler: SagaCompensation): this {
    if (!topic) throw new TypeError('topic is required');
    if (typeof handler !== 'function') throw new TypeError('handler is required');
    this.compensationTopic = topic;
    this.compensationHandler = handler;
    return this;
  }

  /** Lease per fetch for both topics in ms (default 5 min). */
  lockDurationMs(ms: number): this { this.lockMs = ms; return this; }

  /** Retries for the first failure of either handler (default 3). */
  retries(n: number): this { this.retryCount = n; return this; }

  /** The forward action has an irreversible effect (VI.3.2 rule 5): unexpected errors become outcome-unknown. */
  irreversible(v: boolean): this { this.irreversibleFlag = v; return this; }

  /** Variables to fetch for both handlers (undefined = all); the record variable is always added for the compensation. */
  variables(names: string[] | undefined): this { this.variableNames = names ? [...names] : undefined; return this; }

  build(): Saga {
    const step = this.name;
    const a = this.actionHandler;
    const comp = this.compensationHandler;
    if (!a || !comp || !this.actionTopic || !this.compensationTopic) {
      throw new Error(`saga step ${step} needs an action and a compensation`);
    }
    if (this.actionTopic === this.compensationTopic) {
      throw new Error(`saga step ${step}: action and compensation need their own topics`);
    }
    const forward = topicSubscription(this.actionTopic, async (ctx) => Saga.withRecord(step, ctx, await a(ctx)), {
      lockDurationMs: this.lockMs, retries: this.retryCount, irreversible: this.irreversibleFlag, variables: this.variableNames,
    });
    const compVars = this.variableNames ? [...this.variableNames, Saga.recordVariable(step)] : undefined;
    const undo = topicSubscription(this.compensationTopic,
      (ctx) => comp(new CompensationContext(ctx, Saga.recordOf(step, ctx), step)),
      { lockDurationMs: this.lockMs, retries: this.retryCount, variables: compVars });
    return Saga.create(step, forward, undo);
  }
}
