// SPDX-License-Identifier: Apache-2.0
/**
 * P1 bill/payment example worker (IX.4 P1, AC-01, AC-03) for the `payment` topic of the bill model
 * (`wf1_payment`, "Payment instruction").
 *
 * Posts a payment instruction to the treasury/payment gateway with `Idempotency-Key = operationId` (R36), so a
 * retried attempt can never pay twice. A connect failure is safe to retry (nothing was sent); a timeout after
 * sending is reported as outcome-unknown (AC-03 → UNKNOWN_OUTCOME → reconciliation). A gateway rejection is the
 * BPMN error `PAYMENT_REJECTED`.
 *
 * Environment: SANGAM_URL (default http://localhost:8080), either SANGAM_TOKEN or SANGAM_OIDC_TOKEN_URL +
 * SANGAM_CLIENT_ID + SANGAM_CLIENT_SECRET (+ SANGAM_SCOPE), optional SANGAM_TOPIC (default `payment`),
 * SANGAM_WORKER_ID and TREASURY_URL (unset = built-in simulator).
 *
 * Run: `npm run example:bill-payment`
 */
import {
  BpmnError,
  ClientCredentialsTokenProvider,
  OutcomeUnknownError,
  SangamClient,
  SangamWorker,
  StaticTokenProvider,
  TaskFailedError,
  TypedValues,
  type TaskContext,
  type TokenProvider,
  type Variables,
} from '../src/index.js';

function env(name: string, dflt?: string): string | undefined {
  const v = process.env[name];
  return v === undefined || v.trim() === '' ? dflt : v;
}

function tokenProvider(): TokenProvider | undefined {
  const tokenUrl = env('SANGAM_OIDC_TOKEN_URL');
  if (tokenUrl) {
    return new ClientCredentialsTokenProvider({
      tokenEndpoint: tokenUrl,
      clientId: env('SANGAM_CLIENT_ID') ?? '',
      clientSecret: env('SANGAM_CLIENT_SECRET') ?? '',
      scope: env('SANGAM_SCOPE'),
    });
  }
  const token = env('SANGAM_TOKEN');
  return token ? new StaticTokenProvider(token) : undefined;
}

export async function pay(ctx: TaskContext, treasuryUrl: string | undefined): Promise<Variables> {
  // Money is an R33 Decimal ({type:'Decimal', value:'1234.50'}) kept as an exact string; never float arithmetic.
  const rawAmount = ctx.decimal('amount');
  const amount = rawAmount === undefined ? NaN : Number(rawAmount); // only for the sign check
  const beneficiary = ctx.variable<string>('beneficiaryAccount');
  const billNo = ctx.businessKey;
  if (!(amount > 0)) throw new BpmnError('PAYMENT_REJECTED', `bill ${billNo} has no positive amount`);
  if (!beneficiary || beneficiary.trim() === '') {
    throw new BpmnError('PAYMENT_REJECTED', `bill ${billNo} has no beneficiary account`);
  }
  ctx.throwIfCancelled();
  ctx.logger.log('info', 'p1.payment.instruct', 'sending payment instruction', { amount: String(rawAmount) });

  if (!treasuryUrl) {
    // simulator: deterministic reference derived from the operationId (same op -> same reference)
    return {
      paymentStatus: 'ACKNOWLEDGED',
      paymentReference: `SIM-${ctx.operationId.substring(0, 8)}`,
      paidAmount: TypedValues.decimal(rawAmount!),
    };
  }

  let resp: Response;
  try {
    resp = await fetch(`${treasuryUrl}/payment-instructions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': ctx.idempotencyKey },
      body: JSON.stringify({ billNumber: billNo, amount: rawAmount, beneficiaryAccount: beneficiary, operationId: ctx.operationId }),
      signal: AbortSignal.any([AbortSignal.timeout(20_000), ctx.signal]),
    });
  } catch (e) {
    if (ctx.signal.aborted) throw e; // lease lost / cancel / shutdown: the runtime decides
    const err = e as Error & { cause?: { code?: string } };
    if (err.name !== 'TimeoutError' && (err.cause?.code === 'ECONNREFUSED' || err.cause?.code === 'ENOTFOUND')) {
      throw new TaskFailedError('treasury unreachable (nothing sent)', { cause: e });
    }
    throw new OutcomeUnknownError('treasury did not answer; payment may have been made', {
      details: `POST ${treasuryUrl}/payment-instructions`,
      variables: { billNumber: billNo },
      cause: e,
    });
  }
  const text = await resp.text();
  if (resp.status === 200 || resp.status === 201) {
    const ack = (text ? JSON.parse(text) : {}) as { reference?: string };
    return { paymentStatus: 'ACKNOWLEDGED', paymentReference: String(ack.reference ?? ''), paidAmount: TypedValues.decimal(rawAmount!) };
  }
  if (resp.status === 400 || resp.status === 422) {
    throw new BpmnError('PAYMENT_REJECTED', `treasury rejected the instruction: ${text}`, { paymentStatus: 'REJECTED' });
  }
  if (resp.status >= 500) {
    throw new OutcomeUnknownError(`treasury answered ${resp.status}; outcome unknown`, { details: text });
  }
  throw new TaskFailedError(`unexpected treasury status ${resp.status}`);
}

async function main(): Promise<void> {
  const client = new SangamClient({ baseUrl: env('SANGAM_URL', 'http://localhost:8080')!, tokenProvider: tokenProvider() });
  const treasury = env('TREASURY_URL');
  const worker = new SangamWorker(client, { workerId: env('SANGAM_WORKER_ID'), maxConcurrency: 4 });
  worker.subscribe(env('SANGAM_TOPIC', 'payment')!, (ctx) => pay(ctx, treasury), {
    lockDurationMs: 120_000,
    retries: 3,
    // a payment is irreversible: unexpected errors become outcome-unknown, never a blind retry
    irreversible: true,
  });
  await worker.run();
}

main().catch((e: unknown) => {
  console.error(e);
  process.exitCode = 1;
});
